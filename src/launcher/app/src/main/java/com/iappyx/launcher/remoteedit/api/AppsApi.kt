/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — installed apps list.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import android.content.Intent
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject

class AppsApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val arr = JSONArray()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        for (info in resolved) {
            val ai = info.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val activity = ai.name ?: continue
            val label = (ai.loadLabel(pm) ?: pkg).toString()
            arr.put(JSONObject().apply {
                put("pkg", pkg)
                put("activity", activity)
                put("label", label)
            })
        }
        JsonResponse.ok(ex, arr)
    }

    companion object {
        /** Cheap apps-count for state snapshot. */
        fun cachedCount(context: Context): Int = try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).size
        } catch (_: Throwable) { 0 }
    }
}
