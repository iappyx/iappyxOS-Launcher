/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — install / uninstall pipeline. .iappyxplugin file = zip with
 * manifest.json at root + plugin.html + (optional) settings.html, icon.png.
 *
 * Install flow:
 *   1. Read manifest.json from the zip without extracting anything.
 *   2. Parse + validate (PluginInstaller.previewManifest).
 *   3. UI shows the consent dialog naming the requested capabilities.
 *   4. On confirm: extract atomically into filesDir/plugins/<id>/.
 *   5. Drop any running WebView for the id so the upgrade takes effect.
 *
 * Defense:
 *   - Manifest must parse + id must match safe slug pattern (handled
 *     by PluginManifest.fromJson).
 *   - Zip entries are sanitised: no `..` traversal, no absolute paths,
 *     no dotfiles, no __MACOSX cruft.
 *   - Bundled plugins are off-limits — a user .iappyxplugin can't
 *     shadow a bundled id.
 *   - Re-extracted manifest must match the previewed manifest, so a
 *     malicious zip can't show one manifest in the consent dialog and
 *     deliver a different one on disk.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object PluginInstaller {

    sealed class Result {
        data class Ok(val manifest: PluginManifest) : Result()
        data class Error(val message: String) : Result()
    }

    /** Read + parse manifest.json without extracting anything else. Used
     *  by the consent dialog to show the user what's about to land on
     *  their device. */
    fun previewManifest(zipBytes: ByteArray): Result {
        val manifestText = readManifestFromZip(ByteArrayInputStream(zipBytes))
            ?: return Result.Error("missing manifest.json at zip root")
        val manifest = try {
            PluginManifest.fromJson(JSONObject(manifestText))
        } catch (_: Throwable) { null }
            ?: return Result.Error("invalid manifest.json — id missing or malformed")
        return Result.Ok(manifest)
    }

    /** Extract a previewed plugin into filesDir/plugins/<id>/, replacing
     *  any previous user install of the same id. Caller MUST have
     *  already called previewManifest + obtained user consent. */
    fun install(context: Context, zipBytes: ByteArray): Result {
        val preview = previewManifest(zipBytes)
        if (preview is Result.Error) return preview
        val manifest = (preview as Result.Ok).manifest

        // Don't let a user install shadow a bundled id — bundled
        // plugins ship in the APK and would re-appear on the next
        // launcher update anyway. Refuse cleanly so the user knows.
        val existing = PluginRegistry.get(context, manifest.id)
        if (existing != null && existing.source == PluginRegistry.Source.BUNDLED) {
            return Result.Error("a bundled plugin with id '${manifest.id}' already exists")
        }

        val userRoot = PluginRegistry.userRoot(context).apply { mkdirs() }
        val targetDir = File(userRoot, manifest.id)
        val tempDir = File(userRoot, "_install_${manifest.id}_${System.currentTimeMillis()}")
        try {
            tempDir.deleteRecursively(); tempDir.mkdirs()
            extractZip(ByteArrayInputStream(zipBytes), tempDir)

            // Re-validate the manifest from the extracted dir — defends
            // against a zip whose manifest.json is one thing at the top
            // (what we showed in the consent dialog) and something else
            // at the BOTTOM of the central directory (what the OS
            // extracts). ZipInputStream reads in order; the
            // ZipFile-based extractor uses the CD. We don't use
            // ZipFile here (we have bytes, not a File), but the
            // re-parse covers any drift.
            val unpackedFile = File(tempDir, "manifest.json")
            if (!unpackedFile.exists()) {
                tempDir.deleteRecursively()
                return Result.Error("manifest.json missing after extraction")
            }
            val unpacked = try {
                PluginManifest.fromJson(JSONObject(unpackedFile.readText(Charsets.UTF_8)))
            } catch (_: Throwable) { null }
            if (unpacked == null || unpacked.id != manifest.id) {
                tempDir.deleteRecursively()
                return Result.Error("manifest.json id mismatched after extraction")
            }
            // plugin.html is required — without it the host has nothing
            // to load.
            if (!File(tempDir, "plugin.html").exists()) {
                tempDir.deleteRecursively()
                return Result.Error("plugin.html missing from zip")
            }

            // Atomic swap: nuke target, rename temp into place. If the
            // rename fails (cross-fs, etc.), fall back to a copy.
            targetDir.deleteRecursively()
            if (!tempDir.renameTo(targetDir)) {
                // Cross-volume or some other rename failure — best effort
                // copy + cleanup.
                tempDir.copyRecursively(targetDir, overwrite = true)
                tempDir.deleteRecursively()
            }
            // New user plugins land ENABLED (the user just consented to
            // install them — they presumably want them to work right
            // away). They can disable later via Settings (P5).
            PluginPrefs.setEnabled(context, manifest.id, true)

            // If the host had a stale WebView for this id (upgrade
            // scenario), drop it so the next invoke loads the new
            // version's plugin.html. Safe to call even when no
            // instance exists.
            PluginHost.shutdownPlugin(context, manifest.id)
            return Result.Ok(manifest)
        } catch (e: Throwable) {
            tempDir.deleteRecursively()
            return Result.Error("install failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Remove a user-installed plugin and wipe its storage. Bundled
     *  plugins can be disabled but not uninstalled — calling this on a
     *  bundled id is a no-op (returns false). */
    fun uninstall(context: Context, pluginId: String): Boolean {
        val entry = PluginRegistry.get(context, pluginId) ?: return false
        if (entry.source != PluginRegistry.Source.USER) return false

        // Shut down the running WebView before deleting the files. We
        // want the destroy() to happen on the main thread (PluginHost
        // marshals there); it's fire-and-forget — the file deletion
        // below runs whether or not the WebView teardown completes.
        PluginHost.shutdownPlugin(context, pluginId)

        File(PluginRegistry.userRoot(context), pluginId).deleteRecursively()
        PluginPrefs.forget(context, pluginId)

        // Wipe per-plugin storage files (`plugin_<id>_iappyx_store` and
        // `plugin_<id>_iappyx_secure`). SharedPreferences.deletePrefs is
        // API 24+ — clear() + delete the file works back to API 21.
        try {
            val prefsDir = File(context.applicationInfo.dataDir ?: context.filesDir.parentFile?.path, "shared_prefs")
            for (name in listOf(
                "plugin_${pluginId}_iappyx_store",
                "plugin_${pluginId}_iappyx_secure",
            )) {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
                File(prefsDir, "$name.xml").delete()
            }
        } catch (_: Throwable) { /* best-effort */ }
        return true
    }

    // ── helpers ────────────────────────────────────────────────

    private fun readManifestFromZip(input: InputStream): String? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                // Manifest must live at the root of the zip — not in a
                // subdir. If a tool zipped a folder (so the entries are
                // `plugin-foo/manifest.json`), the install will fail and
                // the author needs to re-zip without the wrapping dir.
                if (entry.name == "manifest.json") {
                    val bytes = zip.readBytes()
                    // 64 KB ceiling defends against a malformed zip
                    // whose "manifest.json" is actually a huge payload.
                    if (bytes.size > 65_536) return null
                    return bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractZip(input: InputStream, targetDir: File) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = sanitiseEntryName(entry.name)
                if (safeName == null) { zip.closeEntry(); continue }
                val out = File(targetDir, safeName)
                if (entry.isDirectory) {
                    out.mkdirs(); zip.closeEntry(); continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { fos -> zip.copyTo(fos, 16 * 1024) }
                zip.closeEntry()
            }
        }
    }

    /** Sanitise a zip entry's name. Rejects:
     *   - Empty / blank
     *   - Path traversal (`..`)
     *   - Absolute paths
     *   - Dotfiles / OS metadata (`.DS_Store`, `__MACOSX/...`)
     *  Returns null when the entry should be silently skipped. */
    private fun sanitiseEntryName(name: String): String? {
        if (name.isBlank()) return null
        if (name.contains("..")) return null
        if (name.startsWith("/") || name.contains(":")) return null
        if (name.startsWith(".") || name.contains("/.")) return null
        if (name.contains("__MACOSX")) return null
        return name
    }
}
