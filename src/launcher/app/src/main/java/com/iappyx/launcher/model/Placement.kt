/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CellType {
    ICON,
    STOCK_WIDGET,
    GENERATED_WIDGET,
    FOLDER,
    /** Built-in 1×1 cell that opens the app drawer when tapped. No
     *  package/activity needed — the launcher renders a "9-dot" glyph and
     *  hooks the click to its showAppDrawer flow. Lives on home pages OR
     *  dock slots. */
    APP_DRAWER,
}

/** One app inside a folder. Mirrors the package/activity fields of an icon Placement. */
data class FolderItem(val packageName: String, val activityName: String?) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pkg", packageName)
        activityName?.let { put("activity", it) }
    }
    companion object {
        fun fromJson(o: JSONObject): FolderItem = FolderItem(
            packageName = o.getString("pkg"),
            activityName = if (o.has("activity")) o.getString("activity") else null,
        )
    }
}

/**
 * A single cell on a home page. Position is (row, col), size is (wSpan, hSpan).
 * Type-specific fields hold the reference needed to render that kind of cell.
 */
data class Placement(
    val id: String,
    val type: CellType,
    val row: Int,
    val col: Int,
    val wSpan: Int,
    val hSpan: Int,
    val packageName: String? = null,
    val activityName: String? = null,
    val appWidgetId: Int? = null,
    val generatedWidgetId: String? = null,
    val generatedWidgetAsset: String? = null,
    val folderName: String? = null,
    val folderItems: MutableList<FolderItem> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("row", row); put("col", col); put("w", wSpan); put("h", hSpan)
        packageName?.let { put("pkg", it) }
        activityName?.let { put("activity", it) }
        appWidgetId?.let { put("appWidgetId", it) }
        generatedWidgetId?.let { put("genId", it) }
        generatedWidgetAsset?.let { put("genAsset", it) }
        folderName?.let { put("folderName", it) }
        if (folderItems.isNotEmpty()) {
            put("folderItems", JSONArray().apply { folderItems.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun newId(): String = "p_" + UUID.randomUUID().toString().substring(0, 12)

        /** Returns null if the JSON is missing required fields (type, row, col,
         *  w, h) or names a CellType that no longer exists in this build —
         *  callers should skip that entry rather than fail the whole load. */
        fun fromJson(o: JSONObject): Placement? {
            val typeStr = o.optString("type", "")
            val type = CellType.values().firstOrNull { it.name == typeStr }
            if (type == null) {
                android.util.Log.w("iappyxLauncher", "skipping placement with unknown type='$typeStr'")
                return null
            }
            if (!o.has("row") || !o.has("col") || !o.has("w") || !o.has("h")) {
                android.util.Log.w("iappyxLauncher", "skipping placement missing row/col/w/h: $o")
                return null
            }
            val items = mutableListOf<FolderItem>()
            val arr = o.optJSONArray("folderItems")
            if (arr != null) for (i in 0 until arr.length()) {
                try { items.add(FolderItem.fromJson(arr.getJSONObject(i))) }
                catch (e: Exception) {
                    android.util.Log.w("iappyxLauncher", "skipping folder item: ${e.message}")
                }
            }
            return Placement(
                id = o.optString("id", newId()),
                type = type,
                row = o.getInt("row"), col = o.getInt("col"),
                wSpan = o.getInt("w"), hSpan = o.getInt("h"),
                packageName = if (o.has("pkg")) o.getString("pkg") else null,
                activityName = if (o.has("activity")) o.getString("activity") else null,
                appWidgetId = if (o.has("appWidgetId")) o.getInt("appWidgetId") else null,
                generatedWidgetId = if (o.has("genId")) o.getString("genId") else null,
                generatedWidgetAsset = if (o.has("genAsset")) o.getString("genAsset") else null,
                folderName = if (o.has("folderName")) o.getString("folderName") else null,
                folderItems = items,
            )
        }
    }
}

data class Page(
    val placements: MutableList<Placement> = mutableListOf(),
    /** Optional user-given label for this page (e.g. "Work", "Reading").
     *  Empty string = unnamed, fall back to "Page N" in the UI. */
    var name: String = "",
) {
    /** Serializes as a plain JSONArray of placements when the page has
     *  no name (backward compat with launcher builds that read pages as
     *  arrays), or as `{"name": "...", "placements": [...]}` once the
     *  user has named it. Either shape parses cleanly via [fromJsonAny]. */
    fun toJsonAny(): Any =
        if (name.isBlank()) JSONArray().apply { placements.forEach { put(it.toJson()) } }
        else JSONObject().apply {
            put("name", name)
            put("placements", JSONArray().apply { placements.forEach { put(it.toJson()) } })
        }

    fun toJson(): JSONArray = JSONArray().apply { placements.forEach { put(it.toJson()) } }

    companion object {
        fun fromJson(a: JSONArray): Page {
            val list = mutableListOf<Placement>()
            for (i in 0 until a.length()) {
                Placement.fromJson(a.getJSONObject(i))?.let { list.add(it) }
            }
            return Page(list)
        }
        /** Accept legacy JSONArray-shaped pages and the new
         *  JSONObject-shaped pages with an optional `name` field. */
        fun fromJsonAny(any: Any): Page = when (any) {
            is JSONArray -> fromJson(any)
            is JSONObject -> {
                val name = any.optString("name", "")
                val arr = any.optJSONArray("placements") ?: JSONArray()
                fromJson(arr).copy(name = name)
            }
            else -> Page()
        }
    }
}

/** Dock — icon-only quick launch bar at the bottom. Swipeable: each dock page
 *  is a list of slot-addressed placements (slot stored in `col`). The number
 *  of slots per dock page is [HomeLayout.dockSlots] (configurable). */

data class HomeLayout(
    val cols: Int = 4,
    val rows: Int = 5,
    val dockSlots: Int = 5,
    val pages: MutableList<Page> = mutableListOf(),
    val dockPages: MutableList<MutableList<Placement>> = mutableListOf(mutableListOf()),
    /** Clippings = share-to-launcher captures pinned on the rightmost pager
     *  page. Newest-first ordering. Not part of the grid model — a Clipping
     *  is a pointer into the widgets folder, not a positioned cell. */
    val clippings: MutableList<Clipping> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cols", cols); put("rows", rows); put("dockSlots", dockSlots)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJsonAny()) } })
        put("dockPages", JSONArray().apply {
            dockPages.forEach { dockPage ->
                put(JSONArray().apply { dockPage.forEach { put(it.toJson()) } })
            }
        })
        put("clippings", JSONArray().apply { clippings.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): HomeLayout {
            val cols = o.optInt("cols", 4)
            val rows = o.optInt("rows", 5)
            val dockSlots = o.optInt("dockSlots", 5)
            val pagesArray = o.optJSONArray("pages") ?: JSONArray()
            val list = mutableListOf<Page>()
            for (i in 0 until pagesArray.length()) {
                // Accept legacy JSONArray pages AND named JSONObject pages
                // — pages saved by older builds were bare arrays, the new
                // shape adds an optional `name` wrapper.
                list.add(Page.fromJsonAny(pagesArray.get(i)))
            }

            val dockPages = mutableListOf<MutableList<Placement>>()
            val dockPagesArray = o.optJSONArray("dockPages")
            if (dockPagesArray != null) {
                for (i in 0 until dockPagesArray.length()) {
                    val page = mutableListOf<Placement>()
                    val pageArr = dockPagesArray.getJSONArray(i)
                    for (j in 0 until pageArr.length()) {
                        Placement.fromJson(pageArr.getJSONObject(j))?.let { page.add(it) }
                    }
                    dockPages.add(page)
                }
            } else {
                // Backward compat: migrate flat `dock` array into a single dock page
                val dockArray = o.optJSONArray("dock") ?: JSONArray()
                val dockList = mutableListOf<Placement>()
                for (i in 0 until dockArray.length()) {
                    Placement.fromJson(dockArray.getJSONObject(i))?.let { dockList.add(it) }
                }
                dockPages.add(dockList)
            }
            if (dockPages.isEmpty()) dockPages.add(mutableListOf())
            val clippings = mutableListOf<Clipping>()
            val clipArr = o.optJSONArray("clippings")
            if (clipArr != null) {
                for (i in 0 until clipArr.length()) {
                    Clipping.fromJson(clipArr.getJSONObject(i))?.let { clippings.add(it) }
                }
            }
            return HomeLayout(cols, rows, dockSlots, list, dockPages, clippings)
        }

        /**
         * Build a default layout for this device. Picks grid/dock sizes from the
         * device's shortest screen dimension (so portrait/landscape agree), using
         * reasonable Microsoft-Launcher-ish defaults:
         *
         *   < 360dp → 4×5 cols/rows, dock 4   (small phone)
         *   360–420 → 5×6, dock 5             (typical phone)
         *   420–600 → 5×7, dock 5             (large phone)
         *   >= 600  → 6×8, dock 6             (tablet)
         */
        fun defaultLayout(context: android.content.Context): HomeLayout {
            val cfg = context.resources.configuration
            val shortestDp = cfg.smallestScreenWidthDp
            val (cols, rows, dock) = when {
                shortestDp < 360 -> Triple(4, 5, 4)
                shortestDp < 420 -> Triple(5, 6, 5)
                shortestDp < 600 -> Triple(5, 7, 5)
                else             -> Triple(6, 8, 6)
            }
            return HomeLayout(
                cols = cols, rows = rows, dockSlots = dock,
                pages = mutableListOf(
                    Page(mutableListOf(
                        Placement(
                            id = Placement.newId(),
                            type = CellType.GENERATED_WIDGET,
                            row = 0, col = 0, wSpan = 2, hSpan = 2,
                            generatedWidgetId = "clock",
                            generatedWidgetAsset = "widgets/clock.html",
                        ),
                    )),
                    Page(),
                ),
            )
        }
    }
}
