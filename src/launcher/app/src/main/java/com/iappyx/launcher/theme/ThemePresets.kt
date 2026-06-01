/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * User-saved theme presets — named --iappyx-* override maps the user can
 * store, re-apply, and delete from the theme editor, on top of the built-in
 * presets (Material You / Glass / Sharp / Bold). Also holds the shareable
 * theme-string codec used by import / export.
 *
 * Stored as a JSON array of {name, overrides:{...}} in its own prefs file so
 * it stays independent of the single active-override map in [ThemeOverrides].
 */
package com.iappyx.launcher.theme

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object ThemePresets {
    private const val PREFS = "widget_theme_presets"
    private const val KEY = "presets"

    /** Marks a string as an iappyx theme so import can recognise a paste. */
    private const val SHARE_PREFIX = "iappyxtheme:"

    data class Preset(val name: String, val overrides: Map<String, String>)

    fun all(context: Context): List<Preset> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Preset(name, jsonToMap(o.optJSONObject("overrides") ?: JSONObject()))
            }
        } catch (_: Throwable) { emptyList() }
    }

    /** Save (or replace by case-insensitive name) a preset. */
    fun save(context: Context, name: String, overrides: Map<String, String>) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val kept = all(context).filterNot { it.name.equals(clean, ignoreCase = true) }
        val arr = JSONArray()
        for (p in kept) arr.put(presetJson(p.name, p.overrides))
        arr.put(presetJson(clean, overrides))
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(context: Context, name: String) {
        val kept = all(context).filterNot { it.name.equals(name, ignoreCase = true) }
        val arr = JSONArray()
        for (p in kept) arr.put(presetJson(p.name, p.overrides))
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Encode an override map as a compact shareable token. */
    fun export(overrides: Map<String, String>): String {
        val json = mapToJson(overrides).toString()
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return SHARE_PREFIX + b64
    }

    /** Decode a shared token (or raw JSON) back into an override map. Returns
     *  null when the text isn't a recognisable theme. */
    fun parse(text: String): Map<String, String>? {
        val t = text.trim()
        val candidate = when {
            t.startsWith(SHARE_PREFIX) -> decodeB64(t.removePrefix(SHARE_PREFIX).trim())
            t.startsWith("{") -> t
            else -> decodeB64(t) // bare base64, best-effort
        } ?: return null
        return try {
            val m = jsonToMap(JSONObject(candidate))
            // Only accept --iappyx-* keys; reject anything else as not-a-theme.
            val filtered = m.filterKeys { it.startsWith("--iappyx-") }
            filtered.ifEmpty { null }
        } catch (_: Throwable) { null }
    }

    private fun decodeB64(s: String): String? = try {
        String(Base64.decode(s, Base64.URL_SAFE), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun presetJson(name: String, overrides: Map<String, String>) = JSONObject().apply {
        put("name", name)
        put("overrides", mapToJson(overrides))
    }

    private fun mapToJson(m: Map<String, String>) = JSONObject().apply {
        for ((k, v) in m) put(k, v)
    }

    private fun jsonToMap(o: JSONObject): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (k in o.keys()) m[k] = o.getString(k)
        return m
    }
}
