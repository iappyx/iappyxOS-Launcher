/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.model.HomeLayout

/** ViewPager2 adapter for dock pages. Each page is a horizontal row of 5 slots. */
class DockPagerAdapter(
    private val activity: LauncherActivity,
    private var layout: HomeLayout,
) : RecyclerView.Adapter<DockPagerAdapter.Holder>() {

    /** See [HomePagerAdapter.editMode] — silent setter; activity drives the
     *  targeted notify for just the virtual trailing dock page. */
    var editMode: Boolean = false
        set(value) { field = value }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return Holder(row)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        activity.renderDockPage(holder.row, position)
    }

    override fun getItemCount(): Int =
        layout.dockPages.size + (if (editMode) 1 else 0)

    fun setLayout(l: HomeLayout) { layout = l; notifyDataSetChanged() }

    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)
}
