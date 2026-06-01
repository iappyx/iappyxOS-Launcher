/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.content.res.Configuration
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Page
import com.iappyx.launcher.model.Placement

/**
 * Rotates a [HomeLayout] between the user's *dominant orientation* (the
 * orientation they design the grid in, persisted to disk) and whatever
 * orientation the device is currently in. One placement set, two views.
 *
 * `rotateCW` and `rotateCCW` are inverses — load uses one, save uses the
 * other, so the persisted layout is always in dominant coordinates and the
 * in-memory layout is always in current coordinates. Every consumer that
 * reads `layout.cols`/`layout.rows`/`placement.col`/`row`/`wSpan`/`hSpan`
 * already gets correct values for the screen they're rendering on.
 *
 * Math (90° clockwise rotation of a `C × R` grid into an `R × C` grid):
 *
 * ```
 * col_l = R - row_p - h_p     // dominant bottom row → current right column
 * row_l = col_p
 * w_l   = h_p
 * h_l   = w_p
 * ```
 *
 * Inverse for save (CCW back to dominant):
 *
 * ```
 * col_p = row_l               // current right column → dominant bottom row
 * row_p = C - col_l - w_l
 * w_p   = h_l
 * h_p   = w_l
 * ```
 *
 * The dock isn't transformed: it stays a strip with [HomeLayout.dockSlots]
 * slots, addressed by `col`. The visual position of the dock (bottom in
 * portrait vs right edge in landscape) is a UI concern handled separately
 * by the activity's chrome layout, not by the placement coordinates.
 */
object OrientationTransform {

    /** Rotate a single placement clockwise. [sourceRows] is the row count
     *  of the source (pre-rotation) grid; the result lives in a grid with
     *  cols=sourceRows and rows=sourceCols. */
    fun rotateCW(p: Placement, sourceCols: Int, sourceRows: Int): Placement = p.copy(
        col = sourceRows - p.row - p.hSpan,
        row = p.col,
        wSpan = p.hSpan,
        hSpan = p.wSpan,
    )

    /** Inverse of [rotateCW]. */
    fun rotateCCW(p: Placement, sourceCols: Int, sourceRows: Int): Placement = p.copy(
        col = p.row,
        row = sourceCols - p.col - p.wSpan,
        wSpan = p.hSpan,
        hSpan = p.wSpan,
    )

    fun rotateLayoutCW(layout: HomeLayout): HomeLayout = HomeLayout(
        cols = layout.rows,
        rows = layout.cols,
        dockSlots = layout.dockSlots,
        pages = layout.pages.map { page ->
            // Preserve `page.name` — earlier this constructor only passed
            // placements, so rotating dropped every user-given page label
            // ("Work", "Reading", etc.).
            Page(
                placements = page.placements.map { rotateCW(it, layout.cols, layout.rows) }
                    .toMutableList(),
                name = page.name,
            )
        }.toMutableList(),
        // Dock stays the same — slot index is independent of grid orientation.
        dockPages = layout.dockPages.map { it.toMutableList() }.toMutableList(),
        // Clippings are list-ordered, no grid coords — orientation is a no-op.
        clippings = layout.clippings.toMutableList(),
    )

    fun rotateLayoutCCW(layout: HomeLayout): HomeLayout = HomeLayout(
        cols = layout.rows,
        rows = layout.cols,
        dockSlots = layout.dockSlots,
        pages = layout.pages.map { page ->
            Page(
                placements = page.placements.map { rotateCCW(it, layout.cols, layout.rows) }
                    .toMutableList(),
                name = page.name,
            )
        }.toMutableList(),
        dockPages = layout.dockPages.map { it.toMutableList() }.toMutableList(),
        clippings = layout.clippings.toMutableList(),
    )

    /** True when the device's current orientation matches the user's
     *  dominant pref — i.e. no rotation needed. */
    fun currentMatchesDominant(context: Context): Boolean {
        val prefs = LauncherPrefs(context)
        val isLandscapeNow = context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val dominantIsLandscape = prefs.dominantOrientation == "landscape"
        return isLandscapeNow == dominantIsLandscape
    }
}
