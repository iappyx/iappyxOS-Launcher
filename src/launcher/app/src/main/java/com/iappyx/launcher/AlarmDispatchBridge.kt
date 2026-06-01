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
 * Forwards [AlarmReceiver.ACTION_LAUNCHER_ALARM_FIRED] broadcasts to the
 * matching live widget's bridge as a JS event. Works only while the
 * launcher activity is in the foreground — backgrounded alarms surface
 * via [AlarmReceiver]'s own notification path.
 *
 * Lifecycle: pair [start]/[stop] with `onStart`/`onStop`.
 */
class AlarmDispatchBridge(private val context: Context) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val widgetId = intent.getStringExtra(AlarmReceiver.EXTRA_WIDGET_ID) ?: return
            val callbackFn = intent.getStringExtra(AlarmReceiver.EXTRA_CALLBACK) ?: return
            val alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID) ?: ""
            WidgetHost.find(widgetId)?.fireEvent(
                callbackFn,
                "{\"alarmId\":\"${WidgetHost.escapeJson(alarmId)}\"}",
            )
        }
    }

    private var registered: Boolean = false

    fun start() {
        val filter = IntentFilter(AlarmReceiver.ACTION_LAUNCHER_ALARM_FIRED)
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
}
