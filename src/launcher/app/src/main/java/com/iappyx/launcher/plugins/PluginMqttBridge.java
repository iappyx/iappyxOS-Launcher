/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `mqtt` capability. Eclipse Paho MQTT 3.1.1 client wrapped as
 * a per-plugin bridge. Each plugin gets its own client + connection (no
 * cross-plugin sharing); destroyed when the plugin's WebView is.
 *
 * JS-side surface (when capability granted):
 *   iappyx.mqtt.connect(opts)         → {ok}
 *   iappyx.mqtt.subscribe(opts)       → {ok, subId} ; opts.method fires
 *                                       on every matching message
 *   iappyx.mqtt.unsubscribe({subId})  → {ok, cancelled}
 *   iappyx.mqtt.publish(opts)         → {ok, messageId}
 *   iappyx.mqtt.state({method?})      → {ok, connected, broker}
 *                                       (if method provided, fires on
 *                                       every state change too)
 *   iappyx.mqtt.disconnect()          → {ok}
 *
 * Event delivery (bridge → plugin) reuses the notifications-bridge
 * pattern: store the registered method name on the subscription, and
 * call PluginHost.invoke(ctx, pluginId, method, payloadJson) when a
 * matching message arrives. The plugin's exported `method` then handles
 * re-distribution to its widget consumers via its own storage cache.
 *
 * URL schemes supported via Paho:
 *   tcp://host:1883        plain MQTT
 *   ssl://host:8883        MQTT over TLS
 *   ws://host:9001         MQTT over WebSocket
 *   wss://host:443         MQTT over secure WebSocket
 *
 * insecure:true flag — only honoured for SSL/WSS URLs targeting a LAN
 * address (same DNS-pinning pattern as PluginHttpBridge). Public hosts
 * fall back to strict TLS validation. Self-signed home brokers
 * (Mosquitto on Raspberry Pi) are the target use case.
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

public class PluginMqttBridge {

    private static final String TAG = "iappyx-mqtt";

    private final Context appContext;
    private final String pluginId;
    private final PluginInvocationContext invocationCtx;

    /** One Paho client per bridge instance (= one per plugin WebView). */
    private final AtomicReference<MqttAsyncClient> clientRef = new AtomicReference<>(null);

    /** Subscription registry. Keyed by subId; value is the topic + the
     *  exported plugin method to invoke per message. Held so we can
     *  re-subscribe after a reconnect — Paho's auto-reconnect does NOT
     *  auto-resub on MQTT 3.1.1, the client has to re-issue. */
    private final ConcurrentHashMap<String, Sub> subs = new ConcurrentHashMap<>();

    /** Optional state-change observer method (single, last-write wins). */
    private final AtomicReference<String> stateMethod = new AtomicReference<>(null);

    /** Cached connection target so reconnect can log + state events
     *  carry the broker identity. */
    private final AtomicReference<String> currentBroker = new AtomicReference<>("");

    /** Lifecycle gate. Flipped to true when the plugin's WebView is
     *  being destroyed (via {@link #onPluginDestroyed(String)}). All
     *  in-flight callbacks check this before delivering results,
     *  attempting subscribe/publish, or firing state events — once
     *  destroyed, the bridge is dead weight and any further interaction
     *  with `clientRef` or `invocationCtx` is wasted work or worse. */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    /** Per-pluginId registry so {@link #onPluginDestroyed(String)} can
     *  find the live bridge instance at WebView-teardown time. There's
     *  exactly one bridge per plugin (PluginCapability attaches the
     *  bridge to the plugin's hidden WebView once at createInstance).
     *  A reconnect that creates a new instance would replace the old
     *  entry — the old bridge is GC'd along with its dead WebView. */
    private static final ConcurrentHashMap<String, PluginMqttBridge> INSTANCES =
        new ConcurrentHashMap<>();

    PluginMqttBridge(Context context, String pluginId, PluginInvocationContext invocationCtx) {
        this.appContext = context.getApplicationContext();
        this.pluginId = pluginId;
        this.invocationCtx = invocationCtx;
        INSTANCES.put(pluginId, this);
    }

    /** Called from {@code PluginInstance.destroy()} when the plugin's
     *  WebView is being torn down. Force-disconnects + closes the Paho
     *  client so its background threads + TCP socket don't linger past
     *  the WebView. Subsequent invokes on `this` no-op via the
     *  `destroyed` gate. Safe to call when nothing's registered (no-op). */
    public static void onPluginDestroyed(String pluginId) {
        if (pluginId == null) return;
        PluginMqttBridge bridge = INSTANCES.remove(pluginId);
        if (bridge != null) bridge.destroyInternal();
    }

