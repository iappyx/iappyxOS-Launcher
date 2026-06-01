/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — bridge proxy.
 *
 * Browser-side iframe widgets call iappyx.foo.bar(args, cbId) which our
 * shim turns into POST /api/bridge/call. This handler dispatches the call
 * to a real WidgetHost on the phone via [WidgetHost.invokeBridge].
 *
 * Two call shapes:
 *   sync   — method returns a value. Wait for the value, return it.
 *   async  — caller passes cbId. Register a CompletableFuture against
 *            cbId; the bridge eventually calls deliverResult which our
 *            short-circuit completes the future. Wait, return.
 *
 * The browser shim picks per-method whether to send cbId. Methods that
 * never call a callback are sync; everything else is async.
 *
 * Per-iframe sessions: each iframe gets a session id, gets its own
 * WidgetHost (so saveFile/sqlite are isolated per widget instance, like
 * on the phone). Sessions time out after 90s of inactivity.
 */
package com.iappyx.launcher.remoteedit.api

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.iappyx.launcher.WidgetHost
import com.iappyx.launcher.remoteedit.RemoteEditWebView
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.remoteedit.server.SseEmitter
import com.iappyx.launcher.widget.WidgetSandbox
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class BridgeProxyApi(private val activity: Activity) {

    private data class Session(
        val widgetHost: WidgetHost,
        val webView: WebView,
        @Volatile var lastUsed: Long,
    )

    private val sessions = mutableMapOf<String, Session>()
    private val sessionTimeoutMs = 90_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    /** SSE emitters per widget — receives streaming bridge callbacks
     *  (sensor.subscribe, location.watch, etc.). A widget can have
     *  MULTIPLE concurrent emitters: same widget rendered in multiple
     *  cells, multiple browser tabs, EventSource auto-reconnect after
     *  flake. We broadcast every event to all of them so no client gets
     *  starved. Stale entries are cleared on the next emit attempt
     *  (write fails → close → remove). */
    private val sessionEmitters = ConcurrentHashMap<String, MutableList<SseEmitter>>()

    /** Subscriptions per session — maps proxy cbId → original cbId so we
     *  can clean up on session end. */
    private val sessionSubs = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** Heuristic: methods that begin with subscribe/watch/listen/observe
     *  are streaming (their cbId fires repeatedly). Anything else is one-
     *  shot. */
    private fun isStreamingMethod(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("subscribe") || n.startsWith("watch") ||
               n.startsWith("listen") || n.startsWith("observe")
    }

    /** POST /api/bridge/call
     *  Body: {
     *    widgetId: "weather"           — the widget instance whose
     *                                     storage/sqlite/etc. should back
     *                                     the call. Multiple cells of the
     *                                     same widget share storage, just
     *                                     like on the phone.
     *    bridge:   "iappyxLocation",
     *    method:   "getCurrent",
     *    args:     [ ... ],
     *    cbId:     "optional, present for async methods",
     *    timeoutMs: optional, default 10000
     *  }
     *  Response: { ok: true, result: <method's return value, or json from cb> }
     */
    fun call(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: run {
            JsonResponse.error(ex, 400, "no body"); return
        }
        val widgetId = obj.optString("widgetId").ifBlank {
            // Backward-compat: accept "session" prefixed with "cell-" or
            // "widget-" so older callers keep working.
            obj.optString("session").removePrefix("cell-").removePrefix("widget-")
        }
        if (widgetId.isBlank()) {
            JsonResponse.error(ex, 400, "missing widgetId"); return
        }
        val bridge = obj.optString("bridge").ifBlank {
            JsonResponse.error(ex, 400, "missing bridge"); return
        }
        val method = obj.optString("method").ifBlank {
            JsonResponse.error(ex, 400, "missing method"); return
        }
        val argsArr = obj.optJSONArray("args") ?: JSONArray()
        val cbId = obj.optString("cbId").ifBlank { null }
        val timeoutMs = obj.optLong("timeoutMs", 10_000L)

        val session = try { getOrCreateSession(widgetId) }
        catch (t: Throwable) {
            JsonResponse.error(ex, 500, "session: ${t.message}"); return
        }
        session.lastUsed = System.currentTimeMillis()
        sweepStaleSessions()

        // Convert JSON args to native objects, replacing the cbId placeholder
        // (if any) with the registered cbId so the widget host can find the
        // callback in its proxy map.
        val argsList = MutableList(argsArr.length()) { i -> argsArr.opt(i) }

        if (cbId != null) {
            val proxyCbId = "iax_proxy_$widgetId-${System.nanoTime()}"
            // Replace the browser-supplied cbId with our generated proxy id
            // in the args. The bridge will call deliverResult(proxyCbId, ...)
            // and our short-circuit routes it to the registered Consumer.
            for (i in argsList.indices) {
                if (argsList[i] == cbId) argsList[i] = proxyCbId
            }

            if (isStreamingMethod(method)) {
                // ── Streaming path ─────────────────────────────────────
                // Register a Consumer that pushes every result into the
                // session's SSE emitter, tagged with the ORIGINAL cbId so
                // the browser can fire its registered callback. Stays
                // registered until /api/bridge/unsubscribe (or session
                // teardown).
                val origCbId = cbId
                WidgetHost.registerProxyCallback(proxyCbId, Consumer { json ->
                    val parsed: Any? = try {
                        when {
                            json.trimStart().startsWith("{") -> JSONObject(json)
                            json.trimStart().startsWith("[") -> JSONArray(json)
                            else -> json
                        }
                    } catch (_: Throwable) { json }
                    val payload = JSONObject().put("cbId", origCbId).put("value", parsed)
                    broadcastToWidget(widgetId, "bridge-cb", payload.toString())
                })
                sessionSubs.getOrPut(widgetId) { mutableMapOf() }[proxyCbId] = origCbId
                // Fire the bridge call. Don't wait — streaming has no
                // single completion.
                mainHandler.post {
                    try {
                        session.widgetHost.invokeBridge(bridge, method, argsList.toTypedArray())
                    } catch (t: Throwable) {
                        WidgetHost.unregisterProxyCallback(proxyCbId)
                        sessionSubs[widgetId]?.remove(proxyCbId)
                    }
                }
                JsonResponse.ok(ex, JSONObject().put("ok", true).put("streaming", true))
            } else {
                // ── One-shot async path ────────────────────────────────
                val future = CompletableFuture<String>()
                WidgetHost.registerProxyCallback(proxyCbId, Consumer { json ->
                    WidgetHost.unregisterProxyCallback(proxyCbId)
                    future.complete(json)
                })
                mainHandler.post {
                    try {
                        session.widgetHost.invokeBridge(bridge, method, argsList.toTypedArray())
                    } catch (t: Throwable) {
                        WidgetHost.unregisterProxyCallback(proxyCbId)
                        future.completeExceptionally(t)
                    }
                }
                try {
                    val resultJson = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                    respondWithJsonResult(ex, resultJson)
                } catch (e: java.util.concurrent.TimeoutException) {
                    WidgetHost.unregisterProxyCallback(proxyCbId)
                    JsonResponse.error(ex, 504, "bridge call timed out")
                } catch (t: Throwable) {
                    JsonResponse.error(ex, 500, "${t.javaClass.simpleName}: ${t.message}")
                }
            }
        } else {
            // Sync path. Invoke on main thread, return the value.
            val resultFuture = CompletableFuture<Any?>()
            mainHandler.post {
                try {
                    val r = session.widgetHost.invokeBridge(bridge, method, argsList.toTypedArray())
                    resultFuture.complete(r)
                } catch (t: Throwable) {
                    resultFuture.completeExceptionally(t)
                }
            }
            try {
                val r = resultFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
                JsonResponse.ok(ex, JSONObject().apply {
                    put("ok", true)
                    put("result", normalizeResult(r))
                })
            } catch (e: java.util.concurrent.TimeoutException) {
                JsonResponse.error(ex, 504, "bridge call timed out")
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                JsonResponse.error(ex, 500, "${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
    }

    /** GET /api/bridge/events?widgetId=X
     *  Server-Sent Events stream of bridge callback fires for streaming
     *  subscriptions registered against this widget's session. */
    fun events(ex: MicroHttpServer.Exchange) {
        val widgetId = parseQuery(ex.request.query)["widgetId"]
        if (widgetId.isNullOrBlank()) {
            JsonResponse.error(ex, 400, "missing widgetId"); return
        }
        android.util.Log.d("iappyxRemoteEdit", "SSE open widget=$widgetId")
        val emitter = SseEmitter(ex)
        emitter.start()
        val list = sessionEmitters.computeIfAbsent(widgetId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        list.add(emitter)
        try { emitter.awaitClose() }
        finally {
            list.remove(emitter)
            android.util.Log.d("iappyxRemoteEdit", "SSE close widget=$widgetId remaining=${list.size}")
        }
    }

    /** POST /api/bridge/unsubscribe { widgetId, cbId } — single unsubscribe.
     *  cbId is the BROWSER-side cbId; we look up the matching proxyCbId
     *  in the session subscriptions and tear down both the WidgetHost
     *  callback and our session-tracking entry. */
    fun unsubscribe(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: run {
            JsonResponse.error(ex, 400, "no body"); return
        }
        val widgetId = obj.optString("widgetId").ifBlank {
            JsonResponse.error(ex, 400, "missing widgetId"); return
        }
        val cbId = obj.optString("cbId").ifBlank {
            JsonResponse.error(ex, 400, "missing cbId"); return
        }
        val subs = sessionSubs[widgetId]
        if (subs != null) {
            val proxyCb = subs.entries.firstOrNull { it.value == cbId }?.key
            if (proxyCb != null) {
                WidgetHost.unregisterProxyCallback(proxyCb)
                subs.remove(proxyCb)
            }
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** POST /api/bridge/unsubscribe_all { widgetId } — clean up every
     *  subscription for this widget. Called by the iframe on unload. */
    fun unsubscribeAll(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: run {
            JsonResponse.error(ex, 400, "no body"); return
        }
        val widgetId = obj.optString("widgetId").ifBlank {
            JsonResponse.error(ex, 400, "missing widgetId"); return
        }
        val subs = sessionSubs.remove(widgetId) ?: return run {
            JsonResponse.ok(ex, JSONObject().put("ok", true))
        }
        for (proxyCb in subs.keys) {
            WidgetHost.unregisterProxyCallback(proxyCb)
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Send an event to every active emitter for [widgetId]. Iterates a
     *  snapshot of the list so concurrent removals (close on write
     *  failure) don't break the loop. */
    private fun broadcastToWidget(widgetId: String, event: String, payload: String) {
        val list = sessionEmitters[widgetId]
        if (list == null) {
            android.util.Log.d("iappyxRemoteEdit", "broadcast NO emitters for widget=$widgetId event=$event")
            return
        }
        val snapshot = synchronized(list) { list.toList() }
        if (snapshot.isEmpty()) {
            android.util.Log.d("iappyxRemoteEdit", "broadcast EMPTY list for widget=$widgetId event=$event")
            return
        }
        for (emitter in snapshot) {
            try { emitter.send(event, payload) } catch (_: Throwable) { /* close-on-fail in send() */ }
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null
            else it.substring(0, eq) to java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
    }

    /** Drop all sessions. Called when activity is paused. */
    fun shutdown() {
        synchronized(sessions) {
            for (s in sessions.values) try {
                mainHandler.post {
                    // Tear down the WidgetHost too, not just the WebView —
                    // otherwise sensors/GPS/audio/sockets it opened keep
                    // running for the rest of the process life.
                    try { s.widgetHost.destroy() } catch (_: Throwable) {}
                    try { s.webView.destroy() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            sessions.clear()
        }
    }

    private fun respondWithJsonResult(ex: MicroHttpServer.Exchange, resultJson: String) {
        // The bridge writes its result as a JSON value (object, array,
        // number, string, or null). Forward it under "result".
        val parsed: Any? = try {
            // Try parse as an object first, then array, then primitive.
            when {
                resultJson.trimStart().startsWith("{") -> JSONObject(resultJson)
                resultJson.trimStart().startsWith("[") -> JSONArray(resultJson)
                else -> resultJson
            }
        } catch (_: Throwable) { resultJson }
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("result", parsed)
        })
    }

    private fun normalizeResult(r: Any?): Any {
        if (r == null) return JSONObject.NULL
        return when (r) {
            is String -> r
            is Number -> r
            is Boolean -> r
            else -> r.toString()
        }
    }

    /** Creates (or reuses) a session keyed by widgetId. The session's
     *  [WidgetHost] is constructed with:
     *    - The same sandbox directory the phone-side widget uses
     *      (`files/widgets/{widgetId}/`) so storage, sqlite, and asset
     *      reads all see the user's real widget data
     *    - A [RemoteEditWebView] that intercepts `evaluateJavascript`
     *      calls and streams them as `eval-js` events via SSE to the
     *      browser iframe (where they actually run). Required for
     *      sensor.startCompass / fireEvent / any bridge that calls
     *      back via direct JS evaluation rather than deliverResult. */
    private fun getOrCreateSession(widgetId: String): Session {
        synchronized(sessions) {
            sessions[widgetId]?.let { return it }
        }
        // Build on main thread (WebView constructor needs it).
        val ready = CompletableFuture<Session>()
        mainHandler.post {
            try {
                val wv = RemoteEditWebView(activity) { script ->
                    broadcastToWidget(widgetId, "eval-js", JSONObject().put("js", script).toString())
                }
                val sandboxDir = WidgetSandbox.sandboxFor(activity, widgetId).dir
                val wh = WidgetHost(activity, wv, sandboxDir, widgetId)
                ready.complete(Session(wh, wv, System.currentTimeMillis()))
            } catch (t: Throwable) {
                ready.completeExceptionally(t)
            }
        }
        val s = ready.get(2, TimeUnit.SECONDS)
        synchronized(sessions) {
            sessions.putIfAbsent(widgetId, s)
            return sessions[widgetId]!!
        }
    }

    private fun sweepStaleSessions() {
        val now = System.currentTimeMillis()
        synchronized(sessions) {
            val stale = sessions.entries.filter { now - it.value.lastUsed > sessionTimeoutMs }
            for (e in stale) {
                val s = e.value
                mainHandler.post {
                    try { s.widgetHost.destroy() } catch (_: Throwable) {}
                    try { s.webView.destroy() } catch (_: Throwable) {}
                }
                sessions.remove(e.key)
            }
        }
    }
}
