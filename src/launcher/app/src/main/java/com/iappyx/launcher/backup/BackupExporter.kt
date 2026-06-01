/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.backup

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.ai.SecureStore
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a `.iappyxbackup` archive — a regular ZIP under the hood, with a
 * top-level `manifest.json` describing what's inside. Output:
 *
 * ```
 * manifest.json
 * layout/home_layout.json
 * widgets/{uuid}/widget.html
 * widgets/{uuid}/meta.json
 * wallpapers/{uuid}.html
 * wallpapers/{uuid}.json
 * transitions/{uuid}.json
 * transitions/{uuid}.meta.json
 * prefs.json                    ← whitelisted launcher prefs
 * api_key.txt                   ← optional, plaintext, gated by [Options.includeApiKey]
 * runtime/iappyx_store.json     ← optional, widget shared key/value, gated by [Options.includeRuntimeData]
 * ```
 *
 * Format choices:
 *  - One zip per backup (no streaming) — backups are small (mostly text),
 *    rarely > a few hundred KB even with many widgets.
 *  - JSON for everything we control (manifest, prefs, runtime store) so
 *    a curious user can unzip and read the contents.
 *  - User HTML / JSON files copied verbatim (no transcoding) so a backup
 *    + restore is bit-exact for content.
 *
 * Caller is responsible for closing the [OutputStream]. We close the zip
 * stream we layer on top, but not the user's underlying stream.
 */
object BackupExporter {

    /** Bumped when the on-disk format changes incompatibly. The importer
     *  refuses backups with a higher version than it understands. */
    const val MANIFEST_VERSION = 1

    data class Options(
        /** Include the API key in the backup as plaintext. Off by default —
         *  the key would otherwise be re-encrypted on the new device by
         *  SecureStore, which works, but the backup file itself becomes
         *  sensitive and the user should opt in. */
        val includeApiKey: Boolean = false,
        /** Include the shared `iappyx_store` SharedPreferences (widgets'
         *  saved key/value state). On by default — most users expect
         *  counters / todo lists / habit-tracker history to survive a
         *  device move. Adds a few KB at most. */
        val includeRuntimeData: Boolean = true,
    )

    /** Result of a successful export — counts the manifest will reflect.
     *  Returned to the UI so it can show "exported 12 widgets, 4 wallpapers". */
    data class Result(
        val widgetCount: Int,
        val wallpaperCount: Int,
        val transitionCount: Int,
        val homePageCount: Int,
    )

