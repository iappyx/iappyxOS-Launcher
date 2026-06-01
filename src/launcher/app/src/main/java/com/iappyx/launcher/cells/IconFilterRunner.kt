/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.SweepGradient

/**
 * Interprets an [IconFilterSpec] at icon-render time.
 *
 *  - [applyBake] mutates a freshly-rendered icon bitmap according to the
 *    spec's bake chain. Adjacent matrix ops are post-concatenated into one
 *    [ColorMatrix] for parity with the old hand-coded paths (where e.g.
 *    Vintage = setSaturation(0.5) postConcat warmShift).
 *  - [tintFor] returns the per-cell colour (or null) the icon view should
 *    apply via `setColorFilter`. Position-aware, so cells in different grid
 *    locations can resolve different colours.
 */
object IconFilterRunner {

    /** Apply [spec.bake] to [bmp] in place. Pure pass-through when the chain
     *  is empty (the "none" filter case). */
    fun applyBake(spec: IconFilterSpec, bmp: Bitmap, sizePx: Int) {
        if (spec.bake.isEmpty()) return
        // Coalesce consecutive matrix ops into one ColorMatrix; flush when
        // we hit an atomic op (pixelate/tinted_mono/aurora) so the matrix
        // applies to the bitmap *before* the atomic op runs over the result.
        var pendingMatrix: ColorMatrix? = null
        fun flushMatrix() {
            pendingMatrix?.let { applyMatrixInPlace(bmp, it) }
            pendingMatrix = null
        }
        for (op in spec.bake) {
            try {
                when (op) {
                    is BakeOp.SetSaturation -> {
                    val m = ColorMatrix().apply { setSaturation(op.value) }
                    pendingMatrix = pendingMatrix?.also { it.postConcat(m) } ?: m
                }
                is BakeOp.Matrix -> {
                    val m = ColorMatrix(op.values)
                    pendingMatrix = pendingMatrix?.also { it.postConcat(m) } ?: m
                }
                // ── Tone sugar (compiles to ColorMatrix) ──
                is BakeOp.Brightness -> {
                    val v = op.value.coerceIn(-1f, 1f) * 128f
                    val m = ColorMatrix(floatArrayOf(
                        1f, 0f, 0f, 0f, v,
                        0f, 1f, 0f, 0f, v,
                        0f, 0f, 1f, 0f, v,
                        0f, 0f, 0f, 1f, 0f,
                    ))
                    pendingMatrix = pendingMatrix?.also { it.postConcat(m) } ?: m
                }
                is BakeOp.Contrast -> {
                    // −1 → flat grey, 0 → identity, +1 → 2× contrast scale
                    val s = (1f + op.value.coerceIn(-1f, 1f))
                    val o = (1f - s) * 128f
                    val m = ColorMatrix(floatArrayOf(
                        s,  0f, 0f, 0f, o,
                        0f, s,  0f, 0f, o,
                        0f, 0f, s,  0f, o,
                        0f, 0f, 0f, 1f, 0f,
                    ))
                    pendingMatrix = pendingMatrix?.also { it.postConcat(m) } ?: m
                }
                is BakeOp.HueRotate -> {
                    val rad = Math.toRadians(op.degrees.toDouble())
                    val cos = kotlin.math.cos(rad).toFloat()
                    val sin = kotlin.math.sin(rad).toFloat()
                    // Standard hue-rotation matrix (Wikipedia: HSL/sRGB approx).
                    val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
                    val m = ColorMatrix(floatArrayOf(
                        lr + cos * (1 - lr) + sin * (-lr),
                        lg + cos * (-lg)    + sin * (-lg),
                        lb + cos * (-lb)    + sin * (1 - lb),
                        0f, 0f,

                        lr + cos * (-lr)    + sin * (0.143f),
                        lg + cos * (1 - lg) + sin * (0.140f),
                        lb + cos * (-lb)    + sin * (-0.283f),
                        0f, 0f,

                        lr + cos * (-lr)    + sin * (-(1 - lr)),
                        lg + cos * (-lg)    + sin * (lg),
                        lb + cos * (1 - lb) + sin * (lb),
                        0f, 0f,

                        0f, 0f, 0f, 1f, 0f,
                    ))
                    pendingMatrix = pendingMatrix?.also { it.postConcat(m) } ?: m
                }
                // ── Atomic ops break the matrix batch ──
                is BakeOp.Pixelate -> {
                    flushMatrix()
                    applyPixelateInPlace(bmp, sizePx, op.grid)
                }
                BakeOp.TintedMono -> {
                    flushMatrix()
                    applyTintedMonoInPlace(bmp, sizePx)
                }
                is BakeOp.Aurora -> {
                    flushMatrix()
                    applyAuroraInPlace(bmp, sizePx, op.colors)
                }
                is BakeOp.Duotone -> {
                    flushMatrix()
                    applyDuotoneInPlace(
                        bmp, sizePx,
                        Color.parseColor(op.shadowColor),
                        Color.parseColor(op.highlightColor),
                        op.balance.coerceIn(-1f, 1f),
                    )
                }
                is BakeOp.Vignette -> {
                    flushMatrix()
                    applyVignetteInPlace(
                        bmp, sizePx,
                        op.amount.coerceIn(0f, 1f),
                        op.feather.coerceIn(0f, 1f),
                    )
                }
                is BakeOp.Grain -> {
                    flushMatrix()
                    applyGrainInPlace(
                        bmp, sizePx,
                        op.amount.coerceIn(0f, 1f),
                        op.size.coerceIn(1, 8),
                    )
                }
                is BakeOp.Posterize -> {
                    flushMatrix()
                    applyPosterizeInPlace(bmp, op.levels.coerceIn(2, 16))
                }
                is BakeOp.Glow -> {
                    flushMatrix()
                    applyGlowInPlace(
                        bmp, sizePx,
                        op.radius.coerceIn(0.5f, 40f),
                        Color.parseColor(op.color),
                        op.alpha.coerceIn(0f, 1f),
                    )
                }
                }
            } catch (e: Throwable) {
                // Single-op failure must NOT abort the whole bake chain —
                // otherwise IconCell.bind catches the propagated exception
                // and sets the cell to View.INVISIBLE, which over the
                // entire home grid presents as "the screen went black".
                // Log + skip; the icon still renders with whatever
                // partial transformation succeeded so far.
                android.util.Log.w("iappyxIconFilter",
                    "skipping op ${op::class.simpleName} for filter ${spec.slug}: ${e.message}")
            }
        }
        flushMatrix()
    }

