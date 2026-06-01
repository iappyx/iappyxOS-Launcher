/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: shared TileService logic. Each concrete tile slot is a
 * thin subclass that just declares its slot number; this base handles
 * the lookup + launch dance.
 */
package com.iappyx.launcher.quickwidget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.iappyx.launcher.R
import com.iappyx.launcher.widget.WidgetLibrary

abstract class QuickWidgetTileBase : TileService() {

    protected abstract val slot: Int

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val widgetId = QuickWidgetPrefs.getWidgetForSlot(this, slot)
        val target: Intent = if (widgetId.isNullOrBlank()) {
            Intent(this, QuickWidgetPickerActivity::class.java).apply {
                putExtra(QuickWidgetPickerActivity.EXTRA_SLOT, slot)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            QuickWidgetPanelActivity.intent(this, widgetId)
        }
        // Android 14+ requires a PendingIntent for startActivityAndCollapse;
        // pre-14 accepts a raw Intent. Use the PendingIntent variant where
        // available so the notification shade collapses cleanly on every
        // supported platform.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(this, slot, target, flags)
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(target)
        }
    }

    /** Refresh the tile's label + icon based on what's bound.
     *  State is always STATE_INACTIVE — these are action-type tiles
     *  (like Camera, Screenshot), not toggles. Setting STATE_ACTIVE
     *  made the tile appear "on" / "selected" after tap, implying a
     *  toggle relationship that doesn't exist. */
    private fun refreshTile() {
        val t = qsTile ?: return
        val widgetId = QuickWidgetPrefs.getWidgetForSlot(this, slot)
        t.state = Tile.STATE_INACTIVE
        if (widgetId.isNullOrBlank()) {
            t.label = getString(R.string.quickwidget_tile_unbound_label, slot)
            t.contentDescription = t.label
            // Explicitly reset the icon to the manifest default — the
            // Tile object holds the last-set icon, so without this an
            // unbound slot keeps showing the previously-bound widget's
            // initials.
            t.icon = Icon.createWithResource(this, R.drawable.ic_apps)
        } else {
            val title = try {
                WidgetLibrary.get(this, widgetId)?.title ?: widgetId
            } catch (_: Throwable) { widgetId }
            t.label = title
            t.contentDescription = title
            // Per-widget icon from the title's initials so tiles bound
            // to different widgets are visually distinct in the shade.
            t.icon = initialsIcon(title)
        }
        t.updateTile()
    }

    private fun initialsIcon(title: String): Icon {
        val firstTwo = title.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .let { parts ->
                when {
                    parts.isEmpty() -> "?"
                    parts.size == 1 -> parts[0].take(2)
                    else -> parts[0].take(1) + parts[1].take(1)
                }
            }
            .uppercase()
        val sizePx = (resources.displayMetrics.density * 32).toInt().coerceAtLeast(48)
        val bm = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val baselineY = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(firstTwo, sizePx / 2f, baselineY, paint)
        return Icon.createWithBitmap(bm)
    }
}
