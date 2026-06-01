/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — caller-side bridge. Attached to widgets and wallpapers via
 * `iappyxPlugins`. Routes `invoke(pluginId, method, argsJson, cbId)`
 * into the PluginHost, then forwards the plugin's reply back to the
 * caller via the WidgetHost's existing cbId machinery — same shape as
 * every other bridge in the launcher.
 *
 * Sole external entry point: an `@JavascriptInterface` instance method
 * attached by `PluginsModule.attachCallerBridge`. No static state.
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.iappyx.launcher.WidgetHost;

public class PluginsBridge {

    private final WidgetHost host;

    public PluginsBridge(WidgetHost host) {
        this.host = host;
    }

    public void attachTo(WebView webView) {
        webView.addJavascriptInterface(this, "iappyxPlugins");
    }

    /** Invoke a plugin method. JS shape:
     *    iappyxPlugins.invoke(pluginId, method, argsJson, cbId);
     *  Result is delivered via window._iappyxCb[cbId]({ok, result|error}).
     *  Errors are also delivered through the same callback — never thrown. */
    @JavascriptInterface
    public void invoke(String pluginId, String method, String argsJson, String cbId) {
        // No cbId → fire-and-forget. We still perform the invocation
        // for the side-effects (a plugin author calling `iappyxPlugins.invoke`
        // without expecting a reply), but we can't deliver errors anywhere
        // so we just drop them.
        final String safeArgs = (argsJson == null || argsJson.isEmpty()) ? "{}" : argsJson;
        if (cbId == null) {
            if (pluginId == null || method == null) return;
            Context ctx = host.getApplicationContext();
            PluginHost.invoke(ctx, pluginId, method, safeArgs, json -> { /* drop */ });
            return;
        }
        if (pluginId == null || pluginId.isEmpty() || method == null || method.isEmpty()) {
            host.pluginDeliverResult(cbId,
                "{\"ok\":false,\"error\":\"pluginId and method are required\"}");
            return;
        }
        Context ctx = host.getApplicationContext();
        PluginHost.invoke(ctx, pluginId, method, safeArgs,
            json -> host.pluginDeliverResult(cbId, json));
    }

    /** Lightweight introspection for callers — returns a JSON array of
     *  installed-and-enabled plugin ids. Useful for widgets that want
     *  to feature-detect ("does this device have the Immich plugin?")
     *  before issuing a real invoke. */
    @JavascriptInterface
    public void list(String cbId) {
        if (cbId == null) return;
        Context ctx = host.getApplicationContext();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (PluginRegistry.Entry e : PluginRegistry.INSTANCE.all(ctx)) {
            if (!e.getEnabled()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('{')
              .append("\"id\":\"").append(escape(e.getManifest().getId())).append("\",")
              .append("\"name\":\"").append(escape(e.getManifest().getName())).append("\",")
              .append("\"version\":\"").append(escape(e.getManifest().getVersion())).append("\"")
              .append('}');
        }
        sb.append(']');
        host.pluginDeliverResult(cbId,
            "{\"ok\":true,\"plugins\":" + sb.toString() + "}");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
