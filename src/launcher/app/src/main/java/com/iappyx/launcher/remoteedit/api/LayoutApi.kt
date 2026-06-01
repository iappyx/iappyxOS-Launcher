/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — layout CRUD endpoints. All mutations go through
 * PlacementStore.save() and broadcast CLIPPINGS_CHANGED so the live
 * launcher picks up changes.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import android.content.Intent
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Page
import com.iappyx.launcher.model.Placement
import com.iappyx.launcher.remoteedit.extensions.toBrowserJson
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject

class LayoutApi(private val context: Context) {

    private val store: PlacementStore get() = PlacementStore(context)

    /** Server-side undo stack. Each successful mutation pushes the
     *  pre-mutation snapshot here; [undo] pops one and writes it back.
     *  Lives on the LayoutApi instance — survives across HTTP requests
     *  for as long as the EditServer is up. Capped at [UNDO_MAX] to
     *  bound memory. */
    private val undoStack = ArrayDeque<HomeLayout>()
    private val UNDO_MAX = 30

    fun getLayout(ex: MicroHttpServer.Exchange) {
        JsonResponse.ok(ex, store.load().toBrowserJson())
    }

    /** Pop the most recent pre-mutation snapshot and write it back as
     *  the current layout. Returns 400 if the stack is empty. */
    fun undo(ex: MicroHttpServer.Exchange) {
        val snapshot = undoStack.removeLastOrNull()
        if (snapshot == null) {
            JsonResponse.error(ex, 400, "nothing to undo")
            return
        }
        store.save(snapshot)
        broadcastChanged()
        val resp = JSONObject()
        resp.put("ok", true)
        resp.put("layout", snapshot.toBrowserJson())
        resp.put("undoDepth", undoStack.size)
        JsonResponse.ok(ex, resp)
    }

    fun move(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val targetPage = obj.optInt("page", -1)
        val row = obj.optInt("row", -1)
        val col = obj.optInt("col", -1)
        if (id.isBlank() || targetPage < 0 || row < 0 || col < 0) return@withMutate badRequest("missing id/page/row/col")
        val (sourcePageIdx, placement) = findPlacement(layout, id) ?: return@withMutate badRequest("no such cell")
        if (targetPage >= layout.pages.size) return@withMutate badRequest("page out of range")
        val updated = placement.copy(row = row, col = col)
        if (!fitsOnPage(layout.pages[targetPage], updated, ignoreId = id)) return@withMutate badRequest("target occupied")
        if (sourcePageIdx == targetPage) {
            val list = layout.pages[targetPage].placements
            list.removeAll { it.id == id }
            list.add(updated)
        } else {
            layout.pages[sourcePageIdx].placements.removeAll { it.id == id }
            layout.pages[targetPage].placements.add(updated)
        }
        ok(layout)
    }

    fun resize(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val w = obj.optInt("w", -1)
        val h = obj.optInt("h", -1)
        if (id.isBlank() || w < 1 || h < 1) return@withMutate badRequest("bad args")
        val (pageIdx, placement) = findPlacement(layout, id) ?: return@withMutate badRequest("no such cell")
        val updated = placement.copy(wSpan = w, hSpan = h)
        if (!fitsOnPage(layout.pages[pageIdx], updated, ignoreId = id)) return@withMutate badRequest("would overlap")
        if (placement.row + h > layout.rows || placement.col + w > layout.cols)
            return@withMutate badRequest("out of grid")
        val list = layout.pages[pageIdx].placements
        list.removeAll { it.id == id }
        list.add(updated)
        ok(layout)
    }

