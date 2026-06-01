/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — central dispatcher for the `push` capability. Loads per-
 * plugin subscriptions on demand, matches incoming pushes against
 * their topic, fires the registered plugin method.
 *
 * Invoked from `PushService.onMessageReceived` (fenced hook). The
 * server-side push payload is expected to carry a `data.topic` field;
 * plugins subscribe to topics they care about and ignore everything
 * else. Plugins that subscribe with `topic="*"` get every push.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object PluginPushRouter {

    private data class Subscription(
        val pluginId: String,
        val subId: String,
        val topic: String,
        val method: String,
    )

    /** Cached subscriptions keyed by pluginId. Loaded lazily; cleared
     *  on `invalidate()` so a subscribe/unsubscribe takes effect on
     *  the next push without us pushing change notifications. */
    private val subscriptions = ConcurrentHashMap<String, List<Subscription>>()
    @Volatile private var hydrated = false

    /** Called from `PushService.onMessageReceived` (PLUGINS-fenced).
     *  Matches the push's `data.topic` against subscribers and fires
     *  each plugin method with the full push payload. */
    @JvmStatic
    fun dispatch(context: Context, title: String, body: String, dataJson: String) {
        ensureHydrated(context)
        if (subscriptions.isEmpty()) return
        // Topic discovery — peek inside the data payload. Missing or
        // empty topic still matches wildcard subscribers.
        val topic = try {
            JSONObject(dataJson).optString("topic", "")
        } catch (_: Throwable) { "" }

        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("topic", topic)
            // Forward the whole data blob so plugins don't have to
            // re-fetch fields out of band.
            try { put("data", JSONObject(dataJson)) } catch (_: Throwable) {
                put("data", JSONObject())
            }
        }.toString()

        for ((pluginId, subs) in subscriptions) {
            for (sub in subs) {
                val matches = sub.topic == "*" ||
                    (topic.isNotEmpty() && sub.topic == topic)
                if (!matches) continue
                PluginHost.invoke(
                    context.applicationContext,
                    pluginId,
                    sub.method,
                    payload,
                    PluginResultCallback { /* fire-and-forget */ },
                )
            }
        }
    }

    /** Drop the cache so the next dispatch re-reads from disk. Called
     *  by PluginPushBridge after subscribe/unsubscribe. */
    @JvmStatic
    fun invalidate() {
        subscriptions.clear()
        hydrated = false
    }

    private fun ensureHydrated(context: Context) {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            for (entry in PluginRegistry.all(context)) {
                if (!entry.enabled) continue
                val pluginId = entry.manifest.id
                val prefs = context.getSharedPreferences(
                    PluginPushBridge.prefsName(pluginId), Context.MODE_PRIVATE)
                val list = mutableListOf<Subscription>()
                for ((_, v) in prefs.all) {
                    try {
                        val rec = JSONObject(v.toString())
                        val subId = rec.optString("subId")
                        if (subId.isBlank()) continue
                        val method = rec.optString("method")
                        if (method.isBlank()) continue
                        val topic = rec.optString("topic", "*")
                        list.add(Subscription(pluginId, subId, topic, method))
                    } catch (_: Throwable) { /* skip malformed */ }
                }
                if (list.isNotEmpty()) subscriptions[pluginId] = list
            }
            hydrated = true
        }
    }
}
