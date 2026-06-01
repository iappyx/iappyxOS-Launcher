/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — plugin settings.html proxy (Phase 2).
 *
 * Serves a plugin's settings.html to the browser with a shim injected
 * that mimics the in-WebView `iappyx.{secureStore,storage,httpClient,
 * plugin}` namespaces. The shim is sync-friendly for reads (preloaded
 * data baked into the HTML) and async for writes + fetches (POST back
 * to this server). On close the iframe postMessages the parent.
 *
 * Endpoints:
 *   GET  /api/plugins/{id}/settings.html  → preloaded HTML
 *   POST /api/plugins/{id}/bridge         → set/remove secureStore + storage
 *   POST /api/plugins/{id}/fetch          → server-side HTTP proxy
 *                                            (browsers can't hit cross-origin
 *                                            services like the Microsoft Graph
 *                                            token endpoint directly)
 */
// PLUGINS: BEGIN
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.plugins.PluginsModule
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject

class PluginSettingsServeApi(private val context: Context) {

    /** Add the CORS headers required for the sandboxed iframe's
     *  cross-origin requests back to the launcher server. Wildcard
     *  origin is safe because auth is Bearer-token (in a header), not
     *  cookie — there's no ambient authority for an attacker on a
     *  different origin to exploit. */
    private fun addCorsHeaders(ex: MicroHttpServer.Exchange) {
        ex.setHeader("Access-Control-Allow-Origin", "*")
        ex.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        ex.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    /** Verify that the request carries either a valid Bearer token
     *  scoped to [pluginId] (the new iframe-auth path) OR a valid
     *  paired-session cookie (the legacy path, kept during rollout
     *  so this change doesn't break existing flows).
     *
     *  Returns null on success; an error string on failure. Caller
     *  should respond 401 with that string. */
    private fun verifyAuthFor(ex: MicroHttpServer.Exchange, pluginId: String): String? {
        // Bearer token (sandboxed iframe path).
        val authHeader = ex.request.header("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            val tokenPluginId = PluginSessionTokens.verify(token, ex.clientIp())
            if (tokenPluginId == null) return "invalid or expired token"
            if (tokenPluginId != pluginId) return "token scoped to a different plugin"
            return null
        }
        // No bearer token → fall through. The shared EditServerAuth
        // cookie check already ran in EditServerRoutes before we got
        // here, so unauthenticated requests don't reach this method.
        // (Legacy cookie-auth path for non-sandboxed iframes.)
        return null
    }


    /** GET /api/plugins/{id}/settings.html — returns the plugin's
     *  settings.html with the browser shim + preloaded data injected
     *  in the <head>. Designed to be loaded inside an iframe in the
     *  remote-edit UI. */
    fun serveSettings(ex: MicroHttpServer.Exchange, pluginId: String) {
        val artefacts = PluginsModule.readSettingsArtefactsAsJson(context, pluginId)
            ?: return JsonResponse.error(ex, 404, "no settings UI for plugin '$pluginId'")
        val html = artefacts.optString("html")
        if (html.isEmpty()) return JsonResponse.error(ex, 404, "settings.html missing")
        // Mint a plugin-scoped, IP-pinned token. Embedded in the preload
        // so the sandboxed iframe (no cookies) can authenticate its
        // bridge/fetch calls via `Authorization: Bearer <token>`.
        val clientIp = ex.clientIp() ?: ""
        val sessionToken = PluginSessionTokens.issue(pluginId, clientIp)
        artefacts.put("sessionToken", sessionToken)
        val composed = compose(
            html = html,
            pluginId = pluginId,
            artefactsJson = artefacts.toString(),
        )
        val bytes = composed.toByteArray(Charsets.UTF_8)
        // no-store: this HTML inlines the plugin's decrypted secureStore.
        // Any intermediate cache (rare on LAN, possible on tethering /
        // corporate proxy) would otherwise leak it.
        JsonResponse.rawNoStore(ex, 200, "text/html; charset=utf-8", bytes)
        // CORS: iframe will be sandboxed (opaque origin / "null") and its
        // subsequent bridge/fetch calls are technically cross-origin.
        // Wildcard origin is safe HERE because auth is in a Bearer
        // token, not a cookie — no ambient authority.
        addCorsHeaders(ex)
    }

    /** POST /api/plugins/{id}/bridge — write-only bridge for secureStore
     *  and storage. Reads were preloaded into the HTML; reads at runtime
     *  hit the in-shim cache.
     *  Body: { ns: "secureStore"|"storage", method: "set"|"remove",
     *          key: string, value?: string }
     *  Response: { ok:true } */
    fun bridge(ex: MicroHttpServer.Exchange, pluginId: String) {
        addCorsHeaders(ex)
        verifyAuthFor(ex, pluginId)?.let {
            return JsonResponse.error(ex, 401, it)
        }
        if (!PluginsModule.exists(context, pluginId)) {
            return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        }
        val body = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "expected JSON body")
        val ns = body.optString("ns")
        val method = body.optString("method")
        val key = body.optString("key")
        if (key.isEmpty()) return JsonResponse.error(ex, 400, "missing key")
        val value = if (body.has("value") && !body.isNull("value")) body.optString("value") else null
        when (ns) {
            "secureStore" -> when (method) {
                "set"    -> PluginsModule.secureStoreSet(context, pluginId, key, value)
                "remove" -> PluginsModule.secureStoreSet(context, pluginId, key, null)
                else     -> return JsonResponse.error(ex, 400, "unknown method '$method'")
            }
            "storage" -> when (method) {
                "save"   -> PluginsModule.storageSet(context, pluginId, key, value)
                "remove" -> PluginsModule.storageSet(context, pluginId, key, null)
                else     -> return JsonResponse.error(ex, 400, "unknown method '$method'")
            }
            else -> return JsonResponse.error(ex, 400, "unknown ns '$ns'")
        }
        JsonResponse.ok(ex, JSONObject().apply { put("ok", true) })
    }

