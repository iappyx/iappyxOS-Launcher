/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — catalog of installed plugins. P1: discovers bundled plugins
 * from `assets/plugins/<id>/manifest.json`. P4 adds user installs under
 * `filesDir/plugins/<id>/`; both sources surface through the same
 * `all()` / `get()` API.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import org.json.JSONObject
import java.io.File

internal object PluginRegistry {

    enum class Source {
        /** Shipped in the launcher APK at `assets/plugins/<id>/`. Cannot
         *  be uninstalled, only disabled. */
        BUNDLED,
        /** User-installed under `filesDir/plugins/<id>/`. Uninstall
         *  removes the directory. (P4) */
        USER,
    }

    data class Entry(
        val manifest: PluginManifest,
        val source: Source,
        val enabled: Boolean,
    )

    /** All plugins known to the launcher (bundled + user-installed),
     *  with their current enabled state. Cheap enough to call each
     *  time — the manifest list rarely exceeds ~10 entries. */
    fun all(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        for (m in bundledManifests(context)) {
            out.add(Entry(m, Source.BUNDLED, isEnabled(context, m.id, defaultEnabled = true)))
        }
        for (m in userManifests(context)) {
            out.add(Entry(m, Source.USER, isEnabled(context, m.id, defaultEnabled = false)))
        }
        return out
    }

    fun get(context: Context, id: String): Entry? =
        all(context).firstOrNull { it.manifest.id == id }

    fun isEnabled(context: Context, id: String): Boolean {
        // Default-enabled status depends on source; resolve once via all()
        // to keep the rule in one place.
        val entry = get(context, id) ?: return false
        return entry.enabled
    }

    private fun isEnabled(context: Context, id: String, defaultEnabled: Boolean): Boolean =
        PluginPrefs.isEnabled(context, id, defaultEnabled)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        PluginPrefs.setEnabled(context, id, enabled)
    }

    /** Returns null when the plugin doesn't exist or its file isn't
     *  present in the source. Bundled assets are read via AssetManager;
     *  user plugins from filesDir. */
    fun readPluginFile(context: Context, id: String, file: String): ByteArray? {
        val entry = get(context, id) ?: return null
        return try {
            when (entry.source) {
                Source.BUNDLED -> context.assets.open("plugins/$id/$file").use { it.readBytes() }
                Source.USER -> File(userRoot(context), "$id/$file").readBytes()
            }
        } catch (_: Throwable) { null }
    }

    /** Asset / file URL the plugin's HTML can use as a relative-asset
     *  base. Returned by PluginHost when it composes the load URL —
     *  not used by external callers. */
    internal fun baseUrlFor(context: Context, entry: Entry): String = when (entry.source) {
        Source.BUNDLED -> "file:///android_asset/plugins/${entry.manifest.id}/"
        Source.USER -> "file://${File(userRoot(context), entry.manifest.id).absolutePath}/"
    }

    private fun bundledManifests(context: Context): List<PluginManifest> {
        val out = mutableListOf<PluginManifest>()
        val ids = try {
            context.assets.list("plugins")?.toList() ?: emptyList()
        } catch (_: Throwable) { emptyList() }
        for (id in ids) {
            val m = readBundledManifest(context, id) ?: continue
            out.add(m)
        }
        return out
    }

    private fun readBundledManifest(context: Context, id: String): PluginManifest? {
        return try {
            val text = context.assets.open("plugins/$id/manifest.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            PluginManifest.fromJson(JSONObject(text))
        } catch (_: Throwable) { null }
    }

    private fun userManifests(context: Context): List<PluginManifest> {
        val root = userRoot(context)
        if (!root.exists()) return emptyList()
        val out = mutableListOf<PluginManifest>()
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) continue
            val m = try {
                PluginManifest.fromJson(JSONObject(manifestFile.readText(Charsets.UTF_8)))
            } catch (_: Throwable) { null }
            if (m != null) out.add(m)
        }
        return out
    }

    /** Directory holding user-installed plugins. Lives under the
     *  launcher's filesDir so backup/restore picks it up automatically
     *  via the existing backup tree-copy machinery. */
    internal fun userRoot(context: Context): File =
        File(context.filesDir, "plugins")
}