    /** Throws on I/O error or when filesDir is unreadable. Caller catches. */
    @Throws(java.io.IOException::class)
    fun export(context: Context, out: OutputStream, options: Options): Result {
        val zip = ZipOutputStream(out)
        try {
            // 1. layout — always included
            val layout = PlacementStore(context).load()
            zip.put("layout/home_layout.json", layout.toJson().toString().toByteArray())

            // 2. widgets — copy each user-generated dir verbatim
            val widgetCount = copyDirEntries(zip, File(context.filesDir, "widgets"), "widgets/")

            // 3. wallpapers — copy {uuid}.html + {uuid}.json
            val wallpaperCount = copyFlatPair(
                zip, File(context.filesDir, "wallpapers"), "wallpapers/",
                primaryExt = ".html", secondaryExt = ".json",
            )

            // 4. transitions — copy {uuid}.json + {uuid}.meta.json
            val transitionCount = copyFlatPair(
                zip, File(context.filesDir, "transitions"), "transitions/",
                primaryExt = ".json", secondaryExt = ".meta.json",
                // Skip files whose name contains ".meta." when listing the
                // primary — the secondary pass picks them up.
                primarySkipIfNameContains = ".meta.",
            )

            // 4b. icon filters — each is a subdir with spec.json + meta.json
            val iconFilterCount = copyDirAny(
                zip, File(context.filesDir, "icon_filters"), "icon_filters/",
                requireFile = "spec.json",
            )

            // 4c. profiles — each is a subdir with profile.json
            val profileCount = copyDirAny(
                zip, File(context.filesDir, "profiles"), "profiles/",
                requireFile = "profile.json",
            )

            // 5. prefs — whitelist (no firstRunPending, no lastBackupAt)
            zip.put("prefs.json", buildPrefsJson(context).toString().toByteArray())

            // 6. optional: API key
            if (options.includeApiKey) {
                val key = SecureStore(context).anthropicKey
                if (!key.isNullOrBlank()) {
                    zip.put("api_key.txt", key.toByteArray())
                }
            }

            // 7. optional: widget runtime data (shared iappyx_store prefs)
            if (options.includeRuntimeData) {
                val storeJson = serializeSharedPrefs(context, "iappyx_store")
                zip.put("runtime/iappyx_store.json", storeJson.toString().toByteArray())
            }

            // 8. manifest — written LAST so its counts reflect what we
            //    actually wrote (avoids drift if a copy step threw).
            val manifest = JSONObject().apply {
                put("version", MANIFEST_VERSION)
                put("createdAt", System.currentTimeMillis())
                put("deviceModel", android.os.Build.MODEL ?: "")
                put("includesApiKey", options.includeApiKey)
                put("includesRuntimeData", options.includeRuntimeData)
                put("counts", JSONObject().apply {
                    put("widgets", widgetCount)
                    put("wallpapers", wallpaperCount)
                    put("transitions", transitionCount)
                    put("iconFilters", iconFilterCount)
                    put("profiles", profileCount)
                    put("homePages", layout.pages.size)
                })
            }
            zip.put("manifest.json", manifest.toString(2).toByteArray())

            // Bookkeeping — surface "Last backup: 2 minutes ago" in Settings.
            try {
                LauncherPrefs(context).lastBackupAt = System.currentTimeMillis()
            } catch (_: Throwable) { /* best-effort */ }

            return Result(widgetCount, wallpaperCount, transitionCount, layout.pages.size)
        } finally {
            try { zip.close() } catch (_: Throwable) {}
        }
    }

