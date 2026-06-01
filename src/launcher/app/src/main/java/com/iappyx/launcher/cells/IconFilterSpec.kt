/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative description of a launcher icon filter — a small JSON spec the
 * runner ([IconFilterRunner]) interprets at icon-render time. Replaces the
 * formerly-hardcoded [IconFilter] enum with a data format so user-generated
 * (and AI-generated) filters can ship as plain JSON files alongside the
 * bundled set in `assets/icon_filters/`.
 *
 * Two phases:
 *   - **bake** — list of [BakeOp] applied to the cached icon bitmap (one
 *     pass per chain via [IconFilterRunner.applyBake]). Pure-matrix ops
 *     (set_saturation, color_matrix) are coalesced into a single
 *     ColorMatrix via post-concat for parity with the old hand-coded
 *     paths. Atomic ops (pixelate, aurora, tinted_mono) delegate to the
 *     existing helpers in [IconMask].
 *   - **tint** — optional per-cell colour computed at view-bind time and
 *     applied via `setColorFilter` on the ImageView. Position-aware ops
 *     (position_hue, wallpaper_color) read the cell's [GridPos]; system_accent
 *     reads the OS Material You accent.
 */
data class IconFilterSpec(
    val slug: String,
    val name: String,
    val subtitle: String? = null,
    val bake: List<BakeOp> = emptyList(),
    val tint: TintOp? = null,
    /** Silhouette shape; `null` keeps the launcher default (rounded
     *  square, 22 % corners). Renderers consult this *before* the bake
     *  chain so atmospheric ops (vignette, glow, aurora) trace the new
     *  silhouette instead of the rounded-square. */
    val shape: IconShape? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slug", slug)
        put("name", name)
        subtitle?.let { put("subtitle", it) }
        if (bake.isNotEmpty()) {
            put("bake", JSONArray().apply { bake.forEach { put(it.toJson()) } })
        }
        tint?.let { put("tint", it.toJson()) }
        shape?.let { put("shape", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): IconFilterSpec {
            val slug = o.optString("slug", "").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("spec missing slug")
            val name = o.optString("name", slug)
            val subtitle = o.optString("subtitle", "").takeIf { it.isNotBlank() }
            val bake = mutableListOf<BakeOp>()
            o.optJSONArray("bake")?.let { arr ->
                for (i in 0 until arr.length()) bake.add(BakeOp.fromJson(arr.getJSONObject(i)))
            }
            val tint = o.optJSONObject("tint")?.let { TintOp.fromJson(it) }
            val shape = IconShape.fromJson(o.optJSONObject("shape"))
            return IconFilterSpec(slug, name, subtitle, bake, tint, shape)
        }
    }
}

sealed class BakeOp {
    abstract fun toJson(): JSONObject

