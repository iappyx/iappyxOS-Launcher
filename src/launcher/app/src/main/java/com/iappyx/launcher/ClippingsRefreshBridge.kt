/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Listens for [LauncherPrefs.CLIPPINGS_CHANGED_ACTION] broadcasts (fired
 * by `ShareReceiverActivity` when a new clipping is added) and notifies
 * the activity to refresh its in-memory layout + clippings page.
 *
 * `onResume` already reloads layout on foregrounding, but if a share
 * lands while the launcher IS already foregrounded the new card would
 * otherwise be missed until next pause/resume cycle.
 *
 * Receiver is package-scoped via `setPackage` in the sender;
 * `RECEIVER_NOT_EXPORTED` gates Android 13+ further.
 *
 * Lifecycle: pair [start] with the activity's `onCreate` (after the
 * activity has its own `layout`/adapter wired) and [stop] with `onDestroy`.
 *
 * @param onClippingsChanged invoked on the main thread when a clippings
 *        broadcast arrives. The activity reloads layout, calls
 *        `pagerAdapter.setLayout`, refreshes indicators + the clippings page.
 */
class ClippingsRefreshBridge(
    private val context: Context,
    private val onClippingsChanged: () -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try { onClippingsChanged() } catch (_: Throwable) { /* best-effort refresh */ }
        }
    }

    private var registered: Boolean = false

    fun start() {
        val filter = IntentFilter(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) { /* not registered */ }
        registered = false
    }
}