    /** Bulk-delete N placements as a single transaction. Produces ONE
     *  undo snapshot for the whole batch — Cmd-Z restores everything
     *  the user just deleted, not just the last one. */
    fun deleteMany(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val arr = obj.optJSONArray("ids") ?: return@withMutate badRequest("no ids")
        if (arr.length() == 0) return@withMutate badRequest("empty ids")
        var anyRemoved = false
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isBlank()) continue
            for (page in layout.pages) {
                if (page.placements.removeAll { it.id == id }) anyRemoved = true
            }
            for (dock in layout.dockPages) {
                if (dock.removeAll { it.id == id }) anyRemoved = true
            }
        }
        if (!anyRemoved) return@withMutate badRequest("no matching cells")
        ok(layout)
    }

    fun delete(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        if (id.isBlank()) return@withMutate badRequest("no id")
        var removed = false
        for (page in layout.pages) {
            if (page.placements.removeAll { it.id == id }) removed = true
        }
        for (dock in layout.dockPages) {
            if (dock.removeAll { it.id == id }) removed = true
        }
        if (!removed) return@withMutate badRequest("no such cell")
        ok(layout)
    }

    fun placeApp(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val pkg = obj.optString("pkg").ifBlank { return@withMutate badRequest("no pkg") }
        val activity = obj.optString("activity").ifBlank { null }
        val pageIdx = obj.optInt("page", -1)
        var row = obj.optInt("row", -1)
        var col = obj.optInt("col", -1)
        val targetPage = if (pageIdx in layout.pages.indices) pageIdx else 0
        if (row < 0 || col < 0) {
            val spot = findFirstEmptyCell(layout, targetPage)
                ?: return@withMutate badRequest("no empty cell")
            row = spot.first; col = spot.second
        }
        val newP = Placement(
            id = Placement.newId(),
            type = CellType.ICON,
            row = row, col = col, wSpan = 1, hSpan = 1,
            packageName = pkg, activityName = activity,
        )
        if (!fitsOnPage(layout.pages[targetPage], newP, ignoreId = newP.id))
            return@withMutate badRequest("target occupied")
        layout.pages[targetPage].placements.add(newP)
        ok(layout, extra = JSONObject().put("id", newP.id))
    }

    fun placeWidget(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val widgetId = obj.optString("widgetId").ifBlank { return@withMutate badRequest("no widgetId") }
        val asset = obj.optString("widgetAsset").ifBlank { null }
        val w = obj.optInt("w", 2).coerceAtLeast(1)
        val h = obj.optInt("h", 2).coerceAtLeast(1)
        val pageIdx = obj.optInt("page", -1).let { if (it in layout.pages.indices) it else 0 }
        var row = obj.optInt("row", -1)
        var col = obj.optInt("col", -1)
        if (row < 0 || col < 0) {
            val spot = findEmptyRect(layout, pageIdx, w, h)
                ?: return@withMutate badRequest("no $w×$h spot free")
            row = spot.first; col = spot.second
        }
        val newP = Placement(
            id = Placement.newId(),
            type = CellType.GENERATED_WIDGET,
            row = row, col = col, wSpan = w, hSpan = h,
            generatedWidgetId = widgetId,
            generatedWidgetAsset = asset,
        )
        if (!fitsOnPage(layout.pages[pageIdx], newP, ignoreId = newP.id))
            return@withMutate badRequest("target occupied")
        layout.pages[pageIdx].placements.add(newP)
        ok(layout, extra = JSONObject().put("id", newP.id))
    }

    fun swap(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val a = obj.optString("a"); val b = obj.optString("b")
        if (a.isBlank() || b.isBlank() || a == b) return@withMutate badRequest("bad args")
        val (pa, pA) = findPlacement(layout, a) ?: return@withMutate badRequest("no a")
        val (pb, pB) = findPlacement(layout, b) ?: return@withMutate badRequest("no b")
        val newA = pA.copy(row = pB.row, col = pB.col)
        val newB = pB.copy(row = pA.row, col = pA.col)
        layout.pages[pa].placements.removeAll { it.id == a }
        layout.pages[pb].placements.removeAll { it.id == b }
        layout.pages[pa].placements.add(if (pa == pb) newA else newA)
        layout.pages[pb].placements.add(newB)
        if (pa == pb) {
            // Re-add A on the same page (already done above? defensive)
        }
        ok(layout)
    }

    fun pageAdd(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        layout.pages.add(Page())
        ok(layout)
    }

    fun pageDelete(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val idx = obj.optInt("idx", -1)
        if (idx < 0 || idx >= layout.pages.size) return@withMutate badRequest("bad idx")
        if (layout.pages.size == 1) return@withMutate badRequest("can't delete last page")
        if (layout.pages[idx].placements.isNotEmpty() && !obj.optBoolean("force"))
            return@withMutate badRequest("page non-empty (pass force:true)")
        layout.pages.removeAt(idx)
        ok(layout)
    }

    /** Rename a single page. Empty string = clear the name (UI falls
     *  back to "Page N"). 40-char cap matches folder-name policy. */
    fun pageRename(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val idx = obj.optInt("idx", -1)
        if (idx < 0 || idx >= layout.pages.size) return@withMutate badRequest("bad idx")
        val raw = obj.optString("name", "").trim().take(40)
        layout.pages[idx].name = raw
        ok(layout)
    }

    /** Reorder pages by absolute permutation. Body: `{order: [0,2,1,...]}`
     *  giving the new sequence of original indices. Must include each
     *  index exactly once. */
    fun pageReorder(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val arr = obj.optJSONArray("order") ?: return@withMutate badRequest("no order array")
        val n = layout.pages.size
        if (arr.length() != n) return@withMutate badRequest("order length != page count")
        val seen = BooleanArray(n)
        val newOrder = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val idx = arr.optInt(i, -1)
            if (idx < 0 || idx >= n || seen[idx]) {
                return@withMutate badRequest("bad order[$i] = $idx")
            }
            seen[idx] = true
            newOrder.add(idx)
        }
        val reordered = newOrder.map { layout.pages[it] }
        layout.pages.clear()
        layout.pages.addAll(reordered)
        ok(layout)
    }

    /** Drop a fresh, optionally-named, optionally-pre-populated FOLDER
     *  cell onto the home grid. Positions like placeApp: row/col omitted
     *  → first empty cell on the chosen page. Apps in the optional `apps`
     *  array are added to folderItems on creation; the array is ignored
     *  if the package name lookup fails (no error — silent skip matches
     *  the on-device "drag app onto folder" behaviour). */
    fun folderCreate(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val name = obj.optString("name").trim().take(40).ifBlank { null }
        val pageIdx = obj.optInt("page", -1).let { if (it in layout.pages.indices) it else 0 }
        var row = obj.optInt("row", -1)
        var col = obj.optInt("col", -1)
        if (row < 0 || col < 0) {
            val spot = findFirstEmptyCell(layout, pageIdx)
                ?: return@withMutate badRequest("no empty cell")
            row = spot.first; col = spot.second
        }
        val items = mutableListOf<com.iappyx.launcher.model.FolderItem>()
        obj.optJSONArray("apps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).trim()
                if (pkg.isNotEmpty() && items.none { it.packageName == pkg }) {
                    items.add(com.iappyx.launcher.model.FolderItem(pkg, null))
                }
            }
        }
        val newP = Placement(
            id = Placement.newId(),
            type = CellType.FOLDER,
            row = row, col = col, wSpan = 1, hSpan = 1,
            folderName = name,
            folderItems = items,
        )
        if (!fitsOnPage(layout.pages[pageIdx], newP, ignoreId = newP.id))
            return@withMutate badRequest("target occupied")
        layout.pages[pageIdx].placements.add(newP)
        ok(layout, extra = JSONObject().put("id", newP.id))
    }

    fun folderAdd(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val pkg = obj.optString("pkg")
        if (id.isBlank() || pkg.isBlank()) return@withMutate badRequest("bad args")
        val (pageIdx, p) = findPlacement(layout, id) ?: return@withMutate badRequest("no such cell")
        if (p.type != CellType.FOLDER) return@withMutate badRequest("not a folder")
        if (p.folderItems.any { it.packageName == pkg }) return@withMutate badRequest("already in folder")
        p.folderItems.add(com.iappyx.launcher.model.FolderItem(pkg, null))
        layout.pages[pageIdx].placements.removeAll { it.id == id }
        layout.pages[pageIdx].placements.add(p)
        ok(layout)
    }

    fun folderRemove(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val pkg = obj.optString("pkg")
        if (id.isBlank() || pkg.isBlank()) return@withMutate badRequest("bad args")
        val (pageIdx, p) = findPlacement(layout, id) ?: return@withMutate badRequest("no such cell")
        if (p.type != CellType.FOLDER) return@withMutate badRequest("not a folder")
        if (!p.folderItems.removeAll { it.packageName == pkg }) return@withMutate badRequest("not in folder")
        layout.pages[pageIdx].placements.removeAll { it.id == id }
        layout.pages[pageIdx].placements.add(p)
        ok(layout)
    }

    fun folderRename(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val name = obj.optString("name").trim().take(40)
        if (id.isBlank()) return@withMutate badRequest("no id")
        val (pageIdx, p) = findPlacement(layout, id) ?: return@withMutate badRequest("no such cell")
        if (p.type != CellType.FOLDER) return@withMutate badRequest("not a folder")
        val updated = p.copy(folderName = name.ifBlank { null })
        layout.pages[pageIdx].placements.removeAll { it.id == id }
        layout.pages[pageIdx].placements.add(updated)
        ok(layout)
    }

    fun moveToDock(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val id = obj.optString("id")
        val slot = obj.optInt("slot", -1)
        // Target dock page. Defaults to 0 for backward compatibility
        // with editor builds that only knew about the first dock page.
        // Negative or out-of-range → bad request. Appending a new dock
        // page is a separate endpoint (dockPageAdd).
        val dockPageIdx = obj.optInt("dock_page", 0)
        if (id.isBlank() || slot < 0 || slot >= layout.dockSlots) return@withMutate badRequest("bad args")
        if (layout.dockPages.isEmpty()) layout.dockPages.add(mutableListOf())
        if (dockPageIdx < 0 || dockPageIdx >= layout.dockPages.size)
            return@withMutate badRequest("bad dock_page")
        val dock = layout.dockPages[dockPageIdx]
        // Source can be either a grid placement (move-to-dock) or an
        // existing dock placement on ANY dock page (in-dock reorder or
        // cross-dock-page move). Check grid first; fall through to a
        // global dock search so the same endpoint serves all cases.
        val grid = findPlacement(layout, id)
        if (grid != null) {
            val (pageIdx, p) = grid
            if (p.type != CellType.ICON && p.type != CellType.FOLDER)
                return@withMutate badRequest("only icons/folders go in the dock")
            layout.pages[pageIdx].placements.removeAll { it.id == id }
            dock.removeAll { it.col == slot } // displace target
            dock.add(p.copy(row = 0, col = slot, wSpan = 1, hSpan = 1))
            ok(layout)
        } else {
            // Already in some dock page — could be the same page
            // (reorder) or a different one (cross-dock-page move).
            // Find which page it's on, then move.
            var srcDock: MutableList<Placement>? = null
            var existing: Placement? = null
            for (dp in layout.dockPages) {
                val f = dp.firstOrNull { it.id == id }
                if (f != null) { srcDock = dp; existing = f; break }
            }
            if (existing == null || srcDock == null) {
                return@withMutate badRequest("no such cell")
            }
            // No-op if we're dropping on the same slot of the same page.
            if (srcDock === dock && existing.col == slot) return@withMutate ok(layout)
            srcDock.removeAll { it.id == id }
            dock.removeAll { it.col == slot } // displace target
            dock.add(existing.copy(col = slot))
            ok(layout)
        }
    }

    /** Append a new (empty) dock page. The on-device DockPagerAdapter
     *  surfaces a trailing "virtual" empty page in edit mode for the
     *  same purpose; this endpoint is the editor's equivalent. */
    fun dockPageAdd(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        layout.dockPages.add(mutableListOf())
        ok(layout)
    }

    /** Remove a dock page. Last page is non-deletable (the dock always
     *  has at least one page, even if empty). Body `{idx, force?}` —
     *  force is required if the page has any placements. */
    fun dockPageDelete(ex: MicroHttpServer.Exchange) = withMutate(ex) { layout ->
        val obj = JsonResponse.readJsonObject(ex) ?: return@withMutate badRequest("no body")
        val idx = obj.optInt("idx", -1)
        if (idx < 0 || idx >= layout.dockPages.size) return@withMutate badRequest("bad idx")
        if (layout.dockPages.size == 1) return@withMutate badRequest("can't delete last dock page")
        if (layout.dockPages[idx].isNotEmpty() && !obj.optBoolean("force"))
            return@withMutate badRequest("dock page non-empty (pass force:true)")
        layout.dockPages.removeAt(idx)
        ok(layout)
    }

    // ── helpers ─────────────────────────────────────────────────

    private inline fun withMutate(ex: MicroHttpServer.Exchange, block: (HomeLayout) -> Result) {
        // Two independent loads: one becomes the working copy that
        // [block] mutates in-place; the other is frozen as the
        // pre-mutation snapshot for the undo stack. Two store.load()
        // calls give independent deserialised objects, so mutating
        // [layout] never aliases [snapshot].
        val snapshot = store.load()
        val layout = store.load()
        val result = block(layout)
        when (result) {
            is Result.Ok -> {
                undoStack.addLast(snapshot)
                while (undoStack.size > UNDO_MAX) undoStack.removeFirst()
                store.save(layout)
                broadcastChanged()
                val resp = result.extra ?: JSONObject()
                resp.put("ok", true)
                resp.put("layout", layout.toBrowserJson())
                resp.put("undoDepth", undoStack.size)
                JsonResponse.ok(ex, resp)
            }
            is Result.Bad -> JsonResponse.error(ex, 400, result.message)
        }
    }

    private fun broadcastChanged() {
        val intent = Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
            .setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    private fun ok(layout: HomeLayout, extra: JSONObject? = null): Result = Result.Ok(extra)
    private fun badRequest(msg: String): Result = Result.Bad(msg)

    private sealed class Result {
        class Ok(val extra: JSONObject?) : Result()
        class Bad(val message: String) : Result()
    }

    private fun findPlacement(layout: HomeLayout, id: String): Pair<Int, Placement>? {
        for ((idx, page) in layout.pages.withIndex()) {
            val match = page.placements.firstOrNull { it.id == id }
            if (match != null) return idx to match
        }
        return null
    }

    private fun fitsOnPage(page: Page, p: Placement, ignoreId: String): Boolean {
        for (existing in page.placements) {
            if (existing.id == ignoreId) continue
            if (rectsOverlap(p, existing)) return false
        }
        return true
    }

    private fun rectsOverlap(a: Placement, b: Placement): Boolean {
        val ar1 = a.row; val ar2 = a.row + a.hSpan
        val ac1 = a.col; val ac2 = a.col + a.wSpan
        val br1 = b.row; val br2 = b.row + b.hSpan
        val bc1 = b.col; val bc2 = b.col + b.wSpan
        return ar1 < br2 && ar2 > br1 && ac1 < bc2 && ac2 > bc1
    }

    private fun findFirstEmptyCell(layout: HomeLayout, pageIdx: Int): Pair<Int, Int>? {
        val page = layout.pages.getOrNull(pageIdx) ?: return null
        val occupied = Array(layout.rows) { BooleanArray(layout.cols) }
        for (p in page.placements) {
            for (r in p.row until p.row + p.hSpan) for (c in p.col until p.col + p.wSpan) {
                if (r in 0 until layout.rows && c in 0 until layout.cols) occupied[r][c] = true
            }
        }
        for (r in 0 until layout.rows) for (c in 0 until layout.cols) {
            if (!occupied[r][c]) return r to c
        }
        return null
    }

    private fun findEmptyRect(layout: HomeLayout, pageIdx: Int, w: Int, h: Int): Pair<Int, Int>? {
        val page = layout.pages.getOrNull(pageIdx) ?: return null
        val occupied = Array(layout.rows) { BooleanArray(layout.cols) }
        for (p in page.placements) {
            for (r in p.row until p.row + p.hSpan) for (c in p.col until p.col + p.wSpan) {
                if (r in 0 until layout.rows && c in 0 until layout.cols) occupied[r][c] = true
            }
        }
        for (r in 0..(layout.rows - h)) for (c in 0..(layout.cols - w)) {
            var fits = true
            outer@ for (rr in r until r + h) for (cc in c until c + w) {
                if (occupied[rr][cc]) { fits = false; break@outer }
            }
            if (fits) return r to c
        }
        return null
    }
}
