/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — runtime host for plugin WebViews. Each enabled plugin gets
 * its own hidden WebView with the plugin shim injected; callers invoke
 * exposed methods via `iappyxPlugins.invoke(...)` which routes here.
 *
 * Lifecycle (P1):
 *  - Plugin WebView is lazily created on first invoke().
 *  - Stays alive until shutdown() is called or the launcher process
 *    dies. Idle eviction lands in P2.
 *
 * Threading:
 *  - WebView creation, loading, and evaluateJavascript must happen on
 *    the main thread. All entry points marshal there via mainHandler.
 *  - The pending callback map is concurrent; replies arrive on the JS
 *    thread (from `iappyxPluginInternal.reply`) and we don't hop back
 *    to the main thread to dispatch them — the caller's
 *    `WidgetHost.deliverResult` handles its own threading.
 */
package com.iappyx.launcher.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Callback shape used by the caller bridge to receive plugin replies.
 *  Implemented as a SAM interface so Java callers (PluginsBridge.java)
 *  can pass a lambda without wrestling with Kotlin's Function1 type. */
fun interface PluginResultCallback {
    /** [json] is always a JSON string of shape:
     *    {"ok":true, "result": <any>} on success
     *    {"ok":false, "error":"..."} on failure  */
    fun onResult(json: String)
}

internal object PluginHost {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Plugin id → instance. Concurrent because `invoke` may be called
     *  off the main thread, but mutations to the map happen ONLY on
     *  the main thread inside `mainHandler.post { ... }`. The
     *  concurrent map is a defense-in-depth so a future change that
     *  reads outside the main thread doesn't trip over a half-written
     *  entry. */
    private val instances = ConcurrentHashMap<String, PluginInstance>()

    /** Plugin-side cbId → caller-side reply callback. The plugin's JS
     *  calls `iappyxPluginInternal.reply(cbId, json)`; we look up the
     *  callback here and forward the result. */
    private val pending = ConcurrentHashMap<String, PluginResultCallback>()

    private val cbIdSeq = AtomicLong(0)

    /** Public entry point — called from PluginsBridge on the JS thread.
     *  Result is delivered asynchronously through [onResult]. */
    @JvmStatic
    fun invoke(
        context: Context,
        pluginId: String,
        method: String,
        argsJson: String,
        onResult: PluginResultCallback,
    ) {
        val entry = PluginRegistry.get(context, pluginId)
        if (entry == null) {
            onResult.onResult(errJson("no such plugin: $pluginId"))
            return
        }
        if (!entry.enabled) {
            onResult.onResult(errJson("plugin not enabled: $pluginId"))
            return
        }
        // Network-trust gate: when the plugin is restricted to trusted
        // networks (e.g. self-hosted HA/Immich), short-circuit before
        // the bridge ever fires. Bearer tokens stay on the device.
        val verdict = PluginNetworkTrust.evaluate(context, entry.manifest)
        if (!verdict.allowed) {
            onResult.onResult(errJson(verdict.reason))
            return
        }
        // Plugins with `isolatedProcess: true` run in :plugin_isolated;
        // route invocations through the Messenger client rather than
        // spinning up an in-process WebView. The remote service
        // handles its own lazy WebView spawn + queue.
        if (entry.manifest.isolatedProcess) {
            PluginIsolatedClient.invoke(context, pluginId, method, argsJson, onResult)
            return
        }
        // Allocate a unique cbId we'll watch for the plugin's reply.
        // Prefix `p_cb_` so caller-side cbIds (typically `_cb_…`) and
        // plugin-side ones never collide in shared maps if anyone ever
        // unifies them.
        val cbId = "p_cb_${cbIdSeq.incrementAndGet()}"
        pending[cbId] = onResult
        mainHandler.post {
            try {
                val inst = instances.getOrPut(entry.manifest.id) {
                    createInstance(context, entry)
                }
                inst.invoke(cbId, method, argsJson)
            } catch (t: Throwable) {
                // Creation failure shouldn't dangle the callback —
                // immediate error reply so the caller doesn't wait
                // forever.
                pending.remove(cbId)?.onResult(errJson("plugin host error: ${t.message}"))
            }
        }
    }

    /** Called from the plugin's WebView via the internal bridge. JS
     *  thread; no marshalling. */
    @JvmStatic
    fun deliverPluginResult(cbId: String, json: String) {
        val cb = pending.remove(cbId) ?: return
        try { cb.onResult(json) } catch (_: Throwable) {}
    }

