/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Position → colour mapping for [IconFilter.RAINBOW_MATRIX].
 *
 * Treats the grid as a circular rainbow disk centred at its midpoint:
 *   - Hue varies with the angle from the centre.
 *   - Saturation grows with distance from the centre — so the middle row /
 *     col is whiter and the corners are fully saturated.
 *   - A small per-page hue offset rotates the rainbow as the user swipes
 *     between home pages, so adjacent pages are visibly different.
 *
 * 1D grids (e.g. the dock — 1 row × N cols) degenerate the radial math,
 * so we fall back to a linear hue sweep along the long axis.
 */
object RainbowMatrix {

    fun tintFor(p: GridPos): Int {
        // 1D fall-back (dock, single-column layouts).
        if (p.rows <= 1 || p.cols <= 1) {
            val total = (if (p.cols >= p.rows) p.cols else p.rows).coerceAtLeast(1)
            val pos = if (p.cols >= p.rows) p.col else p.row
            val hue = ((pos.toFloat() / total) * 360f + p.pageIndex * 30f) % 360f
            return Color.HSVToColor(floatArrayOf(hue, 0.7f, 1f))
        }

        val cx = (p.cols - 1) / 2f
        val cy = (p.rows - 1) / 2f
        val dx = (p.col - cx).toDouble()
        val dy = (p.row - cy).toDouble()

        val angleDeg = (Math.toDegrees(atan2(dy, dx)).toFloat() + 360f) % 360f
        val maxDist = hypot(cx.toDouble(), cy.toDouble()).toFloat().coerceAtLeast(1f)
        val dist = hypot(dx, dy).toFloat()
        val sat = (0.35f + 0.65f * (dist / maxDist).coerceIn(0f, 1f))
        val hue = (angleDeg + p.pageIndex * 30f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, sat, 1f))
    }
}
