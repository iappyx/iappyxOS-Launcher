/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — small helper that capability bridges use to deliver async
 * results back to the plugin's JavaScript. Plays the same role
 * WidgetHost.deliverResult plays for widget bridges: looks up
 * `window._iappyxCb[cbId]` on the plugin WebView and fires it with the
 * JSON result. Marshals to the main thread because WebView.evaluate-
 * Javascript MUST be called there.
 *
 * Lifecycle: one per plugin WebView, owned by the PluginInstance,
 * passed into each capability bridge at construction. The reference is
 * held weakly to the WebView only via this class — bridges never see
 * the WebView directly, so they can't get up to mischief outside the
 * supported path.
 */
package com.iappyx.launcher.plugins;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

public class PluginInvocationContext {

    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    PluginInvocationContext(WebView webView) {
        this.webView = webView;
    }

    /** Deliver a JSON-encoded value to the plugin's `_iappyxCb[cbId]`
     *  callback. [resultJson] MUST be a valid JSON value (string,
     *  number, object, array). Caller is responsible for shaping
     *  `{ok:true, ...}` / `{ok:false, error:"..."}`. */
    public void deliverResult(String cbId, String resultJson) {
        if (cbId == null || resultJson == null) return;
        // Same delivery shape WidgetHost uses: look up the registered
        // callback, delete the entry (one-shot), invoke with the parsed
        // value. We wrap in try/catch so a stray JS exception inside
        // the callback doesn't break subsequent invocations.
        String cbLit = JSONObject.quote(cbId);
        String script = "try { var fn = (window._iappyxCb || {})[" + cbLit + "];" +
            " if (fn) { delete window._iappyxCb[" + cbLit + "]; fn(" + resultJson + "); } }" +
            " catch (e) { try { console.error('plugin cb error', e); } catch(_){} }";
        mainHandler.post(() -> {
            try { webView.evaluateJavascript(script, null); }
            catch (Throwable ignored) { /* webview destroyed mid-invoke */ }
        });
    }
}
