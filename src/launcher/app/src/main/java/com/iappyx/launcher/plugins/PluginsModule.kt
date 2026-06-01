/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — public facade. Only file in com.iappyx.launcher.plugins.*
 * that the rest of the launcher imports. Keeping the surface narrow
 * makes "delete the plugin folder + the fenced hooks" a clean removal.
 *
 * See com.iappyx.launcher.plugins/README.md for the removal procedure.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import android.view.View
import android.webkit.WebView
import com.iappyx.launcher.WidgetHost

object PluginsModule {

    /** Attach the `iappyxPlugins` JS bridge to a caller WebView (a
     *  widget or wallpaper). Idempotent — re-attaching the bridge
     *  during a re-bind is safe.
     *
     *  Even when no plugins are installed, attaching is cheap (one
     *  @JavascriptInterface call), and lets callers feature-detect
     *  via `typeof iappyxPlugins !== 'undefined'` without us needing
     *  to thread an enable/disable signal through WidgetHost. */
    @JvmStatic
    fun attachCallerBridge(webView: WebView, host: WidgetHost) {
        PluginsBridge(host).attachTo(webView)
    }

    /** Release all plugin WebViews. Call from
     *  `LauncherActivity.onDestroy` if memory pressure shows up.
     *  Optional — process death frees WebViews automatically. */
    @JvmStatic
    fun shutdown(context: Context) {
        PluginHost.shutdown(context)
    }

    // attachSettingsSection / refreshSettingsSection: removed in Plan A
    // Phase 5 — the inline Plugins section was replaced by the dedicated
    // PluginsActivity. Settings now links to it via a row, not an
    // injected View.

    /** Install a plugin from the raw bytes of a `.iappyxplugin` zip
     *  (e.g. one ShowcaseFetcher.fetchEntry just reconstituted). The
     *  showcase install path lands here via ArtefactBundle.install
     *  for PLUGIN kind. Returns null on success, error message on
     *  failure. */
    @JvmStatic
    fun installFromBytes(context: Context, zipBytes: ByteArray): String? {
        return when (val r = PluginInstaller.install(context, zipBytes)) {
            is PluginInstaller.Result.Ok -> null
            is PluginInstaller.Result.Error -> r.message
        }
    }

    /** Uninstall a user-installed plugin. No-op on bundled plugins
     *  (those can only be disabled). Wipes per-plugin storage +
     *  secure-store files. */
    @JvmStatic
    fun uninstall(context: Context, pluginId: String): Boolean {
        return PluginInstaller.uninstall(context, pluginId)
    }

