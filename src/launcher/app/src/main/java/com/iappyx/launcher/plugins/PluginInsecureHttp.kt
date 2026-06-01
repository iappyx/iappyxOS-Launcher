/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — opt-in permissive HTTPS for plugin code, gated to LAN
 * addresses only. Required for talking to local devices with
 * self-signed certificates (e.g. Hue Bridge Pro, Reolink local API,
 * Bambu Lab printers). Refused for public-internet hosts.
 *
 * Threat model: plugins are human-authored + curated through the
 * showcase PR flow. AI-generated widget+wallpaper HTML is gated by
 * the ForbiddenApiCheck scanner and never sees this surface.
 *
 * - Widgets cannot call this — only the plugin HTTP bridge exposes
 *   `insecure: true`.
 * - Even when a plugin sets `insecure: true`, the target host must
 *   resolve to a private/loopback/link-local/ULA address (or end in
 *   `.local` for mDNS). Public hosts silently fall back to strict
 *   validation, so a plugin can't accidentally — or maliciously —
 *   bypass TLS for the public internet.
 */
package com.iappyx.launcher.plugins

import java.net.HttpURLConnection
import java.net.InetAddress
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PluginInsecureHttp {

    /** True when host is a private/loopback/link-local IP or an mDNS
     *  hostname THAT RESOLVES to a LAN address. The `.local` suffix is
     *  not enough on its own: an attacker controlling DNS could otherwise
     *  return a public IP for `evil.attacker.com.local` and the gate
     *  would happily pass. We always verify the resolved address. */
    @JvmStatic
    fun isLanHost(host: String?): Boolean {
        return resolveLanAddress(host) != null
    }

    /** Resolve [host] once and return the InetAddress IF it points to a
     *  LAN range. Callers that go on to make an HTTP request should
     *  prefer this over [isLanHost] alone and pass the returned address
     *  to OkHttp (via a Dns interceptor) or HttpURLConnection (by using
     *  the IP literal in the URL) — otherwise a second DNS lookup at
     *  connect time can be rebound to a non-LAN address (TOCTOU). */
    @JvmStatic
    fun resolveLanAddress(host: String?): InetAddress? {
        if (host.isNullOrBlank()) return null
        return try {
            val addr = InetAddress.getByName(host)
            val ok = addr.isLoopbackAddress
                || addr.isSiteLocalAddress    // RFC1918 IPv4 (10/8, 172.16/12, 192.168/16)
                || addr.isLinkLocalAddress    // 169.254/16, IPv6 fe80::/10
                || isIpv6UniqueLocal(addr)    // IPv6 fc00::/7
            if (ok) addr else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        // fc00::/7 → first byte 0xfc or 0xfd (top 7 bits = 0xfc>>1 = 0x7e).
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    /** A "trust everything" X509TrustManager. Only ever installed on a
     *  per-request HttpsURLConnection after [isLanHost] cleared the host. */
    private val TRUST_ALL: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<java.security.cert.X509Certificate>?, authType: String?,
        ) { /* permissive */ }
        override fun checkServerTrusted(
            chain: Array<java.security.cert.X509Certificate>?, authType: String?,
        ) { /* permissive */ }
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    /** Build an SSLSocketFactory that accepts any cert. Used exclusively
     *  for connections to LAN hosts that we've already verified are
     *  LAN-side. New instance per call so two concurrent fetches don't
     *  race on SSLContext state. */
    @JvmStatic
    fun permissiveSocketFactory(): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, TRUST_ALL, java.security.SecureRandom())
        return ctx.socketFactory
    }

    /** Permissive hostname verifier — returns true for any host. Only
     *  installed alongside [permissiveSocketFactory] on LAN-verified
     *  connections. */
    @JvmField
    val PERMISSIVE_HOSTNAME_VERIFIER: HostnameVerifier =
        HostnameVerifier { _, _ -> true }

    /** Convenience for the HttpURLConnection-based proxy path. No-op
     *  for plain HTTP or non-LAN hosts. Returns true when permissive
     *  SSL was actually applied so the caller can log if needed. */
    @JvmStatic
    fun applyIfLan(conn: HttpURLConnection, host: String?): Boolean {
        if (conn !is HttpsURLConnection) return false
        if (!isLanHost(host)) return false
        conn.sslSocketFactory = permissiveSocketFactory()
        conn.hostnameVerifier = PERMISSIVE_HOSTNAME_VERIFIER
        return true
    }

    /** Trust manager handle for OkHttp's sslSocketFactory call (it wants
     *  both the factory and the TrustManager). */
    @JvmStatic
    fun trustAllManager(): X509TrustManager = TRUST_ALL[0] as X509TrustManager
}
