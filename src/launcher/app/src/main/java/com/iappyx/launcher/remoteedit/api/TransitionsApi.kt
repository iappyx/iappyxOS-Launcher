/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — transitions library management. List, set
 * active, rename, edit description, delete (user-generated only).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.transitions.TransitionLibrary
import org.json.JSONArray
import org.json.JSONObject

class TransitionsApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val active = LauncherPrefs(context).pageTransitionStyle
        val arr = JSONArray()
        for (e in TransitionLibrary.all(context)) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("isUserGenerated", e.isUserGenerated)
                put("active", e.id == active)
                put("createdAt", e.createdAt)
            })
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("transitions", arr)
            put("activeId", active)
        })
    }

    fun setActive(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val id = obj.optString("id")
        if (id.isBlank()) return JsonResponse.error(ex, 400, "no id")
        // Honour the same "must be a known id" check the on-device
        // picker does — protects against typo'd ids that would wedge
        // the launcher into a missing spec.
        val known = TransitionLibrary.all(context).any { it.id == id }
        if (!known) return JsonResponse.error(ex, 404, "no such transition")
        LauncherPrefs(context).pageTransitionStyle = id
        // Layout broadcast nudges the on-device launcher to re-arm the
        // page transformer with the new spec.
        broadcastLayout()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun rename(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val title = obj.optString("title").trim()
        if (title.isEmpty()) return JsonResponse.error(ex, 400, "title required")
        if (!TransitionLibrary.renameUser(context, id, title)) {
            return JsonResponse.error(ex, 400, "rename refused (bundled or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun updateDescription(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val prompt = obj.optString("description", obj.optString("prompt"))
        if (!TransitionLibrary.updatePrompt(context, id, prompt)) {
            return JsonResponse.error(ex, 400, "update refused (bundled or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun delete(ex: MicroHttpServer.Exchange, id: String) {
        if (!TransitionLibrary.deleteUser(context, id)) {
            return JsonResponse.error(ex, 400, "delete refused (bundled, active, or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    private fun broadcastLayout() {
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
    }
}
