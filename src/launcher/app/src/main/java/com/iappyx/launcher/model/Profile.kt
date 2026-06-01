/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.model

import org.json.JSONObject

/**
 * A named, atomic snapshot of the launcher's user-visible state — pages,
 * dock, wallpaper, icon filter, page transition. Profiles let the user
 * keep distinct configurations for distinct contexts ("Home", "Work",
 * "Travel") and have the launcher swap between them automatically based
 * on the [trigger] (location, WiFi network, etc.).
 *
 * Storage layout: `filesDir/profiles/{slug}/profile.json` — the entire
 * profile lives in one file. Same shape as the existing backup file's
 * inner JSON so existing import/export plumbing can treat profiles as
 * mini-backups.
 */
data class Profile(
    val slug: String,
    val name: String,
    val snapshot: ProfileSnapshot,
    val trigger: ProfileTrigger,
    val onActivate: ProfileActions = ProfileActions(),
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slug", slug)
        put("name", name)
        put("snapshot", snapshot.toJson())
        put("trigger", trigger.toJson())
        put("onActivate", onActivate.toJson())
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            slug = o.getString("slug"),
            name = o.optString("name").ifBlank { o.getString("slug") },
            snapshot = ProfileSnapshot.fromJson(o.getJSONObject("snapshot")),
            trigger = ProfileTrigger.fromJson(o.optJSONObject("trigger")),
            onActivate = ProfileActions.fromJson(o.optJSONObject("onActivate")),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/**
 * Side effects to run when a profile activates.
 *
 * - [launchPackages]: package names to open automatically (e.g. open
 *   Maps + Spotify when "Drive" activates). Apps launch in the order
 *   listed; first becomes foreground via the `FLAG_ACTIVITY_NEW_TASK`
 *   chain. Skipped silently if the package isn't installed.
 *
 * - [customActions]: arbitrary configured intents fired BEFORE
 *   launchPackages, so setup steps (VPN connect, broadcast presence,
 *   set DND, automation hooks) land before user-visible apps come up.
 *   Same fire-and-forget semantics as launchPackages.
 *
 * Both empty = no side effects, just swap the layout.
 */
data class ProfileActions(
    val launchPackages: List<String> = emptyList(),
    val customActions: List<IntentAction> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (launchPackages.isNotEmpty()) {
            put("launchPackages", org.json.JSONArray().apply {
                launchPackages.forEach { put(it) }
            })
        }
        if (customActions.isNotEmpty()) {
            put("customActions", org.json.JSONArray().apply {
                customActions.forEach { put(it.toJson()) }
            })
        }
    }
    companion object {
        fun fromJson(o: JSONObject?): ProfileActions {
            if (o == null) return ProfileActions()
            val pkgs = o.optJSONArray("launchPackages")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val custom = o.optJSONArray("customActions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { IntentAction.fromJson(it) }
                }
            } ?: emptyList()
            return ProfileActions(launchPackages = pkgs, customActions = custom)
        }
    }
}

/**
 * A single configured intent fired on profile activation. Carries
 * everything `Intent.set*` and `putExtra` need; the dispatch verb
 * picks broadcast / activity / service.
 *
 * Most apps that expose this kind of integration (WireGuard, Tasker,
 * KDE Connect, Home Assistant, …) gate it on an in-app "Allow remote
 * control" toggle — no manifest permission needed from us.
 */
data class IntentAction(
    val label: String,
    val verb: Verb,
    val packageName: String?,
    val className: String?,
    val action: String?,
    val dataUri: String?,
    val mimeType: String?,
    val categories: List<String>,
    val flags: Int,
    val extras: List<IntentExtra>,
    /** When true, briefly launch the target package's main activity
     *  before firing the configured intent. Mitigates Android's cached-
     *  app freezer issue: a frozen target may receive our broadcast
     *  but have its native backend suspended, so the receiver wakes,
     *  no-ops, and the broadcast looks dropped. The activity-launch
     *  pulls the target into the active standby bucket and warms its
     *  process before our real intent lands. Off by default — most
     *  targets don't need this, and it's visibly intrusive. */
    val warmupTargetFirst: Boolean = false,
) {
    enum class Verb { BROADCAST, ACTIVITY, SERVICE, FOREGROUND_SERVICE }

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("verb", verb.name)
        packageName?.let { put("package", it) }
        className?.let { put("class", it) }
        action?.let { put("action", it) }
        dataUri?.let { put("dataUri", it) }
        mimeType?.let { put("mime", it) }
        if (categories.isNotEmpty()) {
            put("categories", org.json.JSONArray().apply { categories.forEach { put(it) } })
        }
        if (flags != 0) put("flags", flags)
        if (extras.isNotEmpty()) {
            put("extras", org.json.JSONArray().apply {
                extras.forEach { put(it.toJson()) }
            })
        }
        if (warmupTargetFirst) put("warmup", true)
    }

    companion object {
        fun fromJson(o: JSONObject): IntentAction = IntentAction(
            label = o.optString("label").ifBlank { "Action" },
            verb = runCatching { Verb.valueOf(o.optString("verb")) }.getOrDefault(Verb.BROADCAST),
            packageName = o.optString("package").ifBlank { null },
            className = o.optString("class").ifBlank { null },
            action = o.optString("action").ifBlank { null },
            dataUri = o.optString("dataUri").ifBlank { null },
            mimeType = o.optString("mime").ifBlank { null },
            categories = o.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            flags = o.optInt("flags", 0),
            extras = o.optJSONArray("extras")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { IntentExtra.fromJson(it) }
                }
            } ?: emptyList(),
            warmupTargetFirst = o.optBoolean("warmup", false),
        )
    }
}

