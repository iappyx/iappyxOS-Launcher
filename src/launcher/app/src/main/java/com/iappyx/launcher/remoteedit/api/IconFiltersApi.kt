/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — icon-filters library management. List, set
 * active, rename, edit description, delete (user-generated only).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IconFiltersApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val active = LauncherPrefs(context).iconFilter
        val arr = JSONArray()
        for (e in IconFilterRegistry.all(context)) {
            arr.put(JSONObject().apply {
                put("slug", e.slug)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("isUserGenerated", e.isUserGenerated)
                put("active", e.slug == active)
                put("createdAt", e.createdAt)
            })
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("iconFilters", arr)
            put("activeSlug", active)
        })
    }

    fun setActive(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val slug = obj.optString("slug")
        if (slug.isBlank()) return JsonResponse.error(ex, 400, "no slug")
        val known = IconFilterRegistry.all(context).any { it.slug == slug }
        if (!known) return JsonResponse.error(ex, 404, "no such icon filter")
        LauncherPrefs(context).iconFilter = slug
        // Cache-clears the live launcher needs to re-paint icons with
        // the new filter — mirrors the on-device picker flow. Also
        // invalidate the editor's icon-PNG cache so /api/icons/{pkg}
        // serves freshly-filtered bytes on next request.
        IconMask.clearCache()
        IconFilterRegistry.invalidateAll()
        IconApi.invalidate()
        broadcastLayout()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun rename(ex: MicroHttpServer.Exchange, slug: String) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val title = obj.optString("title").trim()
        if (title.isEmpty()) return JsonResponse.error(ex, 400, "title required")
        if (!IconFilterRegistry.renameUser(context, slug, title)) {
            return JsonResponse.error(ex, 400, "rename refused (bundled or missing)")
        }
        IconFilterRegistry.invalidate(slug)
        IconMask.clearCache()
        IconApi.invalidate()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Edit the stored prompt/description on the meta.json. IconFilterRegistry
     *  doesn't ship a dedicated updatePrompt helper (unlike WidgetLibrary /
     *  TransitionLibrary), so we patch meta.json directly. Bundled slugs are
     *  refused — same independence rule as the launcher's own update paths. */
    fun updateDescription(ex: MicroHttpServer.Exchange, slug: String) {
        if (!IconFilterRegistry.isUserGenerated(slug)) {
            return JsonResponse.error(ex, 400, "bundled filters can't be edited")
        }
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val prompt = obj.optString("description", obj.optString("prompt")).trim()
        val metaFile = File(IconFilterRegistry.userDir(context), "$slug/meta.json")
        if (!metaFile.exists()) return JsonResponse.error(ex, 404, "no meta")
        try {
            val o = JSONObject(metaFile.readText())
            o.put("prompt", prompt)
            metaFile.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
            return JsonResponse.error(ex, 500, "write failed")
        }
        IconFilterRegistry.invalidate(slug)
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun delete(ex: MicroHttpServer.Exchange, slug: String) {
        // Refuse to delete the active filter — the user would see icons
        // fall back to "none" with no obvious recovery path. Match the
        // on-device manage-tab behaviour.
        val active = LauncherPrefs(context).iconFilter
        if (slug == active) return JsonResponse.error(ex, 400, "can't delete the active filter")
        if (!IconFilterRegistry.delete(context, slug)) {
            return JsonResponse.error(ex, 400, "delete refused (bundled or missing)")
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
