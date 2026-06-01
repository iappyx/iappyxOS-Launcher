/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.widget.HomeGrid

/**
 * Recomputes the home chrome strip's `marginBottom` so its vertical
 * centre sits exactly halfway between the bottom of the lowest occupied
 * home-grid row (across all pages) and the top of the dock. Replaces
 * the static XML margin with a runtime value that adapts to the user's
 * layout — sparse layouts get the strip higher, dense ones get it lower.
 *
 * Bounded: never less than a 4dp gap above the dock, even if the
 * computation would put the strip below the dock's top edge.
 *
 * **Important — never call this on a per-swipe / per-settle path.**
 * Mutating `strip.layoutParams` forces an `activity_root` layout pass
 * that ripples into the pager's WebView children → tile rebuild flicker.
 * Call only on actual layout changes (cell drop, edit-mode commit) or
 * on initial post-create layout.
 *
 * @param activity owning activity (for findViewById + resources)
 * @param pager the home ViewPager2
 * @param stripId resource id of the chrome strip View
 * @param dockBarId resource id of the dock-bar View
 * @param layoutProvider returns the current [HomeLayout] (so the
 *        controller doesn't hold stale layout references)
 */
class ChromeStripController(
    private val activity: Activity,
    private val pager: ViewPager2,
    private val stripId: Int,
    private val dockBarId: Int,
    private val layoutProvider: () -> HomeLayout,
) {

    /** Run the recompute. Defers itself one frame if views aren't laid
     *  out yet. Cheap when the new margin matches the current one. */
    fun recenter() {
        val strip = activity.findViewById<View>(stripId) ?: return
        val dockBar = activity.findViewById<View>(dockBarId) ?: return
        if (strip.height <= 0 || dockBar.height <= 0) {
            // Layout hasn't settled yet — defer one frame.
            strip.post { recenter() }
            return
        }
        val grid = currentVisibleHomeGrid()
        if (grid == null) {
            // User is on the Command page (or no home grid bound yet).
            // Defer with a short delay — when the user swipes to a home
            // page, the grid binds and the next recenter call will pick
            // it up. Bailing without retrying leaves the strip stuck at
            // the XML default margin forever (= 150dp, overlaps the
            // bottom row of icons on dense layouts).
            strip.postDelayed({ recenter() }, 250)
            return
        }
        if (grid.measuredHeight <= 0) {
            strip.post { recenter() }
            return
        }
        val density = activity.resources.displayMetrics.density
        val screenH = activity.resources.displayMetrics.heightPixels
        val dockLoc = IntArray(2).also { dockBar.getLocationOnScreen(it) }
        val dockTopScreenY = dockLoc[1]
        // Strip pinned just above the dock — the floor for any layout,
        // dense or sparse. Computed up front so the early-return paths
        // below can fall through to it instead of bailing.
        // marginBottom is the distance from the parent's bottom edge to
        // the strip's BOTTOM edge. To park the strip JUST ABOVE the
        // dock with a 4dp gap, marginBottom must equal:
        //   (distance from screen bottom to dock top) + 4dp
        // i.e. (screenH - dockTopScreenY) + 4dp. Earlier this code
        // subtracted strip.height, which is wrong — that would only
        // be correct if marginBottom referred to the strip's TOP edge.
        // The bug pushed the strip's bottom INSIDE the dock area.
        val minMargin = ((screenH - dockTopScreenY) + 4f * density).toInt()

        // Centre the strip in the gap between the bottom of the WHOLE grid
        // area and the dock — NOT the lowest occupied row. Using the occupied
        // row floated the dots up to mid-screen when a page held a single
        // widget near the top; the user wants the dots in a consistent place
        // (between the full icon/widget area and the dock) regardless of how
        // full the page is. `grid.measuredHeight` is the full grid area.
        val gridLoc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val gridBottomScreenY = (gridLoc[1] + grid.measuredHeight).toFloat()
        val newMargin: Int = if (dockTopScreenY <= gridBottomScreenY) {
            // Grid reaches (or passes) the dock top — no gap to centre in.
            minMargin
        } else {
            // Geometric centre of the gap between the grid-area bottom and
            // the dock top.
            val gapCenterY = (gridBottomScreenY + dockTopScreenY) / 2f
            val stripHalfPx = strip.height / 2
            (screenH - (gapCenterY + stripHalfPx)).toInt()
        }.coerceAtLeast((4f * density).toInt())

        val lp = strip.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != newMargin) {
            lp.bottomMargin = newMargin
            strip.layoutParams = lp
        }
    }

    /** Walk the pager's RecyclerView to find the HomeGrid for the
     *  currently-visible page. Null on the command page or before the
     *  first holder is bound. */
    private fun currentVisibleHomeGrid(): HomeGrid? {
        if (pager.currentItem < 1) return null
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(pager.currentItem) ?: return null
        val itemView = holder.itemView
        if (itemView is ViewGroup) {
            for (i in 0 until itemView.childCount) {
                val c = itemView.getChildAt(i)
                if (c is HomeGrid) return c
            }
        }
        return null
    }
}
