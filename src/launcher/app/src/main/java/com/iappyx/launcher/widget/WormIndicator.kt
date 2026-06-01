/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Page indicator drawn as a single elongated pill that stretches + slides as
 * the pager scrolls — when transitioning between two pages the pill widens to
 * cover both dots, then narrows again on the destination. "Worm"-style, feels
 * alive compared to plain dots.
 *
 * **Tappable.** The visual dots are tiny (~6dp) but the hit zone fills the
 * full strip height × per-dot horizontal slot (dot + gap). Taps map to the
 * nearest dot center, so a finger landing in any slot jumps reliably to that
 * page. Set [onDotClick] to receive the 0-based dot index.
 */
class WormIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pageCount: Int = 1
        set(value) { field = value.coerceAtLeast(1); invalidate() }

    /** Integer page + fractional offset [0..1) — typically driven by
     *  [androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback.onPageScrolled]. */
    private var currentPage: Int = 0
    private var offset: Float = 0f

    /** When true, draw a leading "AI" pill to the left of the dot row,
     *  representing the AI Command page (pager position 0 in the home pager).
     *  Off by default — the dock indicator never wants this. */
    var showCommandGlyph: Boolean = false
        set(value) { field = value; invalidate(); requestLayout() }

    /** When true, draw a trailing paperclip glyph to the right of the dot row,
     *  representing the Clippings page (rightmost pager position). Mirrors
     *  [showCommandGlyph]. The dock indicator never wants this. */
    var showClippingsGlyph: Boolean = false
        set(value) { field = value; invalidate(); requestLayout() }

    /** Fires when the user taps the trailing Clippings glyph. */
    var onClippingsClick: (() -> Unit)? = null

    /** 0..1 — how active the command page is. 1 = pager fully on command
     *  page; 0 = pager on a home page; intermediate during the swipe. The
     *  glyph blends from dim (#55FFFFFF) to accent (worm color) by this value. */
    private var commandActivity: Float = 0f

    fun setCommandActivity(activity: Float) {
        if (!showCommandGlyph) return
        val v = activity.coerceIn(0f, 1f)
        if (v != commandActivity) {
            commandActivity = v
            invalidate()
        }
    }

    /** Fires when the user taps within a dot's hit zone. The argument is the
     *  0-based page index the tap mapped to. Caller wires this into a
     *  ViewPager2.setCurrentItem(...) call. Tap detection uses the system
     *  touch slop + tap timeout so a swipe across the indicator doesn't
     *  accidentally fire. */
    var onDotClick: ((pageIndex: Int) -> Unit)? = null

    /** Fires when the user taps the leading AI pill (short tap). */
    var onCommandClick: (() -> Unit)? = null

    /** Fires when the user holds the AI pill past the long-press threshold —
     *  start recording for voice input. The host should kick off speech
     *  recognition and call [setRecording] to drive the visual state. */
    var onCommandLongPress: (() -> Unit)? = null

    /** Fires when the user releases the AI pill AFTER a long-press fired —
     *  stop recording and finalize the transcription. */
    var onCommandLongPressEnd: (() -> Unit)? = null

    /** Fires when an in-flight long-press is cancelled (pointer leaves the
     *  pill, or the gesture is interrupted). The host should drop the
     *  recognition session without acting on partial results. */
    var onCommandLongPressCancel: (() -> Unit)? = null

    /** Toggles the AI pill's "recording" visual state — red fill instead of
     *  the dim/accent blend, with a slow alpha pulse so the user knows the
     *  microphone is hot. Driven by the host when the speech recognizer
     *  starts/stops. */
    fun setRecording(active: Boolean) {
        if (recording == active) return
        recording = active
        if (active) startRecordingPulse() else stopRecordingPulse()
        invalidate()
    }
    private var recording: Boolean = false
    private var recordingPulse: Float = 1f
    private var pulseAnimator: android.animation.ValueAnimator? = null
    private fun startRecordingPulse() {
        stopRecordingPulse()
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 700L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                recordingPulse = v.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    private fun stopRecordingPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        recordingPulse = 1f
    }

    fun setScroll(page: Int, off: Float) {
        currentPage = page
        offset = off.coerceIn(0f, 1f)
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong() +
        ViewConfiguration.getDoubleTapTimeout().toLong() // generous; user may pause briefly
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.FILL
    }
    private val wormPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accent(context)
        style = Paint.Style.FILL
    }
    /** Pill outline (1.5dp stroke, transparent fill, fully-rounded ends). */
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.5f * density
    }
    /** "AI" letterforms inside the pill. Bold, slightly tracked, centered. */
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        textSize = 11f * density
        letterSpacing = 0.05f
    }
    /** Last ACTION_DOWN coords + timestamp — used to distinguish a tap from
     *  a scroll/long-press in [onTouchEvent]. */
    /** Last theme-font base the "AI" pill paint was built from, so onDraw only
     *  rebuilds the bold Typeface when the font actually changes. */
    private var lastFontBase: android.graphics.Typeface? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var downTime: Long = 0L
    /** Set in ACTION_DOWN when the touch lands on the AI pill — gates the
     *  long-press timer + the eventual UP/CANCEL routing. */
    private var pressOnCommandPill: Boolean = false
    private var commandLongPressFired: Boolean = false
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!pressOnCommandPill) return@Runnable
        commandLongPressFired = true
        // Confirm with a soft haptic so the user knows recording started.
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onCommandLongPress?.invoke()
    }

    init {
        // Talkback affordance — without isClickable, screen readers won't
        // announce the strip as interactive at all. The indicator already
        // has a contentDescription set via the layout XML in some forks,
        // but we set a default here too in case it's missing.
        isClickable = true
        if (contentDescription == null) {
            contentDescription = "Page indicator — tap a dot to jump to that page"
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (pageCount <= 1 && !showCommandGlyph && !showClippingsGlyph) return
        // Re-read the accent each draw so a theme change is picked up live
        // (cheap — Palette caches per override-generation). invalidate() from
        // LauncherActivity.onResume triggers a repaint after the change.
        wormPaint.color = Palette.accent(context)
        // Follow the theme font on the "AI" pill (canvas-drawn, not a TextView
        // the walk reaches). themeTypeface is generation-cached, so this is a
        // cheap reference compare each frame; only rebuild the bold face when
        // the base font actually changes.
        val base = Palette.themeTypeface(context) ?: android.graphics.Typeface.SANS_SERIF
        if (base !== lastFontBase) {
            lastFontBase = base
            pillTextPaint.typeface = android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD)
        }
        val dotSize = 8f * density
        val gap = 10f * density
        val pillW = 24f * density       // pill width (fits "AI" comfortably)
        val pillH = 15f * density       // pill height
        val pillLead = 14f * density    // gap between pill and first/last dot
        val clipSize = 15f * density    // paperclip glyph footprint (square-ish)
        val totalW = pageCount * dotSize + (pageCount - 1) * gap
        // Centre the dot row, but pull it left when there's a trailing
        // clippings glyph and right when there's a leading AI pill, so the
        // whole arrangement is symmetric on screen.
        val leftBias = if (showCommandGlyph) (pillW + pillLead) / 2f else 0f
        val rightBias = if (showClippingsGlyph) (clipSize + pillLead) / 2f else 0f
        val dotStartX = (width - totalW) / 2f + leftBias - rightBias
        val cy = height / 2f

        // Leading "AI" pill — small rounded rectangle outline + bold "AI" text.
        if (showCommandGlyph) {
            val pillCx = dotStartX - pillLead - pillW / 2f
            // Color: red while recording, otherwise blend dim → accent.
            val baseColor = if (recording) {
                // Pulse the alpha while recording so the mic-hot state reads
                // as "I am actively listening." The pulse oscillates 55%→100%.
                val a = (255 * recordingPulse).toInt().coerceIn(0, 255)
                Color.argb(a, 0xFF, 0x52, 0x52) // #FF5252 with pulsing alpha
            } else {
                blendColors(
                    Color.parseColor("#55FFFFFF"),
                    Palette.accent(context),
                    commandActivity,
                )
            }
            pillStrokePaint.color = baseColor
            pillTextPaint.color = baseColor
            val rect = RectF(
                pillCx - pillW / 2f, cy - pillH / 2f,
                pillCx + pillW / 2f, cy + pillH / 2f,
            )
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, pillStrokePaint)
            // Vertical text centring: baseline = cy - (ascent + descent) / 2
            val fm = pillTextPaint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            // While recording, render a small mic dot inside the pill instead
            // of the "AI" text — the dot pulses with the same alpha so the
            // user has an unambiguous visual signal.
            if (recording) {
                canvas.drawCircle(pillCx, cy, pillH * 0.22f, pillTextPaint.apply { style = Paint.Style.FILL })
                pillTextPaint.style = Paint.Style.FILL // already fill but explicit
            } else {
                canvas.drawText("AI", pillCx, baseline, pillTextPaint)
            }
        }

        // Background dots
        for (i in 0 until pageCount) {
            val cx = dotStartX + i * (dotSize + gap) + dotSize / 2f
            canvas.drawCircle(cx, cy, dotSize / 2f, dotPaint)
        }

        // Trailing Clippings paperclip glyph (right of the dot row).
        if (showClippingsGlyph) {
            val lastDotCx = dotStartX + (pageCount - 1) * (dotSize + gap) + dotSize / 2f
            val clipCx = lastDotCx + dotSize / 2f + pillLead + clipSize / 2f
            drawPaperclip(canvas, clipCx, cy, clipSize)
        }

        // Worm: for offset 0, it sits over currentPage; as offset grows the
        // LEADING edge moves to the next dot first, then TRAILING catches up.
        // While the user is sliding into the clippings page (clippingsActivity
        // > 0) the worm fades out — the paperclip becomes the active indicator
        // instead, so the worm shouldn't visually compete with it.
        if (pageCount > 1 && clippingsActivity < 1f) {
            val p = currentPage.coerceIn(0, pageCount - 1)
            val fromCx = dotStartX + p * (dotSize + gap) + dotSize / 2f
            val toCx = dotStartX + (p + 1).coerceAtMost(pageCount - 1) * (dotSize + gap) + dotSize / 2f
            // Leading edge: accelerate out — reaches target at offset 0.6
            val lead = (offset / 0.6f).coerceAtMost(1f)
            // Trailing edge: lag behind — starts moving at offset 0.4
            val trail = ((offset - 0.4f) / 0.6f).coerceIn(0f, 1f)
            val leftCx = fromCx + (toCx - fromCx) * trail
            val rightCx = fromCx + (toCx - fromCx) * lead
            val rect = RectF(
                leftCx - dotSize / 2f, cy - dotSize / 2f,
                rightCx + dotSize / 2f, cy + dotSize / 2f,
            )
            val baseAlpha = Color.alpha(wormPaint.color)
            val faded = (baseAlpha * (1f - clippingsActivity)).toInt().coerceIn(0, 255)
            wormPaint.alpha = faded
            canvas.drawRoundRect(rect, dotSize / 2f, dotSize / 2f, wormPaint)
            wormPaint.alpha = baseAlpha
        }
    }

    /** Draws a small stylised paperclip (two stacked rounded rectangles) at
     *  ([cx], [cy]) sized to fit a [size] × [size] box. Stroke uses the same
     *  dim/accent colour as the dots so the glyph reads as a peer of the
     *  indicator, not a button. */
    private fun drawPaperclip(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val outerW = size * 0.55f
        val outerH = size * 0.95f
        val innerW = size * 0.32f
        val innerH = size * 0.58f
        val outerR = outerW / 2f
        val innerR = innerW / 2f
        val color = blendColors(
            Color.parseColor("#88FFFFFF"),
            Palette.accent(context),
            // Light up the glyph as the user approaches the clippings page —
            // [clippingsActivity] is set externally by the host similar to
            // [commandActivity] for the AI pill.
            clippingsActivity,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.5f * density
        }
        // Tilt the whole glyph ~25° so it reads as a paperclip, not a tube.
        canvas.save()
        canvas.rotate(-25f, cx, cy)
        val outer = RectF(cx - outerW / 2f, cy - outerH / 2f, cx + outerW / 2f, cy + outerH / 2f)
        canvas.drawRoundRect(outer, outerR, outerR, paint)
        val inner = RectF(cx - innerW / 2f, cy - innerH / 2f + size * 0.06f,
            cx + innerW / 2f, cy + innerH / 2f + size * 0.06f)
        canvas.drawRoundRect(inner, innerR, innerR, paint)
        canvas.restore()
    }

    /** 0..1 — driven by the host (LauncherActivity.pager.OnPageScrolled) so
     *  the paperclip lights up smoothly as the user swipes onto the
     *  clippings page. Mirrors [commandActivity] on the leading edge. */
    private var clippingsActivity: Float = 0f

    fun setClippingsActivity(activity: Float) {
        if (!showClippingsGlyph) return
        val v = activity.coerceIn(0f, 1f)
        if (v != clippingsActivity) {
            clippingsActivity = v
            invalidate()
        }
    }

    /** Channel-wise linear blend between two ARGB ints. */
    private fun blendColors(c0: Int, c1: Int, t: Float): Int {
        val a = (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t).toInt()
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t).toInt()
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t).toInt()
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    /** Tap-to-jump handling. We don't intercept moves — only translate a
     *  short clean tap (small displacement, short duration) into a dot
     *  index. Anything else (long-press, drag) is ignored so the user
     *  can still scroll through the parent layout if they happen to
     *  swipe across the indicator strip. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageCount <= 1 && !showCommandGlyph && !showClippingsGlyph) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                pressOnCommandPill = showCommandGlyph && isInCommandGlyphZone(event.x)
                commandLongPressFired = false
                if (pressOnCommandPill) {
                    // Custom threshold (700ms) — system default of ~500ms was
                    // firing on incidental holds. Voice mode is consequential
                    // enough that a deliberate-feeling press is right.
                    longPressHandler.postDelayed(longPressRunnable, 700L)
                }
                // Returning true claims the gesture so we receive the
                // matching ACTION_UP on this view.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // If the finger drifts off the pill before long-press fires,
                // cancel the long-press timer — the user may be aborting.
                if (pressOnCommandPill && !commandLongPressFired) {
                    if (!isInCommandGlyphZone(event.x)) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        pressOnCommandPill = false
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val elapsed = event.eventTime - downTime
                longPressHandler.removeCallbacks(longPressRunnable)
                if (commandLongPressFired) {
                    // Long-press completed — finalize transcription.
                    commandLongPressFired = false
                    pressOnCommandPill = false
                    onCommandLongPressEnd?.invoke()
                    return true
                }
                if (dx <= touchSlop && dy <= touchSlop && elapsed <= tapTimeout) {
                    if (showCommandGlyph && isInCommandGlyphZone(event.x)) {
                        performClick()
                        onCommandClick?.invoke()
                    } else if (showClippingsGlyph && isInClippingsGlyphZone(event.x)) {
                        performClick()
                        onClippingsClick?.invoke()
                    } else {
                        val idx = pageIndexForX(event.x)
                        if (idx in 0 until pageCount) {
                            // Provide a soft haptic + announce so the
                            // interaction feels real. performClick() also
                            // routes through any OnClickListener the host
                            // attached, plus emits the AccessibilityEvent.
                            performClick()
                            onDotClick?.invoke(idx)
                        }
                    }
                }
                pressOnCommandPill = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (commandLongPressFired) {
                    commandLongPressFired = false
                    onCommandLongPressCancel?.invoke()
                }
                pressOnCommandPill = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Map a raw X coordinate within this view to the nearest dot index.
     *  We compute the centre of each dot (matching [onDraw]) and pick the
     *  one whose centre is closest to [x]. Taps outside the dot row's
     *  total horizontal extent still snap to the nearest end dot — a
     *  finger on the far left maps to dot 0, far right to dot N-1. */
    private fun pageIndexForX(x: Float): Int {
        val dotSize = 8f * density
        val gap = 10f * density
        val pillW = 24f * density
        val pillLead = 14f * density
        val clipSize = 15f * density
        val totalW = pageCount * dotSize + (pageCount - 1) * gap
        val leftBias = if (showCommandGlyph) (pillW + pillLead) / 2f else 0f
        val rightBias = if (showClippingsGlyph) (clipSize + pillLead) / 2f else 0f
        val dotStartX = (width - totalW) / 2f + leftBias - rightBias
        val slotWidth = dotSize + gap
        val firstCenterX = dotStartX + dotSize / 2f
        val rel = x - firstCenterX
        val idx = (rel / slotWidth).roundToInt()
        return idx.coerceIn(0, pageCount - 1)
    }

    /** True when [x] is within the leading AI pill's tap region. The pill is
     *  small (~18dp wide) but the hit area is generously expanded so a finger
     *  landing near it reliably triggers the command jump. */
    private fun isInCommandGlyphZone(x: Float): Boolean {
        if (!showCommandGlyph) return false
        val dotSize = 8f * density
        val gap = 10f * density
        val pillW = 24f * density
        val pillLead = 14f * density
        val clipSize = 15f * density
        val totalW = pageCount * dotSize + (pageCount - 1) * gap
        val rightBias = if (showClippingsGlyph) (clipSize + pillLead) / 2f else 0f
        val dotStartX = (width - totalW) / 2f + (pillW + pillLead) / 2f - rightBias
        val pillCx = dotStartX - pillLead - pillW / 2f
        // Hit zone: pill width + 12dp pad on each side
        val halfHit = pillW / 2f + 12f * density
        return x >= pillCx - halfHit && x <= pillCx + halfHit
    }

    /** Mirror of [isInCommandGlyphZone] for the trailing paperclip. */
    private fun isInClippingsGlyphZone(x: Float): Boolean {
        if (!showClippingsGlyph) return false
        val dotSize = 8f * density
        val gap = 10f * density
        val pillW = 24f * density
        val pillLead = 14f * density
        val clipSize = 15f * density
        val totalW = pageCount * dotSize + (pageCount - 1) * gap
        val leftBias = if (showCommandGlyph) (pillW + pillLead) / 2f else 0f
        val dotStartX = (width - totalW) / 2f + leftBias - (clipSize + pillLead) / 2f
        val lastDotCx = dotStartX + (pageCount - 1) * (dotSize + gap) + dotSize / 2f
        val clipCx = lastDotCx + dotSize / 2f + pillLead + clipSize / 2f
        val halfHit = clipSize / 2f + 12f * density
        return x >= clipCx - halfHit && x <= clipCx + halfHit
    }
}
