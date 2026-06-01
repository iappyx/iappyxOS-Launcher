/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — clippings tab. Lists the share-to-launcher
 * captures (videos, music, articles, images, notes), supports lock
 * toggle / TTL reset / delete.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.widget.WidgetLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ClippingsApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val store = PlacementStore(context)
        val layout = store.load()
        val arr = JSONArray()
        for (c in layout.clippings) {
            val meta = readMeta(c.widgetId) ?: continue
            arr.put(JSONObject().apply {
                put("widgetId", c.widgetId)
                put("title", meta.title)
                put("kind", meta.kind)
                put("createdAt", meta.createdAt)
                put("expiresAt", meta.expiresAt)
                put("locked", meta.locked)
                put("sourceHost", meta.sourceHost ?: JSONObject.NULL)
                put("sourceUrl", meta.sourceUrl ?: JSONObject.NULL)
            })
        }
        JsonResponse.ok(ex, JSONObject().put("clippings", arr))
    }

    fun delete(ex: MicroHttpServer.Exchange, widgetId: String) {
        // Two-step: remove from layout.clippings, then delete the widget
        // folder. The folder delete is best-effort — WidgetLibrary refuses
        // for currently-placed widgets, but a clipping shouldn't be on
        // the home grid by definition (it lives only on the clippings
        // page), so the call should succeed.
        val store = PlacementStore(context)
        val layout = store.load()
        val before = layout.clippings.size
        layout.clippings.removeAll { it.widgetId == widgetId }
        if (layout.clippings.size == before) {
            return JsonResponse.error(ex, 404, "no such clipping")
        }
        store.save(layout)
        WidgetLibrary.delete(context, widgetId)
        broadcastChanged()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun patch(ex: MicroHttpServer.Exchange, widgetId: String) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val meta = readMeta(widgetId) ?: return JsonResponse.error(ex, 404, "no such clipping")
        val dir = File(context.filesDir, "widgets/$widgetId")
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return JsonResponse.error(ex, 404, "no meta")
        val existing = try { JSONObject(metaFile.readText()) } catch (_: Throwable) { JSONObject() }

        if (obj.has("locked")) existing.put("locked", obj.optBoolean("locked"))
        if (obj.optBoolean("resetTtl", false)) {
            // Re-apply the per-kind default TTL the launcher uses for fresh
            // captures. Matches Settings → Clippings TTL behaviour.
            val defaultTtl = LauncherPrefs(context).clippingTtlMs(meta.kind)
            val newExpiry = if (defaultTtl <= 0L) 0L else System.currentTimeMillis() + defaultTtl
            existing.put("expiresAt", newExpiry)
        }
        try { metaFile.writeText(existing.toString(), Charsets.UTF_8) }
        catch (_: Throwable) { return JsonResponse.error(ex, 500, "write failed") }
        broadcastChanged()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    private data class Meta(
        val title: String, val kind: String, val createdAt: Long,
        val expiresAt: Long, val locked: Boolean,
        val sourceHost: String?, val sourceUrl: String?,
    )

    private fun readMeta(widgetId: String): Meta? {
        val f = File(context.filesDir, "widgets/$widgetId/meta.json")
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            Meta(
                title = j.optString("title").ifBlank { "Clipping" },
                kind = j.optString("kind").ifBlank { "article" }.lowercase(),
                createdAt = j.optLong("createdAt", 0L),
                expiresAt = j.optLong("expiresAt", 0L),
                locked = j.optBoolean("locked", false),
                sourceHost = j.optString("sourceHost").ifBlank { null },
                sourceUrl = j.optString("sourceUrl").ifBlank { null },
            )
        } catch (_: Throwable) { null }
    }

    private fun broadcastChanged() {
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
    }
}