    /** Snapshot of every installed plugin as a JSON array. One entry per
     *  plugin, shape:
     *      {
     *        id, name, description, version, author,
     *        source: "BUNDLED"|"USER",
     *        enabled: bool,
     *        capabilities: [string],
     *        hasSettingsUi: bool,
     *        exposes: [string]   // method names only
     *      }
     *  Used by the remote-edit web UI's Plugins tab. Cheap — same shape
     *  PluginRegistry.all() already builds. */
    @JvmStatic
    fun listInstalledAsJson(context: Context): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (entry in PluginRegistry.all(context)) {
            val m = entry.manifest
            val mode = PluginPrefs.networkRestriction(context, m.id, m.defaultNetworkRestriction)
            val ssidCount = PluginPrefs.trustedSsids(context, m.id).size
            val allowedNow = PluginNetworkTrust.evaluate(context, m).allowed
            val o = org.json.JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("description", m.description)
                put("version", m.version)
                put("author", m.author)
                put("source", entry.source.name)
                put("enabled", entry.enabled)
                put("capabilities", org.json.JSONArray().apply {
                    for (c in m.capabilities) put(c)
                })
                put("hasSettingsUi", m.settingsUi != null)
                put("exposes", org.json.JSONArray().apply {
                    for (e in m.exposes) put(e)
                })
                // Network summary — for card rendering.
                put("networkMode", mode)
                put("trustedSsidCount", ssidCount)
                put("networkAllowedNow", allowedNow)
            }
            arr.put(o)
        }
        return arr
    }

    /** Toggle enabled state from outside the package (remote-edit API).
     *  Returns the new enabled state after the change, or null if the
     *  plugin doesn't exist. Mirrors what the Settings switch does. */
    @JvmStatic
    fun setEnabledExternal(context: Context, pluginId: String, enabled: Boolean): Boolean? {
        val entry = PluginRegistry.get(context, pluginId) ?: return null
        PluginRegistry.setEnabled(context, pluginId, enabled)
        // Mirror the Settings switch behaviour: tear down the WebView on
        // disable so a disabled plugin doesn't quietly hog memory. Next
        // invoke spawns a fresh instance.
        if (!enabled) {
            try { PluginHost.shutdownPlugin(context, pluginId) } catch (_: Throwable) {}
        }
        // Return final state (we just wrote it; safest to read back).
        return PluginRegistry.isEnabled(context, pluginId)
    }

    /** True if a plugin with this id is installed (bundled OR user). */
    @JvmStatic
    fun exists(context: Context, pluginId: String): Boolean =
        PluginRegistry.get(context, pluginId) != null

    /** Source of the installed plugin ("BUNDLED" or "USER"), or null when
     *  the plugin doesn't exist. Used to gate uninstall (bundled plugins
     *  can only be disabled). */
    @JvmStatic
    fun sourceOf(context: Context, pluginId: String): String? =
        PluginRegistry.get(context, pluginId)?.source?.name

    /** Read a plugin's settings.html (raw, no shim injection) plus the
     *  current per-plugin secureStore + storage data so the remote-edit
     *  server can preload it into the browser iframe.
     *
     *  Returns null when the plugin doesn't exist or has no settings UI.
     *  Returns a triple (settings-html bytes, secureStore JSON, storage
     *  JSON) on success. */
    @JvmStatic
    fun readSettingsArtefactsAsJson(context: Context, pluginId: String): org.json.JSONObject? {
        val entry = PluginRegistry.get(context, pluginId) ?: return null
        val settingsFile = entry.manifest.settingsUi ?: return null
        val bytes = PluginRegistry.readPluginFile(context, pluginId, settingsFile) ?: return null
        val html = String(bytes, Charsets.UTF_8)
        // Preload secureStore (encrypted at rest; the laptop is paired
        // and authenticated, so we can decrypt for the in-browser shim).
        val secObj = org.json.JSONObject()
        try {
            val secPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "plugin_${pluginId}_iappyx_secure",
                androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            for ((k, v) in secPrefs.all) {
                if (v is String) secObj.put(k, v) else secObj.put(k, v?.toString() ?: "")
            }
        } catch (_: Throwable) { /* secure store unavailable — leave empty */ }
        // Preload storage (plain SharedPreferences).
        val stoObj = org.json.JSONObject()
        val stoPrefs = context.getSharedPreferences(
            "plugin_${pluginId}_iappyx_store", Context.MODE_PRIVATE)
        for ((k, v) in stoPrefs.all) {
            stoObj.put(k, v?.toString() ?: "")
        }
        // Manifest summary for window.__iappyxPluginCaps / id.
        val m = entry.manifest
        val capsArr = org.json.JSONArray()
        for (c in m.capabilities) capsArr.put(c)
        return org.json.JSONObject().apply {
            put("html", html)
            put("pluginId", m.id)
            put("pluginName", m.name)
            put("capabilities", capsArr)
            put("secureStore", secObj)
            put("storage", stoObj)
        }
    }

    /** Bridge facade for the remote-edit settings flow. Writes happen on
     *  the launcher process via the same SharedPreferences files the
     *  plugin's own runtime uses, so changes are immediately visible
     *  when the plugin next runs. */
    @JvmStatic
    fun secureStoreSet(context: Context, pluginId: String, key: String, value: String?) {
        try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "plugin_${pluginId}_iappyx_secure",
                androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            if (value == null) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, value).apply()
        } catch (_: Throwable) { /* Keystore unavailable — silently fail like the bridge */ }
    }

    @JvmStatic
    fun storageSet(context: Context, pluginId: String, key: String, value: String?) {
        val prefs = context.getSharedPreferences(
            "plugin_${pluginId}_iappyx_store", Context.MODE_PRIVATE)
        if (value == null) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, value).apply()
    }

    // ── Network restriction facades ─────────────────────────────

    /** Current restriction mode + trusted SSID list for a plugin, as
     *  JSON. Includes the current network state (SSID + VPN) so the UI
     *  can render "currently allowed: yes/no" and a "use current SSID"
     *  button without re-fetching. */
    @JvmStatic
    fun networkRestrictionAsJson(context: Context, pluginId: String): org.json.JSONObject? {
        val entry = PluginRegistry.get(context, pluginId) ?: return null
        val mode = PluginPrefs.networkRestriction(
            context, pluginId, entry.manifest.defaultNetworkRestriction)
        val ssids = PluginPrefs.trustedSsids(context, pluginId)
        val ssidsArr = org.json.JSONArray().apply { ssids.forEach { put(it) } }
        val verdict = PluginNetworkTrust.evaluate(context, entry.manifest)
        return org.json.JSONObject().apply {
            put("mode", mode)
            put("trustedSsids", ssidsArr)
            put("currentSsid", PluginNetworkTrust.currentSsid(context) ?: org.json.JSONObject.NULL)
            put("onVpn", PluginNetworkTrust.onVpn(context))
            put("allowedNow", verdict.allowed)
            put("manifestDefault", entry.manifest.defaultNetworkRestriction ?: "always")
        }
    }

    @JvmStatic
    fun setNetworkRestrictionMode(context: Context, pluginId: String, mode: String): Boolean {
        if (PluginRegistry.get(context, pluginId) == null) return false
        val normalized = when (mode) {
            "always", "trusted_wifi", "vpn", "trusted_wifi_or_vpn" -> mode
            else -> return false
        }
        PluginPrefs.setNetworkRestriction(context, pluginId, normalized)
        return true
    }

    @JvmStatic
    fun setTrustedSsids(context: Context, pluginId: String, ssids: List<String>): Boolean {
        if (PluginRegistry.get(context, pluginId) == null) return false
        PluginPrefs.setTrustedSsids(context, pluginId, ssids)
        return true
    }

    /** Concatenated `aiPrompt` strings from every enabled plugin. Used
     *  by the AI generators (widget, wallpaper, transition, icon-filter)
     *  to teach the model about the plugin surface for `iappyx.plugin(...)`.
     *  Returns empty string when no plugins are enabled OR no enabled
     *  plugin declares an `aiPrompt` — caller can `.isEmpty()` check
     *  before appending. Cheap (no I/O on the hot path; reads the
     *  already-loaded manifest objects). */
    @JvmStatic
    fun aggregateAiPrompts(context: Context): String {
        val enabled = PluginRegistry.all(context)
            .filter { it.enabled }
            .mapNotNull { entry ->
                val p = entry.manifest.aiPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                entry to p
            }
        if (enabled.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# Plugins available on this device\n\n")
        sb.append("Plugins extend the launcher with installable JS modules. Each one ")
        sb.append("exposes a small API callable from widgets and wallpapers as ")
        sb.append("`iappyx.plugin('<id>').method(args)` (returns a Promise). ")
        sb.append("Plugins listed here are CURRENTLY ENABLED on the user's device; ")
        sb.append("if a request maps to one, prefer calling its bridge over inventing ")
        sb.append("equivalent logic. Always check `result.ok` before reading other fields ")
        sb.append("(plugins return `{ok:false, error:\"...\"}` on misconfiguration / network ")
        sb.append("failure / missing capability — never throw).\n\n")
        for ((entry, prompt) in enabled) {
            sb.append("## ").append(entry.manifest.name)
            sb.append(" (`").append(entry.manifest.id).append("`)\n\n")
            sb.append(prompt).append("\n\n")
        }
        return sb.toString().trimEnd()
    }
}
