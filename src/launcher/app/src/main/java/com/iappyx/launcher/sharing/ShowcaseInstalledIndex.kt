/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Match a showcase entry against what's already on the device.
 *
 *  - **Bundled** entries ship in the APK. The launcher's bundled IDs and
 *    the showcase slugs don't always match by string (e.g. showcase
 *    `hue-drift` is bundled as `clock`, `3d-cube` as `cube`), so we
 *    keep an explicit set of "showcase slugs that are already bundled"
 *    per kind. These are read from the same source-of-truth data —
 *    bumped manually whenever a new entry lands in both places.
 *
 *  - **User-installed from the showcase** entries write a `showcaseSlug`
 *    field into their saved `meta.json` via [ArtefactBundle.install].
 *    [installedSlugs] scans the appropriate user dir and collects them.
 *
 * The resulting [Status] for each showcase entry drives the browser UI:
 *  - [Status.Bundled] → "Built-in" badge, no install action
 *  - [Status.Installed] → dim "✓ Installed" badge
 *  - [Status.Available] → green "Install" button
 */
object ShowcaseInstalledIndex {

    enum class Status { BUNDLED, INSTALLED, AVAILABLE }

    /** Showcase slugs that ship in the APK (bundled assets). One per
     *  kind. Update alongside the launcher's bundled set + showcase repo
     *  contents — these MUST match the entries the launcher actually
     *  ships, otherwise the browser will mislabel them. */
    val BUNDLED_WIDGETS: Set<String> = setOf(
        "clock", "compass", "qr-barcode-scanner", "weather",
    )
    val BUNDLED_WALLPAPERS: Set<String> = setOf(
        "rotating-radial-gradient", "hue-drift", "bouncing-balls",
        "digital-rain", "falling-snow", "fireworks",
        "shake-for-a-photo", "material-color-drift", "magnetic-neon-particles",
    )
    val BUNDLED_TRANSITIONS: Set<String> = setOf(
        "horizontal-sweep", "vertical-fall", "3d-cube", "depth-stack",
        "zoom-through", "scatter", "fade", "tilt-cascade",
        "aperture", "card-stack", "frosted-blur", "carousel",
        "dissolve", "explode", "implode-explode", "column-rain",
    )
    val BUNDLED_ICON_FILTERS: Set<String> = setOf(
        "greyscale", "sepia", "vintage", "mono-accent",
        "rainbow-matrix", "wallpaper-themed", "pixelate",
        "tinted-mono", "aurora", "sweetheart", "squircle", "star",
    )
    // PLUGINS: BEGIN — bundled plugin ids matching the
    // `assets/plugins/<id>/` folders. Empty: no plugins ship in the
    // APK any more; everything arrives via the showcase. Re-add an
    // entry here if you bundle a plugin again later.
    val BUNDLED_PLUGINS: Set<String> = emptySet()
    // PLUGINS: END

    private fun bundledFor(kind: ShowcaseFetcher.Kind): Set<String> = when (kind) {
        ShowcaseFetcher.Kind.WIDGET -> BUNDLED_WIDGETS
        ShowcaseFetcher.Kind.WALLPAPER -> BUNDLED_WALLPAPERS
        ShowcaseFetcher.Kind.TRANSITION -> BUNDLED_TRANSITIONS
        ShowcaseFetcher.Kind.ICON_FILTER -> BUNDLED_ICON_FILTERS
        // PLUGINS: BEGIN
        ShowcaseFetcher.Kind.PLUGIN -> BUNDLED_PLUGINS
        // PLUGINS: END
    }

    /** Scan the per-kind library on disk and collect every `showcaseSlug`
     *  field stamped by [ArtefactBundle.install]. Cheap (a small directory
     *  listing + one JSON parse per entry) — caller invokes once per
     *  browser load. */
    fun installedSlugs(context: Context, kind: ShowcaseFetcher.Kind): Set<String> {
        val dir = File(context.filesDir, kind.folder)
        if (!dir.isDirectory) return emptySet()
        val out = mutableSetOf<String>()
        when (kind) {
            ShowcaseFetcher.Kind.WIDGET -> {
                // Widgets: each is a subdir with meta.json inside.
                dir.listFiles { f -> f.isDirectory }?.forEach { sub ->
                    val meta = File(sub, "meta.json")
                    if (meta.isFile) readSlug(meta)?.let(out::add)
                }
            }
            ShowcaseFetcher.Kind.WALLPAPER -> {
                // Wallpapers: flat dir, one .json per entry (no .meta. infix).
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { meta ->
                    readSlug(meta)?.let(out::add)
                }
            }
            ShowcaseFetcher.Kind.TRANSITION -> {
                // Transitions: flat dir, .meta.json sidecar per entry.
                dir.listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }?.forEach { meta ->
                    readSlug(meta)?.let(out::add)
                }
            }
            ShowcaseFetcher.Kind.ICON_FILTER -> {
                // Icon filters: each is a subdir (icon_filters/{slug}/) with
                // meta.json + spec.json. Mirrors the WIDGET layout.
                dir.listFiles { f -> f.isDirectory }?.forEach { sub ->
                    val meta = File(sub, "meta.json")
                    if (meta.isFile) readSlug(meta)?.let(out::add)
                }
            }
            // PLUGINS: BEGIN — plugins differ: their on-disk id is the
            // manifest's `id` field (decided by the author, not the
            // launcher), and the showcase slug is the same as that id.
            // So "installed" = any user-installed plugin dir exists.
            // We use the manifest's id as the slug — no meta.json
            // showcaseSlug stamping needed.
            ShowcaseFetcher.Kind.PLUGIN -> {
                dir.listFiles { f -> f.isDirectory }?.forEach { sub ->
                    val manifest = File(sub, "manifest.json")
                    if (!manifest.isFile) return@forEach
                    val id = try {
                        JSONObject(manifest.readText()).optString("id")
                    } catch (_: Throwable) { "" }
                    if (id.isNotBlank()) out.add(id)
                }
            }
            // PLUGINS: END
        }
        return out
    }

    private fun readSlug(file: File): String? = try {
        val s = JSONObject(file.readText()).optString("showcaseSlug")
        if (s.isBlank()) null else s
    } catch (_: Throwable) { null }

    /** Compute the status of one showcase entry against the on-device state. */
    fun statusOf(
        context: Context,
        kind: ShowcaseFetcher.Kind,
        slug: String,
        installedCache: Set<String>? = null,
    ): Status {
        if (slug in bundledFor(kind)) return Status.BUNDLED
        val installed = installedCache ?: installedSlugs(context, kind)
        if (slug in installed) return Status.INSTALLED
        return Status.AVAILABLE
    }
}
