/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import com.iappyx.launcher.LauncherPrefs

/**
 * Visual transform applied to every app icon on the home grid + dock.
 *
 * Adding a new effect:
 *  1. Add a new enum value with a stable string key (used as the cache-key
 *     suffix in [IconMask] and the persisted [LauncherPrefs.iconFilter]).
 *  2. Teach [IconMask.render] how to post-process the bitmap for it (or, for
 *     view-time effects only, leave [IconMask] alone and apply via
 *     `iconView.setColorFilter` in [IconCell] / [FolderCell] etc.).
 *  3. Add a row in `IconFilterSheet` so the user can pick it.
 */
enum class IconFilter(val key: String) {
    /** Default — no transform. Icons render in full colour. */
    NONE("none"),

    /** Greyscale base + per-cell rainbow tint applied via [setColorFilter]. */
    RAINBOW_MATRIX("rainbow_matrix"),

    /** Pure greyscale, no tint. Minimalist black-and-white aesthetic. */
    GREYSCALE("greyscale"),

    /** Vintage warm ColorMatrix transform — sepia tones. */
    SEPIA("sepia"),

    /** Greyscale base, every icon tinted to the system accent
     *  (Material You) — bakes a single, coherent palette. */
    MONO_ACCENT("mono_accent"),

    /** Faded-photo ColorMatrix transform — desaturated + warm shift. */
    VINTAGE("vintage"),

    /** Greyscale base + per-cell tint sampled from the user's wallpaper.
     *  See [WallpaperPalette]. */
    WALLPAPER_THEMED("wallpaper_themed"),

    /** Aggressive nearest-neighbour downsample for an 8-bit pixel-art look. */
    PIXELATE("pixelate"),

    /** Each app's icon flattened to luminance and recoloured with its own
     *  dominant colour. Per-app palette, iOS-18 tinted-icon flavour. */
    TINTED_MONO("tinted_mono"),

    /** Iridescent sweep gradient (cyan → magenta → gold → teal) layered over
     *  the original icon with an OVERLAY blend, so the underlying art still
     *  reads through. Holographic / soap-bubble shimmer. */
    AURORA("aurora");

    companion object {
        fun fromKey(key: String?): IconFilter =
            values().firstOrNull { it.key == key } ?: NONE

        fun current(context: Context): IconFilter =
            fromKey(LauncherPrefs(context).iconFilter)
    }
}

/** Where a cell sits in the launcher's grid — used by position-aware
 *  filters (currently [IconFilter.RAINBOW_MATRIX]) to decide its tint.
 *  `cols` / `rows` are the dimensions of the containing grid (home grid
 *  or dock); they're embedded so callers like [com.iappyx.launcher.cells.RainbowMatrix]
 *  don't have to thread layout info separately. */
data class GridPos(
    val pageIndex: Int,
    val row: Int,
    val col: Int,
    val cols: Int,
    val rows: Int,
)
