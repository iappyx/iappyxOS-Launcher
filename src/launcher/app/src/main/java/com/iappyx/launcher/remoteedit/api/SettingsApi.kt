/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — settings tab. One get / one patch endpoint
 * surfaces every LauncherPrefs toggle, plus credentials (masked) and
 * grid dimensions. Credentials are written via a separate endpoint
 * that takes the cleartext value (so simple PATCH requests can't
 * accidentally null a key by sending the masked stub back).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.ai.AiException
import com.iappyx.launcher.ai.ModelCatalog
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.transitions.TransitionLibrary
import org.json.JSONArray
import org.json.JSONObject

class SettingsApi(private val context: Context) {

    fun get(ex: MicroHttpServer.Exchange) {
        val prefs = LauncherPrefs(context)
        val secure = SecureStore(context)
        val layout = PlacementStore(context).load()

        val resp = JSONObject().apply {
            // Grid dimensions
            put("cols", layout.cols)
            put("rows", layout.rows)
            put("dockSlots", layout.dockSlots)
            // Toggles
            put("showDockLabels", prefs.showDockLabels)
            put("notificationBadgesEnabled", prefs.notificationBadgesEnabled)
            put("allowRotation", prefs.allowRotation)
            put("useLongPressMenu", prefs.useLongPressMenu)
            put("dominantOrientation", prefs.dominantOrientation)
            // Pickers (kept here so the Settings tab can show the current
            // selection; the dedicated picker tabs / sections still own
            // the lists themselves).
            put("pageTransitionStyle", prefs.pageTransitionStyle)
            put("iconFilter", prefs.iconFilter)
            put("activeWallpaperId", prefs.activeWallpaperId)
            // Credentials — never returned in cleartext. `set` tells the
            // UI whether to render "currently set" vs "not set"; `masked`
            // shows the last 4 characters so the user has a visual
            // confirmation when they re-paste the same key.
            put("credentials", JSONObject().apply {
                put("ai", credentialField(secure.anthropicKey))
                put("github", credentialField(secure.githubToken))
            })
            // AI model selection — separate from credentials because the
            // value is non-sensitive and the dropdown needs the full list
            // to render. Models list is cache-only here (no network call
            // on every settings GET); the web hits /api/settings/refresh_models
            // when it wants a fresh fetch.
            put("anthropicModel", secure.anthropicModel)
            put("iterateModel", secure.iterateModel)
            put("models", listCachedModels())
            put("modelsCached", ModelCatalog.hasCached(context))
            // Picker option lists for in-tab dropdowns.
            put("transitions", listTransitions())
            put("iconFilters", listIconFilters())
        }
        JsonResponse.ok(ex, resp)
    }

