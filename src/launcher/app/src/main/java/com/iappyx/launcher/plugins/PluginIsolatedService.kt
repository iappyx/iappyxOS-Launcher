/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — runs plugins flagged `isolatedProcess: true` in the
 * `:plugin_isolated` Android process. A misbehaving plugin can only
 * crash this process; the launcher's main process keeps running.
 *
 * IPC: Messenger over Service binding. The main process sends:
 *   { what: INVOKE, callId, pluginId, method, argsJson }
 * Receives back:
 *   { what: INVOKE_RESULT, callId, json }
 *
 * Inside this service we mirror PluginHost's logic — per-plugin
 * WebView, lazy-spawn, queue-until-ready, evaluateJavascript with
 * the standard shim. Different process, same behavior.
 *
 * setDataDirectorySuffix MUST run before any WebView constructor in
 * a secondary process (Chromium fatal otherwise — see memory).
 */
package com.iappyx.launcher.plugins

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class PluginIsolatedService : Service() {

    companion object {
        const val MSG_INVOKE = 1
        const val MSG_INVOKE_RESULT = 2

        const val KEY_CALL_ID = "callId"
        const val KEY_PLUGIN_ID = "pluginId"
        const val KEY_METHOD = "method"
        const val KEY_ARGS_JSON = "argsJson"
        const val KEY_RESULT_JSON = "resultJson"

        private const val LOG = "iappyx-plugin-iso"
    }

    private lateinit var serviceMessenger: Messenger
    private val mainHandler = Handler(Looper.getMainLooper())
    private val instances = ConcurrentHashMap<String, IsolatedInstance>()
    /** Plugin-side cbId → main-process Messenger that initiated the
     *  call. Plugin's iappyxPluginInternal.reply lookups by cbId. */
    private val pending = ConcurrentHashMap<String, Pair<Messenger?, String>>()
    private val cbSeq = AtomicLong(0)

    override fun onCreate() {
        // CRITICAL — Chromium crashes if a secondary process instantiates
        // a WebView without a unique data directory suffix.
        try { WebView.setDataDirectorySuffix("plugin_isolated") }
        catch (_: IllegalStateException) { /* already set in this process */ }
        super.onCreate()
        serviceMessenger = Messenger(InHandler())
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    override fun onDestroy() {
        mainHandler.post {
            for ((_, inst) in instances) inst.destroy()
            instances.clear()
        }
        super.onDestroy()
    }

    private inner class InHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_INVOKE -> handleInvoke(msg)
                else -> super.handleMessage(msg)
            }
        }
    }

    private fun handleInvoke(msg: Message) {
        val data = msg.data ?: return
        val callId = data.getString(KEY_CALL_ID) ?: return
        val pluginId = data.getString(KEY_PLUGIN_ID) ?: return
        val method = data.getString(KEY_METHOD) ?: return
        val argsJson = data.getString(KEY_ARGS_JSON) ?: "{}"
        val reply = msg.replyTo

        // Generate a process-local cbId so plugin reply routes back here.
        val cbId = "iso_cb_${cbSeq.incrementAndGet()}"
        pending[cbId] = reply to callId

        val entry = PluginRegistry.get(applicationContext, pluginId)
        if (entry == null || !entry.enabled) {
            sendBack(reply, callId, errJson("plugin not enabled: $pluginId"))
            pending.remove(cbId)
            return
        }

        val inst = instances.getOrPut(pluginId) { createInstance(entry) }
        inst.invoke(cbId, method, argsJson)
    }

    private fun sendBack(reply: Messenger?, callId: String, json: String) {
        if (reply == null) return
        val out = Message.obtain(null, MSG_INVOKE_RESULT)
        val b = Bundle()
        b.putString(KEY_CALL_ID, callId)
        b.putString(KEY_RESULT_JSON, json)
        out.data = b
        try { reply.send(out) }
        catch (_: RemoteException) { /* main process gone — drop */ }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createInstance(entry: PluginRegistry.Entry): IsolatedInstance {
        val webView = WebView(applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(IsolatedInternalBridge(entry.manifest.id), "iappyxPluginInternal")

        val invocationCtx = PluginInvocationContext(webView)
        val namespaces = PluginCapability.attachFor(
            webView = webView,
            pluginId = entry.manifest.id,
            context = applicationContext,
            invocationCtx = invocationCtx,
            capabilities = entry.manifest.capabilities,
        )

        val instance = IsolatedInstance(webView, entry)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mainHandler.postDelayed({ instance.markReadyIfPending() }, 250L)
            }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d(LOG, "[${entry.manifest.id}] ${m.messageLevel().name.lowercase()} ${m.message()}")
                return true
            }
        }

        val shimJs = try {
            applicationContext.assets.open("plugins-system/iappyx-plugin-shim.js")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) { "" }
        val pluginBytes = PluginRegistry.readPluginFile(applicationContext, entry.manifest.id, "plugin.html")
        val pluginHtml = pluginBytes?.toString(Charsets.UTF_8)
            ?: "<!doctype html><body>plugin.html missing</body></html>"
        val capsArray = JSONObject().apply {
            put("ids", org.json.JSONArray().apply { namespaces.forEach { put(it) } })
        }
        val injection = buildString {
            append("<script>window.__iappyxPluginId = ")
            append(JSONObject.quote(entry.manifest.id))
            append("; window.__iappyxPluginCaps = ")
            append(capsArray.toString())
            append("; window.__iappyxPluginIsolated = true;</script>\n")
            append("<script>").append(shimJs).append("</script>\n")
        }
        val headIdx = pluginHtml.indexOf("<head", ignoreCase = true)
        val composed = if (headIdx >= 0) {
            val gt = pluginHtml.indexOf('>', startIndex = headIdx)
            if (gt < 0) "<head>$injection</head>$pluginHtml"
            else pluginHtml.substring(0, gt + 1) + injection + pluginHtml.substring(gt + 1)
        } else {
            "<!doctype html><html><head>$injection</head><body>$pluginHtml</body></html>"
        }
        val baseUrl = PluginRegistry.baseUrlFor(applicationContext, entry)
        webView.loadDataWithBaseURL(baseUrl, composed, "text/html", "utf-8", null)
        return instance
    }

    /** Bridge attached to each isolated plugin's WebView so plugin
     *  JS can declare exports / reply to invocations / log. Same
     *  shape as PluginHost's PluginInternalBridge, just lives in
     *  this process. */
    private inner class IsolatedInternalBridge(private val pluginId: String) {
        @JavascriptInterface
        fun declareExports(@Suppress("UNUSED_PARAMETER") exportsJson: String?) {
            mainHandler.post { instances[pluginId]?.markReady() }
        }

        @JavascriptInterface
        fun reply(cbId: String?, json: String?) {
            if (cbId == null || json == null) return
            val entry = pending.remove(cbId) ?: return
            val (reply, callId) = entry
            sendBack(reply, callId, json)
        }

        @JavascriptInterface
        fun log(message: String?) {
            android.util.Log.d(LOG, "[$pluginId] ${message ?: "(null)"}")
        }
    }

    internal inner class IsolatedInstance(
        val webView: WebView,
        val entry: PluginRegistry.Entry,
    ) {
        @Volatile private var ready = false
        private val queued = ArrayDeque<Triple<String, String, String>>()

        fun invoke(cbId: String, method: String, argsJson: String) {
            val drainNow = synchronized(queued) {
                if (!ready) { queued.add(Triple(cbId, method, argsJson)); return }
                true
            }
            if (drainNow) doInvoke(cbId, method, argsJson)
        }

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

        fun markReadyIfPending() { if (!ready) markReady() }

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
            mainHandler.post { webView.evaluateJavascript(script, null) }
        }

        fun destroy() { try { webView.destroy() } catch (_: Throwable) {} }
    }

    private fun errJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
