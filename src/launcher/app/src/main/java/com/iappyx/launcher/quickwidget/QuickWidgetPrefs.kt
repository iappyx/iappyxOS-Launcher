/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: per-slot widget binding store. Each Quick Settings tile
 * slot persists the widget id the user chose for it. Single file, single
 * SharedPreferences — deletable as a unit with the rest of quickwidget/.
 */
package com.iappyx.launcher.quickwidget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

internal object QuickWidgetPrefs {

    private const val PREFS_NAME = "iappyx_quickwidget"
    private const val KEY_SLOT_PREFIX = "slot_"

    /** Number of tile slots the feature ships with. Each slot is a separate
     *  [android.service.quicksettings.TileService] subclass declared in the
     *  manifest; we use 1 for Phase 1 and grow to 5 in Phase 2. */
    const val MAX_SLOTS = 5

    fun getWidgetForSlot(context: Context, slot: Int): String? {
        if (slot !in 1..MAX_SLOTS) return null
        return prefs(context).getString(KEY_SLOT_PREFIX + slot, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setWidgetForSlot(context: Context, slot: Int, widgetId: String?) {
        if (slot !in 1..MAX_SLOTS) return
        val key = KEY_SLOT_PREFIX + slot
        val editor = prefs(context).edit()
        if (widgetId.isNullOrBlank()) editor.remove(key)
        else editor.putString(key, widgetId)
        editor.apply()
        // Ask Android to bind/refresh the corresponding TileService so
        // its onStartListening runs and the tile picks up the new label
        // + icon. Without this the tile stays stale (showing the old
        // widget's initials) until the user next pulls the shade.
        requestTileRefresh(context, slot)
    }

    /** Re-bind the TileService for [slot] so its `onStartListening` runs
     *  immediately. Safe to call from any context; ignores failures
     *  (the system may already have the tile listening). */
    private fun requestTileRefresh(context: Context, slot: Int) {
        val tileClass = when (slot) {
            1 -> QuickWidget1Tile::class.java
            2 -> QuickWidget2Tile::class.java
            3 -> QuickWidget3Tile::class.java
            4 -> QuickWidget4Tile::class.java
            5 -> QuickWidget5Tile::class.java
            else -> return
        }
        try {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, tileClass),
            )
        } catch (_: Throwable) { /* best-effort */ }
    }

    /** All slots that currently have a widget bound. Used by the settings
     *  picker UI to show "currently bound" state. */
    fun allBindings(context: Context): Map<Int, String> {
        val out = HashMap<Int, String>()
        val p = prefs(context)
        for (slot in 1..MAX_SLOTS) {
            p.getString(KEY_SLOT_PREFIX + slot, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { out[slot] = it }
        }
        return out
    }

    private fun prefs(context: Context) =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