/**
 * Typed key-value pair for an [IntentAction]'s `Bundle` extras.
 * v1 supports Android primitives only — String / Int / Long / Boolean
 * / Float. Arrays and nested bundles are out of scope.
 */
data class IntentExtra(val key: String, val type: ExtraType, val value: String) {
    enum class ExtraType { STRING, INT, LONG, BOOL, FLOAT }

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", key); put("t", type.name); put("v", value)
    }

    companion object {
        fun fromJson(o: JSONObject): IntentExtra? {
            val key = o.optString("k").ifBlank { return null }
            val type = runCatching { ExtraType.valueOf(o.optString("t")) }
                .getOrDefault(ExtraType.STRING)
            return IntentExtra(key, type, o.optString("v"))
        }
    }
}

/**
 * Atomic snapshot of the four things a profile owns. Restored as a unit:
 * activating a profile applies all four in one swap.
 *
 * Wallpaper / icon filter / transition are stored as ID strings — the
 * referenced library entries are NOT bundled into the profile. If the
 * user moves the profile to a device that doesn't have the referenced
 * wallpaper, the launcher falls back to the default. (Profiles travel
 * via backup, which already includes the wallpaper / filter / transition
 * libraries — see [com.iappyx.launcher.backup.BackupExporter].)
 */
data class ProfileSnapshot(
    val layout: HomeLayout,
    val wallpaperId: String,
    val iconFilter: String,
    val pageTransition: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("layout", layout.toJson())
        put("wallpaperId", wallpaperId)
        put("iconFilter", iconFilter)
        put("pageTransition", pageTransition)
    }

    companion object {
        fun fromJson(o: JSONObject): ProfileSnapshot = ProfileSnapshot(
            layout = HomeLayout.fromJson(o.getJSONObject("layout")),
            wallpaperId = o.optString("wallpaperId", "rotating_radial_gradient"),
            iconFilter = o.optString("iconFilter", "none"),
            pageTransition = o.optString("pageTransition", "horizontal"),
        )
    }
}

/**
 * Condition under which the matcher activates the profile. Conflicts
 * between matching triggers are resolved by fixed priority (highest
 * first):
 *   1. [Geofence] — at a named location
 *   2. [AndroidAuto] — phone is in car / Android Auto mode
 *   3. [WifiSsid] — connected to a specific WiFi network
 *   4. [WifiDisconnected] — no WiFi connected ("on the road")
 *   5. [Manual] — never auto-activates; switched via Settings only
 */
sealed class ProfileTrigger {
    abstract val priority: Int
    abstract fun toJson(): JSONObject

    /** At a named location. The geofence's ENTER event activates the
     *  profile; EXIT lets the next-priority match take over (or falls
     *  back to default). [latitude]/[longitude] in decimal degrees,
     *  [radiusM] in metres. [label] is the user-facing name shown in
     *  Settings ("Home", "Work", "Mom's place"). */
    data class Geofence(
        val label: String,
        val latitude: Double,
        val longitude: Double,
        val radiusM: Float,
    ) : ProfileTrigger() {
        override val priority: Int get() = 100
        override fun toJson() = JSONObject().apply {
            put("kind", "geofence")
            put("label", label)
            put("latitude", latitude)
            put("longitude", longitude)
            put("radiusM", radiusM.toDouble())
        }
    }

    /** Phone is in Android Auto / car mode. Detected via
     *  [android.app.UiModeManager.getCurrentModeType] equal to
     *  [android.content.res.Configuration.UI_MODE_TYPE_CAR], with the
     *  watcher subscribed to UI_MODE_CHANGED broadcasts. Useful for
     *  "Drive" profiles that swap to a minimal layout + auto-launch
     *  Maps / music apps via [ProfileActions.launchPackages]. */
    object AndroidAuto : ProfileTrigger() {
        override val priority: Int get() = 90
        override fun toJson() = JSONObject().apply { put("kind", "android_auto") }
    }

    /** Connected to a specific WiFi network. Matches by SSID exactly. */
    data class WifiSsid(val ssid: String) : ProfileTrigger() {
        override val priority: Int get() = 80
        override fun toJson() = JSONObject().apply {
            put("kind", "wifi_ssid")
            put("ssid", ssid)
        }
    }

