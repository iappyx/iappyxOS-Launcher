/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Resolves an icon filter slug to its compiled [IconFilterSpec]. Bundled
 * specs live in `assets/icon_filters/{slug}.json`; user-installed (and
 * AI-generated) specs live in `filesDir/icon_filters/{slug}/spec.json`.
 *
 * Mirrors [com.iappyx.launcher.transitions.TransitionLibrary] /
 * [com.iappyx.launcher.wallpaper.WallpaperLibrary] for surface-consistency.
 *
 * Specs are parsed lazily and cached. [invalidateAll] is called when the
 * user changes their active filter (so the cache stays fresh) and when an
 * AI iterate / rename rewrites a spec.
 */
object IconFilterRegistry {

    /** Stable list of bundled slugs, in chooser-display order. Kept here
     *  rather than in IconFilter.kt so that file can shrink to a slim
     *  legacy enum and eventually be removed. */
    val BUNDLED_SLUGS = listOf(
        "none",
        "greyscale",
        "sepia",
        "vintage",
        "mono_accent",
        "rainbow_matrix",
        "wallpaper_themed",
        "pixelate",
        "tinted_mono",
        "aurora",
        "sweetheart",
        "squircle",
        "star",
    )

    private val cache = mutableMapOf<String, IconFilterSpec?>()

    fun userDir(context: Context): File =
        File(context.filesDir, "icon_filters").also { it.mkdirs() }

    /** Parsed spec for [slug], or null when nothing parses. The "none"
     *  fallback is constructed in-process so a corrupt assets dir never
     *  breaks rendering. */
    fun resolve(context: Context, slug: String): IconFilterSpec {
        cache[slug]?.let { return it }
        val parsed = loadParsed(context, slug) ?: noneSpec
        cache[slug] = parsed
        return parsed
    }

    private fun loadParsed(context: Context, slug: String): IconFilterSpec? {
        // Bundled first so user can't shadow a builtin by naming a custom
        // spec with the same slug.
        if (slug in BUNDLED_SLUGS) {
            return try {
                val raw = context.assets.open("icon_filters/$slug.json")
                    .bufferedReader().use { it.readText() }
                IconFilterSpec.fromJson(JSONObject(raw))
            } catch (_: Throwable) { null }
        }
        val f = File(userDir(context), "$slug/spec.json")
        if (!f.exists()) return null
        return try { IconFilterSpec.fromJson(JSONObject(f.readText())) }
        catch (_: Throwable) { null }
    }

    fun invalidate(slug: String) { cache.remove(slug) }
    fun invalidateAll() { cache.clear() }

    /** Constant fallback. Defined here (not loaded from JSON) so even a
     *  totally broken assets folder still renders unfiltered icons. */
    val noneSpec = IconFilterSpec(slug = "none", name = "None", subtitle = null)

    /** Library entry shown in the manage tab. Mirrors [TransitionLibrary.Entry]. */
    data class Entry(
        val slug: String,
        val title: String,
        val subtitle: String,
        val createdAt: Long,
        val isUserGenerated: Boolean,
    )

    /** Every entry, newest user-generated first then bundled. The bundled
     *  list order matches [BUNDLED_SLUGS] so the chooser sheet keeps a
     *  predictable layout. */
    fun all(context: Context): List<Entry> {
        val bundled = BUNDLED_SLUGS.map { slug ->
            val spec = resolve(context, slug)
            Entry(slug, spec.name, spec.subtitle.orEmpty(), 0L, isUserGenerated = false)
        }
        val user = userDir(context).listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val spec = File(dir, "spec.json").takeIf { it.exists() } ?: return@mapNotNull null
                val meta = File(dir, "meta.json")
                val slug = dir.name
                try {
                    val parsed = IconFilterSpec.fromJson(JSONObject(spec.readText()))
                    val metaObj = if (meta.exists())
                        JSONObject(meta.readText()) else JSONObject()
                    Entry(
                        slug = slug,
                        title = metaObj.optString("title").ifBlank { parsed.name },
                        subtitle = metaObj.optString("prompt").ifBlank {
                            parsed.subtitle ?: "AI-generated"
                        }.take(80),
                        createdAt = metaObj.optLong("createdAt", 0L),
                        isUserGenerated = true,
                    )
                } catch (_: Throwable) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        return user + bundled
    }

    fun isUserGenerated(slug: String): Boolean = slug !in BUNDLED_SLUGS

    fun delete(context: Context, slug: String): Boolean {
        if (!isUserGenerated(slug)) return false
        val dir = File(userDir(context), slug)
        if (!dir.exists()) return false
        invalidate(slug)
        return dir.deleteRecursively()
    }

    fun renameUser(context: Context, slug: String, newTitle: String): Boolean {
        if (!isUserGenerated(slug)) return false
        val clean = newTitle.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isEmpty()) return false
        val meta = File(userDir(context), "$slug/meta.json")
        if (!meta.exists()) return false
        return try {
            val o = JSONObject(meta.readText())
            o.put("title", clean)
            meta.writeText(o.toString(), Charsets.UTF_8)
            // Also update the parsed spec's display name so resolve() returns
            // the new title without a process restart.
            val specFile = File(userDir(context), "$slug/spec.json")
            if (specFile.exists()) {
                val s = JSONObject(specFile.readText())
                s.put("name", clean)
                specFile.writeText(s.toString(), Charsets.UTF_8)
            }
            invalidate(slug)
            true
        } catch (_: Throwable) { false }
    }

    /** Persist meta for a freshly-generated filter. The actual spec.json is
     *  written separately (it's the AI output verbatim). Idempotent. */
    fun writeMeta(context: Context, slug: String, prompt: String, title: String) {
        val obj = JSONObject().apply {
            put("title", title)
            put("prompt", prompt)
            put("createdAt", System.currentTimeMillis())
        }
        try {
            val dir = File(userDir(context), slug).also { it.mkdirs() }
            File(dir, "meta.json").writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }
}
