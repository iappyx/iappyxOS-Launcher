/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

/**
 * Tracks Android's battery-saver state for the launcher activity. The
 * activity reads it to decide whether off-screen widgets should pause
 * even when their page is current (saver-on = pause everything that
 * isn't `keepAlive`).
 *
 * Cached value is read from [PowerManager] once at [start] so pre-existing
 * saver state is reflected without waiting for the next broadcast, and
 * subsequently kept fresh by an [ACTION_POWER_SAVE_MODE_CHANGED] receiver.
 *
 * Lifecycle: pair [start] with the activity's `onStart` and [stop] with
 * `onStop` — matches the lifetime the original inline receiver had.
 *
 * @param onChanged invoked on the main thread whenever the cached value
 *        flips (true→false or false→true). Use it to re-evaluate widget
 *        visibility. Not called on identical-value broadcasts (the
 *        system fires duplicates occasionally).
 */
class PowerSaveBridge(
    private val context: Context,
    private val onChanged: () -> Unit,
) {

    @Volatile
    private var cached: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
            val pm = c.getSystemService(PowerManager::class.java) ?: return
            val now = pm.isPowerSaveMode
            if (now == cached) return
            cached = now
            onChanged()
        }
    }

    private var registered: Boolean = false

    fun start() {
        // Seed once so the first read after start() reflects reality.
        cached = (context.getSystemService(PowerManager::class.java)
            ?.isPowerSaveMode == true)
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
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
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { /* not registered */ }
        registered = false
    }

    /** Most-recent battery-saver state. False until [start] runs at least once. */
    fun isOn(): Boolean = cached
}
