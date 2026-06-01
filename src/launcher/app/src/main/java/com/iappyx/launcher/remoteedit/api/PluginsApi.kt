/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — plugins management.
 *
 * Phase 1: list installed, toggle enabled, uninstall. Install from the
 * showcase already works via ShowcaseApi (kind="plugin"); this surface
 * adds the manage half so the laptop can do plugin onboarding end-to-end
 * without the user picking up the phone.
 *
 * Phase 2 (plugin settings.html in the browser) lives in
 * PluginSettingsServeApi + the per-plugin bridge proxy.
 *
 * All access to the plugin package goes through PluginsModule's public
 * facade — keeps the "delete the plugins folder + fenced hooks" removal
 * procedure clean (this file is one such fenced hook).
 */
// PLUGINS: BEGIN
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.plugins.PluginsModule
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject

class PluginsApi(private val context: Context) {

    /** GET /api/plugins/installed — list installed plugins.
     *  Returns { ok:true, plugins:[…] } where each entry is the shape
     *  documented in PluginsModule.listInstalledAsJson, decorated with
     *  `searchExposed` for plugins that declare `universalSearch` in
     *  their manifest exposes (mirroring the device's per-plugin
     *  "Expose to universal search" toggle). */
    fun listInstalled(ex: MicroHttpServer.Exchange) {
        val arr = PluginsModule.listInstalledAsJson(context)
        val prefs = LauncherPrefs(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val exposes = o.optJSONArray("exposes")
            var hasUniversalSearch = false
            if (exposes != null) {
                for (j in 0 until exposes.length()) {
                    if (exposes.optString(j) == "universalSearch") {
                        hasUniversalSearch = true; break
                    }
                }
            }
            if (hasUniversalSearch) {
                o.put("searchExposed", prefs.isSearchExposed(o.optString("id")))
            }
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("plugins", arr)
        })
    }

    /** POST /api/plugins/{id}/search_exposed — body { exposed: bool }.
     *  Mirrors the on-device buildSearchExposureCard toggle. No-op for
     *  plugins that don't declare `universalSearch` (the value is just
     *  stored — the search aggregator already filters by exposed-set). */
    fun setSearchExposed(ex: MicroHttpServer.Exchange, pluginId: String) {
        val body = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "expected { exposed: bool }")
        if (!body.has("exposed")) {
            return JsonResponse.error(ex, 400, "missing 'exposed' field")
        }
        if (!PluginsModule.exists(context, pluginId)) {
            return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        }
        val exposed = body.optBoolean("exposed", true)
        LauncherPrefs(context).setSearchExposed(pluginId, exposed)
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("exposed", exposed)
        })
    }

    /** POST /api/plugins/{id}/enable — body { enabled: bool }.
     *  Toggles the enabled flag. Same behaviour as the Settings switch
     *  (tears down the running plugin WebView on disable). */
    fun setEnabled(ex: MicroHttpServer.Exchange, pluginId: String) {
        val body = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "expected { enabled: bool }")
        if (!body.has("enabled")) {
            return JsonResponse.error(ex, 400, "missing 'enabled' field")
        }
        val enabled = body.optBoolean("enabled", true)
        val now = PluginsModule.setEnabledExternal(context, pluginId, enabled)
            ?: return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("enabled", now)
        })
    }

    /** GET /api/plugins/{id}/network — fetch the plugin's current
     *  network-restriction mode + trusted SSID list + the device's
     *  current network state (so the laptop can show "currently
     *  allowed: yes/no" and an "add current SSID" affordance). */
    fun getNetwork(ex: MicroHttpServer.Exchange, pluginId: String) {
        val o = PluginsModule.networkRestrictionAsJson(context, pluginId)
            ?: return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("network", o)
        })
    }

    /** POST /api/plugins/{id}/network — body { mode, trustedSsids:[…] }.
     *  Replaces the stored config. mode must be one of:
     *  always | trusted_wifi | vpn | trusted_wifi_or_vpn. */
    fun setNetwork(ex: MicroHttpServer.Exchange, pluginId: String) {
        val body = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "expected JSON body")
        val mode = body.optString("mode").trim()
        if (mode.isEmpty()) return JsonResponse.error(ex, 400, "missing 'mode'")
        if (!PluginsModule.setNetworkRestrictionMode(context, pluginId, mode)) {
            return JsonResponse.error(ex, 400, "invalid mode or unknown plugin")
        }
        val arr = body.optJSONArray("trustedSsids")
        val ssids = mutableListOf<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) ssids.add(s)
        }
        PluginsModule.setTrustedSsids(context, pluginId, ssids)
        // Echo the new state back so the client can refresh without a
        // second round-trip.
        val now = PluginsModule.networkRestrictionAsJson(context, pluginId)
            ?: return JsonResponse.error(ex, 500, "post-write read failed")
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("network", now)
        })
    }

    /** DELETE /api/plugins/{id} — uninstall a user-installed plugin.
     *  Returns 400 for bundled plugins (those can only be disabled). */
    fun uninstall(ex: MicroHttpServer.Exchange, pluginId: String) {
        val source = PluginsModule.sourceOf(context, pluginId)
            ?: return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        if (source == "BUNDLED") {
            return JsonResponse.error(ex, 400,
                "plugin '$pluginId' is bundled — disable instead of uninstall")
        }
        val ok = PluginsModule.uninstall(context, pluginId)
        if (!ok) return JsonResponse.error(ex, 500, "uninstall failed")
        JsonResponse.ok(ex, JSONObject().apply { put("ok", true) })
    }
}
// PLUGINS: END
