/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.cells.GeneratedWidgetCell
import com.iappyx.launcher.cells.StockWidgetCell
import com.iappyx.launcher.model.HomeLayout

/**
 * ViewPager2 adapter — the home pager.
 *
 * Index 0 is the AI Command page; indices 1..N are the home pages
 * ([HomeLayout.pages]); when in edit mode an extra virtual trailing page
 * appears at index N+1 so users can swipe right to add a new page.
 *
 * Position-to-page-index mapping:
 *   pager.currentItem == 0      → command panel
 *   pager.currentItem >= 1      → layout.pages[currentItem - 1]
 */
class HomePagerAdapter(
    private val activity: LauncherActivity,
    private var layout: HomeLayout,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Toggles whether the trailing virtual "+" page is included in [getItemCount].
     *  Does NOT notify — the activity drives a targeted `notifyItemInserted` /
     *  `notifyItemRangeRemoved` for just the virtual slot, leaving the visible
     *  page attached. A blanket `notifyDataSetChanged` here would re-bind every
     *  holder, including the visible one, and that rebind is the source of the
     *  intermittent "blank screen on long-press" race we kept patching. */
    var editMode: Boolean = false
        set(value) { field = value }

    companion object {
        private const val TYPE_COMMAND = 0
        private const val TYPE_HOME = 1
        private const val TYPE_CLIPPINGS = 2
    }

    /** Pager indices: 0 = AI command, 1..N = home pages (+ optional virtual
     *  edit-mode "+" at N+1), and the final position is always Clippings. */
    fun clippingsPosition(): Int = itemCount - 1

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_COMMAND
        position == clippingsPosition() -> TYPE_CLIPPINGS
        else -> TYPE_HOME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_COMMAND -> {
                val panel = activity.createCommandPanel()
                panel.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CommandHolder(panel)
            }
            TYPE_CLIPPINGS -> {
                val view = com.iappyx.launcher.clippings.ClippingsPageView(activity, activity)
                ClippingsHolder(view)
            }
            else -> {
                val grid = activity.createEmptyGrid(layout.cols, layout.rows)
                // Wrap the grid in a FrameLayout that pads 118dp at the bottom —
                // this preserves the clearance for the dock now that the pager
                // itself spans full height. The grid's internal cell math reads
                // measuredHeight, so shrinking via wrapper padding gives the
                // correct cell size without touching HomeGrid.
                val dp = activity.resources.displayMetrics.density
                val wrapper = android.widget.FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setPadding(0, 0, 0, (118 * dp).toInt())
                    clipToPadding = false
                }
                grid.layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                )
                wrapper.addView(grid)
                HomeHolder(wrapper, grid)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HomeHolder -> {
                val homeIdx = position - 1 // skip command page
                // Virtual trailing page in edit mode → empty placement list.
                val page = layout.pages.getOrNull(homeIdx) ?: com.iappyx.launcher.model.Page()
                activity.renderPage(holder.grid, homeIdx, page)
            }
            is ClippingsHolder -> holder.view.refresh()
            // CommandHolder binds itself once via activity.createCommandPanel().
        }
    }

    override fun getItemCount(): Int =
        1 /* command */ + layout.pages.size + (if (editMode) 1 else 0) + 1 /* clippings */

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // Tear down WebViews + widget host views on home pages.
        if (holder is HomeHolder) {
            val grid = holder.grid
            for (i in 0 until grid.childCount) {
                when (val v = grid.getChildAt(i)) {
                    // Page recycle, NOT removal — light teardown so a keepAlive
                    // widget's background work survives (full network teardown
                    // only on permanent removal).
                    is GeneratedWidgetCell -> v.destroyWidget(permanent = false)
                    is StockWidgetCell -> v.release()
                }
            }
            grid.removeAllViews()
        }
        // Clippings page handles its own RV teardown via onViewRecycled
        // inside its inner adapter — no extra work here.
        super.onViewRecycled(holder)
    }

    fun setLayout(newLayout: HomeLayout) {
        this.layout = newLayout
        notifyDataSetChanged()
    }

    /** Update the in-memory layout reference without firing a notify. Use
     *  this when the caller will issue more targeted notifications (or
     *  re-render specific pages in place via a live-grid lookup). Avoids
     *  the recycle storm that notifyDataSetChanged forces on a visible
     *  HomeGrid — that recycle is what blanks the page when called from
     *  inside a system-DnD ACTION_DROP handler. */
    fun updateLayoutSilent(newLayout: HomeLayout) {
        this.layout = newLayout
    }

    class HomeHolder(
        wrapper: android.widget.FrameLayout,
        val grid: com.iappyx.launcher.widget.HomeGrid,
    ) : RecyclerView.ViewHolder(wrapper)
    class CommandHolder(view: android.view.View) : RecyclerView.ViewHolder(view)
    class ClippingsHolder(val view: com.iappyx.launcher.clippings.ClippingsPageView) :
        RecyclerView.ViewHolder(view)
}
