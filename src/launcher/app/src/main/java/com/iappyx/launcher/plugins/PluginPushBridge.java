/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `push` capability. Receive Firebase Cloud Messaging pushes,
 * routed to subscribed plugins by topic.
 *
 * JS-side surface (when capability granted):
 *   iappyx.push.token() → {ok, token} | {ok:false, error:"FCM not configured"}
 *     The FCM device token. The plugin's server uses this to send
 *     directed pushes ("data:{topic:'foo', payload:...}") or subscribes
 *     it to broadcast topics server-side.
 *
 *   iappyx.push.subscribe({topic, method}) → {ok, subId}
 *     When an incoming push has data.topic === topic, fire the
 *     plugin's exported `method(payload)`. The plugin's server is
 *     expected to include a `topic` field in the data payload of
 *     every push it sends. Without a topic, the launcher falls back
 *     to firing every plugin's wildcard subscription (if any).
 *
 *   iappyx.push.unsubscribe({subId}) → {ok, cancelled}
 *
 * Configuration: this bridge degrades gracefully when FCM isn't
 * configured (no google-services.json in app/). token() returns a
 * clear error, subscribe() still records the subscription locally
 * for when FCM lands later. The PushService class is already wired
 * to dispatch — we just give it a hook to call into us on each push.
 *
 * Persistence: subscriptions live in `plugin_<id>_iappyx_push`
 * SharedPreferences and survive launcher restarts. FCM token refresh
 * doesn't affect subscriptions — they're indexed by topic, not by
 * token.
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public class PluginPushBridge {

    private final Context appContext;
    private final String pluginId;
    private final PluginInvocationContext invocationCtx;

    PluginPushBridge(Context context, String pluginId, PluginInvocationContext invocationCtx) {
        this.appContext = context.getApplicationContext();
        this.pluginId = pluginId;
        this.invocationCtx = invocationCtx;
    }

    @JavascriptInterface
    public void token(String cbId) {
        if (cbId == null) return;
        new Thread(() -> {
            try {
                // Reflective access to FirebaseMessaging — keeps the
                // plugins package compilable even on builds that strip
                // the FCM dep later. If FCM isn't on the classpath,
                // we return a clean "not configured" error.
                Class<?> fmClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging");
                Object fm = fmClass.getMethod("getInstance").invoke(null);
                Object task = fmClass.getMethod("getToken").invoke(fm);
                Class<?> taskCls = Class.forName("com.google.android.gms.tasks.Tasks");
                String t = (String) taskCls.getMethod("await", task.getClass().getInterfaces()[0])
                    .invoke(null, task);
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("token", t != null ? t : "");
                invocationCtx.deliverResult(cbId, out.toString());
            } catch (Throwable t) {
                // Most common cause: no google-services.json (Firebase
                // not initialised in this build) → DEFAULT app isn't
                // available → FirebaseMessaging.getInstance throws.
                invocationCtx.deliverResult(cbId, errJson(
                    "FCM not configured — add google-services.json " +
                    "(" + t.getClass().getSimpleName() + ")"));
            }
        }).start();
    }

    @JavascriptInterface
    public void subscribe(String optionsJson, String cbId) {
        if (cbId == null) return;
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            String topic = opts.optString("topic", "*");
            String method = opts.optString("method");
            if (method == null || method.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("method required"));
                return;
            }
            String subId = "push_" + UUID.randomUUID().toString().substring(0, 12);
            JSONObject record = new JSONObject();
            record.put("subId", subId);
            record.put("topic", topic);
            record.put("method", method);
            prefs().edit().putString(subId, record.toString()).apply();
            PluginPushRouter.invalidate();
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("subId", subId);
            out.put("topic", topic);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson("invalid options: " + t.getMessage()));
        }
    }

    @JavascriptInterface
    public void unsubscribe(String subId, String cbId) {
        if (cbId == null) return;
        if (subId == null || subId.isEmpty()) {
            invocationCtx.deliverResult(cbId, errJson("subId required"));
            return;
        }
        boolean existed = prefs().contains(subId);
        prefs().edit().remove(subId).apply();
        PluginPushRouter.invalidate();
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("cancelled", existed);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable ignored) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":false}");
        }
    }

    @JavascriptInterface
    public void list(String cbId) {
        if (cbId == null) return;
        JSONArray arr = new JSONArray();
        for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            try {
                JSONObject rec = new JSONObject(e.getValue().toString());
                arr.put(rec);
            } catch (Throwable ignored) {}
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("subscriptions", arr);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson(t.getMessage()));
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(prefsName(pluginId), Context.MODE_PRIVATE);
    }

    static String prefsName(String pluginId) { return "plugin_" + pluginId + "_iappyx_push"; }

    private static String errJson(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
