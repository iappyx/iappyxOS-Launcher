/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Catalogue of HTML wallpaper payloads available to the launcher.
 *
 * Two sources:
 *   - **Bundled** in `assets/wallpapers/{id}.html` — ships with the APK.
 *   - **User-generated** in `filesDir/wallpapers/{uuid}.html` (+ `.json`) —
 *     written by [WallpaperGenerator] from AI-produced HTML.
 *
 * Resolved IDs are persisted in [com.iappyx.launcher.LauncherPrefs.activeWallpaperId].
 * The wallpaper service (running in `:wallpaper` process) reads these via
 * [urlFor] when (a) the engine first starts and (b) a payload-changed
 * broadcast arrives.
 */
object WallpaperLibrary {

    /** UI metadata for the picker sheet. Title + subtitle; the picker no
     *  longer shows a per-row badge — the section heading already separates
     *  bundled vs AI-generated. */
    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val url: String,
        val isUserGenerated: Boolean,
    )

    private data class BundledMeta(
        val id: String, val title: String, val subtitle: String,
    )

    private val BUNDLED = listOf(
        // Default — the rotating radial rainbow. Picked for first-run because
        // it's the most universally pleasing of the lot: bright, friendly,
        // works on any device, no sensors or GPS required.
        BundledMeta(
            "rotating_radial_gradient", "Rotating Radial Gradient",
            "A radial rainbow gradient that slowly rotates.",
        ),
        BundledMeta(
            // id stays "clock" for pref-value stability — earlier installs may
            // already have it persisted. The on-screen wallpaper itself no
            // longer renders a clock face, just the gradient that used to sit
            // behind it.
            "clock", "Hue drift",
            "A slow-drifting radial colour gradient. Subtle parallax on home-screen swipes, plus a tilt response from the device sensors.",
        ),
        BundledMeta(
            "digital_rain", "Digital Rain",
            "Matrix-style green katakana raining down with a soft glow. Capped at 30fps to stay battery-friendly.",
        ),
        BundledMeta(
            "falling_snow", "Falling Snow",
            "Snowflakes drift downward and pile up at the bottom of the screen. Shake the device to clear the pile with a flash.",
        ),
        BundledMeta(
            "fireworks", "Fireworks",
            "Looped fireworks against a black sky.",
        ),
        BundledMeta(
            "shake_for_a_photo", "Shake for a Photo",
            "Shake your phone to fetch a new random landscape photo. Smooth crossfade between shots.",
        ),
        BundledMeta(
            "material_color_drift", "Material Color Drift",
            "An infinite grid of Material Design colour swatches drifting smoothly in every direction. 190 colours tiled seamlessly.",
        ),
        BundledMeta(
            "magnetic_neon_particles", "Magnetic Neon Particles",
            "Hundreds of glowing particles that respond to your phone's motion. Tilt to fling them, shake for a burst, hold still and they cluster around invisible magnets.",
        ),
        BundledMeta(
            "battery_jelly", "Battery Jelly",
            "A viscous liquid that fills the screen from the bottom — its height tracks your battery level, and the colour shifts from red through teal to green as you charge. While the cable's plugged in, bubbles rise through the jelly and a subtle bolt fades in.",
        ),
        // Bouncing balls last — it's the reference implementation of the
        // layout-aware wallpaper bridge (20 small spheres bouncing off
        // home-grid + dock cells via iappyxLayout.get() /
        // iappyx.onLayoutChanged) but visually it's the least striking
        // of the bundled set.
        BundledMeta(
            "bouncing_balls", "Bouncing balls",
            "Twenty colourful balls bounce around your home screen, dodging your icons and widgets in real time. Rearrange your layout and the balls find a new path.",
        ),
        BundledMeta(
            "ambient_horizon", "Ambient Horizon",
            "A quiet horizon gradient whose colour shifts smoothly across the day — cool indigo at night, amber in the afternoon. Subtle particle drift, gentle parallax on tilt and home-page swipe.",
        ),
    )

    /** All available payloads, bundled then user-generated. Newest user
     *  entries first so freshly-generated wallpapers are easy to find. */
    fun all(context: Context): List<Entry> {
        val bundled = BUNDLED.map {
            Entry(
                it.id, it.title, it.subtitle,
                "file:///android_asset/wallpapers/${it.id}.html", false,
            )
        }
        val user = userDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { readUserMeta(it) }
            ?.sortedByDescending { it.createdAt }
            ?.map { it.entry }
            ?: emptyList()
        return bundled + user
    }

    /** Resolve an id to a file:// URL the WebView can load. Falls back to
     *  the first bundled entry on miss so a stale pref never wedges the
     *  wallpaper service. */
    fun urlFor(context: Context, id: String): String {
        all(context).firstOrNull { it.id == id }?.let { return it.url }
        return "file:///android_asset/wallpapers/${BUNDLED.first().id}.html"
    }

    /** Where AI-generated wallpapers live on disk. Created on first read. */
    fun userDir(context: Context): File =
        File(context.filesDir, "wallpapers").also { it.mkdirs() }


    /** Used by the picker to highlight the current selection. */
    fun isKnown(context: Context, id: String): Boolean =
        all(context).any { it.id == id }

    /** Delete a user-generated entry. Refuses for bundled entries AND when
     *  this id is the currently-active wallpaper (the in-use guard mirrors
     *  the manage-tab UI). Returns true if anything was actually removed. */
    fun deleteUser(context: Context, id: String): Boolean {
        if (BUNDLED.any { it.id == id }) return false
        val active = com.iappyx.launcher.LauncherPrefs(context).activeWallpaperId
        if (id == active) return false
        val dir = userDir(context)
        val html = File(dir, "$id.html").delete()
        val json = File(dir, "$id.json").delete()
        return html || json
    }

    /** Update the stored description (prompt) for a user-generated wallpaper.
     *  Bundled entries are locked. Pure metadata edit — no AI involvement. */
    fun updatePrompt(context: Context, id: String, newPrompt: String): Boolean {
        if (BUNDLED.any { it.id == id }) return false
        val clean = newPrompt.trim()
        val dir = userDir(context)
        val jsonFile = File(dir, "$id.json")
        if (!jsonFile.exists()) return false
        return try {
            val obj = org.json.JSONObject(jsonFile.readText())
            obj.put("prompt", clean)
            jsonFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    /** Rename a user-generated wallpaper. Bundled entries are locked (their
     *  display title comes from the BUNDLED constant; trying to override
     *  would be confusing after an APK update reverts it). */
    fun renameUser(context: Context, id: String, newTitle: String): Boolean {
        if (BUNDLED.any { it.id == id }) return false
        val clean = newTitle.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isEmpty()) return false
        val dir = userDir(context)
        val jsonFile = File(dir, "$id.json")
        if (!jsonFile.exists()) return false
        return try {
            val obj = org.json.JSONObject(jsonFile.readText())
            obj.put("title", clean)
            jsonFile.writeText(obj.toString(), Charsets.UTF_8)
            true
        } catch (_: Throwable) { false }
    }

    private data class UserRecord(val entry: Entry, val createdAt: Long)

    private fun readUserMeta(jsonFile: File): UserRecord? = try {
        val json = JSONObject(jsonFile.readText())
        val id = jsonFile.nameWithoutExtension
        val htmlFile = File(jsonFile.parentFile, "$id.html")
        if (!htmlFile.exists()) null else {
            val storedTitle = json.optString("title")
            val prompt = json.optString("prompt")
            // Auto-heal stale titles: pre-2026-04-27 generations stored just
            // the first ~32 chars of the prompt as the title, which reads as
            // garbage in the picker. If the stored title looks like a raw
            // prompt prefix (ends with "…" or matches the prompt's first
            // chunk verbatim), recompute via WallpaperGenerator's smart-title
            // / <title>-extraction logic and rewrite the .json on disk.
            val title = if (looksLikePromptPrefix(storedTitle, prompt)) {
                val better =
                    WallpaperGenerator.extractHtmlTitle(htmlFile.readText())
                        ?: WallpaperGenerator.smartTitle(prompt)
                if (better != storedTitle && better.isNotBlank()) {
                    runCatching {
                        json.put("title", better)
                        jsonFile.writeText(json.toString(), Charsets.UTF_8)
                    }
                    better
                } else storedTitle.ifBlank { "Generated wallpaper" }
            } else storedTitle.ifBlank { "Generated wallpaper" }

            UserRecord(
                entry = Entry(
                    id = id,
                    title = title,
                    subtitle = prompt.ifBlank { "AI-generated" }.take(80),
                    url = "file://${htmlFile.absolutePath}",
                    isUserGenerated = true,
                ),
                createdAt = json.optLong("createdAt", 0L),
            )
        }
    } catch (_: Throwable) { null }

    /** A title "looks like a prompt prefix" if it ends in ellipsis (the old
     *  truncation marker) OR matches the prompt's first ~32 chars verbatim
     *  (ignoring case). Both indicate the title was auto-derived rather than
     *  AI-supplied. */
    private fun looksLikePromptPrefix(title: String, prompt: String): Boolean {
        if (title.isBlank()) return true
        if (title.endsWith("…") || title.endsWith("...")) return true
        if (prompt.isBlank()) return false
        val cleanTitle = title.trimEnd('…', '.', ' ')
        return prompt.length >= cleanTitle.length &&
            prompt.substring(0, cleanTitle.length).equals(cleanTitle, ignoreCase = true)
    }
}
