/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — manifest.json parser. Plugin authors declare id, name,
 * version, capabilities, exposed methods here. Parsed once on registry
 * load; the rest of the package operates on the parsed struct.
 */
package com.iappyx.launcher.plugins

import org.json.JSONArray
import org.json.JSONObject

/** Parsed `manifest.json` for one plugin. Plugins that fail to parse
 *  (missing id, malformed JSON) are silently dropped from the registry
 *  — the user only sees plugins that loaded cleanly. */
internal data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val minLauncher: Int,
    /** When true, the launcher keeps the plugin's WebView alive even
     *  when no caller is active (background sync, push subscription,
     *  scheduled work). Defaults to false — plugin spins up on first
     *  invoke and idles out. Honored from P2 onwards; P1 keeps all
     *  active plugins resident. */
    val background: Boolean,
    /** When true, the plugin runs in a dedicated Android process
     *  (`:plugin_isolated`) instead of sharing the launcher's main
     *  process. A native crash or memory leak in an isolated plugin
     *  can't bring down the launcher. Trade-off: bridges that depend
     *  on cross-process state (`scheduler`, `notification:read`,
     *  `push`) aren't currently supported in isolated mode — the
     *  manifest validator rejects those combinations cleanly. */
    val isolatedProcess: Boolean,
    /** Capabilities the plugin requires (e.g. "http", "secureStore",
     *  "scheduler:periodic"). The plugin gets exactly these bridges
     *  attached to its WebView. P3 implements gating; P1 attaches no
     *  extra bridges (the hello-world test plugin needs none). */
    val capabilities: List<String>,
    /** Filename of the per-plugin Settings page, relative to the
     *  plugin's root dir. Null when the plugin has no configuration
     *  UI. Surfaced by the Settings tab in P5. */
    val settingsUi: String?,
    /** Names of methods the plugin exposes via `iappyx.plugin.export(...)`.
     *  Advisory: the bridge doesn't validate calls against this list
     *  (the plugin's runtime export map is authoritative), but it's
     *  used by P8 for AI prompt aggregation. */
    val exposes: List<String>,
    /** Optional snippet appended to widget/wallpaper AI generator
     *  system prompts. Plugin authors describe their surface to the
     *  AI here. Used from P8 onwards. */
    val aiPrompt: String?,
    /** PLUGINS: minimum interval between two universal-search `search`
     *  invocations on this plugin, in milliseconds. The aggregator
     *  skips the plugin entirely when the keystroke arrives within this
     *  window of the last invocation. Lets cloud-bound plugins (Drive,
     *  Office365, GitHub) avoid issuing one network round trip per
     *  keystroke. Null / 0 = no throttle, fire every query (default).
     *  Reasonable values: 250 for LAN plugins, 800-1200 for cloud APIs. */
    val searchThrottleMs: Int,
    /** Author-declared HINT for the default network-trust restriction.
     *  Values: "always" (no restriction) or "trusted" (default to
     *  trusted-Wi-Fi-or-VPN). Self-hosted plugins (HA, Immich, etc.)
     *  should declare "trusted" so a fresh install defaults to a safer
     *  posture. User overrides via Settings → Plugins → Network. Null
     *  is treated as "always". */
    val defaultNetworkRestriction: String?,
) {
    companion object {
        fun fromJson(o: JSONObject): PluginManifest? {
            val id = o.optString("id").trim()
            if (id.isBlank()) return null
            // Slug validation: ids end up as path components, prefs
            // key suffixes, and JS object property names. Keep it tight.
            if (!id.matches(Regex("[a-z][a-z0-9_-]*"))) return null
            val isolated = o.optBoolean("isolatedProcess", false)
            val capabilities = parseStringArray(o.optJSONArray("capabilities"))
            // Bridge state ownership rules: scheduler / notification:read /
            // push live in the launcher's main process and can't be reached
            // from an isolated plugin process cleanly. Refuse the manifest
            // up-front so the user gets a meaningful install error, not a
            // silent half-failure at runtime. Adding cross-process bridges
            // later (ContentProvider-backed storage etc.) would relax this.
            if (isolated) {
                val unsupported = capabilities.filter {
                    it == "scheduler" || it == "notification:read" || it == "push"
                }
                if (unsupported.isNotEmpty()) {
                    android.util.Log.w(
                        "iappyx-plugin",
                        "Plugin '$id' declares isolatedProcess + unsupported capabilities $unsupported — refusing",
                    )
                    return null
                }
            }
            val rawRestriction = o.optString("defaultNetworkRestriction").trim().lowercase()
            val restriction = when (rawRestriction) {
                "trusted", "trusted_wifi", "trusted_wifi_or_vpn" -> "trusted"
                "always", "none", "" -> null
                else -> null
            }
            return PluginManifest(
                id = id,
                name = o.optString("name").ifBlank { id },
                description = o.optString("description"),
                version = o.optString("version", "0.0.0"),
                author = o.optString("author"),
                minLauncher = o.optInt("minLauncher", 1),
                background = o.optBoolean("background", false),
                isolatedProcess = isolated,
                capabilities = capabilities,
                settingsUi = o.optString("settingsUi").ifBlank { null },
                exposes = parseExposes(o.optJSONArray("exposes")),
                aiPrompt = o.optString("aiPrompt").ifBlank { null },
                searchThrottleMs = o.optInt("searchThrottleMs", 0).coerceAtLeast(0),
                defaultNetworkRestriction = restriction,
            )
        }

        private fun parseStringArray(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until a.length()) {
                val s = a.optString(i).trim()
                if (s.isNotBlank()) out.add(s)
            }
            return out
        }

        /** `exposes` accepts both the long form `[{"name":"recent","args":{...}}]`
         *  and the short form `["recent","random"]`. The args schema is
         *  metadata only (P8 surfaces it to the AI prompt). We only
         *  pull the names here. */
        private fun parseExposes(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until a.length()) {
                val obj = a.optJSONObject(i)
                if (obj != null) {
                    val name = obj.optString("name").trim()
                    if (name.isNotBlank()) out.add(name)
                } else {
                    val s = a.optString(i).trim()
                    if (s.isNotBlank()) out.add(s)
                }
            }
            return out
        }
    }
}
