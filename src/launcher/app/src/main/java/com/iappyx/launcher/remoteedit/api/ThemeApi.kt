/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — theme tab. Reads/writes the SAME `--iappyx-*`
 * override map the native theme editor uses (ThemeOverrides), so the web
 * interface and on-device editor stay in sync. On write it persists the
 * overrides, downloads a chosen catalog font if the phone doesn't have it
 * yet, and pushes the change live to the running launcher (widgets + native
 * UI) via LauncherActivity.applyThemeLive().
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.theme.FontCatalog
import com.iappyx.launcher.theme.FontDownloader
import com.iappyx.launcher.theme.ThemeFonts
import com.iappyx.launcher.theme.ThemeOverrides
import org.json.JSONArray
import org.json.JSONObject

class ThemeApi(private val context: Context) {

    /** Accent swatches — mirror the native editor's palette. */
    private val accents = listOf(
        "#5b8cff", "#46d39a", "#ff7a59", "#c06bff",
        "#ffd23f", "#ff5d8f", "#22c1c3", "#cfd3da",
    )

    fun get(ex: MicroHttpServer.Exchange) {
        val resp = JSONObject().apply {
            put("overrides", JSONObject(ThemeOverrides.get(context) as Map<*, *>))
            put("accents", JSONArray(accents))
            put("fonts", JSONObject().apply {
                put("bundled", JSONArray().apply {
                    for (f in ThemeFonts.ALL) put(
                        JSONObject().put("family", f.family).put("fallback", f.fallback.name.lowercase()),
                    )
                })
                put("catalog", JSONArray().apply {
                    for (e in FontCatalog.all(context)) put(
                        JSONObject()
                            .put("family", e.family)
                            .put("fallback", e.fallback.name.lowercase())
                            .put("downloaded", FontCatalog.isDownloaded(context, e)),
                    )
                })
            })
        }
        JsonResponse.ok(ex, resp)
    }

    fun set(ex: MicroHttpServer.Exchange) {
        val body = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val ov = body.optJSONObject("overrides") ?: JSONObject()
        val map = LinkedHashMap<String, String>()
        for (k in ov.keys()) {
            if (k.startsWith("--iappyx-")) map[k] = ov.getString(k)
        }
        if (map.isEmpty()) ThemeOverrides.clear(context) else ThemeOverrides.set(context, map)

        // If the chosen font is a catalog font the phone hasn't downloaded yet,
        // fetch it so widgets + native UI can actually render it (the browser
        // previews it from Google Fonts, but the device needs the file).
        map["--iappyx-font"]?.let { stack ->
            FontCatalog.fromStack(context, stack)?.let { e ->
                if (!FontCatalog.isDownloaded(context, e)) {
                    FontDownloader.ensure(context, e) { _, _ -> pushLive() }
                }
            }
        }
        pushLive()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Apply live to the running launcher (widgets JS-push + native re-theme).
     *  No-op if the launcher isn't currently resumed — onResume will pick up
     *  the saved override (ThemeOverrides marks dirty). */
    private fun pushLive() {
        val act = context as? LauncherActivity ?: LauncherActivity.runningInstance ?: return
        act.runOnUiThread { act.applyThemeLive() }
    }
}