    /** Per-cell tint colour for a spec, or null when the filter doesn't use
     *  one (any spec where `tint` is null). [gridPos] may be null for cells
     *  that haven't told us where they live (e.g. a folder cell rendered
     *  before its placement is known); position-aware tint ops fall back to
     *  null in that case rather than crashing. */
    fun tintFor(
        context: Context,
        spec: IconFilterSpec,
        gridPos: GridPos?,
    ): Int? = when (spec.tint) {
        null -> null
        is TintOp.PositionHue -> gridPos?.let {
            // Existing hand-coded RAINBOW_MATRIX path lives in [RainbowMatrix].
            // We respect its semantics — saturation/value parameters are
            // surfaced here for AI-generated specs but the bundled rainbow
            // uses the original (sat=0.7, val=1.0) constants.
            RainbowMatrix.tintFor(it)
        }
        TintOp.WallpaperColor -> gridPos?.let { WallpaperPalette.tintFor(context, it) }
        TintOp.SystemAccent -> systemAccentColor(context)
    }

    // ── Helpers (moved from IconMask, semantics unchanged) ────────────

    private fun applyMatrixInPlace(bmp: Bitmap, matrix: ColorMatrix) {
        val src = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        src.recycle()
    }

    private fun applyPixelateInPlace(bmp: Bitmap, sizePx: Int, grid: Int) {
        val pixelGrid = grid.coerceIn(2, 64)
        val src = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val small = Bitmap.createScaledBitmap(src, pixelGrid, pixelGrid, true)
        val canvas = Canvas(bmp)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(
            small, null,
            RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
            Paint().apply { isFilterBitmap = false },
        )
        src.recycle(); small.recycle()
    }

