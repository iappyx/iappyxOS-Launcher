/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.iappyx.launcher.LauncherPrefs

/**
 * Background watcher that maintains a [ProfileMatcher.State] from
 * device-state signals (WiFi connected/disconnected, Android Auto / car
 * mode, geofences) and asks the matcher to pick the active profile on
 * every change. When the picked profile differs from
 * [LauncherPrefs.activeProfileSlug], the watcher fires [onSwapRequested]
 * — the activity takes care of the actual UI refresh.
 *
 * Owned by [com.iappyx.launcher.LauncherActivity]: started in onCreate,
 * stopped in onDestroy.
 */
class ProfileWatcher(
    private val context: Context,
    private val onSwapRequested: (com.iappyx.launcher.model.Profile) -> Unit,
) {

    private val cm: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    private val wm: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
@Volatile private var currentSsid: String? = null
    /** True while the phone is connected to a car head-unit via Android
     *  Auto (projection) or Automotive OS (native). Driven exclusively by
     *  [androidx.car.app.connection.CarConnection]. We deliberately do NOT
     *  use [UiModeManager.UI_MODE_TYPE_CAR] / `ACTION_ENTER_CAR_MODE` —
     *  those signals fire only when the user manually enters a phone-side
     *  drive-mode UI (rare in 2026), not when the phone projects to a car
     *  display, which is what users actually mean by "in the car". */
    @Volatile private var carMode: Boolean = false
    private val activeGeofences: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    /** MAC addresses of currently-connected Bluetooth devices. Updated by
     *  [bluetoothReceiver] on ACL_CONNECTED/DISCONNECTED. */
    private val connectedBluetooth: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())
    /** null = unplugged. WIRED / WIRELESS otherwise. Driven by
     *  [chargerReceiver] sticky read + ACTION_POWER_CONNECTED/DISCONNECTED. */
    @Volatile private var chargerState: ProfileMatcher.ChargerState? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var geofencePrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var carConnection: androidx.car.app.connection.CarConnection? = null
    private var carConnectionObserver: androidx.lifecycle.Observer<Int>? = null
    private var bluetoothReceiver: android.content.BroadcastReceiver? = null
    private var chargerReceiver: android.content.BroadcastReceiver? = null
    private var timeAlarmReceiver: android.content.BroadcastReceiver? = null

    fun start() {
        // CarConnection's first onChanged seeds carMode once observeForever
        // is wired below. WiFi SSID is read synchronously up front so the
        // first match-and-swap reflects reality before any callback fires.
        currentSsid = readCurrentSsid()
        // Seed the geofence inside-set from the persistent record. Any
        // ENTER/EXIT events that fired while the launcher process was
        // dead are reflected here.
        synchronized(activeGeofences) {
            activeGeofences.clear()
            activeGeofences.addAll(ProfileGeofenceState.snapshot(context))
        }
        registerNetworkCallback()
        registerCarConnection()
        registerGeofenceStateObserver()
        registerBluetoothReceiver()
        registerChargerReceiver()
        registerTimeAlarmReceiver()
        scheduleNextTimeOfDayAlarm()
        evaluate()
    }

    fun stop() {
        networkCallback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Throwable) {} }
        networkCallback = null
        carConnectionObserver?.let { obs ->
            try { carConnection?.type?.removeObserver(obs) } catch (_: Throwable) {}
        }
        carConnectionObserver = null
        carConnection = null
        geofencePrefsListener?.let {
            try {
                context.applicationContext
                    .getSharedPreferences("iappyx_profile_geofence_state", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            } catch (_: Throwable) {}
        }
        geofencePrefsListener = null
        bluetoothReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Throwable) {} }
        bluetoothReceiver = null
        chargerReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Throwable) {} }
        chargerReceiver = null
        timeAlarmReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Throwable) {} }
        timeAlarmReceiver = null
        cancelTimeOfDayAlarm()
    }

    private fun registerGeofenceStateObserver() {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Re-read the inside-set on any change. The receiver writes
            // here from a broadcast; this hook lets the live launcher
            // re-evaluate within milliseconds.
            synchronized(activeGeofences) {
                activeGeofences.clear()
                activeGeofences.addAll(ProfileGeofenceState.snapshot(context))
            }
            evaluate()
        }
        geofencePrefsListener = listener
        try {
            context.applicationContext
                .getSharedPreferences("iappyx_profile_geofence_state", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "geofence state observer register failed: ${e.message}")
        }
    }

    /** Called by GeofenceTransitionReceiver hand-off — drives the
     *  matcher when ENTER/EXIT events fire. */
    fun onGeofenceTransition(slug: String, entered: Boolean) {
        if (entered) activeGeofences.add(slug) else activeGeofences.remove(slug)
        evaluate()
    }

    private fun registerNetworkCallback() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentSsid = readCurrentSsid()
                evaluate()
            }
            override fun onLost(network: Network) {
                currentSsid = null
                evaluate()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // SSID can change without onAvailable / onLost cycling.
                currentSsid = readCurrentSsid()
                evaluate()
            }
        }
        networkCallback = cb
        try { cm?.registerNetworkCallback(req, cb) }
        catch (e: Throwable) { Log.w(TAG, "WiFi network callback register failed: ${e.message}") }
    }

    private fun registerCarConnection() {
        try {
            val conn = androidx.car.app.connection.CarConnection(context)
            val obs = androidx.lifecycle.Observer<Int> { state ->
                if (state == null) return@Observer
                val connected = state !=
                    androidx.car.app.connection.CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                if (carMode != connected) {
                    carMode = connected
                    Log.i(TAG, "CarConnection state=$state -> carMode=$connected")
                    evaluate()
                }
            }
            // observeForever because ProfileWatcher isn't a LifecycleOwner.
            // We balance this with explicit removeObserver() in stop().
            conn.type.observeForever(obs)
            carConnection = conn
            carConnectionObserver = obs
        } catch (e: Throwable) {
            // androidx.car.app on devices without the Auto host can throw
            // during construction. Treat as "Auto unavailable" — carMode
            // stays false, no car-trigger profile swaps.
            Log.w(TAG, "CarConnection unavailable: ${e.message}")
        }
    }