    /** POST /api/plugins/{id}/fetch — server-side HTTP proxy.
     *  Body: { url, method?, headers?, body?, responseType? }
     *  Response: { ok:true, status, body, headers }
     *
     *  Browsers can't fetch cross-origin without CORS — plugin settings.html
     *  typically needs to hit a token endpoint (Microsoft, Spotify) and
     *  a /me endpoint that neither sends CORS headers. The launcher
     *  process can. We deliberately don't sandbox the URL — the plugin
     *  is trusted (user installed it explicitly), same trust level as
     *  when it runs natively. */
    fun fetch(ex: MicroHttpServer.Exchange, pluginId: String) {
        addCorsHeaders(ex)
        verifyAuthFor(ex, pluginId)?.let {
            return JsonResponse.error(ex, 401, it)
        }
        if (!PluginsModule.exists(context, pluginId)) {
            return JsonResponse.error(ex, 404, "no such plugin: $pluginId")
        }
        val req = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "expected JSON body")
        val url = req.optString("url")
        if (url.isEmpty()) return JsonResponse.error(ex, 400, "missing url")
        val method = req.optString("method", "GET").uppercase()
        val bodyStr = if (req.has("body") && !req.isNull("body")) req.optString("body") else null
        val headers = req.optJSONObject("headers")
        // Permissive TLS opt-in: only honoured if target host is LAN.
        // Public hosts silently fall back to strict validation.
        val insecureRequested = req.optBoolean("insecure", false)
        try {
            val parsedUrl = java.net.URL(url)
            val host = parsedUrl.host
            // Resolve once and pin via IP literal: keeps OkHttp/URLConnection
            // from re-resolving at connect time, which a DNS-rebinding
            // attacker (TTL=0) could redirect to a non-LAN address.
            val pinnedAddr = if (insecureRequested) {
                com.iappyx.launcher.plugins.PluginInsecureHttp.resolveLanAddress(host)
            } else null
            val effectiveUrl: java.net.URL = if (pinnedAddr != null) {
                val ipStr = if (pinnedAddr is java.net.Inet6Address) {
                    // Strip IPv6 scope id (after `%`) — not valid in URLs — and bracket.
                    "[" + pinnedAddr.hostAddress.substringBefore('%') + "]"
                } else {
                    pinnedAddr.hostAddress
                }
                val port = if (parsedUrl.port != -1) ":${parsedUrl.port}" else ""
                java.net.URL("${parsedUrl.protocol}://$ipStr$port${parsedUrl.file}")
            } else {
                parsedUrl
            }
            val conn = (effectiveUrl.openConnection() as java.net.HttpURLConnection).apply {
                if (pinnedAddr != null) {
                    // Permissive SSL — only after the LAN-IP pin. SNI is the IP literal;
                    // self-signed LAN devices don't do virtual-hosting so that's OK.
                    com.iappyx.launcher.plugins.PluginInsecureHttp.applyIfLan(this, host)
                }
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                // Disable redirects ONLY on the insecure path — a 3xx
                // could otherwise evade the LAN gate. Strict-TLS path
                // keeps default redirect-following.
                instanceFollowRedirects = pinnedAddr == null
                if (headers != null) {
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        setRequestProperty(k, headers.optString(k))
                    }
                }
                if (bodyStr != null && method != "GET" && method != "HEAD") {
                    doOutput = true
                    outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = conn.responseCode
            val stream = try { conn.inputStream } catch (_: Throwable) { conn.errorStream }
            val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val outHeaders = JSONObject()
            for ((k, v) in conn.headerFields) {
                if (k == null) continue
                outHeaders.put(k, v.joinToString(","))
            }
            JsonResponse.ok(ex, JSONObject().apply {
                put("ok", true)
                put("status", status)
                put("body", respBody)
                put("headers", outHeaders)
            })
        } catch (e: Throwable) {
            JsonResponse.error(ex, 502, "fetch failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Inject the shim + preloaded data block at the top of <head>. If
     *  the document has no <head>, wrap the whole thing in a minimal
     *  shell — same pattern as the in-WebView shim composer.
     *
     *  Critical: replaces `<` in the JSON payload with `<` to
     *  prevent script-tag breakout. JSONObject.toString() does NOT
     *  escape `<`/`>`, so a plugin whose secureStore or storage data
     *  ever contains "</script>..." would otherwise close our script
     *  early and execute attacker JS in launcher origin (CVE-equivalent
     *  pattern). JS sees `<` as `<` after parsing, so plugin code
     *  reads the original character through `window.__iappyxPluginRemoteEdit`. */
    private fun compose(html: String, pluginId: String, artefactsJson: String): String {
        val safeJson = artefactsJson.replace("<", "\\u003c")
        val injection = buildString {
            append("<script>window.__iappyxPluginRemoteEdit = ")
            append(safeJson)
            append(";</script>\n")
            append("<script src=\"/static/iappyx-plugin-web-shim.js\"></script>\n")
        }
        val headIdx = html.indexOf("<head", ignoreCase = true)
        return if (headIdx >= 0) {
            val gt = html.indexOf('>', startIndex = headIdx)
            if (gt < 0) "<head>$injection</head>$html"
            else html.substring(0, gt + 1) + injection + html.substring(gt + 1)
        } else {
            "<!doctype html><html><head>$injection</head><body>$html</body></html>"
        }
    }
}
// PLUGINS: END
