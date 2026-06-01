/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — main HTTP server.
 */
package com.iappyx.launcher.remoteedit.server

import android.app.Activity
import java.security.SecureRandom

/**
 * Activity-bound HTTP server. Lifetime = activity lifetime.
 *
 * - Binds to 0.0.0.0 on an OS-assigned ephemeral port (via MicroHttpServer)
 * - Generates a 6-digit pairing code at construct time
 * - First valid POST /pair {code:"…"} pins the requesting IP and sets a
 *   session cookie. Subsequent requests must come from the same IP and
 *   carry the cookie.
 * - 5 wrong codes within a session = lockout
 */
class EditServer(private val activity: Activity) {

    val pairingCode: String = generatePairingCode()
    private val sessionToken: String = generateSessionToken()

    private val auth = EditServerAuth(pairingCode, sessionToken)
    private val routes = EditServerRoutes(activity, auth)

    private val http = MicroHttpServer { ex ->
        try { routes.handle(ex) }
        catch (t: Throwable) {
            try { JsonResponse.error(ex, 500, "internal: ${t.javaClass.simpleName}") } catch (_: Throwable) {}
        }
    }

    val port: Int get() = http.port

    /** Fires (true, label) when a client successfully pairs, (false, "")
     *  on disconnect / unpair. label is the client IP. Called on a
     *  background thread; UI must dispatch to main thread. */
    var onClientStateChanged: ((Boolean, String) -> Unit)? = null

    init {
        auth.onClientStateChanged = { hasClient, label ->
            onClientStateChanged?.invoke(hasClient, label)
        }
    }

    fun start() { http.start() }

    fun stop() { http.stop() }

    fun hasClient(): Boolean = auth.hasPairedClient()

    fun dropClient() = auth.unpair()

    private fun generatePairingCode(): String {
        val r = SecureRandom()
        val n = r.nextInt(1_000_000)
        return "%06d".format(n)
    }

    private fun generateSessionToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
