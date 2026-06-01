/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — pairing code + IP-pin auth.
 */
package com.iappyx.launcher.remoteedit.server

class EditServerAuth(
    private val pairingCode: String,
    private val sessionToken: String,
) {
    private var pairedIp: String? = null
    private var failedCount: Int = 0
    private var locked: Boolean = false

    var onClientStateChanged: ((Boolean, String) -> Unit)? = null

    fun hasPairedClient(): Boolean = pairedIp != null

    fun unpair() {
        val was = pairedIp
        pairedIp = null
        if (was != null) onClientStateChanged?.invoke(false, "")
    }

    /** Validate the request. Returns reason-why-rejected (null = ok). */
    fun checkRequest(ex: MicroHttpServer.Exchange, requireAuth: Boolean): String? {
        if (locked) return "locked"
        if (!requireAuth) return null
        val cookie = readCookie(ex, "iax_edit") ?: return "unauthorized"
        if (cookie != sessionToken) return "unauthorized"
        val ip = ex.clientIp() ?: return "unauthorized"
        if (pairedIp == null || pairedIp != ip) return "unauthorized"
        return null
    }

    /** Validate a /pair attempt. Returns the cookie value to set, or
     *  null if the attempt was rejected. */
    fun tryPair(ex: MicroHttpServer.Exchange, code: String): String? {
        if (locked) return null
        val ip = ex.clientIp() ?: return null
        if (pairedIp != null && pairedIp != ip) return null
        if (code != pairingCode) {
            failedCount++
            if (failedCount >= 5) locked = true
            return null
        }
        pairedIp = ip
        onClientStateChanged?.invoke(true, ip)
        return sessionToken
    }

    fun isLocked(): Boolean = locked

    private fun readCookie(ex: MicroHttpServer.Exchange, name: String): String? {
        val raw = ex.request.header("Cookie") ?: return null
        for (part in raw.split(";")) {
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq < 0) continue
            if (p.substring(0, eq) == name) return p.substring(eq + 1)
        }
        return null
    }
}
