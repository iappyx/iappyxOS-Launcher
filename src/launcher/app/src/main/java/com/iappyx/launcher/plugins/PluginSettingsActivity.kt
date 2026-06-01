/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — Activity that hosts a plugin's `settings.html`. Same shim,
 * same capability bridges, same plugin id namespacing as the runtime
 * plugin host. Plugin authors typically use this page to let the user
 * configure credentials (URL + API key) via `iappyx.secureStore.set`,
 * preferences via `iappyx.storage.save`, and test the connection.
 *
 * Lifecycle:
 *   - Activity expects EXTRA_PLUGIN_ID. If missing or unknown, finish().
 *   - WebView is the activity's content view (no Settings chrome).
 *   - Plugin signals "done" by calling `iappyx.plugin.close()`; the
 *     bridge maps that to finish().
 *
 * Storage scope: same per-plugin SharedPreferences as the runtime,
 * so settings.html and plugin.html see the same data.
 */
package com.iappyx.launcher.plugins

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class PluginSettingsActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val entry = PluginRegistry.get(this, pluginId)
        if (entry == null || entry.manifest.settingsUi == null) {
            finish(); return
        }

        val webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        // Internal: declare/reply/log + a small `close()` for the page
        // to dismiss itself.
        webView.addJavascriptInterface(SettingsInternalBridge(), "iappyxPluginInternal")

        // Same capability bridges as the runtime plugin webview. Lets
        // settings.html call iappyx.secureStore.set('apiKey', value)
        // to persist credentials, iappyx.storage.save(...) for prefs,
        // iappyx.httpClient.fetch(...) for "test connection" probes.
        val invocationCtx = PluginInvocationContext(webView)
        val attachedNamespaces = PluginCapability.attachFor(
            webView = webView,
            pluginId = pluginId,
            context = applicationContext,
            invocationCtx = invocationCtx,
            capabilities = entry.manifest.capabilities,
        )

        val shimJs = loadAsset("plugins-system/iappyx-plugin-shim.js") ?: ""
        val settingsBytes = PluginRegistry.readPluginFile(this, pluginId, entry.manifest.settingsUi)
        val settingsHtml = settingsBytes?.toString(Charsets.UTF_8)
            ?: "<!doctype html><body>settings.html missing</body></html>"
        val composed = composeHtml(shimJs, settingsHtml, pluginId, attachedNamespaces)
        val baseUrl = PluginRegistry.baseUrlFor(this, entry)
        webView.loadDataWithBaseURL(baseUrl, composed, "text/html", "utf-8", null)
    }

    private inner class SettingsInternalBridge {
        @JavascriptInterface
        fun declareExports(@Suppress("UNUSED_PARAMETER") exportsJson: String?) { /* unused here */ }

        @JavascriptInterface
        fun reply(cbId: String?, json: String?) {
            if (cbId == null || json == null) return
            PluginHost.deliverPluginResult(cbId, json)
        }

        @JavascriptInterface
        fun log(message: String?) {
            android.util.Log.d("iappyx-plugin-settings", message ?: "(null)")
        }

        /** Bridge for `iappyx.plugin.close()` calls — the page signals
         *  it's done; we finish() the activity. Marshalled to the main
         *  thread because @JavascriptInterface runs on a background
         *  thread. */
        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    private fun loadAsset(path: String): String? = try {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Throwable) { null }

    private fun composeHtml(shim: String, html: String, pluginId: String, namespaces: List<String>): String {
        val capsArray = JSONObject().apply {
            put("ids", org.json.JSONArray().apply { namespaces.forEach { put(it) } })
        }
        val injection = buildString {
            append("<script>window.__iappyxPluginId = ")
            append(JSONObject.quote(pluginId))
            append("; window.__iappyxPluginCaps = ")
            append(capsArray.toString())
            // Augment the shim with an iappyx.plugin.close() that the
            // page calls when it wants to dismiss. Mirrors the
            // `iappyxPluginInternal.close()` Java bridge.
            append("; window.__iappyxPluginInSettings = true;</script>\n")
            append("<script>")
            append(shim)
            // Append a tiny post-shim block to add the close() helper.
            // Sits in the same `iappyx.plugin` namespace the shim just
            // created so it reads naturally to plugin authors.
            append(";try { window.iappyx.plugin.close = function(){")
            append(" try { iappyxPluginInternal.close(); } catch(_){}")
            append(" }; } catch(_) {}")
            append("</script>\n")
        }
        val headIdx = html.indexOf("<head", ignoreCase = true)
        return if (headIdx >= 0) {
            val gt = html.indexOf('>', startIndex = headIdx)
            if (gt < 0) "<head>$injection</head>$html"
            else html.substring(0, gt + 1) + injection + html.substring(gt + 1)
        } else {
            "<!doctype html><html><head>$injection</head><body>$html</body></html>"
        }
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }
}
