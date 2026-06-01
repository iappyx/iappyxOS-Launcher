/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Silhouette shape an icon is masked into. The launcher's default look is
 * [RoundedSquare] at 22 % corner radius (matches Pixel / OneUI). An
 * [IconFilterSpec] may override this with one of the named shapes below to
 * cut every app icon into circles, squircles, hexagons, hearts, etc.
 *
 * Each variant supplies a single [Path] sized to a `size × size` square.
 * Renderers ([IconMask]) clip drawing through that path so transparent
 * pixels outside the silhouette stay transparent.
 *
 * Shapes deliberately fill the available square edge-to-edge: tighter
 * shapes (e.g. heart) leave more "room" outside themselves where bake-time
 * effects like vignette, glow and aurora can paint, alpha-clipped to the
 * silhouette. That's intentional — a heart-shaped icon with a magenta glow
 * halo only shows the halo around the heart, not in the corners.
 */
sealed class IconShape {

    abstract fun toPath(size: Float): Path
    abstract fun toJson(): JSONObject

    /** The launcher's default — rounded square. cornerFraction 0..0.5
     *  (0 = sharp square, 0.5 = full circle in disguise). */
    data class RoundedSquare(val cornerFraction: Float = 0.22f) : IconShape() {
        override fun toPath(size: Float): Path = Path().apply {
            val r = size * cornerFraction.coerceIn(0f, 0.5f)
            addRoundRect(RectF(0f, 0f, size, size), r, r, Path.Direction.CW)
        }
        override fun toJson() = JSONObject().apply {
            put("kind", "rounded_square")
            put("corner_fraction", cornerFraction.toDouble())
        }
    }

