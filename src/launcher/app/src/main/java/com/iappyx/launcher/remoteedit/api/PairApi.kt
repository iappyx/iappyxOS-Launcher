/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — pairing endpoint.
 */
package com.iappyx.launcher.remoteedit.api

import com.iappyx.launcher.remoteedit.server.EditServerAuth
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject

class PairApi(private val auth: EditServerAuth) {

    fun tryPair(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex)
        val code = obj?.optString("code")?.replace(" ", "")?.replace("-", "")
        if (code.isNullOrBlank() || code.length != 6 || !code.all { it.isDigit() }) {
            JsonResponse.error(ex, 400, "code must be 6 digits")
            return
        }
        if (auth.isLocked()) {
            JsonResponse.error(ex, 429, "too many attempts; restart pairing on phone")
            return
        }
        val cookie = auth.tryPair(ex, code)
        if (cookie == null) {
            if (auth.isLocked()) JsonResponse.error(ex, 429, "locked")
            else JsonResponse.error(ex, 401, "wrong code")
            return
        }
        JsonResponse.setCookie(ex, "iax_edit", cookie)
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }
}
