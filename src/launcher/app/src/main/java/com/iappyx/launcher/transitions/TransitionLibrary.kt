/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.transitions

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import org.json.JSONObject
import java.io.File

/**
 * Catalogue of page-transition specs — bundled (in `assets/transitions/`)
 * plus user-generated (in `filesDir/transitions/{uuid}.json` + `.meta.json`).
 *
 * Bundled specs are read-only: they ship with the APK, can't be renamed or
 * deleted (locked the same way bundled wallpapers are). User-generated
 * specs are full CRUD: rename, edit description, refine via AI, delete
 * (refused if currently active — same in-use guard pattern as the rest of
 * the manage tabs).
 *
 * Hand-coded transitions in `LauncherActivity.applyPageTransform` (slide,
 * cube, blur, etc.) remain unchanged; the launcher routes through
 * [TransitionSpec.apply] only when the active id corresponds to a JSON
 * spec found here.
 */
object TransitionLibrary {

    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val createdAt: Long,
        val isUserGenerated: Boolean,
    )

    private data class BundledMeta(val id: String, val title: String, val subtitle: String)

    /** Bundled specs. The id is also the asset filename (without .json).
     *  IDs match the original hand-coded transition keys in
     *  LauncherActivity.applyPageTransform — when a spec is present, the
     *  spec engine takes over; the hand-coded `when` block becomes the
     *  fallback for any id without a JSON spec. */
    private val BUNDLED = listOf(
        BundledMeta("horizontal", "Horizontal sweep",  "The default left/right slide between pages."),
        BundledMeta("vertical",   "Vertical fall",     "Pages fall up or down between cards instead of sliding sideways."),
        BundledMeta("cube",       "3D cube",           "Six home screens form a 3D cube; pages turn around the meeting edge as you swipe."),
        BundledMeta("depth",      "Depth stack",       "The outgoing page recedes (fades and scales down) as the incoming one slides over it."),
        BundledMeta("zoom",       "Zoom-through",      "Pages zoom out as new ones zoom in. Quick and punchy."),
        BundledMeta("scatter",    "Scatter",           "Each cell flies to its own off-screen point instead of moving as a slab."),
        BundledMeta("fade",       "Fade",              "A pure crossfade — no motion at all."),
        BundledMeta("tilt",       "Tilt cascade",      "The horizontal slide with a 3D tilt; the page reads as a flipping card."),
        BundledMeta("aperture",   "Aperture",          "Pages contract to a dot in the centre, then bloom back out into the next page."),
        BundledMeta("cardstack",  "Card stack",        "The outgoing page lifts and tilts back like a dealer flicking a card off the top of a deck."),
        BundledMeta("blur",       "Frosted blur",      "The outgoing page blurs out as the incoming one sharpens into focus. Android 12+."),
        BundledMeta("carousel",   "Carousel",          "Pages rotate around their own centres like records on a turntable."),
        BundledMeta("dissolve",   "Dissolve",          "Cells fade out individually in a stochastic speckle, like an old film cut."),
        BundledMeta("explode",    "Explode",           "Cells radiate outward from the centre with a spin, then settle into the new page."),
        BundledMeta("implode_explode", "Implode & explode",
            "Cells collapse to the screen centre, then burst back out into their new positions on the incoming page."),
        BundledMeta("column_rain", "Column rain",
            "Columns drop off the bottom one at a time, left to right; new columns drop in from the top in the same order."),
    )

    /** Where user-generated transitions live. */
    fun userDir(context: Context): File =
        File(context.filesDir, "transitions").also { it.mkdirs() }

    /** Read the JSON source for a transition id (bundled or user). Returns
     *  null if the id is unknown. Cheap — used by both the spec compiler
     *  and the manage-tab edit dialogs. */
    fun rawJsonFor(context: Context, id: String): String? = try {
        if (BUNDLED.any { it.id == id }) {
            context.assets.open("transitions/$id.json").bufferedReader().use { it.readText() }
        } else {
            val f = File(userDir(context), "$id.json")
            if (f.exists()) f.readText() else null
        }
    } catch (_: Throwable) { null }

    // Compiled-spec cache. Keys are ids; values are nullable so we don't
    // re-attempt parsing every frame for an unparseable spec. Invalidate via
    // [invalidate] when a spec's JSON is rewritten (rename of meta doesn't
    // need invalidation; iterate/AI rewrite does).
    private val specCache = mutableMapOf<String, TransitionSpec?>()

    /** Compiled spec for an id, or null if missing / un-parseable / not a
     *  spec-style transition (returns null for hand-coded ids like "cube"
     *  that live in LauncherActivity's hard-coded `when`). Cached. */
    fun specFor(context: Context, id: String): TransitionSpec? {
        if (specCache.containsKey(id)) return specCache[id]
        val raw = rawJsonFor(context, id)
        val spec = raw?.let { TransitionSpec.parse(it) }
        specCache[id] = spec
        return spec
    }

    /** Drop the cached compiled spec for [id]. Call after any change to its
     *  JSON source (AI iterate, user edit). */
    fun invalidate(id: String) { specCache.remove(id) }
    fun invalidateAll() { specCache.clear() }

    /** Every entry, newest user-generated first, then bundled. */
    fun all(context: Context): List<Entry> {
        val bundled = BUNDLED.map {
            Entry(it.id, it.title, it.subtitle, 0L, isUserGenerated = false)
        }
        val user = userDir(context).listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }
            ?.mapNotNull { metaFile ->
                try {
                    val obj = JSONObject(metaFile.readText())
                    val id = metaFile.nameWithoutExtension.removeSuffix(".meta")
                    if (!File(userDir(context), "$id.json").exists()) return@mapNotNull null
                    Entry(
                        id = id,
                        title = obj.optString("title").ifBlank { "Generated transition" },
                        subtitle = obj.optString("prompt").ifBlank { "AI-generated" }.take(80),
                        createdAt = obj.optLong("createdAt", 0L),
                        isUserGenerated = true,
                    )
                } catch (_: Throwable) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
        return user + bundled
    }

    fun isUserGenerated(id: String): Boolean = !BUNDLED.any { it.id == id }

    /** Persist meta for a freshly-generated transition. The actual JSON spec
     *  is written separately (it's the AI output verbatim). Idempotent. */
    fun writeMeta(context: Context, id: String, prompt: String) {
        val obj = JSONObject().apply {
            put("title", smartTitle(prompt))
            put("prompt", prompt)
            put("createdAt", System.currentTimeMillis())
        }
        try {
            File(userDir(context), "$id.meta.json")
                .writeText(obj.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {}
    }

    fun renameUser(context: Context, id: String, newTitle: String): Boolean {
        if (!isUserGenerated(id)) return false
        val clean = newTitle.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isEmpty()) return false
        val metaFile = File(userDir(context), "$id.meta.json")
        if (!metaFile.exists()) return false
        return try {
            val obj = JSONObject(metaFile.readText())
            obj.put("title", clean)
            metaFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    fun updatePrompt(context: Context, id: String, newPrompt: String): Boolean {
        if (!isUserGenerated(id)) return false
        val metaFile = File(userDir(context), "$id.meta.json")
        if (!metaFile.exists()) return false
        return try {
            val obj = JSONObject(metaFile.readText())
            obj.put("prompt", newPrompt.trim())
            metaFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    /** Refuses to delete bundled or currently-active transitions. */
    fun deleteUser(context: Context, id: String): Boolean {
        if (!isUserGenerated(id)) return false
        val active = LauncherPrefs(context).pageTransitionStyle
        if (id == active) return false
        val dir = userDir(context)
        val a = File(dir, "$id.json").delete()
        val b = File(dir, "$id.meta.json").delete()
        return a || b
    }

    /** Light prompt → title heuristic. AI-supplied title from generator
     *  takes precedence; this is the fallback when there isn't one. */
    private fun smartTitle(prompt: String): String {
        var s = prompt.replace(Regex("\\s+"), " ").trim()
        for (p in listOf(
            "A page transition that", "A transition that",
            "Make a transition that", "Make a", "Create a", "I want a",
        )) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim().trimStart(':', '-', '—', ',', '.')
                break
            }
        }
        val cut = s.indexOfFirst { it == ',' || it == '.' || it == ';' || it == ':' || it == '\n' }
        if (cut > 0) s = s.substring(0, cut).trim()
        s = s.replace(Regex("(?i)\\s+(?:transition|effect)\\s*$"), "").trim()
        s = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word[0].uppercaseChar() + word.substring(1).lowercase()
        }
        if (s.length > 36) s = s.substring(0, 33).substringBeforeLast(' ', s.substring(0, 33)) + "…"
        return s.ifBlank { "Generated transition" }
    }
}
