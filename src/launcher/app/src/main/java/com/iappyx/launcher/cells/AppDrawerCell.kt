/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.iappyx.launcher.widget.Palette

/**
 * 1×1 cell that opens the app drawer when tapped. A circular accent-tinted
 * tile with a 3×3 dot grid drawn on top — the universal "All apps" glyph.
 *
 * Lives on home pages and in the dock. Click handler is wired by the
 * activity (so it can route through edit-mode-aware logic).
 *
 * Visually mirrors [IconCell]: 6dp internal padding, optional label below
 * with the same shadow treatment, 0.92× press-scale animation.
 */
class AppDrawerCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val padPx = (6 * density).toInt()
    private val glyphView: GlyphView

    /** Kept for source-compat with [IconCell.showLabel] so call-sites can
     *  set it uniformly. The 3×3 dot grid is universally recognised as
     *  "all apps" — adding a redundant "All apps" label below it pushed
     *  the glyph up and broke the dock-row alignment with neighbouring
     *  IconCells (which centre an icon+label stack). Now this is a no-op. */
    @Suppress("UNUSED_PARAMETER")
    var showLabel: Boolean = false
        set(value) { field = value /* intentional: glyph is self-explanatory */ }

    init {
        setPadding(padPx, padPx, padPx, padPx)

        glyphView = GlyphView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        addView(glyphView)

        isClickable = true
        // Micro-motion on press matching IconCell.
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260L)
                        .setInterpolator(OvershootInterpolator(2.2f)).start()
                }
            }
            false // don't consume — let the click listener fire normally
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Size the glyph to ~72% of the smaller cell dimension, like IconCell's
        // icon sizing — keeps it in proportion across grid sizes.
        val avail = (kotlin.math.min(measuredWidth, measuredHeight) - paddingLeft - paddingRight)
            .coerceAtLeast((24 * density).toInt())
        val glyphSize = (avail * 0.72f).toInt()
            .coerceIn((28 * density).toInt(), (72 * density).toInt())
        val spec = MeasureSpec.makeMeasureSpec(glyphSize, MeasureSpec.EXACTLY)
        glyphView.measure(spec, spec)
    }

    /** Material 3 "All apps" tile — rounded-square accent surface with a 3×3
     *  rounded-square dot grid in the on-primary contrast colour. Drawn
     *  programmatically so the corner radii + paint colours track the live
     *  Material You palette and stay crisp at any cell size. */
    private inner class GlyphView(context: Context) : View(context) {
        private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Subtle drop shadow under the tile for Material elevation feel.
            // setShadowLayer works on hardware-accelerated canvases on API 28+
            // (we target SDK 35), so no LAYER_TYPE_SOFTWARE workaround needed.
            setShadowLayer(
                4f * density, 0f, 1f * density,
                Color.argb(0x55, 0, 0, 0),
            )
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val tileRect = android.graphics.RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val side = kotlin.math.min(w, h)
            val cx = w / 2f; val cy = h / 2f
            // Match the system's adaptive-icon mask shape: ~22% corner radius.
            val tileR = side * 0.22f
            tileRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)

            // Accent surface (Material You primary container, via Palette).
            tilePaint.color = Palette.accent(context)
            canvas.drawRoundRect(tileRect, tileR, tileR, tilePaint)

            // 3×3 rounded-square dot grid — Material 3's "apps" glyph uses
            // squircles, not circles. Each mini-square is ~16% of the tile,
            // 3dp corner radius. Foreground in on-primary contrast.
            dotPaint.color = onPrimaryColor()
            val gridReach = side * 0.27f      // centre-to-outer-row distance
            val dotSize = side * 0.16f
            val dotHalf = dotSize / 2f
            val dotR = 3f * density
            val dotRect = android.graphics.RectF()
            for (rr in -1..1) for (cc in -1..1) {
                val dx = cx + cc * gridReach
                val dy = cy + rr * gridReach
                dotRect.set(dx - dotHalf, dy - dotHalf, dx + dotHalf, dy + dotHalf)
                canvas.drawRoundRect(dotRect, dotR, dotR, dotPaint)
            }
        }

        /** Contrast colour for foreground on the accent tile. On API 31+ this
         *  is the system-derived `accent1_900` (dark text on the accent_200
         *  surface, the canonical Material 3 pairing). Falls back to a near-
         *  black on older versions. */
        private fun onPrimaryColor(): Int {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    return resources.getColor(android.R.color.system_accent1_900, context.theme)
                } catch (_: Throwable) {}
            }
            return Color.parseColor("#0D0D1A")
        }
    }
}