    /** Tear down every plugin WebView. Optional — process death frees
     *  WebViews implicitly. Useful when memory pressure shows up. */
    @JvmStatic
    fun shutdown(@Suppress("UNUSED_PARAMETER") context: Context) {
        mainHandler.post {
            for ((_, inst) in instances) inst.destroy()
            instances.clear()
            pending.clear()
        }
    }

    /** Tear down ONE plugin's WebView and drop it from the cache.
     *  Called after install (so an upgrade picks up new plugin.html
     *  on the next invoke) and after uninstall. Pending callbacks
     *  registered against that plugin's cbIds are NOT cleared — they
     *  belong to the caller, not the plugin instance, and they'll
     *  fire normally if the new instance replies before they time
     *  out (or hang harmlessly if the plugin is gone). */
    @JvmStatic
    fun shutdownPlugin(@Suppress("UNUSED_PARAMETER") context: Context, pluginId: String) {
        mainHandler.post {
            instances.remove(pluginId)?.destroy()
        }
    }

    // ── internals ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createInstance(context: Context, entry: PluginRegistry.Entry): PluginInstance {
        // App context: the WebView outlives any one activity, and we
        // never need an activity-bound theme. Avoids leaks.
        val appCtx = context.applicationContext
        val webView = WebView(appCtx)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        // Internal bridge — always attached. Plugin uses it to declare
        // its export map, reply to host invocations, and log.
        webView.addJavascriptInterface(PluginInternalBridge(entry.manifest.id), "iappyxPluginInternal")

        // Capability bridges (http, storage, secureStore, …) — only
        // those declared in the plugin's manifest.capabilities are
        // attached. Bridges share a single invocation context that
        // routes their async replies back through `_iappyxCb[cbId]`
        // on the plugin's WebView, same as widgets.
        val invocationCtx = PluginInvocationContext(webView)
        val attachedNamespaces = PluginCapability.attachFor(
            webView = webView,
            pluginId = entry.manifest.id,
            context = appCtx,
            invocationCtx = invocationCtx,
            capabilities = entry.manifest.capabilities,
        )

        val instance = PluginInstance(webView, entry)

        // Fallback ready signal: declareExports() in the shim signals
        // ready the moment iappyx.plugin.export() runs. If a plugin
        // forgets to call export (malformed), we still drain its
        // queue 250ms after onPageFinished so callers don't wait
        // forever — they'll just get unknown-method errors instead.
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mainHandler.postDelayed({ instance.markReadyIfPending() }, 250L)
            }
        }
        // Surface plugin-side JS console output to logcat. Without
        // this, errors thrown from the doInvoke evaluateJavascript
        // script vanish silently — and any plugin author's debug
        // `console.log` is lost. Tag matches the plugin id so multi-
        // plugin debugging stays readable.
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                val level = m.messageLevel().name.lowercase()
                android.util.Log.d(
                    LOG_TAG,
                    "[${entry.manifest.id}] console.$level ${m.message()} " +
                        "@${m.sourceId()}:${m.lineNumber()}",
                )
                return true
            }
        }

        val shimJs = loadAsset(appCtx, "plugins-system/iappyx-plugin-shim.js") ?: ""
        val pluginBytes = PluginRegistry.readPluginFile(appCtx, entry.manifest.id, "plugin.html")
        val pluginHtml = pluginBytes?.toString(Charsets.UTF_8)
            ?: "<!doctype html><body>plugin.html missing</body></html>"
        val composed = composeHtml(shimJs, pluginHtml, entry.manifest.id, attachedNamespaces)
        val baseUrl = PluginRegistry.baseUrlFor(appCtx, entry)
        webView.loadDataWithBaseURL(baseUrl, composed, "text/html", "utf-8", null)
        return instance
    }

    private fun composeHtml(shim: String, pluginHtml: String, pluginId: String, namespaces: List<String>): String {
        // Inject the shim, plugin id, and the list of attached
        // capability namespaces BEFORE any of plugin.html's scripts
        // run. The shim reads `__iappyxPluginCaps` to expose the
        // matching `iappyx.<namespace>` properties — namespaces whose
        // bridge isn't attached simply don't appear.
        val capsArray = JSONObject().apply {
            put("ids", org.json.JSONArray().apply { namespaces.forEach { put(it) } })
        }
        val injection = buildString {
            append("<script>window.__iappyxPluginId = ")
            append(JSONObject.quote(pluginId))
            append("; window.__iappyxPluginCaps = ")
            append(capsArray.toString())
            append(";</script>\n")
            append("<script>")
            append(shim)
            append("</script>\n")
        }
        val headIdx = pluginHtml.indexOf("<head", ignoreCase = true)
        return if (headIdx >= 0) {
            val gt = pluginHtml.indexOf('>', startIndex = headIdx)
            if (gt < 0) "<head>$injection</head>$pluginHtml"
            else pluginHtml.substring(0, gt + 1) + injection + pluginHtml.substring(gt + 1)
        } else {
            "<!doctype html><html><head>$injection</head><body>$pluginHtml</body></html>"
        }
    }

    private fun loadAsset(ctx: Context, path: String): String? = try {
        ctx.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Throwable) { null }

    private fun errJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /** Internal @JavascriptInterface attached to plugin WebViews so
     *  plugin JS can: (a) declare its export map, (b) reply to host
     *  invocations, (c) log to logcat for debugging. NOT exposed to
     *  widgets/wallpapers — they get `iappyxPlugins` instead. */
    private class PluginInternalBridge(private val pluginId: String) {
        @JavascriptInterface
        fun declareExports(exportsJson: String?) {
            // Marks the plugin "ready" — any queued invocations drain.
            // We don't validate against manifest.exposes here (P3); the
            // plugin's runtime export map is authoritative.
            mainHandler.post {
                instances[pluginId]?.markReady()
            }
        }

        @JavascriptInterface
        fun reply(cbId: String?, json: String?) {
            if (cbId == null || json == null) return
            deliverPluginResult(cbId, json)
        }

        @JavascriptInterface
        fun log(message: String?) {
            android.util.Log.d(LOG_TAG, "[$pluginId] ${message ?: "(null)"}")
        }
    }

    private const val LOG_TAG = "iappyx-plugin"
}