    /** ColorMatrix.setSaturation(value). Combined with adjacent matrix ops. */
    data class SetSaturation(val value: Float) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "set_saturation"); put("value", value.toDouble())
        }
    }

    /** Direct 4x5 ColorMatrix. Combined with adjacent matrix ops via post-concat. */
    data class Matrix(val values: FloatArray) : BakeOp() {
        init { require(values.size == 20) { "color_matrix needs 20 floats, got ${values.size}" } }
        override fun toJson() = JSONObject().apply {
            put("op", "color_matrix")
            put("values", JSONArray().apply { values.forEach { put(it.toDouble()) } })
        }
        // Equality for testing — FloatArray uses identity by default.
        override fun equals(other: Any?): Boolean =
            other is Matrix && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    /** Downsample to a tiny grid then upscale with nearest-neighbour. */
    data class Pixelate(val grid: Int) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "pixelate"); put("grid", grid)
        }
    }

    /** Per-icon dominant-colour tint (iOS-18 flavour). Atomic. */
    object TintedMono : BakeOp() {
        override fun toJson() = JSONObject().apply { put("op", "tinted_mono") }
    }

    /** Iridescent sweep gradient with OVERLAY blend, alpha-clipped. Atomic. */
    data class Aurora(val colors: List<String>) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "aurora")
            put("colors", JSONArray().apply { colors.forEach { put(it) } })
        }
    }

    /** Brightness offset (±exposure). Sugar over [Matrix]: compiles to a
     *  ColorMatrix that adds the same offset to each channel. `value` is
     *  normalised −1..+1 (clamped); the runner scales to ±128 in 8-bit
     *  space. */
    data class Brightness(val value: Float) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "brightness"); put("value", value.toDouble())
        }
    }

    /** Contrast adjustment around mid-grey. Sugar over [Matrix]: scales
     *  each channel around 128. `value` −1..+1 (clamped); 0 = identity,
     *  +1 doubles contrast, −1 collapses everything to mid-grey. */
    data class Contrast(val value: Float) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "contrast"); put("value", value.toDouble())
        }
    }

    /** Rotate hue around the HSL wheel. Sugar over [Matrix] using the
     *  standard hue-rotation approximation (Wikipedia formula). */
    data class HueRotate(val degrees: Float) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "hue_rotate"); put("degrees", degrees.toDouble())
        }
    }

    /** Map luminance between two colours — split-tone / duotone effect.
     *  `balance` shifts the midpoint: −1 favours shadow_color across the
     *  whole image, +1 favours highlight_color, 0 = clean linear blend.
     *  Atomic. */
    data class Duotone(
        val shadowColor: String,
        val highlightColor: String,
        val balance: Float = 0f,
    ) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "duotone")
            put("shadow_color", shadowColor)
            put("highlight_color", highlightColor)
            put("balance", balance.toDouble())
        }
    }

    /** Radial darkening at the icon's edges. `amount` 0..1 = how dark the
     *  outer ring becomes; `feather` 0..1 = softness of the falloff
     *  (0 = hard edge, 1 = smooth grad). Atomic. */
    data class Vignette(val amount: Float, val feather: Float) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "vignette")
            put("amount", amount.toDouble())
            put("feather", feather.toDouble())
        }
    }

    /** Film-grain noise overlay. `amount` 0..1 = opacity; `size` 1..8 =
     *  pixel-block size of each grain particle (larger = chunkier). Atomic. */
    data class Grain(val amount: Float, val size: Int) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "grain")
            put("amount", amount.toDouble())
            put("size", size)
        }
    }

    /** Quantise each colour channel to [levels] discrete steps. 2..16;
     *  4 looks like a screen-print, 8 still subtle. Atomic. */
    data class Posterize(val levels: Int) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "posterize"); put("levels", levels)
        }
    }

    /** Bloom / glow halo. Renders a blurred copy of the icon, tints it,
     *  alpha-multiplies, and draws under the original. `radius` 1..40 px;
     *  `color` is the glow tint; `alpha` 0..1. API 31+ only — no-op on
     *  older Android. Atomic. */
    data class Glow(
        val radius: Float,
        val color: String,
        val alpha: Float,
    ) : BakeOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "glow")
            put("radius", radius.toDouble())
            put("color", color)
            put("alpha", alpha.toDouble())
        }
    }

    companion object {
        fun fromJson(o: JSONObject): BakeOp = when (val op = o.optString("op")) {
            "set_saturation" -> SetSaturation(o.getDouble("value").toFloat())
            "color_matrix" -> {
                val arr = o.getJSONArray("values")
                if (arr.length() != 20) throw IllegalArgumentException(
                    "color_matrix needs 20 values, got ${arr.length()}",
                )
                val vals = FloatArray(20) { arr.getDouble(it).toFloat() }
                Matrix(vals)
            }
            "pixelate" -> Pixelate(o.optInt("grid", 14))
            "tinted_mono" -> TintedMono
            "aurora" -> {
                val arr = o.getJSONArray("colors")
                val cols = List(arr.length()) { arr.getString(it) }
                cols.forEach { validateHex(it, "aurora") }
                Aurora(cols)
            }
            "brightness" -> Brightness(o.getDouble("value").toFloat())
            "contrast" -> Contrast(o.getDouble("value").toFloat())
            "hue_rotate" -> HueRotate(o.getDouble("degrees").toFloat())
            "duotone" -> {
                val shadow = o.getString("shadow_color")
                val highlight = o.getString("highlight_color")
                validateHex(shadow, "duotone.shadow_color")
                validateHex(highlight, "duotone.highlight_color")
                Duotone(
                    shadowColor = shadow,
                    highlightColor = highlight,
                    balance = o.optDouble("balance", 0.0).toFloat(),
                )
            }
            "vignette" -> Vignette(
                amount = o.optDouble("amount", 0.5).toFloat(),
                feather = o.optDouble("feather", 0.5).toFloat(),
            )
            "grain" -> Grain(
                amount = o.optDouble("amount", 0.4).toFloat(),
                size = o.optInt("size", 1),
            )
            "posterize" -> Posterize(o.optInt("levels", 4))
            "glow" -> {
                val color = o.optString("color", "#FFFFFFFF")
                validateHex(color, "glow.color")
                Glow(
                    radius = o.optDouble("radius", 8.0).toFloat(),
                    color = color,
                    alpha = o.optDouble("alpha", 0.5).toFloat(),
                )
            }
            else -> throw IllegalArgumentException("unknown bake op: $op")
        }

        /** Validate a hex colour at parse time so the runner doesn't have
         *  to deal with [android.graphics.Color.parseColor] throwing
         *  mid-render and aborting the entire bake chain. Accepts
         *  `#RRGGBB` and `#AARRGGBB`; rejects names like "red" / "rgb(...)"
         *  even though Android's parseColor accepts some of them — keeps
         *  the spec format predictable for AI generation. */
        private fun validateHex(value: String, where: String) {
            val ok = value.startsWith("#") && (value.length == 7 || value.length == 9) &&
                value.substring(1).all {
                    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                }
            if (!ok) throw IllegalArgumentException(
                "$where must be #RRGGBB or #AARRGGBB hex, got '$value'",
            )
        }
    }
}

sealed class TintOp {
    abstract fun toJson(): JSONObject

    /** Per-cell hue based on grid position (radial rainbow). */
    data class PositionHue(val saturation: Float, val value: Float) : TintOp() {
        override fun toJson() = JSONObject().apply {
            put("op", "position_hue")
            put("saturation", saturation.toDouble()); put("value", value.toDouble())
        }
    }

    /** Per-cell tint sampled from the user's wallpaper at the cell's region. */
    object WallpaperColor : TintOp() {
        override fun toJson() = JSONObject().apply { put("op", "wallpaper_color") }
    }

    /** System Material You accent (uniform across the grid). */
    object SystemAccent : TintOp() {
        override fun toJson() = JSONObject().apply { put("op", "system_accent") }
    }

    companion object {
        fun fromJson(o: JSONObject): TintOp = when (val op = o.optString("op")) {
            "position_hue" -> PositionHue(
                saturation = o.optDouble("saturation", 0.7).toFloat(),
                value = o.optDouble("value", 1.0).toFloat(),
            )
            "wallpaper_color" -> WallpaperColor
            "system_accent" -> SystemAccent
            else -> throw IllegalArgumentException("unknown tint op: $op")
        }
    }
}
