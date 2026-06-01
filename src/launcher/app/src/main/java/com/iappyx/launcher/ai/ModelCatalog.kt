/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches the result of [AiService.listModels] so the Settings screen
 * doesn't re-fetch on every open. Two layers:
 *
 *  - **Memory** — a [Volatile] reference, lives for the process lifetime,
 *    survives screen rotations & activity recreations.
 *  - **Disk** — a single JSON blob in [SharedPreferences], 24 h TTL. Lets
 *    a cold-launched Settings show the dropdown immediately without
 *    waiting on the network.
 *
 * Cleared by [clear] when the user changes their API key (a different
 * key may have access to a different model set — most obviously the
 * default Anthropic model list vs an enterprise tier).
 *
 * Thread model: callers run [fetchOrCached] on a background thread; the
 * function does a synchronous HTTP call when the cache is cold or stale,
 * then writes back to disk.
 */
object ModelCatalog {

    private const val PREFS_NAME = "iappyx_model_catalog"
    private const val KEY_DATA = "models_json"
    private const val KEY_FETCHED_AT = "fetched_at_ms"
    /** 24 hours. Models are added rarely; refreshing daily keeps the
     *  list fresh without spending API calls every settings open. */
    private const val TTL_MS = 24L * 60L * 60L * 1000L

    @Volatile private var memCached: List<AiService.ModelInfo>? = null

    /** Get the model list, going through the cache. If [force] is true,
     *  bypass both layers and re-fetch. On any failure that bypasses the
     *  cache (network down, 401, malformed response), the [AiException]
     *  propagates — callers should catch and fall back to manual entry. */
    @Throws(AiException::class)
    fun fetchOrCached(
        context: Context, apiKey: String, force: Boolean = false,
    ): List<AiService.ModelInfo> {
        if (!force) {
            memCached?.let { return it }
            readDisk(context)?.let {
                memCached = it
                return it
            }
        }
        val live = AiService.listModels(apiKey)
        memCached = live
        writeDisk(context, live)
        return live
    }

    /** Drop both cache layers. Call when the user changes their API key
     *  or hits the Refresh button in Settings. */
    fun clear(context: Context) {
        memCached = null
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** True when the caller can show a populated dropdown without
     *  blocking on the network. Useful for picking a loading state. */
    fun hasCached(context: Context): Boolean {
        if (memCached != null) return true
        return readDisk(context) != null
    }

    private fun readDisk(context: Context): List<AiService.ModelInfo>? {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return null }
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt == 0L || System.currentTimeMillis() - fetchedAt > TTL_MS) {
            return null
        }
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<AiService.ModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("display_name").ifBlank { id }
                list.add(AiService.ModelInfo(id = id, displayName = name))
            }
            if (list.isEmpty()) null else list
        } catch (_: Throwable) { null }
    }

    private fun writeDisk(context: Context, models: List<AiService.ModelInfo>) {
        try {
            val arr = JSONArray()
            for (m in models) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("display_name", m.displayName)
                })
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { /* best-effort */ }
    }
}
