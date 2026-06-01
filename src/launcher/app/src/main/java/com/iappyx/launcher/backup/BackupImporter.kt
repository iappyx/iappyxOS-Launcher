/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.backup

import android.content.Context
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Placement
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads `.iappyxbackup` archives produced by [BackupExporter]. Two-step:
 *
 *  1. [validate] reads the manifest + counts contents, returns a
 *     [Summary] for the UI to confirm before applying.
 *  2. [apply] re-reads the file (callers re-open the InputStream) and
 *     either Replaces or Merges into the current launcher state.
 *
 * Atomicity: extraction goes into a `pending/` scratch dir under
 * filesDir first, then we swap entries into place. If the user picks a
 * truncated zip, the swap never starts and current state is intact.
 */
object BackupImporter {

    /** Result of [validate] — what's inside the file, plus any obvious
     *  problems we can warn about up front (uninstalled packages, etc.). */
    data class Summary(
        val manifestVersion: Int,
        val createdAt: Long,
        val deviceModel: String,
        val widgetCount: Int,
        val wallpaperCount: Int,
        val transitionCount: Int,
        val iconFilterCount: Int,
        val profileCount: Int,
        val homePageCount: Int,
        val includesApiKey: Boolean,
        val includesRuntimeData: Boolean,
        /** Packages referenced by the backup's layout that aren't installed
         *  on this device. Surfaced in the confirm dialog so the user
         *  isn't surprised when icons appear missing post-import. Note:
         *  with the package-broadcast wiring, reinstalling later puts them
         *  back. */
        val missingPackages: List<String>,
    )

    enum class Mode {
        /** Wipe current widgets / wallpapers / transitions / layout, then
         *  apply the backup. Most destructive but cleanest. */
        REPLACE,
        /** Keep current state; copy backup widgets/wallpapers/transitions
         *  side-by-side under their original UUIDs (skip if id collision).
         *  Layout is NOT touched. */
        MERGE,
    }

    /** Maximum manifest version this build understands. Refuse anything
     *  higher (the user needs to update the launcher). */
    private const val MAX_VERSION = BackupExporter.MANIFEST_VERSION

    class ImportException(msg: String) : Exception(msg)