    /** No WiFi connected — "on the road". Matches when the user is on
     *  cellular only or completely offline. */
    object WifiDisconnected : ProfileTrigger() {
        override val priority: Int get() = 40
        override fun toJson() = JSONObject().apply { put("kind", "wifi_disconnected") }
    }

    /** Specific Bluetooth device connected — by MAC. Higher priority than
     *  [AndroidAuto] because a paired car-stereo / headphone identity is
     *  a more specific signal than generic "in car mode". */
    data class BluetoothDeviceConnected(
        val deviceAddress: String,
        val label: String,
    ) : ProfileTrigger() {
        override val priority: Int get() = 95
        override fun toJson() = JSONObject().apply {
            put("kind", "bt_device")
            put("address", deviceAddress)
            put("label", label)
        }
    }

    /** Time-of-day window. [startMinuteOfDay] / [endMinuteOfDay] are
     *  minutes-since-midnight (0..1439). [daysOfWeek] is a bitmask:
     *  bit 0 = Mon, bit 1 = Tue, …, bit 6 = Sun (matches `DayOfWeek.value -
     *  1`). 0x7F = every day. Cross-midnight semantics: if `end < start`
     *  the window spans midnight (e.g. 22:00–06:00). The day-of-week bit
     *  refers to the START day. [activeFrom] / [activeUntil] (epoch ms,
     *  0 = no bound) gate the window to a calendar range — useful for
     *  "vacation" profiles. */
    data class TimeOfDay(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val daysOfWeek: Int,
        val activeFrom: Long = 0L,
        val activeUntil: Long = 0L,
    ) : ProfileTrigger() {
        override val priority: Int get() = 50
        override fun toJson() = JSONObject().apply {
            put("kind", "time_of_day")
            put("startMinute", startMinuteOfDay)
            put("endMinute", endMinuteOfDay)
            put("daysOfWeek", daysOfWeek)
            if (activeFrom > 0L) put("activeFrom", activeFrom)
            if (activeUntil > 0L) put("activeUntil", activeUntil)
        }
    }

    /** Charger plugged in. [kind] narrows to wired or wireless; ANY
     *  matches both. Lower priority than time-of-day so an "evening
     *  wind-down" profile beats a "charging desk" profile. */
    data class ChargerConnected(
        val kind: ChargerKind = ChargerKind.ANY,
    ) : ProfileTrigger() {
        enum class ChargerKind { ANY, WIRED, WIRELESS }
        override val priority: Int get() = 30
        override fun toJson() = JSONObject().apply {
            put("kind", "charger")
            put("chargerKind", kind.name)
        }
    }

    /** Manual-only — the matcher never picks this profile. Reachable
     *  from Settings → Profiles → Switch to. */
    object Manual : ProfileTrigger() {
        override val priority: Int get() = 0
        override fun toJson() = JSONObject().apply { put("kind", "manual") }
    }

    companion object {
        fun fromJson(o: JSONObject?): ProfileTrigger {
            if (o == null) return Manual
            return when (val kind = o.optString("kind")) {
                "geofence" -> Geofence(
                    label = o.optString("label"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    radiusM = o.optDouble("radiusM", 150.0).toFloat(),
                )
                "android_auto" -> AndroidAuto
                "wifi_ssid" -> WifiSsid(ssid = o.getString("ssid"))
                "wifi_disconnected" -> WifiDisconnected
                "bt_device" -> BluetoothDeviceConnected(
                    deviceAddress = o.optString("address"),
                    label = o.optString("label").ifBlank { o.optString("address") },
                )
                "time_of_day" -> TimeOfDay(
                    startMinuteOfDay = o.optInt("startMinute", 0).coerceIn(0, 1439),
                    endMinuteOfDay = o.optInt("endMinute", 0).coerceIn(0, 1439),
                    daysOfWeek = o.optInt("daysOfWeek", 0x7F),
                    activeFrom = o.optLong("activeFrom", 0L),
                    activeUntil = o.optLong("activeUntil", 0L),
                )
                "charger" -> ChargerConnected(
                    kind = runCatching {
                        ChargerConnected.ChargerKind.valueOf(o.optString("chargerKind", "ANY"))
                    }.getOrDefault(ChargerConnected.ChargerKind.ANY),
                )
                "manual", "" -> Manual
                else -> {
                    // Forward-compat: a profile created on a future
                    // launcher build that introduced a new trigger kind
                    // shouldn't crash the entire load on a downgrade or
                    // sideloaded backup. Fall back to Manual so the
                    // user can re-set the trigger via the UI; log the
                    // unknown kind for diagnostics.
                    android.util.Log.w(
                        "iappyxLauncher",
                        "Unknown trigger kind '$kind' — falling back to Manual",
                    )
                    Manual
                }
            }
        }
    }
}