    private fun applyTintedMonoInPlace(bmp: Bitmap, sizePx: Int) {
        val tint = dominantTintFrom(bmp)
        val src = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        cm.postConcat(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 30f,
            0f, 1f, 0f, 0f, 30f,
            0f, 0f, 1f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f,
        )))
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            })
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        src.recycle()
    }

    private fun dominantTintFrom(bmp: Bitmap): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0L
        val step = (bmp.width / 16).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val px = bmp.getPixel(x, y)
                if (Color.alpha(px) >= 200) {
                    Color.colorToHSV(px, hsv)
                    if (hsv[1] >= 0.3f && hsv[2] >= 0.3f) {
                        rSum += Color.red(px); gSum += Color.green(px); bSum += Color.blue(px)
                        count++
                    }
                }
                x += step
            }
            y += step
        }
        return if (count == 0L) Color.parseColor("#FFB8B8B8")
        else Color.argb(
            255,
            (rSum / count).toInt().coerceIn(90, 235),
            (gSum / count).toInt().coerceIn(90, 235),
            (bSum / count).toInt().coerceIn(90, 235),
        )
    }

    private fun applyAuroraInPlace(bmp: Bitmap, sizePx: Int, hexColors: List<String>) {
        val colors = hexColors.map { Color.parseColor(it) }.toIntArray()
        val src = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val size = sizePx.toFloat()
        canvas.drawRect(0f, 0f, size, size,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = SweepGradient(size / 2f, size / 2f, colors, null)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            })
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        src.recycle()
    }

    /** Returns the device's Material You accent. Mirrors the original
     *  IconCell / FolderCell behaviour exactly so MONO_ACCENT produces the
     *  same tint as before the migration: API 31+ uses
     *  `system_accent1_500`, otherwise (and on any failure) falls back to
     *  the launcher's hard-coded blue accent. */
    fun systemAccentColor(context: Context): Int {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { return context.getColor(android.R.color.system_accent1_500) }
            catch (_: Throwable) { /* fall through */ }
        }
        return Color.parseColor("#FF4FC3F7")
    }

    // ── Duotone / split-tone ────────────────────────────────

    /** Map every pixel's luminance between [shadow] and [highlight]. The
     *  [balance] parameter shifts the midpoint: −1 pulls highlights toward
     *  shadow_color, +1 pulls shadows toward highlight_color. Original
     *  alpha is preserved. */
    private fun applyDuotoneInPlace(
        bmp: Bitmap, sizePx: Int, shadow: Int, highlight: Int, balance: Float,
    ) {
        val sR = Color.red(shadow); val sG = Color.green(shadow); val sB = Color.blue(shadow)
        val hR = Color.red(highlight); val hG = Color.green(highlight); val hB = Color.blue(highlight)
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px ushr 24) and 0xFF
            if (a == 0) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            // Rec. 709 luma weights — match what setSaturation(0) produces.
            var lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            // Apply balance via a power curve (gamma ≈ 1 + balance).
            val gamma = (1f - balance).coerceIn(0.05f, 5f)
            lum = lum.toDouble().pow(gamma.toDouble()).toFloat().coerceIn(0f, 1f)
            val outR = (sR + (hR - sR) * lum).toInt().coerceIn(0, 255)
            val outG = (sG + (hG - sG) * lum).toInt().coerceIn(0, 255)
            val outB = (sB + (hB - sB) * lum).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        // sizePx is unused but kept for signature consistency with the
        // other atomic helpers.
        @Suppress("UNUSED_PARAMETER") val _v = sizePx
    }

    // ── Vignette ────────────────────────────────────────────

    /** Radial darkening at the icon's edges. The clip-to-icon-alpha pass
     *  at the end keeps the darken from spilling into transparent pixels. */
    private fun applyVignetteInPlace(
        bmp: Bitmap, sizePx: Int, amount: Float, feather: Float,
    ) {
        if (amount <= 0f) return
        val src = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val size = sizePx.toFloat()
        // Inner-clear → outer-black gradient. The feather param controls
        // how soon the falloff starts: feather 0 = black ring at the edge
        // (90 % radius), feather 1 = smooth from the centre out.
        val innerStop = (1f - feather).coerceIn(0f, 1f) * 0.5f
        val outerAlpha = (amount * 255f).toInt().coerceIn(0, 255)
        val gradient = android.graphics.RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(0x00000000, (outerAlpha shl 24)),
            floatArrayOf(innerStop, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size, size, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        })
        // Re-clip to the icon's original alpha so the vignette ring stays
        // inside the rounded-square mask.
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        src.recycle()
    }

    // ── Grain ──────────────────────────────────────────────

    /** Per-pixel grain: each pixel's RGB is multiplied by
     *  `1 - amount * (1 - noise)`, where noise ∈ [0,1] is a per-block
     *  random value chunked to [size]-pixel squares. Alpha is left
     *  alone so transparent corners stay transparent and opaque
     *  parts of the icon stay fully opaque (the previous Porter-Duff
     *  MULTIPLY path multiplied alphas too, which silently faded the
     *  whole icon at the default amount). */
    private fun applyGrainInPlace(
        bmp: Bitmap, sizePx: Int, amount: Float, size: Int,
    ) {
        if (amount <= 0f) return
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val rnd = java.util.Random()
        // Pre-build a small noise grid sized blockCount × blockCount so
        // adjacent pixels in the same [size]-block share a noise value
        // (gives the chunky grain look the `size` parameter exposes).
        val blockCount = (w / size).coerceAtLeast(1)
        val blockSize = (w / blockCount).coerceAtLeast(1)
        val noiseGrid = FloatArray(blockCount * blockCount) { rnd.nextFloat() }
        for (y in 0 until h) {
            val by = (y / blockSize).coerceAtMost(blockCount - 1)
            for (x in 0 until w) {
                val bx = (x / blockSize).coerceAtMost(blockCount - 1)
                val idx = y * w + x
                val px = pixels[idx]
                val a = (px ushr 24) and 0xFF
                if (a == 0) continue
                val noise = noiseGrid[by * blockCount + bx]
                // Scale: at noise=1 (bright) leave unchanged; at noise=0
                // (dark) attenuate by `amount`.
                val scale = 1f - amount * (1f - noise)
                val r = (((px ushr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((px ushr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((px and 0xFF) * scale).toInt().coerceIn(0, 255)
                pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        @Suppress("UNUSED_PARAMETER") val _v = sizePx
    }

    // ── Posterize ──────────────────────────────────────────

    /** Quantise each colour channel to [levels] discrete steps. Cheap
     *  per-pixel pass — icons are small (typically 128² = 16K pixels). */
    private fun applyPosterizeInPlace(bmp: Bitmap, levels: Int) {
        val step = 256 / levels
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px ushr 24) and 0xFF
            if (a == 0) continue
            val r = ((px ushr 16) and 0xFF) / step * step
            val g = ((px ushr 8) and 0xFF) / step * step
            val b = (px and 0xFF) / step * step
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    // ── Glow ────────────────────────────────────────────────

    /** Coloured bloom halo around the icon. Implementation has to dodge a
     *  geometric problem: the icon already fills the entire bitmap (the
     *  IconMask path covers the rounded square edge-to-edge), so a halo
     *  drawn at full size has nowhere to go — it clips against the bitmap
     *  boundary and you see nothing.
     *
     *  Fix: shrink the icon to ~78 % of the bitmap, leaving an 11 % ring
     *  on every side for the halo. Then use [Paint.setShadowLayer] on the
     *  shrunk-icon draw — the shadow layer renders a coloured blur of
     *  the bitmap's alpha mask outward into the surrounding ring. The
     *  resulting bitmap is the icon (slightly smaller) with a visible
     *  glow halo. Software-rendered (Bitmap canvas) so the shadow layer
     *  works on every API level. */
    private fun applyGlowInPlace(
        bmp: Bitmap, sizePx: Int, radius: Float, color: Int, alpha: Float,
    ) {
        if (alpha <= 0f) return
        val original = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)

        // Pad matches the requested radius 1:1 (so the halo's pixel-
        // distance fits inside the bitmap edge), capped at 8 % of the
        // bitmap on each side. The cap means a max-radius request gets
        // its halo visually clipped slightly rather than shrinking the
        // icon below 84 % of its natural size.
        val maxPad = (sizePx * 0.08f).toInt()
        val pad = radius.toInt().coerceIn(2, maxPad)
        val inner = sizePx - 2 * pad
        val rect = RectF(
            pad.toFloat(), pad.toFloat(),
            (pad + inner).toFloat(), (pad + inner).toFloat(),
        )
        // Shadow colour with the user's alpha baked in. setShadowLayer
        // ignores paint.alpha for the shadow, so we encode alpha in the
        // shadow colour itself.
        val shadowAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val shadowColor = (shadowAlpha shl 24) or (color and 0x00FFFFFF)
        // Effective radius capped at the available padding so the halo
        // ends inside the bitmap edge. When the requested radius is
        // smaller than maxPad we honour it exactly.
        val effRadius = radius.coerceIn(0.5f, pad.toFloat())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setShadowLayer(effRadius, 0f, 0f, shadowColor)
        }
        canvas.drawBitmap(original, null, rect, paint)
        original.recycle()
    }
}

// Tiny extension to keep duotone's gamma curve compact.
private fun Double.pow(exp: Double): Double = Math.pow(this, exp)