    /** Reads only the manifest + does a cheap pass to count entries.
     *  Doesn't extract anything. Caller must pass a fresh InputStream
     *  before calling [apply] — ZipInputStream is one-shot. */
    @Throws(ImportException::class)
    fun validate(context: Context, input: InputStream): Summary {
        var manifest: JSONObject? = null
        var widgetIds = mutableSetOf<String>()
        var wallpaperIds = mutableSetOf<String>()
        var transitionIds = mutableSetOf<String>()
        var iconFilterIds = mutableSetOf<String>()
        var profileIds = mutableSetOf<String>()
        var layoutJson: String? = null

        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        name == "manifest.json" -> {
                            manifest = JSONObject(zip.readAllSafe().toString(Charsets.UTF_8))
                        }
                        name == "layout/home_layout.json" -> {
                            layoutJson = zip.readAllSafe().toString(Charsets.UTF_8)
                        }
                        name.startsWith("widgets/") -> {
                            // widgets/{uuid}/file — extract the uuid segment
                            name.removePrefix("widgets/").substringBefore('/')
                                .takeIf { it.isNotBlank() }?.let { widgetIds.add(it) }
                        }
                        name.startsWith("wallpapers/") -> {
                            // wallpapers/{uuid}.html | .json
                            val rest = name.removePrefix("wallpapers/")
                            wallpaperIds.add(rest.substringBeforeLast('.'))
                        }
                        name.startsWith("transitions/") -> {
                            // transitions/{uuid}.json | .meta.json
                            val rest = name.removePrefix("transitions/")
                                .removeSuffix(".meta.json")
                                .removeSuffix(".json")
                            if (rest.isNotBlank()) transitionIds.add(rest)
                        }
                        name.startsWith("icon_filters/") -> {
                            name.removePrefix("icon_filters/").substringBefore('/')
                                .takeIf { it.isNotBlank() }?.let { iconFilterIds.add(it) }
                        }
                        name.startsWith("profiles/") -> {
                            name.removePrefix("profiles/").substringBefore('/')
                                .takeIf { it.isNotBlank() }?.let { profileIds.add(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            throw ImportException("Couldn't read backup: ${e.message}")
        }

        val m = manifest ?: throw ImportException(
            "Not a valid iappyx backup (no manifest.json inside)."
        )
        val version = m.optInt("version", -1)
        if (version <= 0) throw ImportException("Backup has no version — can't restore.")
        if (version > MAX_VERSION) throw ImportException(
            "This backup was made by a newer version of iappyxOS Launcher. " +
                "Update the launcher first."
        )

        val missing = layoutJson?.let { findMissingPackages(context, it) } ?: emptyList()

        return Summary(
            manifestVersion = version,
            createdAt = m.optLong("createdAt", 0L),
            deviceModel = m.optString("deviceModel", ""),
            widgetCount = widgetIds.size,
            wallpaperCount = wallpaperIds.size,
            transitionCount = transitionIds.size,
            iconFilterCount = iconFilterIds.size,
            profileCount = profileIds.size,
            homePageCount = m.optJSONObject("counts")?.optInt("homePages", 0) ?: 0,
            includesApiKey = m.optBoolean("includesApiKey", false),
            includesRuntimeData = m.optBoolean("includesRuntimeData", false),
            missingPackages = missing,
        )
    }

    /** Called for each phase / progress tick. [phase] is a short stable
     *  identifier (extracting, applying-widgets, applying-wallpapers,
     *  applying-transitions, applying-icon-filters, applying-profiles,
     *  applying-layout, applying-prefs, finalising, done). [done] /
     *  [total] are bytes during extracting and 0/0 otherwise. */
    fun interface ProgressReporter {
        fun onProgress(phase: String, done: Long, total: Long)
    }

    /** Apply a previously-validated backup. The caller must re-open the
     *  InputStream — ZipInputStream from [validate] is exhausted.
     *  [totalBytes] is the size of the full backup (used to compute %)
     *  and is the [InputStream]'s available() if unknown; 0 disables
     *  the per-byte counter and progress runs in indeterminate mode. */
    @Throws(ImportException::class)
    fun apply(
        context: Context,
        input: InputStream,
        mode: Mode,
        totalBytes: Long = 0L,
        progress: ProgressReporter? = null,
    ) {
        val pending = File(context.filesDir, "_backup_pending").apply {
            deleteRecursively(); mkdirs()
        }
        try {
            // Extract the whole zip into the pending dir so we can validate
            // structure before mutating any live launcher files.
            var bytesSeen = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val safeName = sanitizeEntryName(entry.name)
                    if (safeName == null) {
                        zip.closeEntry(); continue
                    }
                    val outFile = File(pending, safeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val bytes = zip.readAllSafe()
                        outFile.writeBytes(bytes)
                        bytesSeen += bytes.size
                        progress?.onProgress("extracting", bytesSeen, totalBytes)
                    }
                    zip.closeEntry()
                }
            }

            // Sanity: manifest must be present. If not, the file was
            // probably just garbage — abort without touching anything.
            val manifestFile = File(pending, "manifest.json")
            if (!manifestFile.exists()) {
                throw ImportException("Backup is missing manifest.json — refusing to apply.")
            }

            // Replace mode: wipe live dirs first so id collisions can't
            // leave half a frankenstein layout.
            if (mode == Mode.REPLACE) {
                File(context.filesDir, "widgets").deleteRecursively()
                File(context.filesDir, "wallpapers").deleteRecursively()
                File(context.filesDir, "transitions").deleteRecursively()
                File(context.filesDir, "icon_filters").deleteRecursively()
                File(context.filesDir, "profiles").deleteRecursively()
            }

            // Apply each section. The order matters: layout depends on
            // widgets/wallpapers/transitions existing first so its
            // placements resolve.
            progress?.onProgress("applying-widgets", 0L, 0L)
            applyDirAsCopy(File(pending, "widgets"), File(context.filesDir, "widgets"), mode)
            progress?.onProgress("applying-wallpapers", 0L, 0L)
            applyFlatAsCopy(File(pending, "wallpapers"), File(context.filesDir, "wallpapers"), mode)
            progress?.onProgress("applying-transitions", 0L, 0L)
            applyFlatAsCopy(File(pending, "transitions"), File(context.filesDir, "transitions"), mode)
            // Icon filters live in slug-named subdirs (mirrors widgets).
            progress?.onProgress("applying-icon-filters", 0L, 0L)
            applyDirAsCopy(File(pending, "icon_filters"), File(context.filesDir, "icon_filters"), mode)
            // Profiles likewise.
            progress?.onProgress("applying-profiles", 0L, 0L)
            applyDirAsCopy(File(pending, "profiles"), File(context.filesDir, "profiles"), mode)

            // Restored profiles with Geofence triggers need their fences
            // (re-)registered with Play Services. Without this, geofence-
            // triggered auto-switching for imported profiles wouldn't fire
            // until the next launcher process cold-start (which is the
            // only other place reRegisterAll runs). Best-effort —
            // Play Services availability isn't guaranteed.
            try {
                com.iappyx.launcher.profile.ProfileGeofenceManager.reRegisterAll(context)
            } catch (_: Throwable) { /* import succeeds either way */ }

            // Layout is overwritten in REPLACE; left alone in MERGE.
            if (mode == Mode.REPLACE) {
                progress?.onProgress("applying-layout", 0L, 0L)
                val layoutFile = File(pending, "layout/home_layout.json")
                if (layoutFile.exists()) {
                    val layout = HomeLayout.fromJson(JSONObject(layoutFile.readText()))
                    PlacementStore(context).save(layout)
                }
            }

            // Prefs — apply in REPLACE mode (full restore) or only
            // selectively in MERGE? For now: only REPLACE applies prefs;
            // merges keep the user's current grid size + active
            // wallpaper. Less surprising.
            if (mode == Mode.REPLACE) {
                val prefsFile = File(pending, "prefs.json")
                if (prefsFile.exists()) {
                    applyPrefs(context, JSONObject(prefsFile.readText()))
                }
            }

            // Optional: API key. Only restored when present in the backup.
            // Always restored regardless of mode — the user already
            // opted into including it during export.
            val keyFile = File(pending, "api_key.txt")
            if (keyFile.exists()) {
                SecureStore(context).anthropicKey = keyFile.readText().trim()
            }

            // Optional: runtime data — shared iappyx_store (per-widget saved
            // key/values). Restored when present. CRITICAL: in MERGE mode we
            // merge keys WITHOUT clearing, so existing widgets keep their saved
            // data; only REPLACE does a full clear+restore. (Previously this
            // cleared unconditionally, so a MERGE import silently wiped every
            // existing widget's data.)
            val runtimeFile = File(pending, "runtime/iappyx_store.json")
            if (runtimeFile.exists()) {
                applySharedPrefs(
                    context, "iappyx_store", JSONObject(runtimeFile.readText()),
                    clearFirst = (mode == Mode.REPLACE),
                )
            }
            progress?.onProgress("done", 0L, 0L)
        } finally {
            // Clean up the scratch dir whether we succeeded or not.
            try { pending.deleteRecursively() } catch (_: Throwable) {}
        }
    }

