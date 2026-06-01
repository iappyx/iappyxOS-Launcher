/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — network-trust evaluator.
 *
 * Self-hosted plugins that hold bearer tokens (Home Assistant, Immich,
 * Paperless, Nextcloud) shouldn't fire HTTP calls when the device is on
 * a hostile network — even with HTTPS, the token leaves the device on
 * every request. This evaluator gates plugin invocation against the
 * current network state vs the user's per-plugin restriction config.
 *
 * Restriction modes (stored in PluginPrefs):
 *   "always"               no restriction
 *   "trusted_wifi"         only when SSID matches the trusted list
 *   "vpn"                  only when an active connection has VPN transport
 *   "trusted_wifi_or_vpn"  either of the above
 *
 * SSID matching is exact (trim + strip surrounding quotes). The wifi
 * subsystem returns SSIDs wrapped in double quotes on most OEMs —
 * stripped here so the user can type "MyHomeWifi" instead of
 * "\"MyHomeWifi\"".
 */
package com.iappyx.launcher.plugins

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object PluginNetworkTrust {

    /** Result of an evaluation. [allowed] tells the caller whether the
     *  plugin invocation should proceed; [reason] is a short string the
     *  caller can include in a `{ok:false, error:'...'}` response so the
     *  widget can render an informative placeholder. */
    data class Verdict(val allowed: Boolean, val reason: String)

    fun evaluate(ctx: Context, manifest: PluginManifest): Verdict {
        val mode = PluginPrefs.networkRestriction(ctx, manifest.id, manifest.defaultNetworkRestriction)
        if (mode == "always") return Verdict(true, "")

        val onTrustedWifi = onTrustedWifi(ctx, manifest.id)
        val onVpn = onVpn(ctx)

        val ok = when (mode) {
            "trusted_wifi" -> onTrustedWifi
            "vpn" -> onVpn
            "trusted_wifi_or_vpn" -> onTrustedWifi || onVpn
            else -> true  // unknown mode → allow (don't lock the user out)
        }
        if (ok) return Verdict(true, "")
        val why = when (mode) {
            "trusted_wifi" -> "plugin restricted to trusted Wi-Fi networks"
            "vpn" -> "plugin restricted to VPN connection"
            "trusted_wifi_or_vpn" -> "plugin restricted to trusted Wi-Fi or VPN"
            else -> "plugin restricted on this network"
        }
        return Verdict(false, why)
    }

    /** Current SSID, with the wrapping quotes stripped that WifiManager
     *  returns on some OEMs ("\"MyWifi\"" → "MyWifi"). Returns null when
     *  not connected to Wi-Fi or the permission isn't granted. */
    fun currentSsid(ctx: Context): String? {
        // ACCESS_FINE_LOCATION is required to read the SSID on API 27+.
        // The launcher's location bridges already gate this permission;
        // if it isn't granted, getConnectionInfo() returns "<unknown ssid>".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return null
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val info = try { wm.connectionInfo } catch (_: Throwable) { null } ?: return null
        val raw = info.ssid ?: return null
        // <unknown ssid> = WifiManager.UNKNOWN_SSID. Treat as "not on Wi-Fi".
        if (raw == "<unknown ssid>" || raw.isBlank()) return null
        return raw.trim().removeSurrounding("\"")
    }

    /** True when any active network has the VPN transport. Catches
     *  WireGuard, Tailscale, OpenVPN, etc. — anything that registers as
     *  an Android VpnService. */
    fun onVpn(ctx: Context): Boolean {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        try {
            // Iterate all networks (not just the active one) — a phone
            // can have mobile + Wi-Fi + VPN simultaneously, and Android
            // routes some traffic through each. We consider VPN present
            // if ANY network registers it.
            val nets = cm.allNetworks
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun onTrustedWifi(ctx: Context, pluginId: String): Boolean {
        val ssid = currentSsid(ctx) ?: return false
        val trusted = PluginPrefs.trustedSsids(ctx, pluginId)
        return trusted.any { it.equals(ssid, ignoreCase = false) }
    }
}
