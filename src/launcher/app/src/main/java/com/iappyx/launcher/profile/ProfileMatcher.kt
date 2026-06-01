/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.content.Context
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.model.ProfileTrigger

/**
 * Picks the best-matching [Profile] for the device's current trigger
 * state. Conflicts are resolved by fixed priority — see
 * [ProfileTrigger]'s class-level docs.
 *
 * The matcher is stateless and pure; the live state ([State]) is read
 * by the watchers (WiFi, Android Auto, geofence) and passed in.
 */
object ProfileMatcher {

    /** Snapshot of the device's trigger-relevant state at this instant. */
    data class State(
        /** Currently-connected WiFi SSID, or null when no WiFi. */
        val wifiSsid: String? = null,
        /** True when the system reports car / Android Auto UI mode. */
        val androidAuto: Boolean = false,
        /** Slugs of geofences the user is currently inside (zero, one,
         *  or many — the matcher picks the highest-priority profile that
         *  references one of these). */
        val activeGeofences: Set<String> = emptySet(),
        /** MAC addresses of currently-connected Bluetooth devices. */
        val connectedBluetoothAddresses: Set<String> = emptySet(),
        /** Charger plugged-in state. null = unplugged. */
        val charger: ChargerState? = null,
        /** Now (epoch ms) — captured by the watcher so TimeOfDay matching
         *  is deterministic across the evaluate() call. */
        val nowEpochMs: Long = System.currentTimeMillis(),
    )

    enum class ChargerState { WIRED, WIRELESS }

    /**
     * Return the profile to activate, or null when none of the
     * registered triggers match. [Manual] profiles are never picked
     * here — they require an explicit Settings tap.
     *
     * Tie-breaking among same-priority candidates: the most-recently-
     * created profile wins (ProfileLibrary already returns the list
     * sorted by createdAt desc).
     */
    fun match(context: Context, state: State): Profile? {
        val all = ProfileLibrary.all(context)
        if (all.isEmpty()) return null
        return all
            .filter { matches(it.trigger, state, slug = it.slug) }
            .maxByOrNull { it.trigger.priority }
    }

    private fun matches(
        trigger: ProfileTrigger,
        state: ProfileMatcher.State,
        slug: String,
    ): Boolean = when (trigger) {
        is ProfileTrigger.Geofence -> slug in state.activeGeofences
        ProfileTrigger.AndroidAuto -> state.androidAuto
        is ProfileTrigger.WifiSsid -> state.wifiSsid?.equals(trigger.ssid, ignoreCase = false) == true
        ProfileTrigger.WifiDisconnected -> state.wifiSsid == null
        is ProfileTrigger.BluetoothDeviceConnected ->
            trigger.deviceAddress in state.connectedBluetoothAddresses
        is ProfileTrigger.ChargerConnected -> when (state.charger) {
            null -> false
            ChargerState.WIRED -> trigger.kind != ProfileTrigger.ChargerConnected.ChargerKind.WIRELESS
            ChargerState.WIRELESS -> trigger.kind != ProfileTrigger.ChargerConnected.ChargerKind.WIRED
        }
        is ProfileTrigger.TimeOfDay -> isTimeOfDayActive(trigger, state.nowEpochMs)
        ProfileTrigger.Manual -> false
    }

    /** Pure time-of-day check: respects the active-period gates, computes
     *  the user-zone clock minute, handles cross-midnight windows, and
     *  checks the day-of-week bit for the START day. */
    fun isTimeOfDayActive(trigger: ProfileTrigger.TimeOfDay, nowEpochMs: Long): Boolean {
        if (trigger.activeFrom > 0L && nowEpochMs < trigger.activeFrom) return false
        if (trigger.activeUntil > 0L && nowEpochMs > trigger.activeUntil) return false
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDateTime()
        val nowMinute = now.hour * 60 + now.minute
        val s = trigger.startMinuteOfDay
        val e = trigger.endMinuteOfDay
        // Day-of-week: java.time.DayOfWeek.value is Mon=1..Sun=7. Our
        // bitmask is bit0=Mon..bit6=Sun, so bit index = value - 1.
        val crossesMidnight = e <= s
        val startDayOfWeek = if (crossesMidnight && nowMinute < s) {
            // We're in the post-midnight tail of yesterday's window.
            now.minusDays(1).dayOfWeek
        } else {
            now.dayOfWeek
        }
        val bit = 1 shl (startDayOfWeek.value - 1)
        if (trigger.daysOfWeek and bit == 0) return false
        return if (crossesMidnight) {
            nowMinute >= s || nowMinute < e
        } else {
            nowMinute in s until e
        }
    }
}