private fun readCurrentSsid(): String? {
        // WifiManager.connectionInfo is deprecated on API 31+ but still
        // returns the SSID for the launcher's own foreground use. The
        // SSID is wrapped in quotes when valid; "<unknown ssid>" when
        // the system refuses (no permission / WiFi off).
        return try {
            @Suppress("DEPRECATION")
            val info = wm?.connectionInfo ?: return null
            val raw = info.ssid?.removeSurrounding("\"")
            if (raw.isNullOrBlank() || raw == "<unknown ssid>" || info.networkId == -1) null
            else raw
        } catch (_: Throwable) { null }
    }

    private fun evaluate() {
        val prefs = LauncherPrefs(context)
        // Auto-switch is paused (user is editing profiles). State tracking
        // continues — currentSsid / carMode / geofence set stay current —
        // so the moment the user un-pauses, the next evaluate() call
        // applies the correct profile from up-to-date inputs.
        if (prefs.profileAutoSwitchPaused) return
        val state = ProfileMatcher.State(
            wifiSsid = currentSsid,
            androidAuto = carMode,
            activeGeofences = activeGeofences.toSet(),
            connectedBluetoothAddresses = connectedBluetooth.toSet(),
            charger = chargerState,
            nowEpochMs = System.currentTimeMillis(),
        )
        val match = ProfileMatcher.match(context, state) ?: return
        if (prefs.activeProfileSlug == match.slug) return
        onSwapRequested(match)
    }

    // ── Bluetooth ─────────────────────────────────────────────────────

    private fun registerBluetoothReceiver() {
        val cb = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                val action = intent?.action ?: return
                val device = intent.getParcelableExtra(
                    android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                    android.bluetooth.BluetoothDevice::class.java,
                )
                val address = device?.address ?: return
                when (action) {
                    android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED ->
                        connectedBluetooth.add(address)
                    android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                        connectedBluetooth.remove(address)
                    else -> return
                }
                evaluate()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(cb, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(cb, filter)
            }
            bluetoothReceiver = cb
        } catch (e: Throwable) {
            Log.w(TAG, "BT receiver register failed: ${e.message}")
        }
        // Seed the connected-set from currently-bonded-and-connected devices
        // so a profile that was active before launcher start re-applies on
        // start. BluetoothManager doesn't expose a clean "currently connected
        // by ACL" list without GATT/A2DP-specific profile binders, so we
        // rely on the receiver fire-on-connect for the warm path; leave
        // the set empty at start, matcher will pick up on first event.
    }

    // ── Charger ───────────────────────────────────────────────────────

    private fun registerChargerReceiver() {
        // ACTION_BATTERY_CHANGED is sticky — registering with a null
        // receiver returns the most recent broadcast immediately, giving
        // us the current charger state without waiting for a plug event.
        val sticky = try {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) { null }
        chargerState = stateFromBatteryIntent(sticky)

        val cb = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                chargerState = stateFromBatteryIntent(intent)
                evaluate()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            // BATTERY_CHANGED also covers slow plug/unplug edge cases the
            // POWER_CONNECTED/DISCONNECTED can miss.
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(cb, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(cb, filter)
            }
            chargerReceiver = cb
        } catch (e: Throwable) {
            Log.w(TAG, "charger receiver register failed: ${e.message}")
        }
    }

    private fun stateFromBatteryIntent(i: android.content.Intent?): ProfileMatcher.ChargerState? {
        val plugged = i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC,
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> ProfileMatcher.ChargerState.WIRED
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> ProfileMatcher.ChargerState.WIRELESS
            else -> null
        }
    }

    // ── Time-of-day alarm scheduler ───────────────────────────────────

    private fun registerTimeAlarmReceiver() {
        val cb = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                // Re-evaluate now that a window edge has crossed, then
                // schedule the next boundary.
                evaluate()
                scheduleNextTimeOfDayAlarm()
            }
        }
        val filter = android.content.IntentFilter(TIME_ALARM_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(cb, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(cb, filter)
            }
            timeAlarmReceiver = cb
        } catch (e: Throwable) {
            Log.w(TAG, "time alarm receiver register failed: ${e.message}")
        }
    }

    /** Schedule a single AlarmManager fire at the next boundary across
     *  ALL TimeOfDay-triggered profiles. On fire, we re-evaluate and
     *  re-schedule. Keeping it to one alarm at a time is friendly to
     *  Android 12+ exact-alarm budgeting. */
    private fun scheduleNextTimeOfDayAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE)
            as? android.app.AlarmManager ?: return
        val nextMs = computeNextBoundaryMs() ?: run {
            cancelTimeOfDayAlarm(); return
        }
        val pi = timeAlarmPendingIntent()
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, nextMs, pi)
        } catch (e: SecurityException) {
            // Android 12+ may revoke SCHEDULE_EXACT_ALARM via Settings
            // even when declared. Fall back to inexact.
            try { am.set(android.app.AlarmManager.RTC_WAKEUP, nextMs, pi) }
            catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun cancelTimeOfDayAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE)
            as? android.app.AlarmManager ?: return
        try { am.cancel(timeAlarmPendingIntent()) } catch (_: Throwable) {}
    }

    private fun timeAlarmPendingIntent(): android.app.PendingIntent {
        val intent = android.content.Intent(TIME_ALARM_ACTION).setPackage(context.packageName)
        return android.app.PendingIntent.getBroadcast(
            context, 0xC100, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Compute the smallest "next boundary in epoch ms" across every
     *  TimeOfDay trigger of every profile. Returns null when no time-
     *  triggered profile exists (no alarm to schedule). */
    private fun computeNextBoundaryMs(): Long? {
        val profiles = ProfileLibrary.all(context)
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        var soonest: Long? = null
        for (p in profiles) {
            val t = p.trigger as? com.iappyx.launcher.model.ProfileTrigger.TimeOfDay ?: continue
            val candidate = nextBoundaryFor(t, now) ?: continue
            if (soonest == null || candidate < soonest) soonest = candidate
        }
        return soonest
    }

    /** Next boundary (start or end) for a single TimeOfDay trigger,
     *  expressed as epoch ms. Looks ahead at most 8 days (covers any
     *  weekday gap). Honors activeFrom / activeUntil bounds. */
    private fun nextBoundaryFor(
        t: com.iappyx.launcher.model.ProfileTrigger.TimeOfDay,
        now: java.time.ZonedDateTime,
    ): Long? {
        val zone = now.zone
        for (offset in 0..8L) {
            val day = now.toLocalDate().plusDays(offset)
            // For each candidate day, check whether the START or END
            // boundary on that day is in the future and matches the DoW.
            val startOnDay = day.atTime(t.startMinuteOfDay / 60, t.startMinuteOfDay % 60)
                .atZone(zone).toInstant().toEpochMilli()
            val endOnDay = day.atTime(t.endMinuteOfDay / 60, t.endMinuteOfDay % 60)
                .atZone(zone).toInstant().toEpochMilli()
            // Day-of-week bit for the START day. End-on-day belongs to the
            // same start-day if cross-midnight; otherwise to its own date.
            val startBit = 1 shl (day.dayOfWeek.value - 1)
            val crossesMidnight = t.endMinuteOfDay <= t.startMinuteOfDay
            val candidates = mutableListOf<Long>()
            if (t.daysOfWeek and startBit != 0) {
                val startEpoch = startOnDay
                val endEpoch = if (crossesMidnight) {
                    day.plusDays(1).atTime(t.endMinuteOfDay / 60, t.endMinuteOfDay % 60)
                        .atZone(zone).toInstant().toEpochMilli()
                } else {
                    endOnDay
                }
                candidates += startEpoch
                candidates += endEpoch
            }
            for (c in candidates.sorted()) {
                if (c <= now.toInstant().toEpochMilli()) continue
                if (t.activeFrom > 0L && c < t.activeFrom) continue
                if (t.activeUntil > 0L && c > t.activeUntil) return null
                return c
            }
        }
        return null
    }

    /** Public hook so the profiles UI can ask the watcher to re-evaluate
     *  immediately after the user un-pauses auto-switching, without waiting
     *  for the next WiFi/Auto/geofence event. */
    fun reevaluate() = evaluate()

    /** Public so ProfilesActivity can request a re-schedule after the
     *  user adds / edits / deletes a TimeOfDay-triggered profile (the
     *  next boundary may have changed). */
    fun rescheduleTimeAlarms() {
        scheduleNextTimeOfDayAlarm()
    }

    companion object {
        private const val TAG = "iappyxProfileWatcher"
        private const val TIME_ALARM_ACTION = "com.iappyx.launcher.PROFILE_TIME_ALARM"
    }
}
