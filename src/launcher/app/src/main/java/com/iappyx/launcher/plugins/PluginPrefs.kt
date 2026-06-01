/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — small wrapper around SharedPreferences for plugin state
 * (enabled/disabled per-plugin). Lives in its own SharedPreferences file
 * (`plugins_state`) so removal of the plugin system can also wipe the
 * state cleanly via `adb shell pm clear`.
 */
package com.iappyx.launcher.plugins

import android.content.Context

internal object PluginPrefs {
    private const val PREFS_NAME = "plugins_state"

    /** Bundled plugins ship enabled by default; user-installed plugins
     *  default to disabled (the user has to opt in after the consent
     *  dialog — that flow lands in P4). [defaultEnabled] lets the
     *  registry pick per-source. */
    fun isEnabled(ctx: Context, id: String, defaultEnabled: Boolean): Boolean {
        return prefs(ctx).getBoolean("enabled_$id", defaultEnabled)
    }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled_$id", enabled).apply()
    }

    /** Wipe all state for a plugin id — used on uninstall (P4). */
    fun forget(ctx: Context, id: String) {
        val p = prefs(ctx)
        val keysToRemove = p.all.keys.filter { it.endsWith("_$id") }
        if (keysToRemove.isEmpty()) return
        val e = p.edit()
        for (k in keysToRemove) e.remove(k)
        e.apply()
    }

    // ── Network restriction (token-leak hygiene) ──────────────
    //
    // Modes:
    //   "always"             → no restriction (default for HTTP-public plugins)
    //   "trusted_wifi"       → only when current SSID is in the trusted list
    //   "vpn"                → only when a VPN is active
    //   "trusted_wifi_or_vpn"→ either of the above
    //
    // The default for a plugin is derived from manifest.defaultNetworkRestriction:
    //   null / "always" → "always"
    //   "trusted"       → "trusted_wifi_or_vpn"

    fun networkRestriction(ctx: Context, id: String, manifestDefault: String?): String {
        val stored = prefs(ctx).getString("restriction_$id", null)
        if (stored != null) return stored
        // Map manifest hint to a concrete mode.
        return if (manifestDefault == "trusted") "trusted_wifi_or_vpn" else "always"
    }

    fun setNetworkRestriction(ctx: Context, id: String, mode: String) {
        prefs(ctx).edit().putString("restriction_$id", mode).apply()
    }

    /** Comma-separated SSID list per plugin. Empty list when nothing set. */
    fun trustedSsids(ctx: Context, id: String): List<String> {
        val raw = prefs(ctx).getString("trusted_ssids_$id", null) ?: return emptyList()
        return raw.split('')  // unit separator — SSIDs can contain commas
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun setTrustedSsids(ctx: Context, id: String, ssids: List<String>) {
        val cleaned = ssids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs(ctx).edit().putString("trusted_ssids_$id", cleaned.joinToString("")).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
