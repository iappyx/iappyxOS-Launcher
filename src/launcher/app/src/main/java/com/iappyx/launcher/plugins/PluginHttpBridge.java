/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `http` capability. Standalone OkHttp client; no cookie jar
 * (plugins manage their own auth via headers), no trust-all option
 * (plugins should talk to real TLS endpoints). Mirrors a minimal slice
 * of WidgetHost.HttpClientBridge so plugin JS can call iappyx.httpClient.fetch
 * with the same shape as widgets.
 *
 * Usage from plugin JS (via the shim):
 *   const r = await iappyx.httpClient.fetch({
 *     url: 'https://example.com/api/photos',
 *     method: 'GET',
 *     headers: { 'x-api-key': key },
 *   });
 *   // r = { ok: true, status: 200, body: "..." }
 */
package com.iappyx.launcher.plugins;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PluginHttpBridge {

    private final PluginInvocationContext ctx;
    private final OkHttpClient client;

    PluginHttpBridge(PluginInvocationContext ctx) {
        this.ctx = ctx;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /** Per-request permissive client with DNS pinned to [pinnedAddr]
     *  for [host]. Without DNS pinning, OkHttp would re-resolve the
     *  host at connect time and a DNS-rebinding attacker (TTL=0)
     *  could substitute a public IP between our gate check and the
     *  socket connection. With pinning, OkHttp uses the same address
     *  we verified. Redirects are disabled — a redirect target could
     *  evade the LAN gate entirely. */
    private OkHttpClient permissiveClientPinned(final String host,
                                                final java.net.InetAddress pinnedAddr) {
        return new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(
                PluginInsecureHttp.permissiveSocketFactory(),
                PluginInsecureHttp.trustAllManager())
            .hostnameVerifier(PluginInsecureHttp.PERMISSIVE_HOSTNAME_VERIFIER)
            .dns(new okhttp3.Dns() {
                @Override
                public java.util.List<java.net.InetAddress> lookup(String h)
                        throws java.net.UnknownHostException {
                    if (h != null && h.equalsIgnoreCase(host)) {
                        return java.util.Collections.singletonList(pinnedAddr);
                    }
                    return okhttp3.Dns.SYSTEM.lookup(h);
                }
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    @JavascriptInterface
    public void fetch(String optionsJson, String cbId) {
        if (cbId == null) return;
        // @JavascriptInterface invocations already arrive on a
        // background thread, but we still spawn a worker so a slow
        // server doesn't pin the WebView's JS-binder thread for the
        // 30-second readTimeout.
        new Thread(() -> {
            try {
                JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
                String url = opts.optString("url");
                if (url.isEmpty()) {
                    ctx.deliverResult(cbId, errJson("url required"));
                    return;
                }
                // Permissive TLS opt-in: only honoured if the target
                // host resolves to a LAN address. Public hosts silently
                // fall back to strict validation — the flag is for
                // local devices with self-signed certs (Hue Bridge Pro,
                // Reolink, Bambu) and nothing else.
                boolean insecureRequested = opts.optBoolean("insecure", false);
                String host = null;
                try { host = new java.net.URI(url).getHost(); } catch (Throwable ignored) {}
                // Resolve ONCE and pin: a separate DNS lookup at OkHttp
                // connect time could be rebound to a non-LAN address.
                java.net.InetAddress pinnedAddr = insecureRequested
                    ? PluginInsecureHttp.resolveLanAddress(host)
                    : null;
                boolean useInsecure = pinnedAddr != null;
                OkHttpClient effectiveClient = useInsecure
                    ? permissiveClientPinned(host, pinnedAddr)
                    : client;
                String method = opts.optString("method", "GET").toUpperCase();
                JSONObject headers = opts.optJSONObject("headers");
                String body = opts.has("body") ? opts.optString("body", null) : null;

                Request.Builder rb = new Request.Builder().url(url);
                if (headers != null) {
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        rb.header(k, headers.optString(k));
                    }
                }
                RequestBody reqBody;
                if (body != null) {
                    String contentType = headers != null
                        ? headers.optString("Content-Type", "application/json")
                        : "application/json";
                    reqBody = RequestBody.create(body, MediaType.parse(contentType));
                } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                    // OkHttp requires an explicit empty body for these methods.
                    reqBody = RequestBody.create(new byte[0]);
                } else {
                    reqBody = null;
                }
                rb.method(method, reqBody);

                // `responseType` opt-in: "text" (default) returns body
                // as UTF-8 string; "base64" returns the bytes base64-
                // encoded. Binary endpoints (image thumbnails,
                // arbitrary file fetches) must use "base64" or the
                // body comes back corrupted at the first non-UTF-8 byte.
                String responseType = opts.optString("responseType", "text");
                try (Response resp = effectiveClient.newCall(rb.build()).execute()) {
                    String respBody;
                    if ("base64".equalsIgnoreCase(responseType)) {
                        byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
                        respBody = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    } else {
                        respBody = resp.body() != null ? resp.body().string() : "";
                    }
                    // Best effort: surface response headers too so
                    // plugins can read Content-Type / pagination
                    // links without re-fetching.
                    JSONObject respHeaders = new JSONObject();
                    for (String name : resp.headers().names()) {
                        respHeaders.put(name, resp.header(name));
                    }
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("status", resp.code());
                    out.put("body", respBody);
                    out.put("headers", respHeaders);
                    ctx.deliverResult(cbId, out.toString());
                }
            } catch (Throwable e) {
                ctx.deliverResult(cbId, errJson(e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }).start();
    }

    private static String errJson(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
