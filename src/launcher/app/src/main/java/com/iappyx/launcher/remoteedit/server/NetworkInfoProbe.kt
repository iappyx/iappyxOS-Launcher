/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE.
 */
package com.iappyx.launcher.remoteedit.server

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfoProbe {

    /** Returns the first non-loopback IPv4 address — typically the WiFi
     *  LAN address. Null if none found (no WiFi, no hotspot, no ethernet). */
    fun lanAddress(@Suppress("UNUSED_PARAMETER") context: Context): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Throwable) { null }
    }
}
