/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — profiles tab. Mirrors the on-device Profiles
 * settings: list, switch, save current as, edit name/trigger, delete.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.IntentAction
import com.iappyx.launcher.model.Page
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.model.ProfileActions
import com.iappyx.launcher.model.ProfileSnapshot
import com.iappyx.launcher.model.ProfileTrigger
import com.iappyx.launcher.profile.ProfileApplier
import com.iappyx.launcher.profile.ProfileLibrary
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject

class ProfilesApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val prefs = LauncherPrefs(context)
        val active = prefs.activeProfileSlug
        val arr = JSONArray()
        for (p in ProfileLibrary.all(context)) {
            arr.put(JSONObject().apply {
                put("slug", p.slug)
                put("name", p.name)
                put("trigger", triggerSummary(p.trigger))
                put("triggerKind", triggerKind(p.trigger))
                put("triggerPayload", p.trigger.toJson())
                put("active", p.slug == active)
                put("createdAt", p.createdAt)
                // onActivate side-effects so the editor can render the
                // current set of launch-on-activate packages.
                put("launchPackages", JSONArray().apply {
                    p.onActivate.launchPackages.forEach { put(it) }
                })
                put("customActionsCount", p.onActivate.customActions.size)
                // Full customActions array so the editor can render the
                // intent-action list UI. Each entry round-trips through
                // IntentAction.toJson / fromJson.
                put("customActions", JSONArray().apply {
                    p.onActivate.customActions.forEach { put(it.toJson()) }
                })
            })
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("profiles", arr)
            put("activeSlug", active ?: JSONObject.NULL)
            put("autoswitchPaused", prefs.profileAutoSwitchPaused)
        })
    }

    fun activate(ex: MicroHttpServer.Exchange, slug: String) {
        val p = ProfileLibrary.get(context, slug)
            ?: return JsonResponse.error(ex, 404, "no such profile")
        ProfileApplier.apply(context, p)
        notifyLayout()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Create a fresh profile with an empty layout + the launcher's
     *  default wallpaper / icon filter / page transition. Useful when
     *  the user wants to build a profile from scratch (e.g. a stripped-
     *  down "Focus" profile) without having to clear their live state
     *  first to snapshot it. Body: `{name, trigger?}`. */
    fun createBlank(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val name = obj.optString("name").trim().take(60).ifBlank {
            return JsonResponse.error(ex, 400, "name required")
        }
        val slug = ProfileLibrary.freshSlugFor(context, name)
        val prefs = LauncherPrefs(context)
        val blankLayout = HomeLayout.defaultLayout(context).also {
            // One empty page so the profile is renderable, no placements.
            if (it.pages.isEmpty()) it.pages.add(Page())
        }
        val snapshot = ProfileSnapshot(
            layout = blankLayout,
            wallpaperId = prefs.activeWallpaperId,
            iconFilter = prefs.iconFilter,
            pageTransition = prefs.pageTransitionStyle,
        )
        val trigger = obj.optJSONObject("trigger")?.let { ProfileTrigger.fromJson(it) }
            ?: ProfileTrigger.Manual
        val profile = Profile(
            slug = slug, name = name,
            snapshot = snapshot, trigger = trigger,
            createdAt = System.currentTimeMillis(),
        )
        if (!ProfileLibrary.save(context, profile)) {
            return JsonResponse.error(ex, 500, "save failed")
        }
        JsonResponse.ok(ex, JSONObject().apply { put("ok", true); put("slug", slug) })
    }

    /** Clone an existing profile under a new slug. Carries the source
     *  profile's snapshot + onActivate verbatim; trigger defaults to
     *  Manual (so the clone doesn't fight the source for the same
     *  auto-switch condition until the user re-configures it). Body
     *  may override `name` and `trigger`; otherwise we use "Source
     *  name (copy)" + Manual. */
    fun duplicate(ex: MicroHttpServer.Exchange, slug: String) {
        val src = ProfileLibrary.get(context, slug)
            ?: return JsonResponse.error(ex, 404, "no such profile")
        val obj = JsonResponse.readJsonObject(ex) ?: JSONObject()
        val newName = obj.optString("name").trim().take(60)
            .ifBlank { "${src.name} (copy)".take(60) }
        val newSlug = ProfileLibrary.freshSlugFor(context, newName)
        val newTrigger = obj.optJSONObject("trigger")?.let { ProfileTrigger.fromJson(it) }
            ?: ProfileTrigger.Manual
        val cloned = src.copy(
            slug = newSlug,
            name = newName,
            trigger = newTrigger,
            createdAt = System.currentTimeMillis(),
        )
        if (!ProfileLibrary.save(context, cloned)) {
            return JsonResponse.error(ex, 500, "save failed")
        }
        JsonResponse.ok(ex, JSONObject().apply { put("ok", true); put("slug", newSlug) })
    }

    fun saveCurrent(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val name = obj.optString("name").trim().take(60).ifBlank {
            return JsonResponse.error(ex, 400, "name required")
        }
        val slug = ProfileLibrary.freshSlugFor(context, name)
        val snapshot = ProfileApplier.captureCurrent(context)
        val trigger = obj.optJSONObject("trigger")?.let { ProfileTrigger.fromJson(it) }
            ?: ProfileTrigger.Manual
        val profile = Profile(
            slug = slug,
            name = name,
            snapshot = snapshot,
            trigger = trigger,
            createdAt = System.currentTimeMillis(),
        )
        if (!ProfileLibrary.save(context, profile)) {
            return JsonResponse.error(ex, 500, "save failed")
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("slug", slug)
        })
    }

    fun update(ex: MicroHttpServer.Exchange, slug: String) {
        val p = ProfileLibrary.get(context, slug)
            ?: return JsonResponse.error(ex, 404, "no such profile")
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val newName = obj.optString("name", p.name).trim().take(60).ifBlank { p.name }
        val newTrigger = obj.optJSONObject("trigger")?.let { ProfileTrigger.fromJson(it) } ?: p.trigger
        // launchPackages / customActions: only overwrite if the field
        // is present in the body. Missing field → keep existing. Empty
        // array → clear. customActions round-trip through IntentAction
        // fromJson; malformed entries are dropped silently so a stray
        // entry from a future schema doesn't refuse the whole save.
        val newPkgs: List<String> = if (obj.has("launchPackages")) {
            val arr = obj.optJSONArray("launchPackages") ?: JSONArray()
            val pkgs = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).trim()
                if (pkg.isNotEmpty() && pkg !in pkgs) pkgs.add(pkg)
            }
            pkgs
        } else p.onActivate.launchPackages
        val newCustom: List<IntentAction> = if (obj.has("customActions")) {
            val arr = obj.optJSONArray("customActions") ?: JSONArray()
            val out = mutableListOf<IntentAction>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try { out.add(IntentAction.fromJson(o)) } catch (_: Throwable) {}
            }
            out
        } else p.onActivate.customActions
        val newActions = ProfileActions(
            launchPackages = newPkgs,
            customActions = newCustom,
        )
        val updated = p.copy(name = newName, trigger = newTrigger, onActivate = newActions)
        if (!ProfileLibrary.save(context, updated)) {
            return JsonResponse.error(ex, 500, "save failed")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun delete(ex: MicroHttpServer.Exchange, slug: String) {
        val active = LauncherPrefs(context).activeProfileSlug
        if (slug == active) {
            return JsonResponse.error(ex, 400, "can't delete the active profile")
        }
        val ok = ProfileLibrary.delete(context, slug)
        if (!ok) return JsonResponse.error(ex, 404, "no such profile")
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun setAutoswitchPaused(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        LauncherPrefs(context).profileAutoSwitchPaused = obj.optBoolean("paused", false)
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    private fun notifyLayout() {
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
    }

    /** Human-readable summary for the row's right-side caption. */
    private fun triggerSummary(t: ProfileTrigger): String = when (t) {
        is ProfileTrigger.Geofence -> "Geofence: ${t.label} (±${t.radiusM.toInt()}m)"
        ProfileTrigger.AndroidAuto -> "Android Auto"
        is ProfileTrigger.WifiSsid -> "WiFi: ${t.ssid}"
        ProfileTrigger.WifiDisconnected -> "No WiFi"
        is ProfileTrigger.BluetoothDeviceConnected -> "Bluetooth: ${t.label}"
        is ProfileTrigger.TimeOfDay -> "Time of day"
        is ProfileTrigger.ChargerConnected -> "Charging (${t.kind.name.lowercase()})"
        ProfileTrigger.Manual -> "Manual only"
    }

    /** Discriminator the UI uses to pick which editor pane to show. */
    private fun triggerKind(t: ProfileTrigger): String = when (t) {
        is ProfileTrigger.Geofence -> "geofence"
        ProfileTrigger.AndroidAuto -> "android_auto"
        is ProfileTrigger.WifiSsid -> "wifi_ssid"
        ProfileTrigger.WifiDisconnected -> "wifi_disconnected"
        is ProfileTrigger.BluetoothDeviceConnected -> "bt_device"
        is ProfileTrigger.TimeOfDay -> "time_of_day"
        is ProfileTrigger.ChargerConnected -> "charger"
        ProfileTrigger.Manual -> "manual"
    }
}
