/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — universal search fan-out.
 *
 * Iterates over enabled plugins that declare `search` in their manifest's
 * `exposes` list, invokes each plugin's `search({query})` method in
 * parallel through PluginHost, collects results with a 500 ms per-plugin
 * timeout, and emits them via a callback on the main thread.
 *
 * Result shape (each plugin returns):
 *   { ok: true, results: [ {
 *       id: "stable-key",          // unique within this plugin
 *       title: "Living room",
 *       subtitle: "Light · On · 60%",
 *       icon: "data:image/png;base64,..." | null,
 *       html: "<button>…</button>", // inline interactive snippet; the
 *                                   // launcher inflates a tiny WebView
 *                                   // with the plugin's shim attached
 *       height: 80                  // requested row height in dp, default 80
 *     }, … ]
 *   }
 *
 * Plugins that fail / time out are quietly skipped — search must stay
 * responsive even if HomeAssistant is unreachable on the LAN.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PluginSearchAggregator {

    /** A single search result emitted by one plugin. */
    data class Hit(
        val pluginId: String,
        val pluginName: String,
        val id: String,
        val title: String,
        val subtitle: String,
        val icon: String?,    // data: URL or null
        val html: String,
        val heightDp: Int,
        /** True when this is a skeleton placeholder waiting for the
         *  plugin's async `searchResolve(ids)` call to fill in real
         *  content. The SearchPanel renders these with a shimmer / muted
         *  style; when the resolved version arrives the same id swaps
         *  in. Plugins opt into this two-tier flow by returning
         *  `{results:[...], pending:[{id,title,subtitle,height}]}`
         *  from their initial `search` call. */
        val isPending: Boolean = false,
    )

    /** Cap per plugin so a single chatty plugin can't drown the rest. */
    private const val PER_PLUGIN_CAP = 5

    /** Per-plugin time budget. Plugins still loading data after this fall
     *  out of THIS query — but their async work isn't cancelled, the JS
     *  side just discards the result by checking the cancellation token.
     *
     *  History: started at 500 ms which was too tight — HA /api/states
     *  returns hundreds of entities and easily takes 400-800 ms on LAN
     *  + 800-1500 ms over VPN; api.github.com over the public internet
     *  is routinely 300-700 ms. 500 ms missed those almost every time.
     *  2500 ms covers the vast majority of real-world LAN + public-cloud
     *  reads with margin. Plugins that need longer should return
     *  skeleton rows via `pending` + resolve asynchronously. */
    private const val PLUGIN_TIMEOUT_MS = 2500L

    /** Per-plugin "last fired" timestamps for the [searchThrottleMs]
     *  manifest field. Plugins whose last invocation was within their
     *  declared throttle window are skipped for this keystroke; the
     *  next keystroke that lands outside the window picks them up. */
    private val lastFireAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Fan out [query] to every enabled plugin that exposes a `search`
     *  method. [onUpdate] fires on the main thread each time a new
     *  plugin's results arrive (so the UI can append incrementally
     *  rather than waiting for the slowest). [onDone] fires once all
     *  plugins have either responded or timed out. */
    fun query(
        context: Context,
        query: String,
        onUpdate: (List<Hit>) -> Unit,
        onDone: () -> Unit,
    ): Cancellable {
        val token = Token()
        val main = Handler(Looper.getMainLooper())
        if (query.isBlank()) {
            main.post { onUpdate(emptyList()); onDone() }
            return token
        }
        // Snapshot the eligible plugin list — only enabled + exposing
        // `search` + not explicitly excluded by the user via the
        // per-plugin "Expose to search" toggle. Skipping the disabled /
        // non-search / excluded ones keeps the fan-out narrow even when
        // many plugins are installed.
        val excluded = com.iappyx.launcher.LauncherPrefs(context).searchExcludedPlugins
        val nowTs = System.currentTimeMillis()
        val targets = PluginRegistry.all(context)
            .filter {
                if (!it.enabled) return@filter false
                // Plugins opt in by declaring `universalSearch` in their
                // manifest's `exposes` list — separate from GitHub-style
                // REST-API `search` which is unrelated.
                if (!it.manifest.exposes.contains("universalSearch")) return@filter false
                if (excluded.contains(it.manifest.id)) return@filter false
                // Per-plugin throttle (manifest.searchThrottleMs). Skip if
                // we fired this plugin recently — its results from the
                // previous keystroke will still appear in the UI (the
                // SearchPanel keeps the rendered rows until they're
                // explicitly cleared).
                val throttle = it.manifest.searchThrottleMs
                if (throttle > 0) {
                    val last = lastFireAtMs[it.manifest.id] ?: 0L
                    if (nowTs - last < throttle) return@filter false
                }
                true
            }
        for (entry in targets) lastFireAtMs[entry.manifest.id] = nowTs
        if (targets.isEmpty()) {
            main.post { onUpdate(emptyList()); onDone() }
            return token
        }

        val collected = ConcurrentLinkedQueue<Hit>()
        val remaining = AtomicInteger(targets.size)

        // Index of recent-acted "pluginId:resultId" keys → rank (lower is
        // newer). Used to promote previously-tapped rows above the
        // alphabetical baseline.
        val recents = com.iappyx.launcher.LauncherPrefs(context).searchRecentKeys()
        val recentRank: Map<String, Int> = recents.withIndex()
            .associate { (i, k) -> k to i }

        fun reportArrival() {
            if (token.cancelled.get()) return
            main.post {
                if (token.cancelled.get()) return@post
                // Two-tier sort: recent-acted hits first (in recency order),
                // then everything else alphabetically. Stable so repeated
                // updates don't reshuffle in front of the user.
                val snapshot = collected.toList().sortedWith(
                    compareBy<Hit>(
                        { recentRank["${it.pluginId}:${it.id}"] ?: Int.MAX_VALUE },
                        { it.pluginId },
                        { it.title.lowercase() },
                    )
                )
                onUpdate(snapshot)
                if (remaining.get() == 0) onDone()
            }
        }

        for (entry in targets) {
            // Timeout watchdog — fires regardless of whether the plugin
            // ever replies. Plugin reply path checks `done` before
            // double-counting.
            val done = AtomicBoolean(false)
            val pluginId = entry.manifest.id
            val pluginName = entry.manifest.name
            main.postDelayed({
                if (done.compareAndSet(false, true)) {
                    if (remaining.decrementAndGet() <= 0) reportArrival()
                    else reportArrival()
                }
            }, PLUGIN_TIMEOUT_MS)

            // Fire the invoke. PluginHost.invoke handles the network-
            // trust gate + isolated-process routing transparently.
            val args = JSONObject().put("query", query).toString()
            PluginHost.invoke(context.applicationContext, pluginId, "universalSearch", args) { json ->
                if (!done.compareAndSet(false, true)) return@invoke
                val pendingIds = mutableListOf<String>()
                try {
                    // PluginHost wraps the plugin's reply as
                    // {ok:true, result:<value>} on success or
                    // {ok:false, error:"..."} on host-level failure
                    // (unknown method, JS exception). Unwrap both
                    // layers before reading `results` / `pending`.
                    val obj = JSONObject(json)
                    if (obj.optBoolean("ok", false)) {
                        val inner = obj.optJSONObject("result") ?: JSONObject()
                        if (inner.optBoolean("ok", false)) {
                            val arr = inner.optJSONArray("results") ?: org.json.JSONArray()
                            val take = minOf(arr.length(), PER_PLUGIN_CAP)
                            for (i in 0 until take) {
                                val r = arr.optJSONObject(i) ?: continue
                                collected.add(parseHit(r, pluginId, pluginName, i, isPending = false))
                            }
                            // Skeleton rows for async results — same nesting.
                            val pend = inner.optJSONArray("pending")
                            if (pend != null) {
                                val pTake = minOf(pend.length(), PER_PLUGIN_CAP)
                                for (i in 0 until pTake) {
                                    val r = pend.optJSONObject(i) ?: continue
                                    val id = r.optString("id", "")
                                    if (id.isBlank()) continue
                                    pendingIds.add(id)
                                    collected.add(parseHit(r, pluginId, pluginName, i, isPending = true))
                                }
                            }
                        }
                    }
                } catch (_: Throwable) { /* malformed plugin reply — skip */ }
                finally {
                    if (remaining.decrementAndGet() >= 0) reportArrival()
                }
                if (pendingIds.isNotEmpty()) {
                    pollResolve(context, pluginId, pluginName, pendingIds, collected,
                        token, main, onUpdate)
                }
            }
        }
        return token
    }

    /** Parse one result object into a Hit. Shared between the sync and
     *  async parsing paths so the field defaults stay in lockstep. */
    private fun parseHit(r: JSONObject, pluginId: String, pluginName: String,
                          fallbackIdx: Int, isPending: Boolean): Hit = Hit(
        pluginId   = pluginId,
        pluginName = pluginName,
        id         = r.optString("id", "$pluginId#$fallbackIdx"),
        title      = r.optString("title", ""),
        subtitle   = r.optString("subtitle", ""),
        icon       = r.optString("icon").ifEmpty { null },
        html       = r.optString("html", ""),
        heightDp   = r.optInt("height", DEFAULT_HEIGHT_DP),
        isPending  = isPending,
    )

    /** Polling loop for plugins that declared async pending ids. Calls
     *  `searchResolve({ids})` on the plugin every [POLL_INTERVAL_MS]
     *  until either every id is resolved OR [ASYNC_BUDGET_MS] elapses.
     *  Each resolved id REPLACES the existing skeleton hit in [collected]
     *  with the same id (by id-equality removal + add). */
    private fun pollResolve(
        context: Context,
        pluginId: String,
        pluginName: String,
        ids: List<String>,
        collected: ConcurrentLinkedQueue<Hit>,
        token: Token,
        main: Handler,
        onUpdate: (List<Hit>) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + ASYNC_BUDGET_MS
        val outstanding = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        outstanding.addAll(ids)
        fun pollOnce() {
            if (token.cancelled.get() || outstanding.isEmpty()) return
            if (System.currentTimeMillis() > deadline) {
                // Time-out — drop remaining skeleton rows.
                val drop = outstanding.toSet()
                collected.removeAll { it.pluginId == pluginId && it.id in drop }
                onUpdateSorted(collected, token, main, onUpdate)
                return
            }
            val args = JSONObject().put("ids", org.json.JSONArray(outstanding.toList())).toString()
            PluginHost.invoke(
                context.applicationContext, pluginId, "universalSearchResolve", args,
            ) { json ->
                if (token.cancelled.get()) return@invoke
                try {
                    // Same envelope shape as `search`: PluginHost wraps
                    // the plugin's reply as {ok:true, result:<value>}.
                    // Unwrap both layers before reading `resolved`.
                    val obj = JSONObject(json)
                    if (obj.optBoolean("ok", false)) {
                        val inner = obj.optJSONObject("result") ?: JSONObject()
                        if (inner.optBoolean("ok", false)) {
                            val arr = inner.optJSONArray("resolved") ?: org.json.JSONArray()
                            for (i in 0 until arr.length()) {
                                val r = arr.optJSONObject(i) ?: continue
                                val id = r.optString("id", "")
                                if (id.isBlank() || id !in outstanding) continue
                                outstanding.remove(id)
                                // Replace the skeleton row with the resolved version.
                                collected.removeAll { it.pluginId == pluginId && it.id == id }
                                collected.add(parseHit(r, pluginId, pluginName, i, isPending = false))
                            }
                        }
                    }
                } catch (_: Throwable) { /* skip; will retry */ }
                onUpdateSorted(collected, token, main, onUpdate)
                if (outstanding.isNotEmpty()) {
                    main.postDelayed({ pollOnce() }, POLL_INTERVAL_MS)
                }
            }
        }
        // First poll fires after the initial debounce, giving the plugin
        // a moment to start its background work before we ask for results.
        main.postDelayed({ pollOnce() }, POLL_INTERVAL_MS)
    }

    /** Re-sort the collected set and emit on the main thread. Used by
     *  the async resolve loop, which doesn't get the same `recents` /
     *  alphabetical sort that the main fan-out applies — we just push
     *  the latest snapshot and let the UI rebind. */
    private fun onUpdateSorted(
        collected: ConcurrentLinkedQueue<Hit>,
        token: Token,
        main: Handler,
        onUpdate: (List<Hit>) -> Unit,
    ) {
        if (token.cancelled.get()) return
        main.post {
            if (!token.cancelled.get()) onUpdate(collected.toList())
        }
    }

    private const val POLL_INTERVAL_MS = 500L
    private const val ASYNC_BUDGET_MS = 5_000L

    private const val DEFAULT_HEIGHT_DP = 80

    /** Returned to the caller so it can cancel the in-flight query when
     *  the user types another character. Cancellation is co-operative —
     *  in-flight plugin invocations aren't aborted, their late replies
     *  are just discarded by the result handler. */
    interface Cancellable { fun cancel() }

    private class Token : Cancellable {
        val cancelled = AtomicBoolean(false)
        override fun cancel() { cancelled.set(true) }
    }
}
