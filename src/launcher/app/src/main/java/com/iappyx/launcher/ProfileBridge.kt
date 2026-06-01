/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.profile.ProfileGeofenceManager
import com.iappyx.launcher.profile.ProfileWatcher

/**
 * Owns the profile auto-switch surface inside the launcher activity:
 * a [ProfileWatcher] that drives the WiFi / Android Auto / geofence /
 * Bluetooth / charger / time-of-day matcher, plus a broadcast receiver
 * for `ProfilesActivity` resume / reschedule signals, plus the
 * idempotent geofence re-registration on activity start.
 *
 * When the matcher picks a different profile, [onProfileSwap] runs on
 * the main thread — the activity is responsible for the actual UI
 * refresh (layout reload, adapter notifies, wallpaper broadcast,
 * toast). This class doesn't touch views.
 *
 * Lifecycle: pair [start] with `onCreate` (after the activity has wired
 * its own state) and [stop] with `onDestroy`.
 *
 * @param activity host (used as Context for receiver registration +
 *        ProfileWatcher's Context; also receives the geofence
 *        re-registration call)
 * @param onProfileSwap invoked on the main thread when the matcher
 *        picks a profile different from the live one. Provided
 *        [Profile] is the new one to apply.
 */
class ProfileBridge(
    private val activity: Activity,
    private val onProfileSwap: (Profile) -> Unit,
) {

    private var watcher: ProfileWatcher? = null
    private var resumeReceiver: BroadcastReceiver? = null

    fun start() {
        // Watcher: drives the auto-match and asks the activity to swap.
        // Geofence ENTER/EXIT events from GeofenceTransitionReceiver also
        // feed back into this watcher.
        watcher = ProfileWatcher(activity) { matched ->
            activity.runOnUiThread { onProfileSwap(matched) }
        }.also { it.start() }

        // ProfilesActivity sends these broadcasts when:
        //  - the user un-pauses auto-switching from the toggle there
        //  - a TimeOfDay trigger is added/edited/removed (re-schedule
        //    the next alarm boundary)
        // We re-evaluate on the spot so the right profile snaps in
        // without waiting for the next WiFi/Auto/geofence event. The
        // receiver is package-scoped via Intent.setPackage on the sender.
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    ProfilesActivity.ACTION_PROFILE_TIME_RESCHEDULE ->
                        watcher?.rescheduleTimeAlarms()
                    else -> watcher?.reevaluate()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ProfilesActivity.ACTION_PROFILE_AUTOSWITCH_RESUMED)
            addAction(ProfilesActivity.ACTION_PROFILE_TIME_RESCHEDULE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(rx, filter)
        }
        resumeReceiver = rx

        // Re-register profile geofences with Play Services. Idempotent —
        // covers the case where a device reboot cleared its geofence
        // registry, or where the user added a profile while the launcher
        // process was paused so the registration didn't happen at save
        // time. Best-effort; no-op when no profiles use a Geofence trigger.
        try {
            ProfileGeofenceManager.reRegisterAll(activity)
        } catch (_: Throwable) { /* best-effort */ }
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        resumeReceiver?.let {
            try { activity.unregisterReceiver(it) } catch (_: Throwable) { /* not registered */ }
        }
        resumeReceiver = null
    }
}
