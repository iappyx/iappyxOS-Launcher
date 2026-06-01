/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — short-lived, plugin-scoped tokens for iframe auth.
 *
 * The plugin settings iframe is sandboxed without `allow-same-origin`,
 * so it can no longer carry the parent's session cookie. Instead, the
 * launcher mints a one-time random token in [serveSettings], embeds it
 * in the iframe's preloaded data, and the iframe's shim sends it back
 * in `Authorization: Bearer <token>` on every bridge/fetch call.
 *
 * Three properties matter:
 *   1. **Plugin-scoped**: the token is bound to a single pluginId.
 *      A compromised settings.html that tries to call another plugin's
 *      endpoint is refused (URL pluginId mismatches the token's).
 *   2. **Short-lived**: 30 min TTL. Long enough for a typical settings
 *      session; short enough that a leaked token doesn't outlive the
 *      iframe by much.
 *   3. **IP-pinned**: the token is bound to the client IP that opened
 *      the iframe (same paired-laptop IP that holds the session cookie).
 *      Defence-in-depth against token theft from a different network.
 *
 * In-memory only. Process restart invalidates every active token —
 * users would need to reopen Configure. Acceptable for a short session.
 */
package com.iappyx.launcher.remoteedit.api

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal object PluginSessionTokens {

    private const val TTL_MS: Long = 30L * 60 * 1000

    private data class Entry(
        val pluginId: String,
        val clientIp: String,
        val expiresAt: Long,
    )

    private val store = ConcurrentHashMap<String, Entry>()
    private val rng = SecureRandom()

    /** Mint a fresh token for the given plugin + client IP. Returns the
     *  token; caller embeds it in the iframe's preloaded data. */
    fun issue(pluginId: String, clientIp: String): String {
        sweepExpired()
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        // URL-safe base64 without padding — safe to drop into HTTP headers.
        val token = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        store[token] = Entry(pluginId, clientIp, System.currentTimeMillis() + TTL_MS)
        return token
    }

    /** Validate a token. Returns the bound pluginId if valid (and IP
     *  matches), null otherwise. Expired entries are evicted on read. */
    fun verify(token: String?, clientIp: String?): String? {
        if (token.isNullOrBlank() || clientIp == null) return null
        val entry = store[token] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            store.remove(token)
            return null
        }
        if (entry.clientIp != clientIp) return null
        return entry.pluginId
    }

    /** Revoke a specific token. Called when the iframe closes cleanly,
     *  so the token doesn't sit around. */
    fun revoke(token: String?) {
        if (token != null) store.remove(token)
    }

    /** Sweep entries past their TTL. Called lazily on issue. O(n) but
     *  n is small (~one entry per active iframe). */
    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        val iter = store.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.expiresAt < now) iter.remove()
        }
    }
}
