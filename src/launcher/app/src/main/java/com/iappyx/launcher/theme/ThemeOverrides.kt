/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * User theme overrides — a map of --iappyx-* token → value layered on TOP of the
 * Material-You palette + design defaults (see GeneratedWidgetCell.effectiveTokens).
 * The theme editor writes these; cssGuard injects them (overrides win) and
 * buildThemeUpdateJs pushes them to live widgets. Empty = pure Material You.
 */
package com.iappyx.launcher.theme

import android.content.Context
import org.json.JSONObject

object ThemeOverrides {
    private const val PREFS = "widget_theme_overrides"
    private const val KEY = "overrides"

    @Volatile private var dirty = false

    /** Bumped whenever the override map changes. Lets caches (e.g.
     *  [com.iappyx.launcher.widget.Palette]) skip re-parsing prefs/JSON until
     *  the theme actually changes, so accent() is cheap to call per-draw. */
    @Volatile private var gen = 0
    val generation: Int get() = gen

    fun get(context: Context): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) m[k] = o.getString(k)
            m
        } catch (_: Throwable) { emptyMap() }
    }

    /** @param sync commit synchronously — use before an abrupt process exit
     *  (the "Restart launcher" path) so the write isn't lost to async flush. */
    fun set(context: Context, overrides: Map<String, String>, sync: Boolean = false) {
        val o = JSONObject()
        for ((k, v) in overrides) o.put(k, v)
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString())
        if (sync) ed.commit() else ed.apply()
        dirty = true
        gen++
    }

    fun clear(context: Context, sync: Boolean = false) {
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY)
        if (sync) ed.commit() else ed.apply()
        dirty = true
        gen++
    }

    /** Bump the cache generation without changing the override map — used when
     *  font *availability* changes (download / delete) so font-resolving caches
     *  (Palette.themeTypeface / overrideAccent) recompute. Does NOT set dirty. */
    fun bumpGeneration() { gen++ }

    /** Set by the editor; LauncherActivity consumes it on resume to live-push the
     *  new tokens to already-visible widgets (others re-inject on rebind). */
    fun consumeDirty(): Boolean { val d = dirty; dirty = false; return d }
}
