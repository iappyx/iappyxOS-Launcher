/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — full state snapshot for initial browser load.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.notify.BadgeStore
import com.iappyx.launcher.remoteedit.extensions.toBrowserJson
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.widget.WidgetLibrary
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject

class StateApi(private val context: Context) {

    fun getState(ex: MicroHttpServer.Exchange) {
        val store = PlacementStore(context)
        val layout = store.load()
        val widgets = WidgetLibrary.all(context)
        // Visual prefs that affect how the editor's home tab renders.
        // Kept in a `viewPrefs` sub-object so future additions don't
        // bloat the top-level state shape.
        val prefs = LauncherPrefs(context)

        val obj = JSONObject().apply {
            put("layout", layout.toBrowserJson())
            put("widgets", org.json.JSONArray().apply {
                for (w in widgets) put(JSONObject().apply {
                    put("id", w.id)
                    put("title", w.title)
                    put("subtitle", w.subtitle)
                    put("isUserGenerated", w.isUserGenerated)
                    put("inUse", w.isInUse)
                })
            })
            put("appsCount", AppsApi.cachedCount(context))
            // Notification badge counts as `{pkg: count}`. Only
            // populated when the launcher's `notificationBadgesEnabled`
            // pref is on; otherwise empty. The editor renders the
            // red number-pill on icon cells from this map.
            put("badgeCounts", JSONObject().apply {
                if (prefs.notificationBadgesEnabled) {
                    for ((pkg, count) in BadgeStore.snapshot()) {
                        if (count > 0) put(pkg, count)
                    }
                }
            })
            put("viewPrefs", JSONObject().apply {
                put("showDockLabels", prefs.showDockLabels)
                put("activeWallpaperId", prefs.activeWallpaperId)
                put("iconFilter", prefs.iconFilter)
                put("dominantOrientation", prefs.dominantOrientation)
                // Real device screen dimensions in pixels, so the
                // editor's phone-frame can adopt the actual
                // aspect ratio of the phone instead of a hard-
                // coded 9:19.5. Reported as the launcher's
                // dominant-orientation rectangle (portrait → tall,
                // landscape → wide) so the editor always shows the
                // home grid in the orientation the user designed.
                val dm = context.resources.displayMetrics
                val isPortraitDominant = prefs.dominantOrientation == "portrait"
                val portraitW = minOf(dm.widthPixels, dm.heightPixels)
                val portraitH = maxOf(dm.widthPixels, dm.heightPixels)
                put("screenWidth", if (isPortraitDominant) portraitW else portraitH)
                put("screenHeight", if (isPortraitDominant) portraitH else portraitW)
                put("density", dm.density.toDouble())
            })
        }
        JsonResponse.ok(ex, obj)
    }
}
