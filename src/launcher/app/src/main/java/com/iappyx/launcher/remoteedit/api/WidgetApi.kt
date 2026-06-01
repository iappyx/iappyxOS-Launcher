/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — widget library read endpoints.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.widget.WidgetLibrary
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject

class WidgetApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val arr = JSONArray()
        for (w in WidgetLibrary.all(context)) {
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("title", w.title)
                put("subtitle", w.subtitle)
                put("isUserGenerated", w.isUserGenerated)
                put("inUse", w.isInUse)
                put("createdAt", w.createdAt)
                put("assetPath", w.assetPath ?: JSONObject.NULL)
            })
        }
        JsonResponse.ok(ex, arr)
    }

    fun get(ex: MicroHttpServer.Exchange, id: String) {
        val w = WidgetLibrary.get(context, id)
        if (w == null) {
            JsonResponse.error(ex, 404, "no such widget")
            return
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("id", w.id)
            put("title", w.title)
            put("subtitle", w.subtitle)
            put("isUserGenerated", w.isUserGenerated)
            put("inUse", w.isInUse)
            put("createdAt", w.createdAt)
            put("assetPath", w.assetPath ?: JSONObject.NULL)
        })
    }

    /** Rename a user-generated widget. Refuses for bundled entries
     *  (their titles come from the build's BUNDLED list and reverting
     *  an APK update would clobber any override anyway). */
    fun rename(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "no body")
        val newTitle = obj.optString("title").trim()
        if (newTitle.isEmpty()) return JsonResponse.error(ex, 400, "title required")
        if (!WidgetLibrary.rename(context, id, newTitle)) {
            return JsonResponse.error(ex, 400, "rename refused (bundled, missing, or invalid)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Update the stored prompt/description for a user-generated widget.
     *  Pure metadata — doesn't trigger an AI call or change the HTML. */
    fun updateDescription(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "no body")
        val prompt = obj.optString("description", obj.optString("prompt"))
        if (!WidgetLibrary.updatePrompt(context, id, prompt)) {
            return JsonResponse.error(ex, 400, "update refused (bundled or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Delete a user-generated widget. WidgetLibrary.delete refuses
     *  bundled entries AND any widget currently placed on the home
     *  grid or dock — same "in-use guard" the on-device manage tab
     *  uses, so the editor doesn't need to re-check. */
    fun delete(ex: MicroHttpServer.Exchange, id: String) {
        if (!WidgetLibrary.delete(context, id)) {
            return JsonResponse.error(ex, 400, "delete refused (bundled, in use, or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Dump a widget's per-instance SharedPreferences (the bucket
     *  `iappyx.storage.*` writes to on the phone). The WidgetHost
     *  redirects `getSharedPreferences("iappyx_store")` to a per-
     *  widget file `widget_<id>_iappyx_store`, so values from one
     *  widget never leak into another. We read the same file directly
     *  and return its key/value map. Used for debugging "why does my
     *  weather widget remember yesterday's city". */
    fun listStorage(ex: MicroHttpServer.Exchange, id: String) {
        if (WidgetLibrary.get(context, id) == null) {
            return JsonResponse.error(ex, 404, "no such widget")
        }
        val prefs = context.getSharedPreferences(
            "widget_${id}_iappyx_store", Context.MODE_PRIVATE,
        )
        val entries = JSONArray()
        for ((k, v) in prefs.all) {
            entries.put(JSONObject().apply {
                put("key", k)
                put("value", v?.toString() ?: "")
                put("type", when (v) {
                    is String -> "string"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Boolean -> "bool"
                    is Set<*> -> "set"
                    null -> "null"
                    else -> v.javaClass.simpleName
                })
            })
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("widgetId", id)
            put("entries", entries)
        })
    }

    /** Clear all keys for one widget. Body optionally `{key: "..."}`
     *  to remove a single entry — otherwise the whole bucket is
     *  wiped. Surfaces a tiny "cleared N keys" response so the UI
     *  can confirm. */
    fun clearStorage(ex: MicroHttpServer.Exchange, id: String) {
        if (WidgetLibrary.get(context, id) == null) {
            return JsonResponse.error(ex, 404, "no such widget")
        }
        val prefs = context.getSharedPreferences(
            "widget_${id}_iappyx_store", Context.MODE_PRIVATE,
        )
        // Body is OPTIONAL — DELETE requests without a body should
        // wipe the whole bucket. readJsonObject swallows empty bodies
        // and returns null; treat that as "clear all" instead of an
        // error.
        val obj = JsonResponse.readJsonObject(ex)
        val specificKey = obj?.optString("key")?.takeIf { it.isNotEmpty() }
        val countBefore = prefs.all.size
        val editor = prefs.edit()
        if (specificKey != null) {
            editor.remove(specificKey)
        } else {
            editor.clear()
        }
        editor.apply()
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("clearedKeys", if (specificKey != null) 1 else countBefore)
            put("widgetId", id)
        })
    }
}
