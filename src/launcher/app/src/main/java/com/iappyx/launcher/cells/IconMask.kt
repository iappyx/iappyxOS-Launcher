/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache

/**
 * Normalises the visual presentation of every app icon on the home grid so
 * the launcher reads as one cohesive surface instead of a mix of Android's
 * stock shapes. Produces a rounded-rect "superellipse-ish" masked bitmap:
 *
 *  - On API 26+, when the app ships an [AdaptiveIconDrawable], draws foreground
 *    over background into our mask — matches what Pixel / OneUI etc. do.
 *  - Otherwise the original icon is centered on a launcher-coloured backplate
 *    with a subtle diagonal gloss so legacy icons don't look lost.
 *
 * Filter post-processing is delegated to [IconFilterRunner], which interprets
 * the active [IconFilterSpec]. Results are cached by `(pkg, sizePx, filterSlug)`
 * so the same call doesn't re-render every page bind.
 */
object IconMask {

    // Byte-sized cache (H8-2): a count-based LruCache(64) ignored bitmap size,
    // so 64 high-density 128dp icons could hold tens of MB while 64 tiny
    // folder-minis wasted the budget. Cap at ~1/8 of the heap, clamped 4–32 MB,
    // and measure entries by their actual byte footprint.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L)
            .coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024)
            .toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** Application context, captured so [render] (whose signature carries no
     *  Context) can resolve the active icon pack. Set by [attach] and by
     *  [prewarm]; null until then, in which case pack substitution is simply
     *  skipped and icons render with the built-in treatment. */
    @Volatile private var appCtx: android.content.Context? = null

    /** Wire up the application context for pack lookups. Safe to call repeatedly
     *  (idempotent); call early in the launcher's lifecycle. */
    fun attach(context: android.content.Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    fun render(
        packageName: String,
        drawable: Drawable,
        sizePx: Int,
        spec: IconFilterSpec = IconFilterRegistry.noneSpec,
    ): Bitmap {
        // An active icon pack takes precedence over colour filters (the pack is
        // a curated look; running a duotone over it would clash). When one is
        // active we force the "none" spec — no bake, default shape for any
        // unthemed-app fallback.
        val ctx = appCtx
        val activePack = ctx?.let {
            val pk = com.iappyx.launcher.LauncherPrefs(it).iconPack
            if (pk.isNotBlank() && IconPack.isActive(it, pk)) pk else null
        }
        val effSpec = if (activePack != null) IconFilterRegistry.noneSpec else spec

        // Cache key includes the filter slug — RAINBOW_MATRIX bitmaps are
        // greyscale and would render wrong if a NONE caller pulled them out.
        // Different shapes also yield different cached bitmaps, but slug
        // already disambiguates because shape is a property of the spec. The
        // pack id is appended so switching packs can't collide on a stale key.
        val key = "$packageName:$sizePx:${effSpec.slug}:${activePack ?: ""}"
        cache.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = (effSpec.shape ?: IconShape.DEFAULT).toPath(sizePx.toFloat())

        val themed = if (activePack != null && ctx != null) {
            IconPack.iconFor(ctx, activePack, packageName)
        } else {
            null
        }

        // For apps the pack doesn't theme, optionally apply the pack's
        // iconback/mask/upon so the grid stays cohesive (gated by the
        // "Mask unthemed icons" toggle + the pack actually shipping them).
        val masked = if (themed == null && activePack != null && ctx != null &&
            com.iappyx.launcher.LauncherPrefs(ctx).maskUnthemed
        ) {
            IconPack.maskTreatment(ctx, activePack, packageName, drawable, sizePx)
        } else {
            null
        }

        when {
            // Pack supplies a themed icon: it's a final, pre-shaped asset —
            // draw it edge-to-edge as the pack intends, no mask / backplate /
            // scale-to-fill.
            themed != null -> drawThemed(canvas, themed, sizePx)
            masked != null -> {
                canvas.drawBitmap(masked, 0f, 0f, null)
                masked.recycle()
            }
            Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable ->
                drawAdaptive(canvas, drawable, sizePx, path)
            else -> drawLegacy(canvas, drawable, packageName, sizePx, path)
        }

        // Skip colour filters whenever a pack is active (decision: pack
        // overrides filters), including for unthemed-app fallbacks.
        if (activePack == null) IconFilterRunner.applyBake(effSpec, bmp, sizePx)

        cache.put(key, bmp)
        return bmp
    }

    /** Draw a pack-supplied themed icon. Pack assets are already styled and
     *  shaped, so they're drawn to fill the cell with no further masking. */
    private fun drawThemed(canvas: Canvas, drawable: Drawable, sizePx: Int) {
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
    }

    /** Drop all cached bitmaps. Called when the user changes
     *  the active [IconFilterSpec] so the next render produces a fresh
     *  bitmap under the new filter. */
    fun clearCache() { cache.evictAll() }

    /** Pre-render every icon referenced by [layout] (home placements + dock
     *  + folder members) into the cache, on a background thread. By the time
     *  the user does their first home swipe, [IconCell.bind] hits a warm
     *  cache (~0.1ms) instead of paying the ~5–15ms render cost per icon.
     *  Cheap to call repeatedly: cache hits are no-ops; cache size is bounded
     *  to 64 so a typical home/dock/folder set fits.
     *
     *  Uses 128dp at the device's density — same target [IconCell.bind] uses
     *  (line 132 of IconCell.kt). Folder previews use a smaller bake size so
     *  they remain a cold render; that cost is bounded by [FolderCell] only
     *  rendering on first bind. */
    fun prewarm(context: android.content.Context, layout: com.iappyx.launcher.model.HomeLayout) {
        val app = context.applicationContext
        attach(app)
        Thread {
            try {
                val density = app.resources.displayMetrics.density
                val targetPx = (128 * density).toInt()
                val spec = IconFilterRegistry.resolve(
                    app, com.iappyx.launcher.LauncherPrefs(app).iconFilter,
                )
                // Mirror render()'s effective key exactly so the cache-hit
                // `continue` below actually fires. render() forces the "none"
                // spec and appends the active icon-pack id when a pack is
                // selected; an out-of-sync key here meant the dedup probe never
                // matched, so prewarm re-ran PM lookups for every package.
                val activePack = com.iappyx.launcher.LauncherPrefs(app).iconPack
                    .takeIf { it.isNotBlank() && IconPack.isActive(app, it) }
                val effSpec = if (activePack != null) IconFilterRegistry.noneSpec else spec
                val pm = app.packageManager
                // Collect every package the home layout will need to render —
                // dedupe so we don't redo work for an app that's both on the
                // grid and inside a folder.
                val packages = mutableSetOf<String>()
                for (page in layout.pages) {
                    for (p in page.placements) {
                        when (p.type) {
                            com.iappyx.launcher.model.CellType.ICON ->
                                p.packageName?.let { packages.add(it) }
                            com.iappyx.launcher.model.CellType.FOLDER ->
                                p.folderItems.forEach { packages.add(it.packageName) }
                            else -> { /* widgets/drawer don't use IconMask */ }
                        }
                    }
                }
                for (dockPage in layout.dockPages) {
                    for (p in dockPage) {
                        if (p.type == com.iappyx.launcher.model.CellType.ICON) {
                            p.packageName?.let { packages.add(it) }
                        }
                    }
                }
                for (pkg in packages) {
                    val key = "$pkg:$targetPx:${effSpec.slug}:${activePack ?: ""}"
                    if (cache.get(key) != null) continue
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        val raw = pm.getApplicationIcon(info)
                        render(pkg, raw, targetPx, effSpec)
                    } catch (_: Throwable) {
                        /* uninstalled / restricted — IconCell.bind will fall
                         * back to its own catch block on cold render */
                    }
                }
            } catch (_: Throwable) { /* prewarm is best-effort */ }
        }.apply { isDaemon = true; name = "IconMask-prewarm"; start() }
    }

    /**
     * Render an [AdaptiveIconDrawable] (Android's two-layer 108dp icon spec).
     *
     * The full layer is 108dp but only the inner 72dp "safe zone" is
     * guaranteed visible — the outer 36dp ring is reserved for launcher
     * parallax / different mask shapes. Drawing the layer at exactly `sizePx`
     * would render the safe zone at only 72/108 = 66.7% of the cell, which
     * makes apps with conservative artwork inside the safe zone (e.g. banking
     * apps with small centred logos) look tiny.
     *
     * Match Pixel / OneUI / MIUI behaviour: scale the layer up by 1.5× so the
     * safe zone fills the cell exactly. The 0.25× overhang on each side falls
     * outside the canvas / mask and gets clipped.
     */
    private fun drawAdaptive(canvas: Canvas, aid: AdaptiveIconDrawable, sizePx: Int, mask: Path) {
        canvas.save()
        canvas.clipPath(mask)
        val pad = (sizePx * 0.25f).toInt()
        val left = -pad; val top = -pad
        val right = sizePx + pad; val bottom = sizePx + pad
        aid.setBounds(left, top, right, bottom)
        aid.background?.setBounds(left, top, right, bottom)
        aid.foreground?.setBounds(left, top, right, bottom)
        aid.background?.draw(canvas)
        aid.foreground?.draw(canvas)
        canvas.restore()
        drawGloss(canvas, sizePx.toFloat(), mask)
    }

    /**
     * Legacy, non-adaptive icon.
     *
     * The artwork's opaque content is measured ([opaqueBounds]) and one of two
     * presentations is chosen:
     *
     *  - **Self-contained icon** (its own square/round background fills most of
     *    the asset, usually with baked-in transparent padding — e.g. a white
     *    rounded-square with a centred logo): scale the opaque region UP so it
     *    fills the cell exactly, then let the mask clip the overhang. This is
     *    the same trick [drawAdaptive] uses for the 72dp safe zone, and it
     *    stops these icons from looking like a small "box within the shape".
     *  - **Small centred mark on transparency**: keep it inset on the backplate
     *    so it doesn't look lost in the cell.
     *
     * The tinted backplate is painted in BOTH cases. It's invisible behind an
     * opaque square logo, but fills the mask corners for round logos (so the
     * wallpaper doesn't show through) and gives inset marks a tinted surface.
     */
    private fun drawLegacy(canvas: Canvas, drawable: Drawable, pkg: String, sizePx: Int, mask: Path) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = deriveBackplateColor(pkg)
        }
        val size = sizePx.toFloat()
        val b = opaqueBounds(drawable)
        canvas.save()
        canvas.clipPath(mask)
        canvas.drawRect(0f, 0f, size, size, paint)
        if (b != null) {
            val spanX = b[2] - b[0]
            val spanY = b[3] - b[1]
            val minSpan = minOf(spanX, spanY)
            if (minSpan >= 0.60f) {
                // Scale the asset so its opaque region covers the cell (uniform
                // scale = 1 / minSpan, capped by the ≥0.60 gate at ~1.67×), then
                // centre that region. Overhang falls outside the mask and is
                // clipped — the icon reads edge-to-edge like an adaptive icon.
                val d = size / minSpan
                val cx = (b[0] + b[2]) / 2f
                val cy = (b[1] + b[3]) / 2f
                val left = size / 2f - cx * d
                val top = size / 2f - cy * d
                drawable.setBounds(
                    left.toInt(), top.toInt(),
                    (left + d).toInt(), (top + d).toInt(),
                )
            } else {
                val inset = (sizePx * 0.16f).toInt()
                drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            }
            drawable.draw(canvas)
        }
        canvas.restore()
        drawGloss(canvas, size, mask)
    }

    /**
     * Normalised bounding box of [drawable]'s non-transparent content, as
     * `[left, top, right, bottom]` fractions of the icon canvas, or null when
     * the icon is effectively transparent. Renders into a 48×48 sample bitmap
     * (≈2.3k pixel reads) and only runs on a cold render — [render] caches the
     * result and [prewarm] pays the cost off the main thread.
     */
    private fun opaqueBounds(drawable: Drawable): FloatArray? {
        return try {
            val s = 48
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val saved = Rect(drawable.bounds)
            drawable.setBounds(0, 0, s, s)
            drawable.draw(c)
            drawable.bounds = saved
            var minX = s; var minY = s; var maxX = -1; var maxY = -1
            val alphaThreshold = 24
            for (y in 0 until s) {
                for (x in 0 until s) {
                    val a = (bmp.getPixel(x, y) ushr 24) and 0xFF
                    if (a > alphaThreshold) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            bmp.recycle()
            if (maxX < 0) null
            else floatArrayOf(
                minX.toFloat() / s, minY.toFloat() / s,
                (maxX + 1).toFloat() / s, (maxY + 1).toFloat() / s,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Very subtle top-to-bottom light gloss, clipped to the mask. */
    private fun drawGloss(canvas: Canvas, size: Float, mask: Path) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, size,
                intArrayOf(0x22FFFFFF, 0x00FFFFFF),
                null, Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        canvas.save()
        canvas.clipPath(mask)
        canvas.drawRect(0f, 0f, size, size, paint)
        canvas.restore()
    }

    /** Pick a pleasant dark tint by hashing the package — stable per app,
     *  feels branded without having to sample the icon. */
    private fun deriveBackplateColor(pkg: String): Int {
        val seed = pkg.hashCode()
        val hue = ((seed and 0xFFFF) % 360).toFloat()
        val hsv = floatArrayOf(hue, 0.35f, 0.22f) // dark, slightly saturated
        return Color.HSVToColor(hsv)
    }
}
