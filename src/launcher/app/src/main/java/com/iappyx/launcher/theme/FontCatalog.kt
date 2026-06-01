/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Downloadable theme fonts (open-source, OFL 1.1). The catalog ships as
 * assets/fonts/catalog.json (metadata only, ~few KB); the .ttf files are
 * fetched on demand from the canonical Google Fonts GitHub repo (see
 * FontDownloader) into filesDir/fonts/ and cached. The 6 always-available
 * fonts are NOT here — they're bundled in assets (see ThemeFonts.ALL).
 */
package com.iappyx.launcher.theme

import android.content.Context
import org.json.JSONObject
import java.io.File

object FontCatalog {

    data class Entry(
        val family: String,
        val file: String,
        val fallback: ThemeFonts.Fallback,
        val variable: Boolean,
        val url: String,
    )

    @Volatile private var cached: List<Entry>? = null

    fun all(context: Context): List<Entry> {
        cached?.let { return it }
        val list = try {
            val raw = context.applicationContext.assets.open("fonts/catalog.json")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(raw)
            val base = o.optString("base", "https://github.com/google/fonts/raw/main/")
            val arr = o.getJSONArray("fonts")
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                Entry(
                    family = f.getString("family"),
                    file = f.getString("file"),
                    fallback = when (f.optString("fallback")) {
                        "serif" -> ThemeFonts.Fallback.SERIF
                        "mono" -> ThemeFonts.Fallback.MONO
                        else -> ThemeFonts.Fallback.SANS
                    },
                    variable = f.optBoolean("variable", true),
                    url = base + f.getString("path"),
                )
            }
        } catch (_: Throwable) { emptyList() }
        cached = list
        return list
    }

    fun byFamily(context: Context, family: String): Entry? =
        all(context).firstOrNull { it.family.equals(family, ignoreCase = true) }

    /** The catalog font named (by family) in a `--iappyx-font` stack, if any. */
    fun fromStack(context: Context, stack: String): Entry? =
        all(context).firstOrNull {
            stack.contains("\"${it.family}\"", ignoreCase = true) || stack.contains(it.family, ignoreCase = true)
        }

    fun dir(context: Context): File = File(context.filesDir, "fonts").also { it.mkdirs() }
    fun localFile(context: Context, e: Entry): File = File(dir(context), e.file)

    // Cache the set of downloaded font filenames so isDownloaded() — called per
    // catalog entry while building every widget's @font-face block — is a set
    // lookup, not a filesystem stat each time. Invalidated on download/delete.
    @Volatile private var downloadedCache: Set<String>? = null
    fun invalidateDownloaded() { downloadedCache = null }
    private fun downloadedFiles(context: Context): Set<String> {
        downloadedCache?.let { return it }
        val s = dir(context).listFiles()
            ?.mapNotNull { if (it.isFile && it.length() > 0 && !it.name.endsWith(".tmp")) it.name else null }
            ?.toSet() ?: emptySet()
        downloadedCache = s
        return s
    }

    fun isDownloaded(context: Context, e: Entry): Boolean = e.file in downloadedFiles(context)

    fun cssStack(e: Entry): String = "\"${e.family}\", ${e.fallback.stack}"
}
