/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — central bus for the `notification:read` capability.
 *
 *  - NotificationBadgeListener pushes per-notification events here via
 *    [dispatch] (fenced call site).
 *  - Plugins register subscriptions via PluginNotificationsBridge.
 *    Subscriptions persist in per-plugin SharedPreferences so they
 *    survive plugin WebView teardown / launcher process death — the
 *    bus rehydrates from disk on first access and on package replace.
 *  - On match: PluginHost.invoke lazily spawns the plugin if needed
 *    and calls the registered method with the notification payload.
 *
 * Filter shape (all optional, AND-combined):
 *   { packages: ["com.whatsapp"], categories: ["msg"], ongoing: false }
 *
 * Payload delivered to plugin's method:
 *   { packageName, title, text, subText, postedAt, category, ongoing,
 *     id, key, group }
 */
package com.iappyx.launcher.plugins

import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object PluginNotificationsBus {

    /** Per-plugin subscription list, keyed by pluginId. Loaded lazily
     *  on first access; mutated on subscribe/unsubscribe. */
    private val subscriptions = ConcurrentHashMap<String, MutableList<Subscription>>()
    @Volatile private var hydrated = false

    data class Subscription(
        val pluginId: String,
        val subId: String,
        val method: String,
        val packages: List<String>,
        val categories: List<String>,
        val ongoing: Boolean?,
    ) {
        fun matches(sbn: StatusBarNotification): Boolean {
            if (packages.isNotEmpty() && sbn.packageName !in packages) return false
            val n = sbn.notification
            if (categories.isNotEmpty()) {
                val cat = n?.category ?: ""
                if (cat !in categories) return false
            }
            if (ongoing != null) {
                val isOngoing = (n?.flags ?: 0) and
                    android.app.Notification.FLAG_ONGOING_EVENT != 0
                if (isOngoing != ongoing) return false
            }
            return true
        }
    }

    fun add(context: Context, sub: Subscription) {
        ensureHydrated(context)
        val list = subscriptions.getOrPut(sub.pluginId) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.subId == sub.subId }
            list.add(sub)
        }
        persistFor(context, sub.pluginId)
    }

    fun remove(context: Context, pluginId: String, subId: String): Boolean {
        ensureHydrated(context)
        val list = subscriptions[pluginId] ?: return false
        val removed = synchronized(list) { list.removeAll { it.subId == subId } }
        if (removed) persistFor(context, pluginId)
        return removed
    }

    fun clearAllForPlugin(context: Context, pluginId: String) {
        subscriptions.remove(pluginId)
        prefs(context, pluginId).edit().clear().apply()
    }

    fun listFor(pluginId: String): List<Subscription> {
        return subscriptions[pluginId]?.let { synchronized(it) { it.toList() } } ?: emptyList()
    }

    /** Called from the NotificationBadgeListener (fenced) on every
     *  posted notification. Iterates active subscriptions, dispatches
     *  matches via PluginHost.invoke. */
    @JvmStatic
    fun dispatch(context: Context, sbn: StatusBarNotification) {
        ensureHydrated(context)
        if (subscriptions.isEmpty()) return
        val payload = payloadJson(sbn)
        for ((pluginId, list) in subscriptions) {
            val matches = synchronized(list) {
                list.filter { it.matches(sbn) }.toList()
            }
            for (sub in matches) {
                PluginHost.invoke(
                    context.applicationContext,
                    pluginId,
                    sub.method,
                    payload.toString(),
                    PluginResultCallback { /* fire-and-forget */ },
                )
            }
        }
    }

    fun payloadJson(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        val extras = n?.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        return JSONObject().apply {
            put("packageName", sbn.packageName)
            put("title", title)
            put("text", text)
            put("subText", subText)
            put("postedAt", sbn.postTime)
            put("category", n?.category ?: "")
            put("ongoing", (n?.flags ?: 0) and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            put("id", sbn.id)
            put("key", sbn.key ?: "")
            put("group", sbn.groupKey ?: "")
        }
    }

    /** Drop the in-memory cache and force a fresh disk read next call.
     *  Useful after install/uninstall to keep our state in sync. */
    fun invalidate() {
        subscriptions.clear()
        hydrated = false
    }

    // ── persistence ────────────────────────────────────────────

    private fun prefs(context: Context, pluginId: String) =
        context.getSharedPreferences("plugin_${pluginId}_iappyx_notifications",
            Context.MODE_PRIVATE)

    private fun persistFor(context: Context, pluginId: String) {
        val list = subscriptions[pluginId] ?: emptyList()
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("subId", s.subId)
                put("method", s.method)
                put("packages", JSONArray(s.packages))
                put("categories", JSONArray(s.categories))
                if (s.ongoing != null) put("ongoing", s.ongoing)
            })
        }
        prefs(context, pluginId).edit()
            .putString("subs", arr.toString())
            .apply()
    }

    private fun ensureHydrated(context: Context) {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            for (entry in PluginRegistry.all(context)) {
                if (!entry.enabled) continue
                hydrateOne(context, entry.manifest.id)
            }
            hydrated = true
        }
    }

    private fun hydrateOne(context: Context, pluginId: String) {
        val raw = prefs(context, pluginId).getString("subs", null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<Subscription>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val subId = o.optString("subId")
                if (subId.isBlank()) continue
                val method = o.optString("method")
                if (method.isBlank()) continue
                list.add(Subscription(
                    pluginId = pluginId,
                    subId = subId,
                    method = method,
                    packages = parseStringList(o.optJSONArray("packages")),
                    categories = parseStringList(o.optJSONArray("categories")),
                    ongoing = if (o.has("ongoing")) o.optBoolean("ongoing") else null,
                ))
            }
            if (list.isNotEmpty()) subscriptions[pluginId] = list
        } catch (_: Throwable) { /* malformed prefs — ignore */ }
    }

    private fun parseStringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i).trim()
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