    /** Find packages referenced by ICON / FOLDER placements in [layoutJson]
     *  that aren't currently installed. Used for the pre-apply warning. */
    private fun findMissingPackages(context: Context, layoutJson: String): List<String> {
        val pm = context.packageManager
        val missing = mutableSetOf<String>()
        try {
            val layout = HomeLayout.fromJson(JSONObject(layoutJson))
            val seen = mutableSetOf<String>()
            fun check(p: Placement) {
                p.packageName?.let { pkg ->
                    if (pkg in seen) return@let
                    seen.add(pkg)
                    try { pm.getApplicationInfo(pkg, 0) }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        missing.add(pkg)
                    }
                }
                for (item in p.folderItems) {
                    if (item.packageName in seen) continue
                    seen.add(item.packageName)
                    try { pm.getApplicationInfo(item.packageName, 0) }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        missing.add(item.packageName)
                    }
                }
            }
            for (page in layout.pages) for (p in page.placements) check(p)
            for (dockPage in layout.dockPages) for (p in dockPage) check(p)
        } catch (_: Throwable) { /* best-effort */ }
        return missing.toList().sorted()
    }

    /** Reject zip entries that would escape the pending dir (path
     *  traversal via `../` or absolute paths) or that target reserved
     *  system files. Returns the cleaned name, or null to skip. */
    private fun sanitizeEntryName(raw: String): String? {
        if (raw.isEmpty() || raw.contains("..") || raw.startsWith("/")) return null
        // Forbid absolute or device-specific paths.
        if (raw.contains("\\")) return null
        return raw
    }

    /** Copy widgets/{uuid}/(files) directories. In MERGE mode, skip uuids
     *  that already exist locally so we don't overwrite user state. */
    private fun applyDirAsCopy(src: File, dst: File, mode: Mode) {
        if (!src.isDirectory) return
        dst.mkdirs()
        for (sub in src.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val targetDir = File(dst, sub.name)
            if (mode == Mode.MERGE && targetDir.exists()) continue
            targetDir.deleteRecursively()
            sub.copyRecursively(targetDir, overwrite = true)
        }
    }

    /** Copy wallpapers / transitions whose layout is flat (`{uuid}.html` +
     *  `{uuid}.json`). Skips a file pair if its uuid already exists locally
     *  in MERGE mode. */
    private fun applyFlatAsCopy(src: File, dst: File, mode: Mode) {
        if (!src.isDirectory) return
        dst.mkdirs()
        for (f in src.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val target = File(dst, f.name)
            if (mode == Mode.MERGE && target.exists()) continue
            f.copyTo(target, overwrite = true)
        }
    }

    private fun applyPrefs(context: Context, json: JSONObject) {
        applySharedPrefs(context, "iappyx_launcher_prefs", json)
    }

    /** Generic SharedPreferences applier — reads the typed JSON shape
     *  written by [BackupExporter.buildPrefsJson] and re-encodes each
     *  key into the right SharedPreferences method. */
    private fun applySharedPrefs(context: Context, name: String, json: JSONObject, clearFirst: Boolean = true) {
        val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        if (clearFirst) ed.clear()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = json.optJSONObject(k) ?: continue
            when (entry.optString("type")) {
                "bool" -> ed.putBoolean(k, entry.optBoolean("v", false))
                "int" -> ed.putInt(k, entry.optInt("v", 0))
                "long" -> ed.putLong(k, entry.optLong("v", 0L))
                "float" -> ed.putFloat(k, entry.optDouble("v", 0.0).toFloat())
                "string" -> ed.putString(k, entry.optString("v", ""))
                "stringset" -> {
                    val arr = entry.optJSONArray("v")
                    if (arr != null) {
                        val s = (0 until arr.length()).mapNotNullTo(mutableSetOf()) {
                            arr.optString(it).takeIf { v -> v.isNotEmpty() }
                        }
                        ed.putStringSet(k, s)
                    }
                }
            }
        }
        ed.apply()
    }

    /** ZipInputStream.readBytes() doesn't exist on the streamed-entry
     *  shape — read in 8KB chunks until we hit -1. Same as what
     *  ByteArrayOutputStream does internally. */
    private fun ZipInputStream.readAllSafe(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