    /** Walk [dir]'s immediate children — each child is itself a directory
     *  (the widget's UUID); copy every regular file inside (preserving the
     *  flat shape `widgets/{uuid}/file`). Returns the number of widget
     *  directories captured.
     *
     *  Skips orphan dirs that don't contain a `widget.html` — the launcher
     *  accumulates these over time because [WidgetSandbox.sandboxFor]
     *  eagerly mkdirs() the per-instance sandbox even for asset-based
     *  placements (demo clock, etc.) which never write a widget.html.
     *  Without this filter, exports were reporting "247 widgets" on devices
     *  whose manage tab only showed a handful — the count was including
     *  every stub sandbox dir.
     *
     *  Mirrors [WidgetLibrary.all]'s filter so the exported count always
     *  matches what the user sees in the manage tab. */
    private fun copyDirEntries(zip: ZipOutputStream, dir: File, prefix: String): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        for (sub in dir.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            // Same definition of "valid widget" the manage tab uses.
            if (!File(sub, "widget.html").isFile) continue
            count++
            for (f in sub.listFiles().orEmpty()) {
                if (!f.isFile) continue
                zip.put("$prefix${sub.name}/${f.name}", f.readBytes())
            }
        }
        return count
    }

    /** Generic per-subdir copy for artefact types whose layout is
     *  `{root}/{slug}/{requireFile + sidecars}`. Used for icon filters
     *  ({slug}/spec.json + meta.json) and profiles ({slug}/profile.json).
     *  Skips slugs missing the [requireFile] marker so half-written
     *  installs don't end up in the backup. */
    private fun copyDirAny(
        zip: ZipOutputStream, dir: File, prefix: String, requireFile: String,
    ): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        for (sub in dir.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            if (!File(sub, requireFile).isFile) continue
            count++
            for (f in sub.listFiles().orEmpty()) {
                if (!f.isFile) continue
                zip.put("$prefix${sub.name}/${f.name}", f.readBytes())
            }
        }
        return count
    }

    /** Wallpaper / transition pattern: a flat dir of `{uuid}.html + .json`
     *  pairs (or `{uuid}.json + .meta.json` for transitions). We only
     *  include pairs where BOTH files exist — so an orphan `.html` without
     *  its meta (or vice versa) is dropped, matching what the corresponding
     *  Library considers a real entry. */
    private fun copyFlatPair(
        zip: ZipOutputStream,
        dir: File,
        prefix: String,
        primaryExt: String,
        secondaryExt: String,
        primarySkipIfNameContains: String? = null,
    ): Int {
        if (!dir.isDirectory) return 0
        // First pass: collect uuid → which-files-exist so we can require
        // both halves of a pair before copying. The secondary-extension
        // names sometimes end in `.json` too (transition meta), so the
        // skip-substring filter disambiguates which half is which.
        data class Pair(var primary: File? = null, var secondary: File? = null)
        val pairs = mutableMapOf<String, Pair>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val name = f.name
            val isPrimary = name.endsWith(primaryExt) &&
                (primarySkipIfNameContains == null || !name.contains(primarySkipIfNameContains))
            val isSecondary = name.endsWith(secondaryExt) &&
                // For transitions: secondary is `.meta.json`. The naive
                // ".endsWith(.json)" would also match the primary, so we
                // use the substring as the discriminator.
                (primarySkipIfNameContains == null || name.contains(primarySkipIfNameContains))
            if (isPrimary) {
                val uuid = name.removeSuffix(primaryExt)
                pairs.getOrPut(uuid) { Pair() }.primary = f
            } else if (isSecondary) {
                val uuid = name.removeSuffix(secondaryExt)
                pairs.getOrPut(uuid) { Pair() }.secondary = f
            }
        }
        var count = 0
        for ((_, pair) in pairs) {
            val p = pair.primary ?: continue
            val s = pair.secondary ?: continue
            count++
            zip.put("$prefix${p.name}", p.readBytes())
            zip.put("$prefix${s.name}", s.readBytes())
        }
        return count
    }

    /** Build the prefs.json — only includes keys we want to restore. The
     *  exclusion list (firstRunPending, lastBackupAt) keeps imported
     *  devices from suppressing their own first-run flow or pretending
     *  they have a recent backup. */
    private fun buildPrefsJson(context: Context): JSONObject {
        val src = context.getSharedPreferences("iappyx_launcher_prefs", Context.MODE_PRIVATE)
        val skip = setOf("first_run_pending", "last_backup_at")
        val out = JSONObject()
        for ((k, v) in src.all) {
            if (k in skip) continue
            // SharedPreferences values are typed; emit a {type, value}
            // pair so the importer can put them back with the right
            // method. Keeps round-trip exact.
            val entry = JSONObject()
            when (v) {
                is Boolean -> { entry.put("type", "bool"); entry.put("v", v) }
                is Int     -> { entry.put("type", "int"); entry.put("v", v) }
                is Long    -> { entry.put("type", "long"); entry.put("v", v) }
                is Float   -> { entry.put("type", "float"); entry.put("v", v.toDouble()) }
                is String  -> { entry.put("type", "string"); entry.put("v", v) }
                is Set<*>  -> {
                    entry.put("type", "stringset")
                    entry.put("v", org.json.JSONArray(v.filterIsInstance<String>()))
                }
                else -> continue // unknown type, drop
            }
            out.put(k, entry)
        }
        return out
    }

    private fun serializeSharedPrefs(context: Context, name: String): JSONObject {
        val src = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in src.all) {
            // iappyx_store is a key/value store widgets use via
            // iappyx.save / iappyx.load — values are always strings (the
            // bridge's contract), but we encode defensively.
            val entry = JSONObject()
            when (v) {
                is String -> { entry.put("type", "string"); entry.put("v", v) }
                is Boolean -> { entry.put("type", "bool"); entry.put("v", v) }
                is Int -> { entry.put("type", "int"); entry.put("v", v) }
                is Long -> { entry.put("type", "long"); entry.put("v", v) }
                is Float -> { entry.put("type", "float"); entry.put("v", v.toDouble()) }
                else -> continue
            }
            out.put(k, entry)
        }
        return out
    }

    /** Small helper so call-sites don't have to remember the entry +
     *  closeEntry dance. ZipOutputStream eats this for breakfast. */
    private fun ZipOutputStream.put(path: String, data: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(data)
        closeEntry()
    }
}
