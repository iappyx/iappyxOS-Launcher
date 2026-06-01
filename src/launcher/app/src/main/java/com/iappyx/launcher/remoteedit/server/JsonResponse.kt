/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — small helpers for HTTP responses.
 */
package com.iappyx.launcher.remoteedit.server

import org.json.JSONArray
import org.json.JSONObject

object JsonResponse {

    fun ok(ex: MicroHttpServer.Exchange, body: JSONObject) = json(ex, 200, body.toString())
    fun ok(ex: MicroHttpServer.Exchange, body: JSONArray) = json(ex, 200, body.toString())

    fun error(ex: MicroHttpServer.Exchange, code: Int, message: String) {
        val body = JSONObject().put("error", message).toString()
        json(ex, code, body)
    }

    fun raw(ex: MicroHttpServer.Exchange, code: Int, contentType: String, bytes: ByteArray) {
        ex.status = code
        ex.setHeader("Content-Type", contentType)
        ex.setBody(bytes)
    }

    /** No-body response — used for CORS preflight (204). The exchange
     *  may already have CORS headers set by the caller; this just
     *  finalises status + empty body. */
    fun empty(ex: MicroHttpServer.Exchange, code: Int) {
        ex.status = code
        ex.setBody(ByteArray(0))
    }

    /** Same as [raw] but explicitly Cache-Control: no-store. Used for
     *  responses that inline sensitive data (e.g. plugin settings HTML
     *  with decrypted secureStore preloaded) where any intermediate
     *  cache would be a leak. Generic static assets keep using [raw]. */
    fun rawNoStore(ex: MicroHttpServer.Exchange, code: Int, contentType: String, bytes: ByteArray) {
        ex.status = code
        ex.setHeader("Content-Type", contentType)
        ex.setHeader("Cache-Control", "no-store")
        ex.setBody(bytes)
    }

    private fun json(ex: MicroHttpServer.Exchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.status = code
        ex.setHeader("Content-Type", "application/json; charset=utf-8")
        ex.setHeader("Cache-Control", "no-store")
        ex.setBody(bytes)
    }

    fun readJsonObject(ex: MicroHttpServer.Exchange): JSONObject? {
        val body = ex.request.bodyAsString()
        if (body.isBlank()) return null
        return try { JSONObject(body) } catch (_: Throwable) { null }
    }

    fun setCookie(ex: MicroHttpServer.Exchange, name: String, value: String) {
        ex.addHeader("Set-Cookie", "$name=$value; Path=/; HttpOnly; SameSite=Strict")
    }
}
