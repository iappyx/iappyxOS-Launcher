/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.content.Context
import com.iappyx.launcher.transitions.TransitionLibrary
import com.iappyx.launcher.wallpaper.WallpaperLibrary
import com.iappyx.launcher.widget.WidgetLibrary
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Single source of truth for "the bundle that represents one shared
 * artefact". Used by all three sharing flows (Android share, Nearby, QR)
 * and by the per-card "Save to file" path.
 *
 * Each artefact type has its own canonical zip layout:
 *
 *  - Widget:     `bundle.json` + `widget.html` + (optional) `meta.json`
 *  - Wallpaper:  `bundle.json` + `wallpaper.html` + `meta.json`
 *  - Transition: `bundle.json` + `spec.json` + `meta.json`
 *
 * The top-level `bundle.json` is the discriminator — `kind` is one of
 * `widget` / `wallpaper` / `transition`. The file extension follows
 * suit (`.iappyx-widget` / `.iappyx-wallpaper` / `.iappyx-transition`)
 * so a user inspecting the file can immediately tell what's inside.
 */
object ArtefactBundle {

    enum class Kind(val ext: String, val mime: String, val label: String) {
        WIDGET("iappyx-widget", "application/zip", "widget"),
        WALLPAPER("iappyx-wallpaper", "application/zip", "wallpaper"),
        TRANSITION("iappyx-transition", "application/zip", "transition"),
        ICON_FILTER("iappyx-icon-filter", "application/zip", "icon_filter"),
        // PLUGINS: BEGIN
        PLUGIN("iappyxplugin", "application/zip", "plugin"),
        // PLUGINS: END
    }

    /** Result of [readBundle] — a single artefact ready to install into
     *  the appropriate library. The caller chooses what to do with it
     *  (save directly, prompt the user, etc.). */
    data class Imported(
        val kind: Kind,
        val title: String,
        val prompt: String,
        /** The zip entries' raw bytes, keyed by canonical filename inside
         *  the artefact (e.g. "widget.html", "meta.json"). */
        val files: Map<String, ByteArray>,
        /** When this Imported originated from the Showcase, the entry's
         *  slug (e.g. "water-tracker"). [install] stamps the slug into
         *  the saved `meta.json` under `showcaseSlug` so the browser can
         *  later mark this entry as already-installed. Null for imports
         *  from Nearby / QR / file pickers. */
        val showcaseSlug: String? = null,
    )

