/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — read-only helpers for serializing existing
 * launcher data into a browser-friendly shape.
 */
package com.iappyx.launcher.remoteedit.extensions

import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Placement
import org.json.JSONArray
import org.json.JSONObject

/** Browser-facing layout snapshot. Distinct from the on-disk JSON
 *  so we can shape it for direct rendering (cell labels resolved,
 *  page indices flattened, dock pages combined into one for v1). */
fun HomeLayout.toBrowserJson(): JSONObject {
    return JSONObject().apply {
        put("cols", cols)
        put("rows", rows)
        put("dockSlots", dockSlots)
        put("pages", JSONArray().apply {
            for ((idx, page) in pages.withIndex()) {
                put(JSONObject().apply {
                    put("index", idx)
                    // Empty string when the user hasn't named the page;
                    // the editor renders the index ("Page N") in that case.
                    put("name", page.name)
                    put("placements", JSONArray().apply {
                        for (p in page.placements) put(p.toBrowserJson())
                    })
                })
            }
        })
        // Backward-compat: `dock` is the first dock page. New editor
        // builds prefer the full `dockPages` array below; legacy
        // builds reading `dock` keep working unchanged.
        put("dock", JSONArray().apply {
            val activeDock = dockPages.firstOrNull() ?: emptyList<Placement>()
            for (p in activeDock) put(p.toBrowserJson())
        })
        // Full multi-page dock — one JSONArray of placements per page.
        // The on-device pager swipes between these; the editor mirrors
        // that with a small page indicator below the dock strip.
        put("dockPages", JSONArray().apply {
            for (dp in dockPages) {
                put(JSONArray().apply {
                    for (p in dp) put(p.toBrowserJson())
                })
            }
        })
    }
}

fun Placement.toBrowserJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("type", type.name)
    put("row", row); put("col", col)
    put("w", wSpan); put("h", hSpan)
    packageName?.let { put("pkg", it) }
    activityName?.let { put("activity", it) }
    appWidgetId?.let { put("appWidgetId", it) }
    generatedWidgetId?.let { put("widgetId", it) }
    generatedWidgetAsset?.let { put("widgetAsset", it) }
    folderName?.let { put("folderName", it) }
    if (folderItems.isNotEmpty()) {
        put("folderItems", JSONArray().apply {
            for (f in folderItems) put(JSONObject().apply {
                put("pkg", f.packageName)
                f.activityName?.let { put("activity", it) }
            })
        })
    }
}
