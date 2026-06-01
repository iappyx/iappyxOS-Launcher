/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.command

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.ai.WidgetPromptBuilder
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Placement
import com.iappyx.launcher.widget.WidgetSandbox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Glue between [Tools] schemas and the running launcher state. Each tool is a
 * pure function over (input → result string). The runner owns no UI; it just
 * mutates [HomeLayout] via the supplied [Listener] callbacks (which apply the
 * change + save + refresh adapters from LauncherActivity).
 *
 * Long-running tools (create_generated_widget) make their own AI calls
 * synchronously — the caller must invoke [run] on a background thread.
 */
class LauncherCommandRunner(
    private val activity: Activity,
    private val store: PlacementStore,
    private val listener: Listener,
) {
    /** Per-runner undo stack for the AI Command Bar. [CommandSession]
     *  begins / commits one snapshot per user-action; tool handlers add
     *  file backups. Public so the session can drive the lifecycle. */
    val snapshotStore: SnapshotStore = SnapshotStore(activity)

    /** Live progress sink for streaming tool calls (long iterate paths).
     *  Set by [CommandSession] before each tool dispatch; cleared on exit.
     *  Receives the cumulative character count of model output streamed so
     *  far, so the UI can update a "Generating… N chars" pulse. Set on the
     *  command thread, read on the OkHttp dispatcher thread — `@Volatile`
     *  is enough; we never mutate the underlying lambda. */
    @Volatile var progressSink: ((Int) -> Unit)? = null

    /** Build a [com.iappyx.launcher.ai.AiService.StreamProgress] that feeds
     *  [progressSink] when set; null otherwise so the AiService takes its
     *  non-streaming fast path. The StreamProgress accumulates character
     *  counts internally — callers don't need to maintain their own. */
    private fun buildStreamProgress(): com.iappyx.launcher.ai.AiService.StreamProgress? {
        val sink = progressSink ?: return null
        val acc = java.util.concurrent.atomic.AtomicInteger(0)
        return com.iappyx.launcher.ai.AiService.StreamProgress { delta ->
            val total = acc.addAndGet(delta.length)
            try { sink(total) } catch (_: Throwable) {}
        }
    }

    interface Listener {
        /** Current launcher layout — runner reads it before each tool call. */
        fun getLayout(): HomeLayout
        /** Apply a new layout; activity persists, refreshes adapters. */
        fun applyLayout(layout: HomeLayout)
        /** Currently visible home page (index inside [HomeLayout.pages]). */
        fun currentHomePageIndex(): Int
    }

    /** Execute one tool call. Returns a JSON string suitable for sending back
     *  to the model as a `tool_result.content`. */
    fun run(name: String, input: JSONObject): String {
        return try {
            when (name) {
                "find_empty_spot" -> findEmptySpot(input)
                "create_generated_widget" -> createGeneratedWidget(input)
                "edit_generated_widget" -> editGeneratedWidget(input)
                "place_app_icon" -> placeAppIcon(input)
                "create_folder" -> createFolder(input)
                "open_app" -> openApp(input)
                "list_installed_apps" -> listInstalledApps(input)
                "get_layout" -> getLayoutJson()
                "remove_cell" -> removeCell(input)
                "move_cell" -> moveCell(input)
                "add_to_folder" -> addToFolder(input)
                "remove_from_folder" -> removeFromFolder(input)
                "rename_folder" -> renameFolder(input)
                "rename_page" -> renamePage(input)
                // PLUGINS: BEGIN
                "get_plugins" -> getPlugins(input)
                // PLUGINS: END
                "add_to_dock" -> addToDock(input)
                "remove_from_dock" -> removeFromDock(input)
                "swap_cells" -> swapCells(input)
                "reorganize_into_folders" -> reorganizeIntoFolders(input)
                "generate_wallpaper" -> generateWallpaper(input)
                "iterate_wallpaper" -> iterateWallpaper(input)
                "undo_last_action" -> undoLastAction(input)
                "set_iappyx_wallpaper" -> setIappyxWallpaper(input)
                "generate_transition" -> generateTransition(input)
                "iterate_transition" -> iterateTransition(input)
                "generate_icon_filter" -> generateIconFilter(input)
                else -> jsonError("unknown tool '$name'")
            }
        } catch (t: Throwable) {
            jsonError(t.message ?: t.javaClass.simpleName)
        }
    }

    // ── find_empty_spot ─────────────────────────────────────

    private fun findEmptySpot(input: JSONObject): String {
        val w = input.optInt("w_span", 1).coerceAtLeast(1)
        val h = input.optInt("h_span", 1).coerceAtLeast(1)
        val pageIdx = if (input.has("page_index") && !input.isNull("page_index"))
            input.optInt("page_index") else null
        val spot = findFreeRect(pageIdx, w, h)
            ?: return jsonError("no free space — page is full or widget too large")
        return JSONObject().apply {
            put("page_index", spot.first); put("row", spot.second); put("col", spot.third)
        }.toString()
    }

    /**
     * Scan home pages for a free `w × h` rectangle and return its position.
     * Considers EVERY placement on each page — icons, folders, generated
     * widgets and stock Android widgets — so a 2×2 stock weather widget
     * correctly blocks all four cells.
     *
     * Behavior depends on [pageIdx]:
     *  - When [pageIdx] is non-null → only that page is checked. Returns null
     *    if the rect doesn't fit. The caller can then surface an error rather
     *    than silently moving to another page.
     *  - When [pageIdx] is null → all pages are checked in order. If none
     *    fits, a NEW empty home page is appended and the rect lands at
     *    (newIndex, 0, 0). Mutation happens via [Listener.applyLayout] so it
     *    persists + refreshes the adapter.
     */
    private fun findFreeRect(pageIdx: Int?, w: Int, h: Int): Triple<Int, Int, Int>? {
        val layout = listener.getLayout()
        if (w > layout.cols || h > layout.rows) return null // never fits
        val targetPages = if (pageIdx != null) listOf(pageIdx) else layout.pages.indices.toList()
        for (pi in targetPages) {
            val page = layout.pages.getOrNull(pi) ?: continue
            val occ = Array(layout.rows) { BooleanArray(layout.cols) }
            for (p in page.placements) {
                for (r in p.row until (p.row + p.hSpan).coerceAtMost(layout.rows))
                    for (c in p.col until (p.col + p.wSpan).coerceAtMost(layout.cols))
                        occ[r][c] = true
            }
            for (r in 0..(layout.rows - h)) for (c in 0..(layout.cols - w)) {
                var ok = true
                outer@ for (rr in r until r + h) for (cc in c until c + w) {
                    if (occ[rr][cc]) { ok = false; break@outer }
                }
                if (ok) return Triple(pi, r, c)
            }
        }
        // No spot on any existing page — auto-create a new home page only
        // when the caller didn't pin to a specific one.
        if (pageIdx == null) {
            layout.pages.add(com.iappyx.launcher.model.Page())
            listener.applyLayout(layout)
            return Triple(layout.pages.size - 1, 0, 0)
        }
        return null
    }

    // ── create_generated_widget ─────────────────────────────

    private fun createGeneratedWidget(input: JSONObject): String {
        val description = input.optString("description").ifBlank {
            return jsonError("description is required")
        }
        val w = input.optInt("w_span", 2).coerceAtLeast(1)
        val h = input.optInt("h_span", 2).coerceAtLeast(1)
        val explicit = input.has("page_index") && input.has("row") && input.has("col")
        val (pageIdx, row, col) = if (explicit)
            Triple(input.optInt("page_index"), input.optInt("row"), input.optInt("col"))
        else findFreeRect(null, w, h)
            ?: return jsonError("no free space for ${w}×${h} widget — try a smaller size")

        // Generate + persist the HTML via the shared WidgetGenerator. Same
        // code path the manage tab's "Refine with AI" flow uses, so any
        // change to AI generation lands in both places.
        val widgetId = try {
            com.iappyx.launcher.widget.WidgetGenerator.generate(activity, description)
        } catch (e: com.iappyx.launcher.widget.WidgetGenerator.GenerationException) {
            return jsonError(e.message ?: "Generation failed")
        }

        // Step 3: append placement to the layout.
        val layout = listener.getLayout()
        val page = layout.pages.getOrNull(pageIdx)
            ?: return jsonError("invalid page_index $pageIdx")
        val placement = Placement(
            id = Placement.newId(),
            type = CellType.GENERATED_WIDGET,
            row = row, col = col,
            wSpan = w, hSpan = h,
            generatedWidgetId = widgetId,
        )
        page.placements.add(placement)
        listener.applyLayout(layout)

        return JSONObject().apply {
            put("ok", true)
            put("placement_id", placement.id)
            put("page_index", pageIdx); put("row", row); put("col", col)
            // Include the widget's <title> so the AI can refer to it by name in its reply.
            widgetTitle(widgetId, null)?.let { put("label", it) }
        }.toString()
    }

    // ── edit_generated_widget ──────────────────────────────

    /** Refine an existing generated widget in place. The bridge to
     *  [com.iappyx.launcher.widget.WidgetGenerator.iterate], which embeds
     *  the current widget HTML into the AI prompt automatically — so the
     *  AI sees the actual code, not just the user's instruction. After the
     *  HTML is rewritten on disk, [Listener.applyLayout] forces the home
     *  pager to re-bind the cell so the new HTML loads immediately. */
    private fun editGeneratedWidget(input: JSONObject): String {
        val placementId = input.optString("placement_id").ifBlank {
            return jsonError("placement_id is required")
        }
        val instruction = input.optString("instruction").ifBlank {
            return jsonError("instruction is required")
        }
        val layout = listener.getLayout()
        var found: Placement? = null
        var foundPage = -1
        outer@ for ((i, page) in layout.pages.withIndex()) {
            for (p in page.placements) {
                if (p.id == placementId) { found = p; foundPage = i; break@outer }
            }
        }
        // Also check the dock — generated widgets technically can't sit in
        // the dock (it's icon-only) but be defensive: if a future schema
        // change allows it, this won't quietly miss the placement.
        if (found == null) {
            for (dockPage in layout.dockPages) {
                for (p in dockPage) {
                    if (p.id == placementId) { found = p; break }
                }
                if (found != null) break
            }
        }
        val placement = found ?: return jsonError("placement_id not found: $placementId")
        if (placement.type != CellType.GENERATED_WIDGET) {
            return jsonError("placement is not a generated widget (type=${placement.type})")
        }

        // Auto-fork bundled widgets: a placement with `generatedWidgetAsset`
        // set points at a read-only HTML file shipped in the APK. NB:
        // bundled placements ALSO carry `generatedWidgetId` set to the bundle
        // slug (e.g. "clock") — that's just an identifier for the bundled
        // entry, NOT a writable filesDir/widgets/<id>/ directory. So the
        // signal for "needs forking" is `generatedWidgetAsset != null`,
        // independent of the id field.
        //
        // We can't write to assets, so the first edit copies the asset into
        // the user widget library and rewrites the placement to point at
        // the new user-owned id (clearing generatedWidgetAsset). From here
        // on, edits route through the normal iterate path against the user
        // copy. `forked` flag is included in the response so the AI / UI
        // can surface "we customised the built-in clock" in the confirmation.
        var forkedFromBundled = false
        var workingLayout = layout
        var workingPlacement = placement
        if (placement.generatedWidgetAsset != null) {
            val assetPath = placement.generatedWidgetAsset!!
            val sourceTitle = com.iappyx.launcher.widget.WidgetLibrary
                .get(activity, deriveBundledIdFromAsset(assetPath))?.title
                ?: "Built-in widget"
            val newId = try {
                com.iappyx.launcher.widget.WidgetGenerator
                    .forkBundledWidget(activity, assetPath, sourceTitle)
            } catch (e: com.iappyx.launcher.widget.WidgetGenerator.GenerationException) {
                return jsonError(e.message ?: "Fork failed")
            }
            // Build a new layout with this placement swapped to the user
            // copy. Same id / position / span — only the source fields flip.
            workingPlacement = placement.copy(
                generatedWidgetId = newId,
                generatedWidgetAsset = null,
            )
            workingLayout = swapPlacement(layout, foundPage, placement.id, workingPlacement)
            forkedFromBundled = true
        }

        val widgetId = workingPlacement.generatedWidgetId
            ?: return jsonError("placement has no generatedWidgetId — cannot edit")

        // User lock: refuse iterate if the user has locked this widget. The
        // fork-from-bundled path above is exempt — bundled widgets can't be
        // user-locked (they ship locked at a different layer; see
        // WidgetLibrary.isUserLocked) — but the freshly-forked copy is also
        // newly-created and definitionally not yet locked, so this branch
        // only fires for pre-existing user widgets.
        if (!forkedFromBundled &&
            com.iappyx.launcher.widget.WidgetLibrary.isUserLocked(activity, widgetId)
        ) {
            return jsonError(
                "this widget is locked — unlock it from Manage Widgets " +
                "before refining or editing.",
            )
        }

        // Back up the user-copy widget HTML before the edit runs (only for
        // user widgets — bundled ones have no per-uuid file, the fork above
        // already created the user copy from the asset). Layout snapshot
        // captured by CommandSession at runLoop entry covers placement
        // changes; this covers content changes.
        if (!forkedFromBundled) {
            snapshotStore.addFileBackup("widgets/$widgetId/widget.html")
        }
        try {
            com.iappyx.launcher.widget.WidgetGenerator.iterate(
                activity, widgetId, instruction, buildStreamProgress(),
            )
        } catch (e: com.iappyx.launcher.widget.WidgetGenerator.NoOpException) {
            // AI deliberately made no change (already in place / ambiguous /
            // declined). If we already forked, persist the swap so the user
            // copy is in place even though no edits ran (next edit goes to
            // the user copy, not the bundled asset). Then return success.
            if (forkedFromBundled) listener.applyLayout(workingLayout)
            return JSONObject().apply {
                put("ok", true)
                put("noop", true)
                put("reason", e.reason)
                put("placement_id", placementId)
                if (foundPage >= 0) put("page_index", foundPage)
                if (forkedFromBundled) put("forked", true)
            }.toString()
        } catch (e: com.iappyx.launcher.widget.WidgetGenerator.GenerationException) {
            return jsonError(e.message ?: "Edit failed")
        }
        // Force the pager to rebind — this rebuilds the GeneratedWidgetCell
        // and pulls the rewritten widget.html. Same pattern the manage tab
        // uses (notifyItemChanged) but at the home-grid scope. If we forked
        // the placement, applyLayout(workingLayout) commits both the swap
        // and the rebind in one shot.
        listener.applyLayout(workingLayout)

        return JSONObject().apply {
            put("ok", true)
            put("placement_id", placementId)
            if (foundPage >= 0) put("page_index", foundPage)
            widgetTitle(widgetId, null)?.let { put("label", it) }
            if (forkedFromBundled) put("forked", true)
        }.toString()
    }

    /** Map an asset path like "widgets/clock.html" → bundled widget id
     *  ("clock"). Used to look up the bundled entry's display title for
     *  the fork-source description. */
    private fun deriveBundledIdFromAsset(assetPath: String): String =
        assetPath.substringAfterLast('/').removeSuffix(".html")

    /** Return a copy of [layout] with the placement matching [placementId]
     *  on [pageIndex] replaced by [replacement]. If pageIndex is < 0, scans
     *  every page (defensive for placements that move). Mutates the
     *  layout's mutable lists in-place — caller passes the result to
     *  [Listener.applyLayout] to persist. */
    private fun swapPlacement(
        layout: HomeLayout, pageIndex: Int, placementId: String,
        replacement: Placement,
    ): HomeLayout {
        // Scan home pages first.
        val pages = layout.pages.toMutableList()
        val targetPages = if (pageIndex in pages.indices) listOf(pageIndex)
                          else pages.indices.toList()
        for (idx in targetPages) {
            val page = pages[idx]
            val items = page.placements.toMutableList()
            val pos = items.indexOfFirst { it.id == placementId }
            if (pos >= 0) {
                items[pos] = replacement
                pages[idx] = page.copy(placements = items)
                return layout.copy(pages = pages)
            }
        }
        // Defensive: also scan dock pages. Generated widgets aren't supposed
        // to live in the dock today, but the lookup path scans dock too —
        // keep both code paths consistent so a future schema change doesn't
        // produce a silent no-op fork.
        val dockPages = layout.dockPages.toMutableList()
        for (idx in dockPages.indices) {
            val dockPage = dockPages[idx]
            val pos = dockPage.indexOfFirst { it.id == placementId }
            if (pos >= 0) {
                val newDockPage = dockPage.toMutableList()
                newDockPage[pos] = replacement
                dockPages[idx] = newDockPage
                return layout.copy(dockPages = dockPages)
            }
        }
        return layout
    }

    // ── place_app_icon ─────────────────────────────────────

    private fun placeAppIcon(input: JSONObject): String {
        val pkg = input.optString("package_name").ifBlank {
            return jsonError("package_name is required")
        }
        val pm = activity.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return jsonError("app not installed: $pkg")
        val activityName = launchIntent.component?.className
        val explicit = input.has("page_index") && input.has("row") && input.has("col")
        val (pageIdx, row, col) = if (explicit)
            Triple(input.optInt("page_index"), input.optInt("row"), input.optInt("col"))
        else findFreeRect(null, 1, 1)
            ?: return jsonError("no free 1×1 space on any page")
        val layout = listener.getLayout()
        val page = layout.pages.getOrNull(pageIdx)
            ?: return jsonError("invalid page_index $pageIdx")
        val placement = Placement(
            id = Placement.newId(),
            type = CellType.ICON,
            row = row, col = col, wSpan = 1, hSpan = 1,
            packageName = pkg, activityName = activityName,
        )
        page.placements.add(placement)
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("placement_id", placement.id)
            put("page_index", pageIdx); put("row", row); put("col", col)
        }.toString()
    }

    // ── create_folder ──────────────────────────────────────

    private fun createFolder(input: JSONObject): String {
        val pkgsArray = input.optJSONArray("package_names")
            ?: return jsonError("package_names is required")
        val pm = activity.packageManager
        val items = mutableListOf<com.iappyx.launcher.model.FolderItem>()
        val missing = mutableListOf<String>()
        for (i in 0 until pkgsArray.length()) {
            val pkg = pkgsArray.optString(i).trim()
            if (pkg.isEmpty()) continue
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) { missing.add(pkg); continue }
            items.add(com.iappyx.launcher.model.FolderItem(pkg, launchIntent.component?.className))
        }
        if (items.size < 2) {
            val why = if (missing.isNotEmpty()) "not installed: ${missing.joinToString(", ")}" else "need at least 2 apps"
            return jsonError("can't create folder — $why")
        }
        val folderName = input.optString("folder_name").ifBlank { "Folder" }

        val explicit = input.has("page_index") && input.has("row") && input.has("col")
        val (pageIdx, row, col) = if (explicit)
            Triple(input.optInt("page_index"), input.optInt("row"), input.optInt("col"))
        else findFreeRect(null, 1, 1)
            ?: return jsonError("no free 1×1 space on any page")

        val layout = listener.getLayout()
        val page = layout.pages.getOrNull(pageIdx)
            ?: return jsonError("invalid page_index $pageIdx")
        val placement = Placement(
            id = Placement.newId(),
            type = CellType.FOLDER,
            row = row, col = col, wSpan = 1, hSpan = 1,
            folderName = folderName,
            folderItems = items,
        )
        page.placements.add(placement)
        listener.applyLayout(layout)

        return JSONObject().apply {
            put("ok", true); put("placement_id", placement.id)
            put("page_index", pageIdx); put("row", row); put("col", col)
            put("folder_name", folderName); put("item_count", items.size)
            if (missing.isNotEmpty()) put("skipped_packages", JSONArray(missing))
        }.toString()
    }

    // ── open_app ───────────────────────────────────────────

    private fun openApp(input: JSONObject): String {
        val pkg = input.optString("package_name").ifBlank { return jsonError("package_name is required") }
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
            ?: return jsonError("app not installed: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LauncherPrefs(activity).recordAppLaunch(pkg)
        activity.startActivity(intent)
        return JSONObject().apply { put("ok", true) }.toString()
    }

    // ── list_installed_apps ────────────────────────────────

    private fun listInstalledApps(input: JSONObject): String {
        val q = input.optString("query").trim().lowercase()
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { ri: ResolveInfo ->
                JSONObject().apply {
                    put("package", ri.activityInfo.packageName)
                    put("label", ri.loadLabel(pm).toString())
                }
            }
            .filter {
                if (q.isEmpty()) true
                else it.optString("label").lowercase().contains(q) ||
                    it.optString("package").lowercase().contains(q)
            }
            .take(40) // cap for token budget
        val arr = JSONArray()
        for (app in apps) arr.put(app)
        return arr.toString()
    }

    // ── get_layout ─────────────────────────────────────────

    private fun getLayoutJson(): String {
        val layout = listener.getLayout()
        val pm = activity.packageManager
        return JSONObject().apply {
            put("cols", layout.cols); put("rows", layout.rows)
            put("dock_slots", layout.dockSlots)
            val pageArr = JSONArray()
            for ((i, page) in layout.pages.withIndex()) {
                val pj = JSONObject().apply {
                    put("index", i)
                    // Optional user-given page name. Empty string when
                    // the user hasn't renamed it; the AI should fall
                    // back to "page ${i+1}" in that case.
                    if (page.name.isNotBlank()) put("name", page.name)
                    val pa = JSONArray()
                    for (p in page.placements) {
                        pa.put(JSONObject().apply {
                            put("id", p.id); put("type", p.type.name)
                            put("row", p.row); put("col", p.col)
                            put("w", p.wSpan); put("h", p.hSpan)
                            p.packageName?.let { pkg ->
                                put("package", pkg)
                                // App label so the AI can refer to "Gmail" not "com.google.android.gm".
                                try {
                                    put("label", pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
                                } catch (_: Exception) { /* uninstalled */ }
                            }
                            p.folderName?.let { put("folder_name", it); put("label", it) }
                            p.generatedWidgetId?.let { wid ->
                                put("widget_id", wid)
                                // Pull the <title> out of the widget's saved HTML so the AI
                                // can reason about it by name ("world clock", "water tracker").
                                widgetTitle(wid, p.generatedWidgetAsset)?.let { put("label", it) }
                            }
                        })
                    }
                    put("placements", pa)
                }
                pageArr.put(pj)
            }
            put("pages", pageArr)
        }.toString()
    }

    /** Extract the `<title>` from a generated widget's HTML. Returns null if
     *  the file is missing or has no title. Tries the user's sandbox first,
     *  then bundled assets when [asset] is set. */
    private fun widgetTitle(widgetId: String, asset: String?): String? {
        val text: String? = try {
            val file = File(activity.filesDir, "widgets/$widgetId/widget.html")
            if (file.exists()) file.readText()
            else if (asset != null) activity.assets.open(asset).bufferedReader().use { it.readText() }
            else null
        } catch (_: Exception) { null }
        if (text.isNullOrBlank()) return null
        // Match <title>...</title> case-insensitively, allow attributes, span newlines.
        val rx = Regex("<title\\b[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        val raw = rx.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (raw.isBlank()) return null
        // Decode common HTML entities.
        return raw
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .take(80)
    }

    // ── remove_cell ────────────────────────────────────────

    private fun removeCell(input: JSONObject): String {
        val id = input.optString("placement_id").ifBlank { return jsonError("placement_id is required") }
        val layout = listener.getLayout()
        var removed = false
        for (page in layout.pages) {
            if (page.placements.removeAll { it.id == id }) removed = true
        }
        for (dockPage in layout.dockPages) {
            if (dockPage.removeAll { it.id == id }) removed = true
        }
        if (!removed) return jsonError("placement not found: $id")
        listener.applyLayout(layout)
        return JSONObject().apply { put("ok", true) }.toString()
    }

    // ── move_cell ──────────────────────────────────────────

    private fun moveCell(input: JSONObject): String {
        val id = input.optString("placement_id").ifBlank { return jsonError("placement_id is required") }
        val layout = listener.getLayout()
        // Locate the placement and its current page.
        var srcPageIdx = -1
        var src: Placement? = null
        for ((i, page) in layout.pages.withIndex()) {
            val found = page.placements.firstOrNull { it.id == id }
            if (found != null) { srcPageIdx = i; src = found; break }
        }
        if (src == null || srcPageIdx < 0) return jsonError("placement not found: $id")

        val w = src.wSpan; val h = src.hSpan
        val explicitPage = input.has("page_index") && !input.isNull("page_index")
        val explicitCell = input.has("row") && input.has("col") &&
            !input.isNull("row") && !input.isNull("col")

        // Resolve destination (page, row, col).
        val dest: Triple<Int, Int, Int> = when {
            explicitPage && explicitCell -> {
                val p = input.optInt("page_index")
                val r = input.optInt("row"); val c = input.optInt("col")
                if (p !in layout.pages.indices) return jsonError("invalid page_index $p")
                if (r < 0 || c < 0 || r + h > layout.rows || c + w > layout.cols)
                    return jsonError("destination out of grid bounds")
                if (cellsOccupiedByOthers(layout, p, r, c, w, h, exceptId = id))
                    return jsonError("destination is not empty")
                Triple(p, r, c)
            }
            explicitPage -> {
                val p = input.optInt("page_index")
                if (p !in layout.pages.indices) return jsonError("invalid page_index $p")
                findFreeOnPage(layout, p, w, h, exceptId = id)
                    ?: return jsonError("no free space on page $p")
            }
            else -> {
                // No destination specified — pick any OTHER page that fits, append a new page if needed.
                findFreeOnDifferentPage(layout, srcPageIdx, w, h, id)
                    ?: run {
                        // No other page has room — append a fresh page.
                        layout.pages.add(com.iappyx.launcher.model.Page())
                        Triple(layout.pages.size - 1, 0, 0)
                    }
            }
        }

        if (dest.first == srcPageIdx && dest.second == src.row && dest.third == src.col) {
            return JSONObject().apply { put("ok", true); put("note", "already there") }.toString()
        }

        // Mutate: remove from source, add to destination with new (row, col).
        layout.pages[srcPageIdx].placements.removeAll { it.id == id }
        layout.pages[dest.first].placements.add(
            src.copy(row = dest.second, col = dest.third)
        )
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("placement_id", id)
            put("page_index", dest.first); put("row", dest.second); put("col", dest.third)
        }.toString()
    }

    /** True if any placement on (pageIdx) other than [exceptId] overlaps the
     *  given (row, col, w, h) rectangle. */
    private fun cellsOccupiedByOthers(
        layout: com.iappyx.launcher.model.HomeLayout,
        pageIdx: Int, row: Int, col: Int, w: Int, h: Int, exceptId: String,
    ): Boolean {
        val page = layout.pages.getOrNull(pageIdx) ?: return true
        for (p in page.placements) {
            if (p.id == exceptId) continue
            if (col + w <= p.col || col >= p.col + p.wSpan) continue
            if (row + h <= p.row || row >= p.row + p.hSpan) continue
            return true
        }
        return false
    }

    /** First free (row, col) on [pageIdx] for a w×h rect, ignoring [exceptId]. */
    private fun findFreeOnPage(
        layout: com.iappyx.launcher.model.HomeLayout,
        pageIdx: Int, w: Int, h: Int, exceptId: String,
    ): Triple<Int, Int, Int>? {
        val page = layout.pages.getOrNull(pageIdx) ?: return null
        if (w > layout.cols || h > layout.rows) return null
        val occ = Array(layout.rows) { BooleanArray(layout.cols) }
        for (p in page.placements) {
            if (p.id == exceptId) continue
            for (r in p.row until (p.row + p.hSpan).coerceAtMost(layout.rows))
                for (c in p.col until (p.col + p.wSpan).coerceAtMost(layout.cols))
                    occ[r][c] = true
        }
        for (r in 0..(layout.rows - h)) for (c in 0..(layout.cols - w)) {
            var ok = true
            outer@ for (rr in r until r + h) for (cc in c until c + w) {
                if (occ[rr][cc]) { ok = false; break@outer }
            }
            if (ok) return Triple(pageIdx, r, c)
        }
        return null
    }

    /** Find a free spot on any page OTHER than [excludePageIdx]. */
    private fun findFreeOnDifferentPage(
        layout: com.iappyx.launcher.model.HomeLayout,
        excludePageIdx: Int, w: Int, h: Int, exceptId: String,
    ): Triple<Int, Int, Int>? {
        for (pi in layout.pages.indices) {
            if (pi == excludePageIdx) continue
            val spot = findFreeOnPage(layout, pi, w, h, exceptId)
            if (spot != null) return spot
        }
        return null
    }

    // ── add_to_folder ──────────────────────────────────────

    /** Locate a FOLDER placement by id (exact) or name (case-insensitive,
     *  exact then substring). Returns the page + placement, or null. */
    private fun findFolder(
        layout: com.iappyx.launcher.model.HomeLayout,
        folderId: String?,
        folderName: String?,
    ): Pair<com.iappyx.launcher.model.Page, Placement>? {
        if (folderId.isNullOrBlank() && folderName.isNullOrBlank()) return null
        for (page in layout.pages) {
            for (p in page.placements) {
                if (p.type != CellType.FOLDER) continue
                if (!folderId.isNullOrBlank() && p.id == folderId) return page to p
            }
        }
        if (!folderName.isNullOrBlank()) {
            // Exact match first.
            for (page in layout.pages) for (p in page.placements) {
                if (p.type == CellType.FOLDER &&
                    p.folderName?.equals(folderName, ignoreCase = true) == true) return page to p
            }
            for (page in layout.pages) for (p in page.placements) {
                if (p.type == CellType.FOLDER &&
                    p.folderName?.contains(folderName, ignoreCase = true) == true) return page to p
            }
        }
        return null
    }

    private fun addToFolder(input: JSONObject): String {
        val pkg = input.optString("package_name").ifBlank {
            return jsonError("package_name is required")
        }
        val folderId = if (input.has("folder_id")) input.optString("folder_id") else null
        val folderName = if (input.has("folder_name")) input.optString("folder_name") else null
        val pm = activity.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return jsonError("app not installed: $pkg")
        val layout = listener.getLayout()
        val (_, folder) = findFolder(layout, folderId, folderName)
            ?: return jsonError("folder not found")
        if (folder.folderItems.any { it.packageName == pkg }) {
            return jsonError("'$pkg' is already in folder '${folder.folderName ?: "Folder"}'")
        }
        folder.folderItems.add(
            com.iappyx.launcher.model.FolderItem(pkg, launchIntent.component?.className)
        )
        // Default: also drop any existing home-grid icon for this package, so
        // users don't end up with duplicates of the same app.
        val removeFromHome = input.optBoolean("remove_from_home", true)
        if (removeFromHome) {
            for (page in layout.pages) {
                page.placements.removeAll { it.type == CellType.ICON && it.packageName == pkg }
            }
        }
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("placement_id", folder.id)
            put("folder_name", folder.folderName ?: "")
            put("item_count", folder.folderItems.size)
        }.toString()
    }

    // ── remove_from_folder ─────────────────────────────────

    private fun removeFromFolder(input: JSONObject): String {
        val pkg = input.optString("package_name").ifBlank {
            return jsonError("package_name is required")
        }
        val folderId = if (input.has("folder_id")) input.optString("folder_id") else null
        val folderName = if (input.has("folder_name")) input.optString("folder_name") else null
        val toHome = input.optBoolean("to_home", false)
        val layout = listener.getLayout()
        val (page, folder) = findFolder(layout, folderId, folderName)
            ?: return jsonError("folder not found")
        val removed = folder.folderItems.removeAll { it.packageName == pkg }
        if (!removed) return jsonError("'$pkg' was not in folder")

        var collapsedToIcon = false
        var movedToHomePageIndex: Int? = null
        var movedToRow: Int? = null
        var movedToCol: Int? = null

        if (toHome) {
            // Place the removed app as a 1×1 icon on the first free spot.
            val pm = activity.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            val spot = findFreeRect(null, 1, 1)
            if (launchIntent != null && spot != null) {
                val newPlacement = Placement(
                    id = Placement.newId(), type = CellType.ICON,
                    row = spot.second, col = spot.third, wSpan = 1, hSpan = 1,
                    packageName = pkg, activityName = launchIntent.component?.className,
                )
                layout.pages[spot.first].placements.add(newPlacement)
                movedToHomePageIndex = spot.first
                movedToRow = spot.second; movedToCol = spot.third
            }
        }

        // Auto-collapse: 0 items → drop folder; 1 item → replace folder cell
        // with a plain icon at the same position.
        when (folder.folderItems.size) {
            0 -> {
                page.placements.removeAll { it.id == folder.id }
                collapsedToIcon = true
            }
            1 -> {
                val only = folder.folderItems.first()
                val replacement = Placement(
                    id = Placement.newId(), type = CellType.ICON,
                    row = folder.row, col = folder.col, wSpan = 1, hSpan = 1,
                    packageName = only.packageName, activityName = only.activityName,
                )
                page.placements.removeAll { it.id == folder.id }
                page.placements.add(replacement)
                collapsedToIcon = true
            }
        }

        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true)
            put("item_count_remaining", folder.folderItems.size)
            put("folder_collapsed", collapsedToIcon)
            if (movedToHomePageIndex != null) {
                put("moved_to_home", JSONObject().apply {
                    put("page_index", movedToHomePageIndex)
                    put("row", movedToRow); put("col", movedToCol)
                })
            }
        }.toString()
    }

    // ── rename_folder ──────────────────────────────────────

    private fun renameFolder(input: JSONObject): String {
        val newName = input.optString("new_name").ifBlank {
            return jsonError("new_name is required")
        }
        val folderId = if (input.has("folder_id")) input.optString("folder_id") else null
        val folderName = if (input.has("folder_name")) input.optString("folder_name") else null
        val layout = listener.getLayout()
        val (page, folder) = findFolder(layout, folderId, folderName)
            ?: return jsonError("folder not found")
        val updated = folder.copy(folderName = newName)
        val idx = page.placements.indexOfFirst { it.id == folder.id }
        if (idx < 0) return jsonError("folder lookup inconsistency — try again")
        page.placements[idx] = updated
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("placement_id", folder.id); put("new_name", newName)
        }.toString()
    }

    // ── rename_page ────────────────────────────────────────

    private fun renamePage(input: JSONObject): String {
        if (!input.has("page_index")) {
            return jsonError("page_index is required (0-based)")
        }
        if (!input.has("new_name")) {
            return jsonError("new_name is required (empty string to clear)")
        }
        val pageIdx = input.optInt("page_index", -1)
        val newName = input.optString("new_name", "").trim().take(40)
        val layout = listener.getLayout()
        if (pageIdx < 0 || pageIdx >= layout.pages.size) {
            return jsonError("bad page_index $pageIdx (have ${layout.pages.size} pages)")
        }
        layout.pages[pageIdx].name = newName
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true)
            put("page_index", pageIdx)
            put("new_name", newName)
            // Echo a user-facing label so the AI can write a tight confirmation
            // ("Renamed page 2 to Work").
            put("page_label", newName.ifBlank { "Page ${pageIdx + 1}" })
        }.toString()
    }

    // PLUGINS: BEGIN — list installed-and-enabled plugins for the AI
    // Command Bar. The returned shape is intentionally redundant with
    // PluginsModule.aggregateAiPrompts (which the AI already sees in
    // its system prompt) so the AI can interrogate at runtime when a
    // user-prompt mentions a service that might map to a plugin.
    private fun getPlugins(@Suppress("UNUSED_PARAMETER") input: JSONObject): String {
        val ctx = activity.applicationContext
        val entries = com.iappyx.launcher.plugins.PluginRegistry.all(ctx)
            .filter { it.enabled }
        val arr = JSONArray()
        for (entry in entries) {
            val m = entry.manifest
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("version", m.version)
                put("description", m.description)
                put("source", entry.source.name.lowercase())
                put("capabilities", JSONArray().apply { m.capabilities.forEach { put(it) } })
                put("exposes", JSONArray().apply { m.exposes.forEach { put(it) } })
                if (!m.aiPrompt.isNullOrBlank()) put("aiPrompt", m.aiPrompt)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("plugins", arr)
        }.toString()
    }
    // PLUGINS: END

    // ── add_to_dock ────────────────────────────────────────

    /** First (dockPageIdx, slotCol) with no placement, scanning pages in order
     *  then slots 0..dockSlots-1. */
    private fun findFreeDockSlot(layout: com.iappyx.launcher.model.HomeLayout): Pair<Int, Int>? {
        for ((pi, dockPage) in layout.dockPages.withIndex()) {
            val occupied = dockPage.map { it.col }.toSet()
            for (s in 0 until layout.dockSlots) {
                if (s !in occupied) return pi to s
            }
        }
        return null
    }

    private fun addToDock(input: JSONObject): String {
        val pkg = input.optString("package_name").ifBlank {
            return jsonError("package_name is required")
        }
        val pm = activity.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return jsonError("app not installed: $pkg")
        val layout = listener.getLayout()
        val explicit = input.has("dock_page_index") && input.has("slot")
        val (pageIdx, slot) = if (explicit) {
            input.optInt("dock_page_index") to input.optInt("slot")
        } else {
            findFreeDockSlot(layout) ?: run {
                // All current dock pages are full — append a fresh page.
                layout.dockPages.add(mutableListOf())
                (layout.dockPages.size - 1) to 0
            }
        }
        if (pageIdx !in layout.dockPages.indices) return jsonError("invalid dock_page_index $pageIdx")
        if (slot < 0 || slot >= layout.dockSlots) return jsonError("invalid slot $slot (0..${layout.dockSlots - 1})")
        val dockPage = layout.dockPages[pageIdx]
        if (dockPage.any { it.col == slot }) return jsonError("dock slot $pageIdx,$slot is already occupied")
        val placement = Placement(
            id = Placement.newId(), type = CellType.ICON,
            row = 0, col = slot, wSpan = 1, hSpan = 1,
            packageName = pkg, activityName = launchIntent.component?.className,
        )
        dockPage.add(placement)
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("placement_id", placement.id)
            put("dock_page_index", pageIdx); put("slot", slot)
        }.toString()
    }

    // ── remove_from_dock ───────────────────────────────────

    private fun removeFromDock(input: JSONObject): String {
        val pkg = if (input.has("package_name")) input.optString("package_name").ifBlank { null } else null
        val pid = if (input.has("placement_id")) input.optString("placement_id").ifBlank { null } else null
        if (pkg == null && pid == null) return jsonError("package_name or placement_id is required")
        val layout = listener.getLayout()
        var removed = 0
        for (dockPage in layout.dockPages) {
            val before = dockPage.size
            dockPage.removeAll { p ->
                (pid != null && p.id == pid) || (pkg != null && p.packageName == pkg)
            }
            removed += before - dockPage.size
        }
        if (removed == 0) return jsonError("not found in dock")
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("count_removed", removed)
        }.toString()
    }

    // ── swap_cells ─────────────────────────────────────────

    private fun swapCells(input: JSONObject): String {
        val a = input.optString("placement_id_a").ifBlank {
            return jsonError("placement_id_a is required")
        }
        val b = input.optString("placement_id_b").ifBlank {
            return jsonError("placement_id_b is required")
        }
        if (a == b) return jsonError("can't swap a cell with itself")
        val layout = listener.getLayout()
        var pageA = -1; var placA: Placement? = null
        var pageB = -1; var placB: Placement? = null
        for ((pi, page) in layout.pages.withIndex()) {
            for (p in page.placements) {
                if (p.id == a) { pageA = pi; placA = p }
                if (p.id == b) { pageB = pi; placB = p }
            }
        }
        if (placA == null || pageA < 0) return jsonError("placement_id_a not found: $a")
        if (placB == null || pageB < 0) return jsonError("placement_id_b not found: $b")
        if (placA.wSpan != placB.wSpan || placA.hSpan != placB.hSpan) {
            return jsonError(
                "can't swap cells of different sizes (" +
                    "${placA.wSpan}×${placA.hSpan} vs ${placB.wSpan}×${placB.hSpan})"
            )
        }
        // newA goes to where B was (page + position); newB goes to where A was.
        val newA = placA.copy(row = placB.row, col = placB.col)
        val newB = placB.copy(row = placA.row, col = placA.col)
        layout.pages[pageA].placements.removeAll { it.id == a }
        layout.pages[pageB].placements.removeAll { it.id == b }
        layout.pages[pageB].placements.add(newA)
        layout.pages[pageA].placements.add(newB)
        listener.applyLayout(layout)
        return JSONObject().apply { put("ok", true) }.toString()
    }

    // ── reorganize_into_folders ────────────────────────────

    /** Non-mutating-listener version of [findFreeRect] for batch use:
     *  finds a w×h spot anywhere in [layout], appending a new page if none
     *  fits. The caller is responsible for [Listener.applyLayout]. */
    private fun findFreeRectInLayout(
        layout: com.iappyx.launcher.model.HomeLayout, w: Int, h: Int,
    ): Triple<Int, Int, Int>? {
        if (w > layout.cols || h > layout.rows) return null
        for ((pi, page) in layout.pages.withIndex()) {
            val occ = Array(layout.rows) { BooleanArray(layout.cols) }
            for (p in page.placements) {
                for (r in p.row until (p.row + p.hSpan).coerceAtMost(layout.rows))
                    for (c in p.col until (p.col + p.wSpan).coerceAtMost(layout.cols))
                        occ[r][c] = true
            }
            for (r in 0..(layout.rows - h)) for (c in 0..(layout.cols - w)) {
                var ok = true
                outer@ for (rr in r until r + h) for (cc in c until c + w) {
                    if (occ[rr][cc]) { ok = false; break@outer }
                }
                if (ok) return Triple(pi, r, c)
            }
        }
        // Nothing fit — append a fresh page and place at (0, 0).
        layout.pages.add(com.iappyx.launcher.model.Page())
        return Triple(layout.pages.size - 1, 0, 0)
    }

    private fun reorganizeIntoFolders(input: JSONObject): String {
        val foldersArr = input.optJSONArray("folders")
            ?: return jsonError("folders is required (array of {name, package_names})")
        if (foldersArr.length() == 0) return jsonError("folders array is empty")
        val removeOriginals = input.optBoolean("remove_originals", true)
        val layout = listener.getLayout()
        val pm = activity.packageManager
        val created = JSONArray()
        val skipped = JSONArray()

        for (i in 0 until foldersArr.length()) {
            val spec = foldersArr.optJSONObject(i) ?: continue
            val name = spec.optString("name").ifBlank { "Folder" }
            val pkgsArr = spec.optJSONArray("package_names") ?: continue
            val items = mutableListOf<com.iappyx.launcher.model.FolderItem>()
            val skippedPkgs = mutableListOf<String>()
            for (j in 0 until pkgsArr.length()) {
                val pkg = pkgsArr.optString(j).trim()
                if (pkg.isEmpty()) continue
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent == null) { skippedPkgs.add(pkg); continue }
                items.add(com.iappyx.launcher.model.FolderItem(pkg, launchIntent.component?.className))
            }
            if (items.size < 2) {
                skipped.put(JSONObject().apply {
                    put("name", name)
                    put("reason", "fewer than 2 installed apps")
                    if (skippedPkgs.isNotEmpty()) put("not_installed", JSONArray(skippedPkgs))
                })
                continue
            }
            if (removeOriginals) {
                val pkgsToStrip = items.map { it.packageName }.toSet()
                for (page in layout.pages) {
                    page.placements.removeAll {
                        it.type == CellType.ICON && it.packageName != null && it.packageName in pkgsToStrip
                    }
                }
            }
            val spot = findFreeRectInLayout(layout, 1, 1)
            if (spot == null) {
                skipped.put(JSONObject().apply {
                    put("name", name); put("reason", "no free space (grid too small?)")
                })
                continue
            }
            val (pageIdx, row, col) = spot
            val placement = Placement(
                id = Placement.newId(), type = CellType.FOLDER,
                row = row, col = col, wSpan = 1, hSpan = 1,
                folderName = name, folderItems = items,
            )
            layout.pages[pageIdx].placements.add(placement)
            created.put(JSONObject().apply {
                put("id", placement.id); put("name", name); put("item_count", items.size)
                put("page_index", pageIdx); put("row", row); put("col", col)
                if (skippedPkgs.isNotEmpty()) put("skipped_packages", JSONArray(skippedPkgs))
            })
        }
        // Single applyLayout for the entire batch — avoids N adapter refreshes.
        listener.applyLayout(layout)
        return JSONObject().apply {
            put("ok", true); put("created", created); put("skipped", skipped)
        }.toString()
    }

    // ── generate_wallpaper / set_iappyx_wallpaper ────────────

    private fun generateWallpaper(input: JSONObject): String {
        val prompt = input.optString("prompt").trim()
        if (prompt.isBlank()) return jsonError("prompt is required")

        val id = try {
            com.iappyx.launcher.wallpaper.WallpaperGenerator.generate(activity, prompt)
        } catch (e: com.iappyx.launcher.wallpaper.WallpaperGenerator.GenerationException) {
            return jsonError(e.message ?: "generation failed")
        }

        // Persist the new id and tell the running wallpaper engine to swap.
        val prefs = com.iappyx.launcher.LauncherPrefs(activity)
        prefs.activeWallpaperId = id
        val intent = android.content.Intent(com.iappyx.launcher.LauncherPrefs.WALLPAPER_CHANGED_ACTION)
            .setPackage(activity.packageName)
            .putExtra("id", id)
        activity.sendBroadcast(intent)

        // Detect whether iappyxOS Live is the user's actual wallpaper, so the
        // AI can nudge them to set it when it's not.
        val wm = android.app.WallpaperManager.getInstance(activity)
        val info = wm.wallpaperInfo
        val active = info?.packageName == activity.packageName &&
            info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"

        val title = com.iappyx.launcher.wallpaper.WallpaperLibrary.all(activity)
            .firstOrNull { it.id == id }?.title ?: "Generated wallpaper"

        return JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("title", title)
            put("wallpaper_active", active)
            put("hint", if (active)
                "Wallpaper hot-swapped — swipe to home to see it."
            else
                "Saved. iappyxOS Live isn't the active wallpaper — call set_iappyx_wallpaper or open Launcher Settings → Live wallpaper to set it.")
        }.toString()
    }

    /** Refine the user's currently-active wallpaper in place. Mirrors
     *  edit_generated_widget but for wallpapers — reads the current HTML,
     *  asks the AI to apply [instruction], writes back over the same id.
     *  No new wallpaper entry, no UUID change → the running wallpaper
     *  service hot-swaps the new HTML on the same surface.
     *
     *  Input:
     *   - `instruction` (required) — natural-language change description.
     *   - `wallpaper_id` (optional) — explicit id to edit. Defaults to
     *     [LauncherPrefs.activeWallpaperId] when missing, which matches
     *     the natural "edit my wallpaper" intent.
     */
    private fun iterateWallpaper(input: JSONObject): String {
        val instruction = input.optString("instruction").trim().ifBlank {
            return jsonError("instruction is required")
        }
        val prefs = com.iappyx.launcher.LauncherPrefs(activity)
        val id = input.optString("wallpaper_id").trim()
            .ifBlank { prefs.activeWallpaperId.ifBlank {
                return jsonError("no active wallpaper — generate one first with generate_wallpaper")
            } }
        // Back up the current wallpaper HTML BEFORE the edit runs, so undo
        // can restore it. The snapshot is still in "pending" state at this
        // point — committed at runLoop exit by CommandSession.
        snapshotStore.addFileBackup("wallpapers/$id.html")
        try {
            com.iappyx.launcher.wallpaper.WallpaperGenerator.iterate(
                activity, id, instruction, buildStreamProgress(),
            )
        } catch (e: com.iappyx.launcher.wallpaper.WallpaperGenerator.GenerationException) {
            return jsonError(e.message ?: "Edit failed")
        }
        // Tell the running wallpaper service to hot-reload the (rewritten)
        // HTML so the user sees the change without re-setting the wallpaper.
        val intent = android.content.Intent(
            com.iappyx.launcher.LauncherPrefs.WALLPAPER_CHANGED_ACTION,
        ).setPackage(activity.packageName).putExtra("id", id)
        activity.sendBroadcast(intent)
        val title = com.iappyx.launcher.wallpaper.WallpaperLibrary.all(activity)
            .firstOrNull { it.id == id }?.title ?: "Wallpaper"
        return JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("title", title)
        }.toString()
    }

    private fun setIappyxWallpaper(input: JSONObject): String {
        val component = android.content.ComponentName(
            activity, "com.iappyx.launcher.wallpaper.IappyxWallpaperService",
        )
        val intent = android.content.Intent(
            android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER,
        ).apply {
            putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            activity.startActivity(intent)
            JSONObject().apply {
                put("ok", true)
                put("hint", "Opened the system live-wallpaper preview. The user must tap 'Apply' / 'Set wallpaper'.")
            }.toString()
        } catch (_: Throwable) {
            // Fall back to the generic chooser if the OEM rejected the direct intent.
            try {
                activity.startActivity(android.content.Intent(
                    android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER,
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().apply {
                    put("ok", true)
                    put("hint", "Opened the live-wallpaper chooser — user picks 'iappyxOS Live'.")
                }.toString()
            } catch (e: Throwable) {
                jsonError(e.message ?: "couldn't open wallpaper picker")
            }
        }
    }

    // ── generate_transition ─────────────────────────────────

    private fun generateTransition(input: JSONObject): String {
        val prompt = input.optString("prompt").trim()
        if (prompt.isBlank()) return jsonError("prompt is required")
        val id = try {
            com.iappyx.launcher.transitions.TransitionGenerator.generate(activity, prompt)
        } catch (e: com.iappyx.launcher.transitions.TransitionGenerator.GenerationException) {
            return jsonError(e.message ?: "generation failed")
        }
        // Auto-set as active so the next swipe shows the new effect.
        com.iappyx.launcher.LauncherPrefs(activity).pageTransitionStyle = id
        com.iappyx.launcher.transitions.TransitionLibrary.invalidate(id)
        val title = com.iappyx.launcher.transitions.TransitionLibrary.all(activity)
            .firstOrNull { it.id == id }?.title ?: "Generated transition"
        return JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("title", title)
            put("hint", "Set as active. Swipe between home pages to see it.")
        }.toString()
    }

    /** Pop the most-recent committed snapshot from [snapshotStore] and
     *  apply it. Reverts placements (including newly-created widgets,
     *  moves, folder changes), the active wallpaper / transition / icon
     *  filter selections, and the contents of any widget / wallpaper /
     *  transition file that the previous action mutated.
     *
     *  Note: snapshots taken by [CommandSession] include the current
     *  user-action's "before" state in the ring. The handler discards
     *  the most recent (= state right before this undo, equal to current
     *  state) and applies the next-most-recent (= state before the
     *  action being undone).
     *
     *  Files newly created by `generate_*` tools are NOT cleaned up on
     *  undo — they become orphans (the layout no longer references them
     *  so they don't show up in UI). Future cleanup can collect them.
     */
    private fun undoLastAction(input: JSONObject): String {
        // The current turn's snapshot is still PENDING here — CommandSession
        // commits it only in its finally, AFTER this tool loop returns — so it
        // is NOT on the ring yet. The previous code popped twice assuming it
        // was, which reverted one action too far (or falsely reported "Nothing
        // to undo"). Pop exactly once: the ring's top is the pre-last-action
        // state, which is what "undo" should restore. (H4-4)
        val target = snapshotStore.popLatest()
            ?: return jsonError("Nothing to undo — no recent actions on the stack.")
        val result = snapshotStore.restore(target)
        listener.applyLayout(result.layout)
        // Tell the live wallpaper engine to hot-reload if the active
        // wallpaper id changed back. Without this the user keeps seeing
        // whatever was active when undo ran.
        val prefs = com.iappyx.launcher.LauncherPrefs(activity)
        val intent = android.content.Intent(
            com.iappyx.launcher.LauncherPrefs.WALLPAPER_CHANGED_ACTION,
        ).setPackage(activity.packageName).putExtra("id", prefs.activeWallpaperId)
        activity.sendBroadcast(intent)
        // Same for transitions — drop the cached compiled spec so the
        // restored JSON is picked up on the next swipe.
        com.iappyx.launcher.transitions.TransitionLibrary
            .invalidate(prefs.pageTransitionStyle)
        return JSONObject().apply {
            put("ok", true)
            put("description", target.description)
            put("files_restored", result.filesRestored)
        }.toString()
    }

    /** Refine the user's currently-active transition in place. Mirrors
     *  iterate_wallpaper but for transitions — reads the current JSON
     *  spec, asks the AI to apply [instruction], writes back over the
     *  same id, and invalidates the compiled-spec cache so the next page
     *  swipe shows the change.
     *
     *  Bundled transitions auto-fork: the first edit copies the bundled
     *  spec into the user library under a new UUID, swaps the active-
     *  transition pref, and applies the change. Hand-coded bundled
     *  transitions (no JSON spec asset) reject with a friendly error.
     *
     *  Input:
     *   - `instruction` (required) — natural-language change description.
     *   - `transition_id` (optional) — explicit id to edit; defaults to
     *     [LauncherPrefs.pageTransitionStyle] (the active transition).
     */
    private fun iterateTransition(input: JSONObject): String {
        val instruction = input.optString("instruction").trim().ifBlank {
            return jsonError("instruction is required")
        }
        val prefs = com.iappyx.launcher.LauncherPrefs(activity)
        val id = input.optString("transition_id").trim()
            .ifBlank { prefs.pageTransitionStyle }
        // Back up the transition spec BEFORE the edit. For BUNDLED ids the
        // user dir doesn't have the file yet (and won't after fork either,
        // since fork creates a new uuid); the addFileBackup call is a
        // no-op for non-existent paths so this is safe to call
        // unconditionally.
        snapshotStore.addFileBackup("transitions/$id.json")
        val result = try {
            com.iappyx.launcher.transitions.TransitionGenerator
                .iterateWithFork(activity, id, instruction)
        } catch (e: com.iappyx.launcher.transitions.TransitionGenerator.GenerationException) {
            return jsonError(e.message ?: "Edit failed")
        }
        // If we forked from a bundled transition, swap the active pref to
        // the new user-owned id so the user actually sees their customised
        // version on the next page swipe. Without this, the bundled
        // original would still be active and the new spec would sit unused.
        if (result.forked) prefs.pageTransitionStyle = result.id
        val title = com.iappyx.launcher.transitions.TransitionLibrary.all(activity)
            .firstOrNull { it.id == result.id }?.title ?: "Transition"
        return JSONObject().apply {
            put("ok", true)
            put("id", result.id)
            put("title", title)
            if (result.forked) put("forked", true)
            put("hint", "Swipe between home pages to see the change.")
        }.toString()
    }

    // ── generate_icon_filter ────────────────────────────────

    private fun generateIconFilter(input: JSONObject): String {
        val prompt = input.optString("prompt").trim()
        if (prompt.isBlank()) return jsonError("prompt is required")
        val slug = try {
            com.iappyx.launcher.cells.IconFilterGenerator.generate(activity, prompt)
        } catch (e: com.iappyx.launcher.cells.IconFilterGenerator.GenerationException) {
            return jsonError(e.message ?: "generation failed")
        }
        // Auto-set as active so the user sees the new style instantly.
        com.iappyx.launcher.LauncherPrefs(activity).iconFilter = slug
        com.iappyx.launcher.cells.IconMask.clearCache()
        com.iappyx.launcher.cells.IconFilterRegistry.invalidate(slug)
        (activity as? com.iappyx.launcher.LauncherActivity)?.notifyIconFiltersChanged()
        val title = com.iappyx.launcher.cells.IconFilterRegistry.all(activity)
            .firstOrNull { it.slug == slug }?.title ?: "Generated icon style"
        return JSONObject().apply {
            put("ok", true)
            put("id", slug)
            put("title", title)
            put("hint", "Applied and set active. Reply to the user with one short confirmation sentence; do not call any more tools.")
        }.toString()
    }

    private fun jsonError(msg: String): String =
        JSONObject().apply { put("error", msg) }.toString()
}