    /** POST /api/settings/refresh_models — force-refetch the Anthropic
     *  model catalog. Requires an API key on file. Synchronous: the
     *  response carries the new list so the web client can repopulate
     *  immediately. */
    fun refreshModels(ex: MicroHttpServer.Exchange) {
        val key = SecureStore(context).anthropicKey?.takeIf { it.isNotBlank() }
            ?: return JsonResponse.error(ex, 400, "no API key on file")
        try {
            ModelCatalog.fetchOrCached(context, key, force = true)
        } catch (e: AiException) {
            return JsonResponse.error(ex, 502, e.message ?: "refresh failed")
        } catch (t: Throwable) {
            return JsonResponse.error(ex, 502, t.message ?: "refresh failed")
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("models", listCachedModels())
        })
    }

    /** Read-only accessor that returns the in-memory / disk-cached model
     *  list without ever hitting the network. Used by [get] so settings
     *  loads in <50 ms even when the catalog is cold. */
    private fun listCachedModels(): JSONArray {
        if (!ModelCatalog.hasCached(context)) return JSONArray()
        // hasCached==true guarantees fetchOrCached returns without
        // network (mem cache or fresh disk read).
        val key = SecureStore(context).anthropicKey ?: return JSONArray()
        return try {
            val arr = JSONArray()
            for (m in ModelCatalog.fetchOrCached(context, key, force = false)) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("displayName", m.displayName)
                })
            }
            arr
        } catch (_: Throwable) { JSONArray() }
    }

    fun patch(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val prefs = LauncherPrefs(context)
        var layoutChanged = false
        var iconFiltersChanged = false

        // Grid dimensions — go through PlacementStore so the layout file
        // stays in sync. Existing placements outside the new bounds are
        // dropped silently; this matches the on-device "Apply" flow.
        if (obj.has("cols") || obj.has("rows") || obj.has("dockSlots")) {
            val store = PlacementStore(context)
            val current = store.load()
            val newCols = obj.optInt("cols", current.cols).coerceIn(2, 12)
            val newRows = obj.optInt("rows", current.rows).coerceIn(2, 12)
            val newDock = obj.optInt("dockSlots", current.dockSlots).coerceIn(0, 12)
            if (newCols != current.cols || newRows != current.rows || newDock != current.dockSlots) {
                val resized = current.copy(
                    cols = newCols, rows = newRows, dockSlots = newDock,
                )
                // Drop any placement that no longer fits (its bounding box
                // would extend past the new grid).
                for (page in resized.pages) {
                    page.placements.removeAll { p ->
                        p.row + p.hSpan > newRows || p.col + p.wSpan > newCols
                    }
                }
                for (dock in resized.dockPages) {
                    dock.removeAll { it.col >= newDock }
                }
                store.save(resized)
                layoutChanged = true
            }
        }

        // Toggles
        if (obj.has("showDockLabels")) prefs.showDockLabels = obj.optBoolean("showDockLabels")
        if (obj.has("notificationBadgesEnabled"))
            prefs.notificationBadgesEnabled = obj.optBoolean("notificationBadgesEnabled")
        if (obj.has("allowRotation")) prefs.allowRotation = obj.optBoolean("allowRotation")
        if (obj.has("useLongPressMenu")) prefs.useLongPressMenu = obj.optBoolean("useLongPressMenu")
        if (obj.has("dominantOrientation")) {
            val v = obj.optString("dominantOrientation")
            if (v == "portrait" || v == "landscape") prefs.dominantOrientation = v
        }

        // Pickers
        if (obj.has("pageTransitionStyle")) {
            prefs.pageTransitionStyle = obj.optString("pageTransitionStyle")
            layoutChanged = true
        }
        if (obj.has("iconFilter")) {
            prefs.iconFilter = obj.optString("iconFilter")
            IconMask.clearCache()
            IconFilterRegistry.invalidateAll()
            IconApi.invalidate()
            iconFiltersChanged = true
        }
        if (obj.has("activeWallpaperId")) {
            prefs.activeWallpaperId = obj.optString("activeWallpaperId")
            broadcastWallpaper(prefs.activeWallpaperId)
        }

        // AI model selection — non-empty string only (empty would
        // shadow the SecureStore default to "" and break generation).
        val secureForPatch = SecureStore(context)
        obj.optString("anthropicModel").takeIf { it.isNotBlank() }
            ?.let { secureForPatch.anthropicModel = it }
        obj.optString("iterateModel").takeIf { it.isNotBlank() }
            ?.let { secureForPatch.iterateModel = it }

        if (layoutChanged) broadcastLayout()
        if (iconFiltersChanged) broadcastLayout()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun setCredential(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val kind = obj.optString("kind")
        val raw = obj.optString("value", "")
        val store = SecureStore(context)
        when (kind) {
            "ai" -> store.anthropicKey = raw.ifBlank { null }
            "github" -> store.githubToken = raw.ifBlank { null }
            else -> return JsonResponse.error(ex, 400, "unknown credential kind '$kind'")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    private fun credentialField(value: String?): JSONObject = JSONObject().apply {
        if (value.isNullOrBlank()) {
            put("set", false)
            put("masked", JSONObject.NULL)
        } else {
            put("set", true)
            // Last 4 chars as the user-visible tail; everything before is
            // a fixed-length bullet run so the masked length doesn't leak
            // the full key length.
            val tail = value.takeLast(4)
            put("masked", "••••${tail}")
        }
    }

    private fun listTransitions(): JSONArray {
        val arr = JSONArray()
        for (e in TransitionLibrary.all(context)) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("isUserGenerated", e.isUserGenerated)
            })
        }
        return arr
    }

    private fun listIconFilters(): JSONArray {
        val arr = JSONArray()
        for (e in IconFilterRegistry.all(context)) {
            arr.put(JSONObject().apply {
                put("slug", e.slug)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("isUserGenerated", e.isUserGenerated)
            })
        }
        return arr
    }

    private fun broadcastLayout() {
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
    }

    private fun broadcastWallpaper(id: String) {
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
                    .setPackage(context.packageName)
                    .putExtra("id", id),
            )
        } catch (_: Throwable) {}
    }
}
