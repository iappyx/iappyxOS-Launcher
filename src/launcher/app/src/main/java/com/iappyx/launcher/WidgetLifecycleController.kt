/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.view.Display
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
 * **Screen-off pause.** Activity `onPause` does NOT fire on screen-off
 * when the launcher is the user's home app — the activity stays in the
 * resumed state while the display dozes (especially with Daydream or
 * no lock screen). Without an explicit display-state hook, widget
 * sensors stay registered for the entire screen-off window — measured
 * 17h of continuous rotation-vector sampling against 1h of screen-on
 * time on a real tablet. Two complementary signals close the gap:
 *
 *  - [DisplayManager.DisplayListener] reacts to `Display.STATE_OFF/DOZE`.
 *  - A [BroadcastReceiver] on `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT`
 *    handles devices where AOD keeps `Display.STATE` at `ON` even when
 *    the user can't see anything (typical Always-On Display behaviour).
 *    Without this receiver, `watchPosition` GPS subscriptions can survive
 *    overnight on those devices — measured 17h continuous GPS / ~4%/hr
 *    drain on an AOD-enabled device. `ACTION_SCREEN_OFF` fires reliably
 *    on the user-perceived screen-off event regardless of AOD.
 *
 * Resumes only happen when the activity is actually foreground; tracked
 * via [onActivityResumed]/[onActivityPaused].
 *
 * @param pager the home ViewPager2
 * @param isSaverOn returns true when the system is in battery-saver
 *        mode; reads from [PowerSaveBridge].
 */
class WidgetLifecycleController(
    private val pager: ViewPager2,
    private val isSaverOn: () -> Boolean,
) {

    private var activityResumed = false

    private val displayManager: DisplayManager? by lazy {
        pager.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            when (display.state) {
                Display.STATE_OFF,
                Display.STATE_DOZE,
                Display.STATE_DOZE_SUSPEND -> pauseAll()
                Display.STATE_ON,
                Display.STATE_ON_SUSPEND -> {
                    // STATE_ON fires whenever the user wakes the device into
                    // ANY app, not just home. Only resume our widgets if
                    // LauncherActivity is actually foreground — otherwise the
                    // widgets stay paused until the user returns to home, at
                    // which point `onResume` calls applyForCurrentPage again.
                    if (activityResumed) applyForCurrentPage()
                }
            }
        }
    }

    /** Receiver for [Intent.ACTION_SCREEN_OFF] and [Intent.ACTION_USER_PRESENT].
     *  Necessary because some OEM AOD implementations keep `Display.STATE`
     *  at `ON` during Daydream, so the [DisplayManager.DisplayListener]
     *  above never fires a pause transition. `ACTION_SCREEN_OFF` is the
     *  reliable user-perceived-screen-off signal across OEM AOD modes. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseAll()
                Intent.ACTION_USER_PRESENT -> if (activityResumed) applyForCurrentPage()
            }
        }
    }

    private var screenReceiverRegistered = false

    /** Start listening to display state. Call from [LauncherActivity.onCreate]. */
    fun attach() {
        displayManager?.registerDisplayListener(displayListener, null)
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            pager.context.applicationContext.registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }
    }

    /** Stop listening. Call from [LauncherActivity.onDestroy]. */
    fun detach() {
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        if (screenReceiverRegistered) {
            try { pager.context.applicationContext.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
            screenReceiverRegistered = false
        }
    }

    /** Mark the activity as foreground. Call from [LauncherActivity.onResume]. */
    fun onActivityResumed() { activityResumed = true }

    /** Mark the activity as background. Call from [LauncherActivity.onPause]. */
    fun onActivityPaused() { activityResumed = false }

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
        // Walk the pager's realized pages and pause each widget. This is
        // sufficient: a widget on the current page is an attached child and
        // is caught here; a widget on any other page was already paused when
        // it left the current page (onPageSelected → applyForCurrentPage);
        // and a recycled widget's host tears down its own location/sensor
        // listeners in WidgetHost.destroy(). (An earlier version also looped
        // over WidgetHost.hostsByWidgetId, but that global map also holds
        // preview + remote-edit hosts — which applyForCurrentPage never
        // resumes — so it froze those features' sensors after a screen-off.)
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