/** One plugin's runtime state. Each instance owns one WebView and a
 *  queue of invocations that landed before the plugin signalled ready. */
internal class PluginInstance(
    val webView: WebView,
    val entry: PluginRegistry.Entry,
) {
    @Volatile private var ready: Boolean = false
    private val queued = ArrayDeque<Triple<String, String, String>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun invoke(cbId: String, method: String, argsJson: String) {
        val drainNow = synchronized(queued) {
            if (!ready) { queued.add(Triple(cbId, method, argsJson)); return }
            true
        }
        if (drainNow) doInvoke(cbId, method, argsJson)
    }

    /** Called from the shim's `iappyx.plugin.export(...)` path —
     *  authoritative ready signal. */
    fun markReady() {
        val pending = synchronized(queued) {
            if (ready) return
            ready = true
            val copy = queued.toList()
            queued.clear()
            copy
        }
        for ((cb, m, a) in pending) doInvoke(cb, m, a)
    }

    /** Fallback path — onPageFinished + 250ms. If the plugin already
     *  signalled ready via declareExports, this is a no-op. */
    fun markReadyIfPending() {
        if (!ready) markReady()
    }

    private fun doInvoke(cbId: String, method: String, argsJson: String) {
        val cbLit = JSONObject.quote(cbId)
        val methodLit = JSONObject.quote(method)
        val argsLit = JSONObject.quote(argsJson)
        val script = """
            (async function() {
              var cbId = $cbLit;
              var method = $methodLit;
              try {
                var fn = (window._iappyxPluginExports || {})[method];
                if (typeof fn !== 'function') {
                  iappyxPluginInternal.reply(cbId, JSON.stringify({ok:false, error:"unknown method: " + method}));
                  return;
                }
                var args = JSON.parse($argsLit);
                var r = await fn(args);
                iappyxPluginInternal.reply(cbId, JSON.stringify({ok:true, result:r}));
              } catch (e) {
                iappyxPluginInternal.reply(cbId, JSON.stringify({ok:false, error: String((e && e.message) || e)}));
              }
            })();
        """.trimIndent()
        // Plugin WebViews are never attached to a window — they're
        // hidden, off-screen runtime contexts. `webView.post(...)`
        // routes through View.getRunQueue() and queues until
        // dispatchAttachedToWindow fires, which it never does here, so
        // the Runnable would sit there forever and evaluateJavascript
        // would never run (plugins appear "loaded but unresponsive").
        // Post directly to the main-thread handler — evaluateJavascript
        // is safe from the main thread without window attachment.
        mainHandler.post { webView.evaluateJavascript(script, null) }
    }

    fun destroy() {
        // Notify the MQTT bridge BEFORE the WebView dies so it can
        // force-disconnect its Paho client + stop background threads.
        // No-op when the plugin didn't declare the `mqtt` capability
        // (no registry entry).
        try { PluginMqttBridge.onPluginDestroyed(entry.manifest.id) }
        catch (_: Throwable) {}
        try { webView.destroy() } catch (_: Throwable) {}
    }
}
