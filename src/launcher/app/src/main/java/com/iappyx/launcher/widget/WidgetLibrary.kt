/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.model.CellType
import org.json.JSONObject
import java.io.File

/**
 * Catalogue of AI-generated widgets stored in `filesDir/widgets/{id}/`.
 *
 * Each widget is a directory with:
 *   - `widget.html` — the payload (always present)
 *   - `meta.json`   — `{title, prompt, createdAt}` (added in this generation;
 *     older widgets may lack this and fall back to <title>-extraction)
 *
 * The directory IS the widget id — placements reference a widget by directory
 * name, and the widget's sandbox storage lives in subdirectories of the same
 * folder (see [WidgetSandbox]).
 *
 * Removing a placement from the home grid does NOT delete the widget — its
 * files survive in the library so the user can re-place it from the manage
 * tab. Real deletion only happens via [delete], which refuses while the
 * widget is in use anywhere on the home grid or dock.
 */
object WidgetLibrary {

    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val createdAt: Long,
        val isInUse: Boolean,
        /** False for entries that ship with the APK (bundled in `assets/`).
         *  Bundled entries can't be renamed / refined / deleted from the
         *  manage UI — same convention as bundled wallpapers + transitions. */
        val isUserGenerated: Boolean = true,
        /** When non-null, this is a bundled widget; the placement uses
         *  `generatedWidgetAsset = assetPath` to load the HTML from the APK
         *  instead of from a per-instance sandbox dir. */
        val assetPath: String? = null,
        /** User-set lock. When true, AI iterate / refine paths refuse to
         *  modify this widget — a one-way fence the user opens explicitly
         *  when they want to keep working on it. Distinct from
         *  [isUserGenerated]'s inverse (bundled = read-only by APK design). */
        val userLocked: Boolean = false,
    )

    private data class BundledMeta(
        val id: String,
        val title: String,
        val subtitle: String,
        val assetPath: String,
    )

    /** Bundled widgets ship in `app/src/main/assets/widgets/{slug}.html` and
     *  appear in the manage carousel as locked entries. Picking "Place on
     *  home" creates a placement that loads HTML directly from the asset —
     *  no copy to filesDir, no per-instance sandbox until the user refines
     *  it (which would convert it to a user-generated copy; not supported
     *  in v1). */
    private val BUNDLED = listOf(
        BundledMeta(
            "clock", "Clock",
            "The classic clock — time and date, nothing else.",
            "widgets/clock.html",
        ),
        BundledMeta(
            "compass", "Compass",
            "A simple magnetic compass. Combines the magnetometer and accelerometer for a steady bearing. Works offline.",
            "widgets/compass.html",
        ),
        BundledMeta(
            "qr_barcode_scanner", "QR & Barcode Scanner",
            "Scans both QR codes and 1D barcodes through the camera. Copies the result to the clipboard, or opens it directly if it's a URL or phone number.",
            "widgets/qr_barcode_scanner.html",
        ),
        BundledMeta(
            "weather", "Weather",
            "Current conditions for your GPS location. Pulls from the free Open-Meteo API — no key needed.",
            "widgets/weather.html",
        ),
        BundledMeta(
            "diagnostics", "Bridge diagnostics",
            "Probes every iappyx.* bridge end-to-end and reports pass/fail. Use it as a regression check after R8 minification, dependency bumps, or OS upgrades. Three sections: namespace existence, method counts per namespace, and functional probes (sensors, http, storage, plugins, etc.). Tap a failed row for the stacktrace.",
            "widgets/diagnostics.html",
        ),
    )

    /** Root directory holding every user widget's folder. */
    fun rootDir(context: Context): File =
        File(context.filesDir, "widgets").also { it.mkdirs() }

    /** Resolve the asset path of a bundled widget by id. Null for user
     *  widgets — the caller falls back to the per-id sandbox dir. */
    fun bundledAssetPath(id: String): String? =
        BUNDLED.firstOrNull { it.id == id }?.assetPath

    /** True when [id] names a bundled widget (locked from rename / delete /
     *  refine). User widgets are always editable. */
    fun isBundled(id: String): Boolean = BUNDLED.any { it.id == id }

    /** All known widgets — bundled first, then user-generated newest-first. */
    fun all(context: Context): List<Entry> {
        val inUse = inUseIds(context)
        val bundled = BUNDLED.map {
            Entry(
                id = it.id,
                title = it.title,
                subtitle = it.subtitle,
                createdAt = 0L,
                isInUse = it.id in inUse,
                isUserGenerated = false,
                assetPath = it.assetPath,
            )
        }
        val user = rootDir(context).listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val htmlFile = File(dir, "widget.html")
                if (!htmlFile.exists()) return@mapNotNull null
                val id = dir.name
                // Skip user-dir entries that collide with a bundled id —
                // shouldn't happen but defensive.
                if (BUNDLED.any { it.id == id }) return@mapNotNull null
                val meta = readMeta(dir)
                // Ambient (share-to-launcher) widgets live in the Clippings
                // inbox, not the regular widget library. Hide them from the
                // Manage → Widgets carousel so the two surfaces stay separate.
                if (meta.ambient) return@mapNotNull null
                Entry(
                    id = id,
                    title = meta.title,
                    subtitle = meta.prompt.ifBlank { "AI-generated" }.take(80),
                    createdAt = meta.createdAt,
                    isInUse = id in inUse,
                    userLocked = meta.locked,
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        return bundled + user
    }

    fun get(context: Context, id: String): Entry? =
        all(context).firstOrNull { it.id == id }

    /** Refuse to delete a widget that's currently placed somewhere — return
     *  false. The manage UI's "Delete" button is disabled in that case; this
     *  is the second line of defence. */
    fun delete(context: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        if (isBundled(id)) return false
        if (id in inUseIds(context)) return false
        val dir = File(rootDir(context), id)
        if (!dir.exists() || !dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    /** Update the widget's stored description (the original generation
     *  prompt). Used by the manage-tab "Edit description" action — purely
     *  metadata; doesn't touch the HTML or trigger an AI call. */
    fun updatePrompt(context: Context, id: String, newPrompt: String): Boolean {
        if (isBundled(id)) return false
        val clean = newPrompt.trim()
        val dir = File(rootDir(context), id)
        val metaFile = File(dir, "meta.json")
        return try {
            val obj = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
            obj.put("prompt", clean)
            // Preserve fields we don't touch.
            if (!obj.has("title")) obj.put("title", "Generated widget")
            if (!obj.has("createdAt")) obj.put("createdAt", System.currentTimeMillis())
            metaFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    /** Update the widget's display title. Writes back into `meta.json` and
     *  also rewrites the HTML's `<title>` tag so future re-imports / exports
     *  stay consistent. */
    fun rename(context: Context, id: String, newTitle: String): Boolean {
        if (isBundled(id)) return false
        val clean = newTitle.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isEmpty()) return false
        val dir = File(rootDir(context), id)
        val htmlFile = File(dir, "widget.html")
        if (!htmlFile.exists()) return false

        val meta = readMeta(dir)
        val updated = JSONObject().apply {
            put("title", clean)
            put("prompt", meta.prompt)
            put("createdAt", meta.createdAt)
            // Preserve the user lock — rename should not silently unlock.
            if (meta.locked) put("locked", true)
        }
        try {
            File(dir, "meta.json").writeText(updated.toString(), Charsets.UTF_8)
            // Best-effort rewrite of <title> in the HTML so the in-app source
            // of truth matches. If the HTML had no <title> we don't insert
            // one — keep the file untouched in that edge case.
            val html = htmlFile.readText(Charsets.UTF_8)
            val titleRe = Regex("(<title[^>]*>)([^<]*)(</title>)", RegexOption.IGNORE_CASE)
            if (titleRe.containsMatchIn(html)) {
                htmlFile.writeText(
                    titleRe.replace(html, "$1${clean.replace("<", "&lt;")}$3"),
                    Charsets.UTF_8,
                )
            }
        } catch (_: Throwable) { return false }
        return true
    }

    /** Persist `meta.json` for a freshly-created widget. Called by the two
     *  creation paths (LauncherCommandRunner.createGeneratedWidget and
     *  ManualAiWidgetActivity). Idempotent — overwrites any existing file. */
    fun writeMeta(context: Context, id: String, prompt: String, html: String) {
        val dir = File(rootDir(context), id)
        if (!dir.exists()) dir.mkdirs()
        val title = extractHtmlTitle(html) ?: smartTitleFromPrompt(prompt)
        val meta = JSONObject().apply {
            put("title", title)
            put("prompt", prompt)
            put("createdAt", System.currentTimeMillis())
        }
        try { File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8) }
        catch (_: Throwable) { /* best-effort */ }
    }

    // ── Internals ────────────────────────────────────────────────

    private data class Meta(
        val title: String,
        val prompt: String,
        val createdAt: Long,
        val locked: Boolean = false,
        /** True for widgets created by share-to-launcher (transient cards
         *  with a TTL). [expiresAt] is the epoch-ms when the auto-cleanup
         *  sweep should remove the placement + delete the widget files. */
        val ambient: Boolean = false,
        val expiresAt: Long = 0L,
    )

    /** Read the cached meta.json, falling back to <title>-extraction +
     *  filesystem mtime when the file isn't there (older widgets). */
    private fun readMeta(dir: File): Meta {
        val metaFile = File(dir, "meta.json")
        val htmlFile = File(dir, "widget.html")
        if (metaFile.exists()) {
            try {
                val j = JSONObject(metaFile.readText())
                return Meta(
                    title = j.optString("title").ifBlank {
                        extractHtmlTitle(htmlFile.readText()) ?: "Generated widget"
                    },
                    prompt = j.optString("prompt"),
                    createdAt = j.optLong("createdAt", htmlFile.lastModified()),
                    locked = j.optBoolean("locked", false),
                    ambient = j.optBoolean("ambient", false),
                    expiresAt = j.optLong("expiresAt", 0L),
                )
            } catch (_: Throwable) { /* corrupt — fall through */ }
        }
        val html = if (htmlFile.exists()) htmlFile.readText() else ""
        return Meta(
            title = extractHtmlTitle(html) ?: "Generated widget",
            prompt = "",
            createdAt = htmlFile.lastModified(),
        )
    }

    /** Refresh the widget.html of every ambient (share-to-launcher) widget
     *  by overwriting it with the latest `share_widgets/share_card.html`
     *  asset from the APK. Existing share widgets created on older app
     *  versions get the current rendering / playback / styling code on
     *  next launcher resume. Cheap when there are zero share widgets. */
    fun refreshAmbientShareWidgets(context: Context): Int {
        val tplBytes = try {
            context.assets.open("share_widgets/share_card.html").use { it.readBytes() }
        } catch (_: Throwable) { return 0 }
        var refreshed = 0
        rootDir(context).listFiles { f -> f.isDirectory }?.forEach { dir ->
            val meta = readMeta(dir)
            if (!meta.ambient) return@forEach
            val htmlFile = File(dir, "widget.html")
            try {
                val current = if (htmlFile.exists()) htmlFile.readBytes() else null
                if (current == null || !current.contentEquals(tplBytes)) {
                    htmlFile.writeBytes(tplBytes)
                    refreshed++
                }
            } catch (_: Throwable) { /* skip */ }
        }
        return refreshed
    }

    /** Sweep ambient (share-to-launcher) clippings whose TTL has expired:
     *  remove them from `layout.clippings`, delete their sandbox dirs, and
     *  persist the trimmed layout. Locked clippings (`meta.locked = true`)
     *  are kept indefinitely. Idempotent — call freely from onResume.
     *  Returns the number of clippings removed. */
    fun expireAmbientWidgets(context: Context): Int {
        val now = System.currentTimeMillis()
        val expired = mutableListOf<String>()
        rootDir(context).listFiles { f -> f.isDirectory }?.forEach { dir ->
            val meta = readMeta(dir)
            if (meta.ambient && !meta.locked && meta.expiresAt in 1 until now) {
                expired += dir.name
            }
        }
        if (expired.isEmpty()) return 0
        // Remove from clippings list. Defensive sweep on home pages too —
        // covers the legacy case where ambient widgets used to be placed
        // on home pages before the Clippings page existed.
        val store = PlacementStore(context)
        val layout = store.load()
        var changed = false
        val clipBefore = layout.clippings.size
        layout.clippings.removeAll { it.widgetId in expired }
        if (layout.clippings.size != clipBefore) changed = true
        for (page in layout.pages) {
            val before = page.placements.size
            page.placements.removeAll { p ->
                p.type == CellType.GENERATED_WIDGET &&
                    p.generatedWidgetId in expired
            }
            if (page.placements.size != before) changed = true
        }
        for (dock in layout.dockPages) {
            val before = dock.size
            dock.removeAll { p ->
                p.type == CellType.GENERATED_WIDGET &&
                    p.generatedWidgetId in expired
            }
            if (dock.size != before) changed = true
        }
        if (changed) store.save(layout)
        // Now delete the widget files for each expired id.
        val stillInUse = inUseIds(context)
        for (id in expired) {
            if (id in stillInUse) continue
            val dir = File(rootDir(context), id)
            if (dir.exists()) dir.deleteRecursively()
        }
        return expired.size
    }

    /** Migrate ambient (share-to-launcher) widgets that are still living on
     *  home/dock pages (legacy placements created before the Clippings page
     *  existed) into the new [com.iappyx.launcher.model.HomeLayout.clippings]
     *  list. Idempotent — running twice is a no-op. Returns the number of
     *  widgets moved. Caller is expected to refresh the pager + indicator
     *  after a non-zero return. */
    fun migrateAmbientWidgetsToClippings(context: Context): Int {
        val ambientIds = mutableSetOf<String>()
        rootDir(context).listFiles { f -> f.isDirectory }?.forEach { dir ->
            val meta = readMeta(dir)
            if (meta.ambient) ambientIds += dir.name
        }
        if (ambientIds.isEmpty()) return 0
        val store = PlacementStore(context)
        val layout = store.load()
        // Compute the set of ambient ids ALREADY in the clippings list.
        // Those are not legacy stragglers — they're already in the right
        // place. We must NOT touch their home/dock placements, otherwise
        // we'd yank them out every onResume even after the user has
        // deliberately re-pinned a clipping back to a home page.
        val alreadyInClippings = layout.clippings.map { it.widgetId }.toSet()
        val migratableIds = ambientIds - alreadyInClippings
        if (migratableIds.isEmpty()) return 0
        val moved = mutableListOf<String>()
        // Walk home pages — only remove placements whose id is in the
        // migratable set (= ambient and NOT yet in clippings).
        for (page in layout.pages) {
            val it = page.placements.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.type == CellType.GENERATED_WIDGET &&
                    p.generatedWidgetId != null &&
                    p.generatedWidgetId in migratableIds
                ) {
                    moved += p.generatedWidgetId
                    it.remove()
                }
            }
        }
        // Same sweep on dock pages — unlikely to have ambient widgets there,
        // but defensive.
        for (dock in layout.dockPages) {
            val it = dock.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (p.type == CellType.GENERATED_WIDGET &&
                    p.generatedWidgetId != null &&
                    p.generatedWidgetId in migratableIds
                ) {
                    moved += p.generatedWidgetId
                    it.remove()
                }
            }
        }
        if (moved.isEmpty()) return 0
        // Newest first by createdAt, then prepend.
        val sorted = moved.distinct().sortedByDescending { id ->
            readMeta(File(rootDir(context), id)).createdAt
        }
        for (id in sorted) {
            layout.clippings.add(0, com.iappyx.launcher.model.Clipping(id))
        }
        store.save(layout)
        return moved.size
    }

    /** True when this user widget has been locked against AI changes. Bundled
     *  widgets are not "userLocked" in this sense (they're locked at a different
     *  layer). Returns false for unknown ids and for bundled widgets. */
    fun isUserLocked(context: Context, id: String): Boolean {
        if (isBundled(id)) return false
        val dir = File(rootDir(context), id)
        if (!dir.exists()) return false
        return readMeta(dir).locked
    }

    /** Reset a clipping's auto-expire to "now + the user's per-kind TTL".
     *  Reads the kind out of the widget's `meta.json` and the TTL out of
     *  [LauncherPrefs.clippingTtlMs]. No-op for non-ambient widgets. Returns
     *  true when the meta was successfully rewritten. TTL == 0 → expiresAt
     *  set to 0 too (never-expire). */
    fun resetClippingTtl(context: Context, id: String): Boolean {
        if (isBundled(id)) return false
        val dir = File(rootDir(context), id)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return false
        return try {
            val obj = JSONObject(metaFile.readText())
            if (!obj.optBoolean("ambient", false)) return false
            val kind = obj.optString("kind", "article")
            val ttl = com.iappyx.launcher.LauncherPrefs(context).clippingTtlMs(kind)
            val now = System.currentTimeMillis()
            obj.put("ttlMs", ttl)
            obj.put("expiresAt", if (ttl <= 0L) 0L else now + ttl)
            metaFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    /** Toggle the user lock on a widget. Returns true when persisted. Refuses
     *  for bundled / non-existent widgets. */
    fun setUserLocked(context: Context, id: String, locked: Boolean): Boolean {
        if (isBundled(id)) return false
        val dir = File(rootDir(context), id)
        val metaFile = File(dir, "meta.json")
        if (!dir.exists()) return false
        return try {
            val obj = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
            obj.put("locked", locked)
            // Preserve the rest — readMeta will hydrate defaults if missing.
            if (!obj.has("title")) obj.put("title", "Generated widget")
            if (!obj.has("createdAt")) obj.put("createdAt", System.currentTimeMillis())
            metaFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    /** Set of widget IDs currently referenced by any placement on home,
     *  dock, or the clippings inbox. Used both for [Entry.isInUse] tagging
     *  and as the delete guard. */
    private fun inUseIds(context: Context): Set<String> {
        val layout = PlacementStore(context).load()
        val ids = mutableSetOf<String>()
        for (page in layout.pages) {
            for (p in page.placements) {
                if (p.type == CellType.GENERATED_WIDGET) {
                    p.generatedWidgetId?.let(ids::add)
                }
            }
        }
        for (dp in layout.dockPages) {
            for (p in dp) {
                if (p.type == CellType.GENERATED_WIDGET) {
                    p.generatedWidgetId?.let(ids::add)
                }
            }
        }
        for (c in layout.clippings) ids.add(c.widgetId)
        return ids
    }

    private fun extractHtmlTitle(html: String): String? {
        val m = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        var t = m.groupValues[1].replace(Regex("\\s+"), " ").trim()
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        if (t.isEmpty() || t.length > 60) return null
        return t
    }

    /** Lightweight prompt → title fallback for widgets that lack both
     *  meta.json and a <title> tag. Mirrors WallpaperGenerator.smartTitle's
     *  approach but tuned for widget prompts ("water tracker with cup count
     *  and a big + button" → "Water Tracker"). */
    private fun smartTitleFromPrompt(prompt: String): String {
        var s = prompt.replace(Regex("\\s+"), " ").trim()
        for (p in listOf(
            "A widget that displays", "A widget that shows", "A widget that",
            "Widget that", "A widget", "Make a", "Create a", "I want a",
        )) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim().trimStart(':', '-', '—', ',', '.')
                break
            }
        }
        val cutAt = s.indexOfFirst { it == ',' || it == '.' || it == ';' || it == '—' || it == ':' || it == '\n' }
        if (cutAt > 0) s = s.substring(0, cutAt).trim()
        s = s.replace(Regex("(?i)\\s+widget\\s*$"), "").trim()
        s = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word[0].uppercaseChar() + word.substring(1).lowercase()
        }
        if (s.length > 36) s = s.substring(0, 33).substringBeforeLast(' ', s.substring(0, 33)) + "…"
        return s.ifBlank { "Generated widget" }
    }
}
