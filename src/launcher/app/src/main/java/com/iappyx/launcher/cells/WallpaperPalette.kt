/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color

/**
 * Position → wallpaper-colour mapping for [IconFilter.WALLPAPER_THEMED].
 *
 * Sample 1–3 colours from the system wallpaper via
 * [WallpaperManager.getWallpaperColors] (API 27+) and distribute them
 * across the grid by hashing the cell's `(pageIndex, row, col)`. A given
 * cell always picks the same colour from the palette, so the grid reads
 * as a stable mosaic rather than reshuffling between binds.
 *
 * The palette is cached process-wide and invalidated when the wallpaper
 * changes (see [invalidate]; the launcher hooks
 * [WallpaperManager.OnColorsChangedListener]).
 */
object WallpaperPalette {

    @Volatile private var cached: List<Int>? = null

    fun tintFor(context: Context, p: GridPos): Int {
        val palette = colors(context)
        // Hash the cell coordinates to a stable index. Mixing primes keeps
        // adjacent cells on different palette entries — otherwise an
        // entire row would share one colour.
        val mix = (p.row * 7919) xor (p.col * 6151) xor (p.pageIndex * 5749)
        val idx = ((mix and 0x7fffffff) % palette.size)
        return palette[idx]
    }

    fun invalidate() { cached = null }

    private fun colors(context: Context): List<Int> {
        cached?.let { return it }
        val list = ArrayList<Int>(3)
        try {
            val wpm = WallpaperManager.getInstance(context)
            val wc = wpm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (wc != null) {
                list.add(wc.primaryColor.toArgb())
                wc.secondaryColor?.let { list.add(it.toArgb()) }
                wc.tertiaryColor?.let { list.add(it.toArgb()) }
            }
        } catch (_: Throwable) { /* permission / no wallpaper */ }
        if (list.isEmpty()) {
            // Fallback palette so the filter still does something visible
            // on devices that don't expose wallpaper colours.
            list.add(Color.parseColor("#4FC3F7"))
            list.add(Color.parseColor("#FFB74D"))
            list.add(Color.parseColor("#AED581"))
        }
        cached = list
        return list
    }
}
