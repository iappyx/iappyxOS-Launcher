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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Vertical alphabet rail: letters A-Z + "#". Drag/tap to report a letter back
 * to the host. Active letters (present in the current dataset) render crisp;
 * missing letters render dim.
 */
class AlphabetRail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val letters = ("#" + ('A'..'Z').joinToString("")).toCharArray()

    /** Letters present in the current data source. Only these are "active". */
    var activeLetters: Set<Char> = letters.toSet()
        set(value) { field = value; invalidate() }

    /** Letters whose section is currently visible in the list — highlighted
     *  subtly while the user scrolls so they can see where they are. */
    var visibleLetters: Set<Char> = emptySet()
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Current highlight (the user's finger position). */
    var highlight: Char? = null
        set(value) { field = value; invalidate() }

    /** Called as the user drags over the rail, repeatedly while finger is down. */
    var onLetterTouch: ((Char) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEFFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accent(context)
        style = Paint.Style.FILL
    }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D1A")
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        isFakeBoldText = true
    }
    /** A soft rounded highlight behind letters whose section is currently in the list's
     *  viewport. Much more subtle than the finger-drag pill. */
    private val scrollHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accentAlpha(context, 0x33)
        style = Paint.Style.FILL
    }
    private val visibleLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accent(context)
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val step = h / letters.size
        val pillRadius = (w.coerceAtMost(step) * 0.5f) - 2 * density

        // Draw a continuous soft-highlight capsule covering the range of visible
        // letters. Much cleaner than per-letter pills and gives a scrubber feel.
        if (visibleLetters.isNotEmpty() && highlight == null) {
            val indices = visibleLetters.mapNotNull { c -> letters.indexOf(c).takeIf { it >= 0 } }
                .sorted()
            if (indices.isNotEmpty()) {
                val top = indices.first() * step + 1.5f * density
                val bottom = (indices.last() + 1) * step - 1.5f * density
                val cx = w / 2f
                val left = cx - pillRadius
                val right = cx + pillRadius
                val rect = android.graphics.RectF(left, top, right, bottom)
                canvas.drawRoundRect(rect, pillRadius, pillRadius, scrollHighlight)
            }
        }

        for (i in letters.indices) {
            val c = letters[i]
            val cy = i * step + step / 2f + activePaint.textSize / 3f
            if (highlight == c) {
                val cxf = w / 2f
                val cyPill = i * step + step / 2f
                canvas.drawCircle(cxf, cyPill, pillRadius, pill)
                canvas.drawText(c.toString(), w / 2f, cy, hlPaint)
            } else {
                val p = when {
                    visibleLetters.contains(c) -> visibleLetterPaint
                    activeLetters.contains(c) -> activePaint
                    else -> dimPaint
                }
                canvas.drawText(c.toString(), w / 2f, cy, p)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val idx = ((ev.y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
                val c = letters[idx]
                if (c != highlight) {
                    highlight = c
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onLetterTouch?.invoke(c)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                highlight = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