    /** Circle inscribed in the size×size square. */
    object Circle : IconShape() {
        override fun toPath(size: Float): Path = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        }
        override fun toJson() = JSONObject().apply { put("kind", "circle") }
    }

    /** Superellipse |x/a|^n + |y/b|^n = 1. n=2 → circle, n=4 → squircle
     *  (iOS), higher → approaches a square. Sampled to a polyline. */
    data class Squircle(val n: Float = 4f) : IconShape() {
        override fun toPath(size: Float): Path = Path().apply {
            val r = size / 2f
            val cx = r; val cy = r
            val nn = n.coerceIn(1.5f, 12f)
            val steps = 96
            for (i in 0..steps) {
                val t = (i.toFloat() / steps) * (2f * PI.toFloat())
                val cosT = cos(t); val sinT = sin(t)
                val x = cx + sign(cosT) * abs(cosT.toDouble())
                    .pow((2.0 / nn).toDouble()).toFloat() * r
                val y = cy + sign(sinT) * abs(sinT.toDouble())
                    .pow((2.0 / nn).toDouble()).toFloat() * r
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        override fun toJson() = JSONObject().apply {
            put("kind", "squircle"); put("n", n.toDouble())
        }
    }

    /** Regular hexagon, flat-top orientation by default. */
    data class Hexagon(val flatTop: Boolean = true) : IconShape() {
        override fun toPath(size: Float): Path = Path().apply {
            val cx = size / 2f; val cy = size / 2f
            val r = size / 2f
            val rotation = if (flatTop) 0.0 else (PI / 6.0)
            for (i in 0 until 6) {
                val angle = rotation + i * (PI / 3.0)
                val x = cx + (r * cos(angle)).toFloat()
                val y = cy + (r * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        override fun toJson() = JSONObject().apply {
            put("kind", "hexagon"); put("flat_top", flatTop)
        }
    }

    /** Arbitrary SVG path data (M/L/C/Q/A/Z commands). The path is parsed
     *  with [androidx.core.graphics.PathParser] and auto-scaled to fit the
     *  size×size square (preserves aspect ratio, leaves a 4 % inset for
     *  anti-aliased edges). Use for hearts/stars/clouds/anything you can
     *  draw in an SVG editor — paste the `d` attribute string here.
     *
     *  Validity is checked at parse time ([fromJson]) so a malformed path
     *  is surfaced before the spec ever reaches the renderer. */
    data class CustomPath(val pathData: String) : IconShape() {
        private val parsedPath: Path by lazy {
            androidx.core.graphics.PathParser.createPathFromPathData(pathData)
                ?: throw IllegalArgumentException("invalid SVG path data")
        }
        override fun toPath(size: Float): Path {
            val src = parsedPath
            val bounds = android.graphics.RectF()
            // Two-arg form deprecated at API 30; the boolean is ignored on
            // every API level and the single-arg replacement was only added
            // at API 30, so we keep this for minSdk 29 compatibility.
            @Suppress("DEPRECATION")
            src.computeBounds(bounds, true)
            if (bounds.isEmpty) return Path()
            val inset = size * 0.04f
            val target = size - 2f * inset
            val scale = minOf(target / bounds.width(), target / bounds.height())
            val sw = bounds.width() * scale
            val sh = bounds.height() * scale
            val ox = (size - sw) / 2f - bounds.left * scale
            val oy = (size - sh) / 2f - bounds.top * scale
            val m = android.graphics.Matrix()
            m.postScale(scale, scale)
            m.postTranslate(ox, oy)
            val out = Path()
            src.transform(m, out)
            return out
        }
        override fun toJson() = JSONObject().apply {
            put("kind", "custom")
            put("path", pathData)
        }
    }

    /** Classic heart curve, scaled to fill the size×size square (centred,
     *  edge-to-edge horizontally). Sampled. */
    object Heart : IconShape() {
        override fun toPath(size: Float): Path = Path().apply {
            // Parametric heart: x = 16 sin³(t), y = 13 cos(t) − 5 cos(2t)
            // − 2 cos(3t) − cos(4t). Native span is roughly [-16,16] × [-17,11].
            val cx = size / 2f
            val steps = 128
            // Pre-compute bounds so we can scale to fit.
            var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
            val xs = FloatArray(steps + 1)
            val ys = FloatArray(steps + 1)
            for (i in 0..steps) {
                val t = (i.toFloat() / steps) * (2f * PI.toFloat())
                val sx = 16f * sin(t).toDouble().pow(3.0).toFloat()
                val sy = -(13f * cos(t) - 5f * cos(2f * t) -
                    2f * cos(3f * t) - cos(4f * t))
                xs[i] = sx; ys[i] = sy
                if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
                if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
            }
            val nativeW = maxX - minX
            val nativeH = maxY - minY
            // Fit within the size×size box, leaving a tiny inset so anti-
            // aliased edges don't get clipped by the bitmap boundary.
            val inset = size * 0.04f
            val target = size - 2f * inset
            val scale = minOf(target / nativeW, target / nativeH)
            val scaledW = nativeW * scale
            val scaledH = nativeH * scale
            val ox = cx - scaledW / 2f - minX * scale
            val oy = inset - minY * scale
            // Vertically centre the heart's bounding box.
            val extraOffsetY = (size - 2f * inset - scaledH) / 2f
            for (i in 0..steps) {
                val x = ox + xs[i] * scale
                val y = oy + ys[i] * scale + extraOffsetY
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        override fun toJson() = JSONObject().apply { put("kind", "heart") }
    }

    companion object {
        fun fromJson(o: JSONObject?): IconShape? {
            if (o == null) return null
            return when (val kind = o.optString("kind")) {
                "rounded_square" -> RoundedSquare(
                    cornerFraction = o.optDouble("corner_fraction", 0.22).toFloat(),
                )
                "circle" -> Circle
                "squircle" -> Squircle(n = o.optDouble("n", 4.0).toFloat())
                "hexagon" -> Hexagon(flatTop = o.optBoolean("flat_top", true))
                "heart" -> Heart
                "custom" -> {
                    val data = o.optString("path")
                    if (data.isBlank()) throw IllegalArgumentException(
                        "custom shape: 'path' field is required",
                    )
                    // Parse early so a malformed SVG path is rejected at
                    // install time, not at first render.
                    androidx.core.graphics.PathParser.createPathFromPathData(data)
                        ?: throw IllegalArgumentException("custom shape: invalid SVG path data")
                    CustomPath(data)
                }
                "" -> null
                else -> throw IllegalArgumentException("unknown icon shape: $kind")
            }
        }

        /** Default fallback — matches the launcher's pre-shape look. */
        val DEFAULT: IconShape = RoundedSquare(0.22f)
    }
}

private fun sign(v: Float): Float = if (v >= 0f) 1f else -1f
@Suppress("unused") private fun sqrtF(v: Float) = sqrt(v.toDouble()).toFloat()