    /** Build a bundle file in [destDir] for the given widget id. The
     *  returned File's name uses the widget's title (sanitised) plus the
     *  `.iappyx-widget` extension so the user sees a human-readable name
     *  in the share-sheet preview. */
    @Throws(IOException::class)
    fun buildWidget(context: Context, widgetId: String, destDir: File): File {
        val widgetDir = File(context.filesDir, "widgets/$widgetId")
        val htmlFile = File(widgetDir, "widget.html")
        if (!htmlFile.exists()) throw IOException("Widget content missing")
        val metaFile = File(widgetDir, "meta.json")
        val title = WidgetLibrary.get(context, widgetId)?.title ?: "widget"
        val out = File(destDir, sanitise(title) + ".${Kind.WIDGET.ext}")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.put("bundle.json", makeManifest(Kind.WIDGET, title, readPromptOf(metaFile)))
            zip.put("widget.html", htmlFile.readBytes())
            if (metaFile.exists()) zip.put("meta.json", metaFile.readBytes())
            // Bundled-resource files (per-widget data the HTML reads via
            // iappyx.storage.readAsset/extractAsset/listAssets). Live at
            // <sandbox>/resources/* on disk; carry over verbatim into the
            // zip under the same path prefix so the importer sees a
            // stable shape. Stream-copy each — a 60 MB SQLite DB
            // shouldn't sit in heap during export.
            val resourcesDir = File(widgetDir, "resources")
            if (resourcesDir.isDirectory) {
                resourcesDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    zip.putStream("resources/${f.name}", f)
                }
            }
        }
        return out
    }

    @Throws(IOException::class)
    fun buildWallpaper(context: Context, wallpaperId: String, destDir: File): File {
        val dir = File(context.filesDir, "wallpapers")
        val html = File(dir, "$wallpaperId.html")
        val meta = File(dir, "$wallpaperId.json")
        if (!html.exists() || !meta.exists()) throw IOException("Wallpaper content missing")
        val title = WallpaperLibrary.all(context).firstOrNull { it.id == wallpaperId }
            ?.title ?: "wallpaper"
        val out = File(destDir, sanitise(title) + ".${Kind.WALLPAPER.ext}")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.put("bundle.json", makeManifest(Kind.WALLPAPER, title, readPromptOf(meta)))
            zip.put("wallpaper.html", html.readBytes())
            zip.put("meta.json", meta.readBytes())
        }
        return out
    }

    @Throws(IOException::class)
    fun buildIconFilter(context: Context, slug: String, destDir: File): File {
        val dir = File(context.filesDir, "icon_filters/$slug")
        val spec = File(dir, "spec.json")
        val meta = File(dir, "meta.json")
        if (!spec.exists()) throw IOException("Icon filter content missing")
        // Title from meta when present, otherwise the slug itself.
        val title = try {
            if (meta.exists()) JSONObject(meta.readText()).optString("title").ifBlank { slug }
            else slug
        } catch (_: Throwable) { slug }
        val out = File(destDir, sanitise(title) + ".${Kind.ICON_FILTER.ext}")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.put("bundle.json", makeManifest(Kind.ICON_FILTER, title, readPromptOf(meta)))
            zip.put("spec.json", spec.readBytes())
            if (meta.exists()) zip.put("meta.json", meta.readBytes())
        }
        return out
    }

    @Throws(IOException::class)
    fun buildTransition(context: Context, transitionId: String, destDir: File): File {
        val dir = File(context.filesDir, "transitions")
        val spec = File(dir, "$transitionId.json")
        val meta = File(dir, "$transitionId.meta.json")
        if (!spec.exists()) throw IOException("Transition content missing")
        val title = TransitionLibrary.all(context).firstOrNull { it.id == transitionId }
            ?.title ?: "transition"
        val out = File(destDir, sanitise(title) + ".${Kind.TRANSITION.ext}")
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            zip.put("bundle.json", makeManifest(Kind.TRANSITION, title, readPromptOf(meta)))
            zip.put("spec.json", spec.readBytes())
            if (meta.exists()) zip.put("meta.json", meta.readBytes())
        }
        return out
    }

    /** Parse a previously-built bundle from raw zip bytes. Used by all
     *  three receive flows (file picker import, Nearby download, QR
     *  reassembly). Validates `bundle.json` is present and announces a
     *  known kind; throws otherwise. */
    @Throws(IOException::class)
    fun readBundle(zipBytes: ByteArray): Imported {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val buf = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (true) {
                        val n = zip.read(tmp); if (n <= 0) break
                        buf.write(tmp, 0, n)
                    }
                    files[entry.name] = buf.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = files["bundle.json"]
            ?: throw IOException("Not an iappyx bundle (no bundle.json)")
        val manifest = try { JSONObject(manifestBytes.toString(Charsets.UTF_8)) }
            catch (e: Exception) { throw IOException("Bundle manifest is corrupt: ${e.message}") }
        val kindStr = manifest.optString("kind")
        val kind = Kind.values().firstOrNull { it.label == kindStr }
            ?: throw IOException("Unknown artefact kind: '$kindStr'")
        return Imported(
            kind = kind,
            title = manifest.optString("title").ifBlank { kindStr },
            prompt = manifest.optString("prompt"),
            files = files,
        )
    }

    /** Save an imported bundle into the matching library so it appears in
     *  the manage tab. Returns the local id.
     *
     *  Re-install behaviour: when the bundle carries a `showcaseSlug` and
     *  an entry with the same slug already lives on disk, we **reuse that
     *  entry's id and overwrite its files in place** instead of creating a
     *  duplicate. This makes "Install again" from the showcase browser
     *  behave like an update — any home-screen placement, dock pin or
     *  edit-mode shortcut keeps working because its widgetId / wallpaperId
     *  stays the same. Bundles without a slug (one-off shares, manual
     *  imports) still get a fresh UUID. */
    @Throws(IOException::class)
    fun install(context: Context, bundle: Imported): String {
        val id = bundle.showcaseSlug
            ?.let { findExistingBySlug(context, bundle.kind, it) }
            ?: java.util.UUID.randomUUID().toString()
        when (bundle.kind) {
            Kind.WIDGET -> {
                val dir = File(context.filesDir, "widgets/$id").also { it.mkdirs() }
                val html = bundle.files["widget.html"]
                    ?: throw IOException("Widget bundle missing widget.html")
                File(dir, "widget.html").writeBytes(html)
                // Extract any bundled resources/* entries into the new
                // widget's per-id resources/ dir. Mirror the export side
                // in buildWidget(); each entry is the leaf name (no
                // subdirs) — anything with a slash inside the name or
                // path-traversal sequences is rejected to keep zip-slip
                // out of reach. Same defensive shape BackupImporter uses.
                // On re-install (same showcaseSlug → reused id), wipe any
                // existing resources first so files removed from the new
                // bundle don't linger. New install paths see an empty dir
                // anyway.
                val resourcesDir = File(dir, "resources")
                if (resourcesDir.isDirectory) {
                    resourcesDir.listFiles()?.forEach { it.delete() }
                }
                for ((entryName, bytes) in bundle.files) {
                    if (!entryName.startsWith("resources/")) continue
                    val leaf = sanitizeResourceLeaf(entryName.removePrefix("resources/"))
                        ?: continue
                    if (!resourcesDir.exists()) resourcesDir.mkdirs()
                    File(resourcesDir, leaf).writeBytes(bytes)
                }
                val meta = stampShowcaseSlug(
                    bundle.files["meta.json"] ?: makeWidgetMeta(bundle).toByteArray(),
                    bundle.showcaseSlug,
                )
                File(dir, "meta.json").writeBytes(meta)
            }
            Kind.WALLPAPER -> {
                val dir = File(context.filesDir, "wallpapers").also { it.mkdirs() }
                val html = bundle.files["wallpaper.html"]
                    ?: throw IOException("Wallpaper bundle missing wallpaper.html")
                File(dir, "$id.html").writeBytes(html)
                val meta = stampShowcaseSlug(
                    bundle.files["meta.json"] ?: makeFreshMeta(bundle).toByteArray(),
                    bundle.showcaseSlug,
                )
                File(dir, "$id.json").writeBytes(meta)
            }
            Kind.TRANSITION -> {
                val dir = File(context.filesDir, "transitions").also { it.mkdirs() }
                val spec = bundle.files["spec.json"]
                    ?: throw IOException("Transition bundle missing spec.json")
                File(dir, "$id.json").writeBytes(spec)
                val meta = stampShowcaseSlug(
                    bundle.files["meta.json"] ?: makeFreshMeta(bundle).toByteArray(),
                    bundle.showcaseSlug,
                )
                File(dir, "$id.meta.json").writeBytes(meta)
            }
            Kind.ICON_FILTER -> {
                // Each icon filter lives in its own subdir (matches the layout
                // [com.iappyx.launcher.cells.IconFilterRegistry] reads).
                val dir = File(context.filesDir, "icon_filters/$id").also { it.mkdirs() }
                val spec = bundle.files["spec.json"]
                    ?: throw IOException("Icon filter bundle missing spec.json")
                // Re-stamp slug into the spec body so the runtime registry's
                // resolve(slug) keys line up with the filename folder. The
                // sender's UUID has no meaning on this device.
                val rewrittenSpec = try {
                    val o = JSONObject(spec.toString(Charsets.UTF_8))
                    o.put("slug", id)
                    o.toString().toByteArray(Charsets.UTF_8)
                } catch (_: Throwable) { spec }
                File(dir, "spec.json").writeBytes(rewrittenSpec)
                val meta = stampShowcaseSlug(
                    bundle.files["meta.json"] ?: makeFreshMeta(bundle).toByteArray(),
                    bundle.showcaseSlug,
                )
                File(dir, "meta.json").writeBytes(meta)
            }
            // PLUGINS: BEGIN
            Kind.PLUGIN -> {
                // Plugins differ from the other artefact kinds: their
                // primary id is decided by the plugin author (the
                // `id` field in manifest.json), not by the launcher.
                // We hand the bytes off to PluginsModule.installFromBytes
                // which reconstitutes a .iappyxplugin zip and uses the
                // standard install pipeline (manifest validation,
                // capability gating, etc.). Re-emit a tiny zip
                // in-memory from the bundle's file map so the
                // unified install path stays intact.
                val zipBytes = pluginFilesToZip(bundle.files)
                val err = com.iappyx.launcher.plugins.PluginsModule
                    .installFromBytes(context, zipBytes)
                if (err != null) throw IOException("Plugin install failed: $err")
                // For plugins, the canonical id is in manifest.json
                // (the launcher honours it as the directory name).
                // Pull it back out of the bundle so the caller knows
                // what got installed.
                val manifestBytes = bundle.files["manifest.json"]
                    ?: throw IOException("Plugin bundle missing manifest.json")
                val manifestObj = try {
                    JSONObject(manifestBytes.toString(Charsets.UTF_8))
                } catch (_: Throwable) {
                    throw IOException("Plugin manifest.json is malformed")
                }
                return manifestObj.optString("id", id)
            }
            // PLUGINS: END
        }
        return id
    }

    // PLUGINS: BEGIN — utility: re-zip a plugin's files map into the
    // .iappyxplugin format. The plugin install path takes raw zip bytes
    // (so the IntentFilter path can hand it the zip the user opened).
    // Showcase installs already have the unpacked file map, so we
    // re-emit a zip in-memory rather than introduce a second install
    // entry point. Cheap: plugins are typically <100 KB.
    private fun pluginFilesToZip(files: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                // Skip showcase-only files that the plugin runtime
                // doesn't need (meta.json is the showcase entry's
                // metadata; the plugin has its own manifest.json).
                if (name == "meta.json") continue
                if (name.startsWith("resources/")) continue
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    // PLUGINS: END

    /** Scan the on-device library for an existing entry whose stamped
     *  `showcaseSlug` matches [slug], and return its local id (the UUID
     *  that names its dir / file). Returns null if no match — caller
     *  generates a fresh UUID. Mirrors the file layouts written by
     *  [install] for each kind. */
    private fun findExistingBySlug(context: Context, kind: Kind, slug: String): String? {
        when (kind) {
            Kind.WIDGET -> {
                // widgets/<id>/meta.json
                val root = File(context.filesDir, "widgets")
                root.listFiles { f -> f.isDirectory }?.forEach { sub ->
                    val meta = File(sub, "meta.json")
                    if (meta.isFile && readShowcaseSlug(meta) == slug) return sub.name
                }
            }
            Kind.WALLPAPER -> {
                // wallpapers/<id>.json (sidecar) + <id>.html
                val root = File(context.filesDir, "wallpapers")
                root.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { meta ->
                    if (readShowcaseSlug(meta) == slug) {
                        return meta.nameWithoutExtension
                    }
                }
            }
            Kind.TRANSITION -> {
                // transitions/<id>.meta.json + <id>.json
                val root = File(context.filesDir, "transitions")
                root.listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }?.forEach { meta ->
                    if (readShowcaseSlug(meta) == slug) {
                        return meta.name.removeSuffix(".meta.json")
                    }
                }
            }
            Kind.ICON_FILTER -> {
                // icon_filters/<id>/meta.json (+ spec.json with slug field too)
                val root = File(context.filesDir, "icon_filters")
                root.listFiles { f -> f.isDirectory }?.forEach { sub ->
                    val meta = File(sub, "meta.json")
                    if (meta.isFile && readShowcaseSlug(meta) == slug) return sub.name
                }
            }
            // PLUGINS: BEGIN — plugin id == showcase slug, so the
            // directory itself is the "match". No showcaseSlug stamp
            // needed — plugins use the manifest's `id` as the canonical
            // identity, and the showcase repo's plugins/<slug>/
            // matches that on a 1-to-1 basis by convention.
            Kind.PLUGIN -> {
                val dir = File(context.filesDir, "plugins/$slug")
                if (dir.isDirectory) return slug
            }
            // PLUGINS: END
        }
        return null
    }

    private fun readShowcaseSlug(meta: File): String? = try {
        val s = JSONObject(meta.readText()).optString("showcaseSlug")
        if (s.isBlank()) null else s
    } catch (_: Throwable) { null }

    /** Inject `showcaseSlug` into the meta JSON if [slug] is non-null,
     *  preserving every other field. Used so the showcase browser can
     *  recognise an entry as already-installed without relying on title
     *  matching. */
    private fun stampShowcaseSlug(metaBytes: ByteArray, slug: String?): ByteArray {
        if (slug.isNullOrBlank()) return metaBytes
        return try {
            val obj = JSONObject(metaBytes.toString(Charsets.UTF_8))
            obj.put("showcaseSlug", slug)
            obj.toString().toByteArray()
        } catch (_: Throwable) {
            // Meta wasn't valid JSON — leave it untouched rather than nuke it.
            metaBytes
        }
    }

    private fun makeManifest(kind: Kind, title: String, prompt: String): ByteArray =
        JSONObject().apply {
            put("kind", kind.label)
            put("title", title)
            put("prompt", prompt)
            put("createdAt", System.currentTimeMillis())
            put("schemaVersion", 1)
        }.toString().toByteArray()

    /** Read the `prompt` field from a meta.json sidecar (or empty if the
     *  file doesn't exist / can't parse). Used to populate the bundle
     *  manifest so the receiver sees the original generation prompt. */
    private fun readPromptOf(meta: File): String = try {
        if (!meta.exists()) "" else JSONObject(meta.readText()).optString("prompt", "")
    } catch (_: Throwable) { "" }

    private fun makeFreshMeta(bundle: Imported): String = JSONObject().apply {
        put("title", bundle.title)
        put("prompt", bundle.prompt)
        put("createdAt", System.currentTimeMillis())
    }.toString()

    /** Widget meta has a slightly different shape (matches WidgetLibrary's
     *  expectations): {title, prompt, createdAt}. Same fields, separate
     *  helper kept in case the schema ever diverges. */
    private fun makeWidgetMeta(bundle: Imported): String = JSONObject().apply {
        put("title", bundle.title)
        put("prompt", bundle.prompt)
        put("createdAt", System.currentTimeMillis())
    }.toString()

    /** Make a string safe to embed in a filename — drops slashes, control
     *  chars, and trims to 50 chars. The underscore fallback prevents an
     *  all-special-chars title from producing a zero-length filename. */
    private fun sanitise(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9 _-]+"), "")
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .take(50)
        return cleaned.ifBlank { "artefact" }
    }

    /** Sanitise a `resources/<X>` zip-entry leaf name. Returns null when
     *  the entry can't be safely extracted — slashes anywhere in the leaf
     *  (nested subdirs aren't supported), path-traversal sequences, an
     *  empty result, or a result that exceeds 100 chars. Same threat
     *  shape that BackupImporter.sanitizeEntryName protects against. */
    private fun sanitizeResourceLeaf(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.contains('/') || raw.contains('\\')) return null
        if (raw == "." || raw == "..") return null
        val cleaned = raw.replace(Regex("[^\\w.\\-]"), "_")
        val capped = if (cleaned.length > 100) cleaned.substring(0, 100) else cleaned
        return capped.ifBlank { null }
    }

    /** Stream-copy a file into the zip — used by [buildWidget] for
     *  per-widget bundled resources so a 60 MB SQLite DB doesn't sit
     *  in heap during export. */
    private fun ZipOutputStream.putStream(path: String, file: File) {
        putNextEntry(java.util.zip.ZipEntry(path))
        java.io.FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            while (true) {
                val n = fis.read(buf); if (n <= 0) break
                write(buf, 0, n)
            }
        }
        closeEntry()
    }

    private fun ZipOutputStream.put(path: String, data: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(data)
        closeEntry()
    }
}
