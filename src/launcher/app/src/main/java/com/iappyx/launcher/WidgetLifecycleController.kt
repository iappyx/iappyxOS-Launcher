/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.iappyx.launcher.cells.GeneratedWidgetCell

/**
 * Walks the home pager's realized pages and pauses/resumes each
 * [GeneratedWidgetCell]'s WebView based on visibility. Without this,
 * every widget on every realized page (typically 2–3 with ViewPager2's
 * default offscreenPageLimit) keeps RAF + JS timers running — the
 * dominant background drain on a populated home screen. The widget's
 * `setLifecycleVisible` itself respects each widget's `keepAlive`
 * meta-tag (music players, live trackers).
 *
 * **Battery-saver override.** When the system reports power-save mode,
 * every non-keepAlive widget is paused regardless of which page is
 * current. Caller provides the saver state via [isSaverOn] (so this
 * class doesn't reach for the singleton bridge directly).
 *
 * @param pager the home ViewPager2
 * @param isSaverOn returns true when the system is in battery-saver
 *        mode; reads from [PowerSaveBridge].
 */
class WidgetLifecycleController(
    private val pager: ViewPager2,
    private val isSaverOn: () -> Boolean,
) {

    /** Resume widgets on the currently-visible page; pause widgets on
     *  every other realized page. Called on page change AND on activity
     *  resume. No-op if the pager hasn't realized a RecyclerView yet
     *  (cold start before the first layout pass). */
    fun applyForCurrentPage() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        val current = pager.currentItem
        val saver = isSaverOn()
        for (i in 0 until rv.childCount) {
            val pageRoot = rv.getChildAt(i) as? ViewGroup ?: continue
            val pos = rv.getChildAdapterPosition(pageRoot)
            val visible = !saver && (pos == current)
            forEachGeneratedWidgetIn(pageRoot) { it.setLifecycleVisible(visible) }
        }
    }

    /** Pause every realized widget regardless of which page they're on
     *  — used when the launcher goes to the background. Activity calls
     *  this from `onPause`; matched by [applyForCurrentPage] in
     *  `onResume` which selectively resumes only the current page. */
    fun pauseAll() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val pageRoot = rv.getChildAt(i) as? ViewGroup ?: continue
            forEachGeneratedWidgetIn(pageRoot) { it.setLifecycleVisible(false) }
        }
    }

    private fun forEachGeneratedWidgetIn(
        vg: ViewGroup,
        block: (GeneratedWidgetCell) -> Unit,
    ) {
        for (i in 0 until vg.childCount) {
            when (val c = vg.getChildAt(i)) {
                is GeneratedWidgetCell -> block(c)
                is ViewGroup -> forEachGeneratedWidgetIn(c, block)
            }
        }
    }
}
