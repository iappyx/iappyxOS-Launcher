/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.app.Activity
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.iappyx.launcher.R
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.widget.HomeGrid
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the bounding-box JSON the live wallpaper sees through
 * `iappyxLayout.get()` and `iappyx.onLayoutChanged(layout)`.
 *
 * Privacy contract: rectangles only — no package names, widget ids, labels,
 * folder contents, icon types. Just enough geometry for a wallpaper to do
 * collision-aware animations (a ball that bounces between cells, an orbit
 * that hugs the dock, a particle field that avoids icons).
 *
 * Coordinate space: **CSS pixels** matching the wallpaper WebView's natural
 * `window.innerWidth` / `window.innerHeight` space (with the standard
 * `viewport: width=device-width, initial-scale=1` meta tag every wallpaper
 * uses). The launcher's `getLocationInWindow` returns DEVICE pixels, so we
 * divide everything by `density` before emitting. `screen.density` is still
 * included for wallpapers that want to scale stroke widths or particle sizes
 * proportionally to physical screen size, but coordinates are always CSS px.
 *
 * Output shape:
 *
 * ```json
 * {
 *   "screen":     { "width": 1080, "height": 2280, "density": 2.75 },
 *   "pageCount":  4,
 *   "pageWidth":  1080,
 *   "currentPage": 1,
 *   "systemBars": { "top": 80, "bottom": 60 },
 *   "cells": [{ "page": 0, "x": 24, "y": 200, "w": 200, "h": 200 }, …],
 *   "dock":  [{ "x": 24, "y": 1980, "w": 200, "h": 200 }, …]
 * }
 * ```
 *
 * `cells[].page` and `currentPage` are both in HOME-PAGE INDEX SPACE (0..N-1
 * across the home grid pages — the AI command page at pager index 0 is NOT
 * counted, since the wallpaper is invisible behind it anyway).
 *
 * Live cell position during a swipe: combine with the existing
 * `iappyx.onPageOffset(x)` push event, where `x` is in [0, 1] ACROSS THE
 * WHOLE HOME PAGER (not per-page — that's a WallpaperManager convention).
 * The conversion is:
 *
 *     fracHome = pageOffset * max(1, pageCount - 1)         // home-page index, fractional
 *     liveX    = cell.x + (cell.page - fracHome) * pageWidth
 *
 * We do NOT push a new layout per swipe frame — that's why this transformation
 * is the wallpaper's job.
 */
object LayoutSerializer {

    /**
     * Serialize the launcher's current layout to JSON. Must be called after
     * the home pager has at least one realized [HomeGrid] (we need its
     * cellWidth/cellHeight + window-coords origin to compute cell rectangles
     * for non-realized pages too — every page shares the same geometry).
     *
     * Returns `null` if the views aren't laid out yet (caller should retry
     * after the next frame, or skip the broadcast).
     */
    fun serialize(activity: Activity, layout: HomeLayout): String? {
        val pager = activity.findViewById<ViewPager2>(R.id.pager) ?: return null
        if (pager.width <= 0 || pager.height <= 0) return null

        val grid = findRealizedHomeGrid(pager) ?: return null
        if (grid.measuredWidth <= 0 || grid.measuredHeight <= 0) return null

        val cellW = grid.cellWidthPx()
        val cellH = grid.cellHeightPx()
        val spacing = grid.spacingPx
        val gridLoc = IntArray(2).also { grid.getLocationInWindow(it) }
        val gridX = gridLoc[0]
        val gridY = gridLoc[1]

        val dm = activity.resources.displayMetrics
        val density = dm.density

        // System-bar insets read from the activity's decor view — same path
        // the launcher uses for its own padding, so the wallpaper sees the
        // exact same safe-area numbers.
        val barInsets = activity.window?.decorView?.let { decor ->
            decor.rootWindowInsets?.let { rootInsets ->
                WindowInsetsCompat.toWindowInsetsCompat(rootInsets, decor)
                    .getInsets(WindowInsetsCompat.Type.systemBars())
            }
        }
        val statusTop = barInsets?.top ?: 0
        val navBottom = barInsets?.bottom ?: 0

        // Convert device-pixel measurements to CSS-pixel measurements before
        // emitting. The wallpaper canvas with `width=device-width,
        // initial-scale=1` has innerWidth = deviceWidth / density, so cells
        // emitted in raw device pixels would render off by a factor of
        // `density` and balls would slide right past visible icons.
        fun cssPx(devicePx: Float): Int = kotlin.math.round(devicePx / density).toInt()
        fun cssPx(devicePx: Int): Int = kotlin.math.round(devicePx / density).toInt()

        val cells = JSONArray()
        for ((pageIdx, page) in layout.pages.withIndex()) {
            for (p in page.placements) {
                val x = gridX + spacing + p.col * (cellW + spacing)
                val y = gridY + spacing + p.row * (cellH + spacing)
                val w = cellW * p.wSpan + spacing * (p.wSpan - 1)
                val h = cellH * p.hSpan + spacing * (p.hSpan - 1)
                cells.put(JSONObject().apply {
                    put("page", pageIdx)
                    put("x", cssPx(x))
                    put("y", cssPx(y))
                    put("w", cssPx(w))
                    put("h", cssPx(h))
                })
            }
        }

        // Dock: read the live dock_bar bounds (window coords) and slice it
        // into N equal-width slots. Only the currently-visible dock page's
        // occupied slots are reported — when the user swipes the dock to a
        // different page, the launcher re-broadcasts with the new slots.
        val dock = JSONArray()
        val dockBar = activity.findViewById<View>(R.id.dock_bar)
        val activeDockPage = layout.dockPages.getOrNull(0) ?: emptyList()
        if (dockBar != null && dockBar.width > 0 && dockBar.height > 0) {
            val dockLoc = IntArray(2).also { dockBar.getLocationInWindow(it) }
            val dockX = dockLoc[0]
            val dockY = dockLoc[1]
            val slotCount = layout.dockSlots.coerceAtLeast(1)
            val slotW = dockBar.width.toFloat() / slotCount
            for (placement in activeDockPage) {
                val slot = placement.col.coerceIn(0, slotCount - 1)
                dock.put(JSONObject().apply {
                    put("x", cssPx(dockX + slot * slotW))
                    put("y", cssPx(dockY))
                    put("w", cssPx(slotW))
                    put("h", cssPx(dockBar.height))
                })
            }
        }

        val obj = JSONObject().apply {
            put("screen", JSONObject().apply {
                put("width", cssPx(dm.widthPixels))
                put("height", cssPx(dm.heightPixels))
                put("density", density)
            })
            put("pageCount", layout.pages.size)
            // Optional user-given page names, parallel to pages[].
            // Empty string at slot i means "page i has no name; fall back
            // to ordinal in any UI surface". Wallpapers that label pages
            // (mini-map, breadcrumb dots, etc.) read this.
            put("pageNames", JSONArray().apply {
                for (p in layout.pages) put(p.name)
            })
            put("pageWidth", cssPx(pager.width))
            // Home-page index (0..pageCount-1) the user is currently on.
            // The pager's index 0 is the AI command panel; subtract 1 to get
            // a value in the same space as `cells[].page`. Clamp at 0 so the
            // wallpaper sees something sensible even while the user is on
            // the command page (where the wallpaper is occluded anyway).
            put("currentPage", (pager.currentItem - 1)
                .coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0)))
            put("systemBars", JSONObject().apply {
                put("top", cssPx(statusTop))
                put("bottom", cssPx(navBottom))
            })
            put("cells", cells)
            put("dock", dock)
        }
        return obj.toString()
    }

    /** Walk the pager's RecyclerView to find the HomeGrid for the **currently
     *  visible** page. ViewPager2 keeps adjacent pages realized for smooth
     *  swiping, so picking just any realized grid risks getting one that's
     *  shifted off-screen by ±pageWidth — every cell coordinate would then
     *  inherit that offset and balls slide right past the last page's
     *  widgets. We compare each realized child's adapter position to
     *  [ViewPager2.getCurrentItem] to be sure we pick the right one. */
    private fun findRealizedHomeGrid(pager: ViewPager2): HomeGrid? {
        val rv = (0 until pager.childCount)
            .map { pager.getChildAt(it) }
            .filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
            .firstOrNull() ?: return null
        val currentItem = pager.currentItem
        // First pass: prefer the grid whose adapter position matches the
        // pager's current item.
        for (i in 0 until rv.childCount) {
            val pageRoot = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(pageRoot)
            if (pos != currentItem) continue
            val grid = findHomeGridIn(pageRoot)
            if (grid != null && grid.measuredWidth > 0) return grid
        }
        // Fallback: any realized HomeGrid (the current page might be the
        // command panel, which has no HomeGrid). Geometry is shared across
        // pages so cellW/cellH still come out right; only the absolute x
        // origin will be off, but that fallback path runs only when the
        // user is on the AI command page where the wallpaper is occluded.
        for (i in 0 until rv.childCount) {
            val grid = findHomeGridIn(rv.getChildAt(i))
            if (grid != null && grid.measuredWidth > 0) return grid
        }
        return null
    }

    private fun findHomeGridIn(view: View): HomeGrid? {
        if (view is HomeGrid) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findHomeGridIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

}