    private void destroyInternal() {
        if (!destroyed.compareAndSet(false, true)) return;
        MqttAsyncClient c = clientRef.getAndSet(null);
        subs.clear();
        stateMethod.set(null);
        if (c != null) {
            try { c.disconnectForcibly(0, 250); } catch (Throwable ignored) {}
            try { c.close(true); } catch (Throwable ignored) {}
        }
    }

    // ── connect ─────────────────────────────────────────────────

    /** Connect to a broker. Tears down any existing client first so a
     *  reconnect with new credentials works without leaking the old
     *  TCP connection.
     *
     *  Options:
     *    url            tcp://... | ssl://... | ws://... | wss://...
     *    username       optional
     *    password       optional
     *    clientId       optional; auto-generated if omitted
     *    cleanSession   default true
     *    keepAlive      seconds, default 60
     *    insecure       only honoured for ssl/wss to LAN addresses
     *    lwt            { topic, payload, qos, retained } — last will
     */
    @JavascriptInterface
    public void connect(String optionsJson, String cbId) {
        if (cbId == null) return;
        if (destroyed.get()) {
            invocationCtx.deliverResult(cbId, errJson("bridge destroyed"));
            return;
        }
        new Thread(() -> {
            try {
                if (destroyed.get()) return;  // bail before any heavy work
                JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
                String url = opts.optString("url");
                if (url.isEmpty()) {
                    invocationCtx.deliverResult(cbId, errJson("url required"));
                    return;
                }
                String clientId = opts.optString("clientId", "");
                if (clientId.isEmpty()) {
                    clientId = "iappyx-" + pluginId + "-" + UUID.randomUUID().toString().substring(0, 8);
                }
                // Drop the old client if there is one — connect() is
                // idempotent from the caller's perspective. Awaiting the
                // disconnect would slow this down; we just abandon it.
                MqttAsyncClient existing = clientRef.getAndSet(null);
                if (existing != null) {
                    try { existing.disconnectForcibly(0, 250); } catch (Throwable ignored) {}
                    try { existing.close(true); } catch (Throwable ignored) {}
                }

                MqttAsyncClient client = new MqttAsyncClient(url, clientId, new MemoryPersistence());
                MqttConnectOptions copts = new MqttConnectOptions();
                copts.setAutomaticReconnect(true);
                copts.setCleanSession(opts.optBoolean("cleanSession", true));
                copts.setKeepAliveInterval(opts.optInt("keepAlive", 60));
                copts.setConnectionTimeout(15);

                String username = opts.optString("username", "");
                String password = opts.optString("password", "");
                if (!username.isEmpty()) copts.setUserName(username);
                if (!password.isEmpty()) copts.setPassword(password.toCharArray());

                // Last-will-and-testament — fires on the broker's side
                // when our connection drops without a clean DISCONNECT.
                // Useful for "device went offline" presence topics.
                JSONObject lwt = opts.optJSONObject("lwt");
                if (lwt != null) {
                    String wTopic = lwt.optString("topic", "");
                    if (!wTopic.isEmpty()) {
                        MqttMessage wMsg = new MqttMessage(
                            lwt.optString("payload", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        wMsg.setQos(clampQos(lwt.optInt("qos", 0)));
                        wMsg.setRetained(lwt.optBoolean("retained", false));
                        copts.setWill(wTopic, wMsg.getPayload(), wMsg.getQos(), wMsg.isRetained());
                    }
                }

                // Permissive TLS opt-in: only honoured if the target
                // resolves to a LAN address — same gate as the http
                // bridge. Affects ssl:// and wss:// schemes only.
                boolean insecureRequested = opts.optBoolean("insecure", false);
                String host = parseHost(url);
                String scheme = parseScheme(url).toLowerCase();
                boolean wantsTls = scheme.equals("ssl") || scheme.equals("wss");
                if (insecureRequested && wantsTls && host != null) {
                    InetAddress lan = PluginInsecureHttp.resolveLanAddress(host);
                    if (lan != null) {
                        SSLContext sslCtx = SSLContext.getInstance("TLS");
                        sslCtx.init(null,
                            new javax.net.ssl.TrustManager[]{ PluginInsecureHttp.trustAllManager() },
                            new java.security.SecureRandom());
                        copts.setSocketFactory(sslCtx.getSocketFactory());
                        copts.setHttpsHostnameVerificationEnabled(false);
                    }
                }

                client.setCallback(new ClientCallback());
                client.connect(copts, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                    @Override public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken t) {
                        // If the bridge was destroyed during the
                        // handshake, abandon the client we just built
                        // instead of stashing it in clientRef (where
                        // destroyInternal() can't reach it anymore).
                        if (destroyed.get()) {
                            try { client.disconnectForcibly(0, 250); } catch (Throwable ignored) {}
                            try { client.close(true); } catch (Throwable ignored) {}
                            return;
                        }
                        clientRef.set(client);
                        currentBroker.set(url);
                        invocationCtx.deliverResult(cbId,
                            okJson("connected", true).toString());
                    }
                    @Override public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken t, Throwable e) {
                        try { client.close(true); } catch (Throwable ignored) {}
                        if (destroyed.get()) return;
                        invocationCtx.deliverResult(cbId,
                            errJson("connect failed: " + (e != null ? e.getMessage() : "unknown")));
                    }
                });
            } catch (Throwable t) {
                if (destroyed.get()) return;
                invocationCtx.deliverResult(cbId,
                    errJson("connect error: " + (t.getMessage() != null ? t.getMessage() : t.toString())));
            }
        }).start();
    }

    // ── subscribe / unsubscribe ─────────────────────────────────

    /** Subscribe to a topic (or wildcard pattern). Required field
     *  `method` names the plugin's exported function that fires per
     *  matching message — same pattern as notifications.subscribe.
     *
     *  Options:
     *    topic    string, required. Standard MQTT wildcards: + (single
     *             level), # (multi-level, must be last segment).
     *    qos      0|1|2, default 0
     *    method   plugin's exported method name to invoke per message
     */
    @JavascriptInterface
    public void subscribe(String optionsJson, String cbId) {
        if (cbId == null) return;
        if (destroyed.get()) {
            invocationCtx.deliverResult(cbId, errJson("bridge destroyed"));
            return;
        }
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            String topic = opts.optString("topic", "");
            String method = opts.optString("method", "");
            int qos = clampQos(opts.optInt("qos", 0));
            if (topic.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("topic required"));
                return;
            }
            if (method.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("method required"));
                return;
            }
            MqttAsyncClient c = clientRef.get();
            if (c == null) {
                invocationCtx.deliverResult(cbId, errJson("not connected"));
                return;
            }
            String subId = "mqtt_" + UUID.randomUUID().toString().substring(0, 12);
            Sub sub = new Sub(subId, topic, qos, method);
            subs.put(subId, sub);
            c.subscribe(topic, qos, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                @Override public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken t) {
                    try {
                        JSONObject out = new JSONObject();
                        out.put("ok", true);
                        out.put("subId", subId);
                        out.put("topic", topic);
                        invocationCtx.deliverResult(cbId, out.toString());
                    } catch (Throwable e) {
                        invocationCtx.deliverResult(cbId, "{\"ok\":true,\"subId\":\"" + subId + "\"}");
                    }
                }
                @Override public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken t, Throwable e) {
                    subs.remove(subId);
                    invocationCtx.deliverResult(cbId,
                        errJson("subscribe failed: " + (e != null ? e.getMessage() : "unknown")));
                }
            });
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId,
                errJson("subscribe error: " + (t.getMessage() != null ? t.getMessage() : t.toString())));
        }
    }

    @JavascriptInterface
    public void unsubscribe(String subId, String cbId) {
        if (cbId == null) return;
        if (destroyed.get()) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":true}");
            return;
        }
        if (subId == null || subId.isEmpty()) {
            invocationCtx.deliverResult(cbId, errJson("subId required"));
            return;
        }
        Sub sub = subs.remove(subId);
        if (sub == null) {
            try {
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("cancelled", false);
                invocationCtx.deliverResult(cbId, out.toString());
            } catch (Throwable ignored) {
                invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":false}");
            }
            return;
        }
        MqttAsyncClient c = clientRef.get();
        if (c == null) {
            // Local state cleared; broker-side cleanup happens on
            // disconnect anyway.
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":true}");
            return;
        }
        // Only unsub from the broker if no other sub maps to the same
        // topic. Two callers can subscribe to the same topic; the second
        // unsubscribe leaves the broker-side subscription in place.
        boolean stillReferenced = false;
        for (Sub other : subs.values()) {
            if (other.topic.equals(sub.topic)) { stillReferenced = true; break; }
        }
        if (stillReferenced) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":true}");
            return;
        }
        try {
            c.unsubscribe(sub.topic, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                @Override public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken t) {
                    invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":true}");
                }
                @Override public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken t, Throwable e) {
                    // Local state already cleared — surface the error
                    // but report cancellation since the bridge no longer
                    // routes messages for this sub.
                    invocationCtx.deliverResult(cbId,
                        "{\"ok\":true,\"cancelled\":true,\"warning\":\"broker unsub failed\"}");
                }
            });
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":true}");
        }
    }

    // ── publish ─────────────────────────────────────────────────

    /** Publish a message. The payload is sent as UTF-8 bytes. Binary
     *  payloads (images, blobs) must be base64 already — the bridge
     *  doesn't decode, the broker echoes them as bytes. */
    @JavascriptInterface
    public void publish(String optionsJson, String cbId) {
        if (cbId == null) return;
        if (destroyed.get()) {
            invocationCtx.deliverResult(cbId, errJson("bridge destroyed"));
            return;
        }
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            String topic = opts.optString("topic", "");
            if (topic.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("topic required"));
                return;
            }
            String payload = opts.optString("payload", "");
            int qos = clampQos(opts.optInt("qos", 0));
            boolean retained = opts.optBoolean("retained", false);
            MqttAsyncClient c = clientRef.get();
            if (c == null) {
                invocationCtx.deliverResult(cbId, errJson("not connected"));
                return;
            }
            MqttMessage msg = new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            msg.setQos(qos);
            msg.setRetained(retained);
            c.publish(topic, msg, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                @Override public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken t) {
                    invocationCtx.deliverResult(cbId, "{\"ok\":true}");
                }
                @Override public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken t, Throwable e) {
                    invocationCtx.deliverResult(cbId,
                        errJson("publish failed: " + (e != null ? e.getMessage() : "unknown")));
                }
            });
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId,
                errJson("publish error: " + (t.getMessage() != null ? t.getMessage() : t.toString())));
        }
    }

    // ── state ───────────────────────────────────────────────────

    /** Query connection state. If `method` is provided, also register
     *  the plugin's exported method to fire on every subsequent state
     *  change (connected / disconnected). Only one observer at a time —
     *  calling state() twice replaces the previous method. */
    @JavascriptInterface
    public void state(String optionsJson, String cbId) {
        if (cbId == null) return;
        if (destroyed.get()) {
            invocationCtx.deliverResult(cbId,
                "{\"ok\":true,\"connected\":false,\"broker\":\"\"}");
            return;
        }
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            String method = opts.optString("method", "");
            if (!method.isEmpty()) stateMethod.set(method);
            MqttAsyncClient c = clientRef.get();
            boolean connected = c != null && c.isConnected();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("connected", connected);
            out.put("broker", currentBroker.get());
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId,
                errJson("state error: " + (t.getMessage() != null ? t.getMessage() : t.toString())));
        }
    }

    // ── disconnect ──────────────────────────────────────────────

    @JavascriptInterface
    public void disconnect(String cbId) {
        if (cbId == null) return;
        MqttAsyncClient c = clientRef.getAndSet(null);
        subs.clear();
        stateMethod.set(null);
        if (c == null) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true}");
            return;
        }
        try {
            c.disconnect(null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                @Override public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken t) {
                    try { c.close(true); } catch (Throwable ignored) {}
                    invocationCtx.deliverResult(cbId, "{\"ok\":true}");
                }
                @Override public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken t, Throwable e) {
                    // Force close anyway — caller wants this gone.
                    try { c.disconnectForcibly(0, 250); } catch (Throwable ignored) {}
                    try { c.close(true); } catch (Throwable ignored) {}
                    invocationCtx.deliverResult(cbId, "{\"ok\":true}");
                }
            });
        } catch (Throwable t) {
            try { c.close(true); } catch (Throwable ignored) {}
            invocationCtx.deliverResult(cbId, "{\"ok\":true}");
        }
    }

    // ── Paho callback wiring ────────────────────────────────────

    /** Handles incoming messages + state transitions. Dispatches per
     *  the sub registry — wildcard matching uses Paho's built-in topic
     *  filter (we delegate to the broker so we don't re-implement it). */
    private final class ClientCallback implements MqttCallbackExtended {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            if (destroyed.get()) return;
            currentBroker.set(serverURI);
            // On reconnect Paho doesn't auto-resub for MQTT 3.1.1.
            // Walk our subs and re-issue. Snapshot the values to a
            // local list so a concurrent unsubscribe doesn't surprise
            // the iteration (ConcurrentHashMap's iterator is weakly
            // consistent but we'd rather have a deterministic walk).
            if (reconnect) {
                MqttAsyncClient c = clientRef.get();
                if (c != null) {
                    for (Sub sub : new ArrayList<>(subs.values())) {
                        if (destroyed.get()) return;
                        try { c.subscribe(sub.topic, sub.qos); }
                        catch (Throwable ignored) {}
                    }
                }
            }
            fireState(true);
        }

        @Override
        public void connectionLost(Throwable cause) {
            if (destroyed.get()) return;
            fireState(false);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            if (destroyed.get()) return;
            // Paho dispatches each message ONCE per client, even if
            // multiple subscriptions overlap (e.g. `home/+` and
            // `home/#`). We fan out ourselves by walking subs. Snapshot
            // the values list so a concurrent unsubscribe mid-dispatch
            // doesn't fire callbacks for a sub that just got removed.
            String payload = new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            for (Sub sub : new ArrayList<>(subs.values())) {
                if (destroyed.get()) return;
                if (!topicMatches(sub.topic, topic)) continue;
                JSONObject evt = new JSONObject();
                try {
                    evt.put("subId", sub.subId);
                    evt.put("topic", topic);
                    evt.put("payload", payload);
                    evt.put("qos", message.getQos());
                    evt.put("retained", message.isRetained());
                    evt.put("timestamp", System.currentTimeMillis());
                } catch (Throwable ignored) {}
                PluginHost.invoke(appContext, pluginId, sub.method, evt.toString(),
                    json -> { /* fire-and-forget — plugin's handler
                                 returns a reply we don't need */ });
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Publish QoS-1/2 ack — no-op; we already replied to the
            // caller when c.publish() called onSuccess.
        }
    }

    private void fireState(boolean connected) {
        if (destroyed.get()) return;
        String method = stateMethod.get();
        if (method == null || method.isEmpty()) return;
        JSONObject evt = new JSONObject();
        try {
            evt.put("connected", connected);
            evt.put("broker", currentBroker.get());
            evt.put("timestamp", System.currentTimeMillis());
        } catch (Throwable ignored) {}
        PluginHost.invoke(appContext, pluginId, method, evt.toString(),
            json -> { /* fire-and-forget */ });
    }

    // ── topic wildcard matching ─────────────────────────────────

    /** MQTT topic filter matcher. `+` matches a single level, `#`
     *  matches the rest. Paho only invokes messageArrived ONCE per
     *  client, so we have to do this ourselves to fan out a message
     *  to multiple overlapping subscriptions. */
    private static boolean topicMatches(String filter, String topic) {
        if (filter == null || topic == null) return false;
        if (filter.equals(topic)) return true;
        String[] fParts = filter.split("/", -1);
        String[] tParts = topic.split("/", -1);
        int fi = 0, ti = 0;
        while (fi < fParts.length && ti < tParts.length) {
            String fp = fParts[fi];
            if (fp.equals("#")) return true;
            if (!fp.equals("+") && !fp.equals(tParts[ti])) return false;
            fi++; ti++;
        }
        // Tail #
        if (fi < fParts.length && fParts[fi].equals("#")) return true;
        return fi == fParts.length && ti == tParts.length;
    }

    // ── helpers ─────────────────────────────────────────────────

    private static int clampQos(int q) {
        if (q < 0) return 0;
        if (q > 2) return 2;
        return q;
    }

    private static String parseScheme(String url) {
        int idx = url.indexOf("://");
        return idx > 0 ? url.substring(0, idx) : "";
    }

    private static String parseHost(String url) {
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return null;
            String rest = url.substring(schemeEnd + 3);
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);
            int at = rest.indexOf('@');
            if (at >= 0) rest = rest.substring(at + 1);
            int colon = rest.indexOf(':');
            if (colon >= 0) rest = rest.substring(0, colon);
            // Strip IPv6 brackets.
            if (rest.startsWith("[") && rest.endsWith("]")) {
                rest = rest.substring(1, rest.length() - 1);
            }
            return rest.isEmpty() ? null : rest;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject okJson(String key, Object value) {
        try {
            return new JSONObject().put("ok", true).put(key, value);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static String errJson(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    /** One subscription. */
    private static final class Sub {
        final String subId;
        final String topic;
        final int qos;
        final String method;
        Sub(String subId, String topic, int qos, String method) {
            this.subId = subId;
            this.topic = topic;
            this.qos = qos;
            this.method = method;
        }
    }
}
