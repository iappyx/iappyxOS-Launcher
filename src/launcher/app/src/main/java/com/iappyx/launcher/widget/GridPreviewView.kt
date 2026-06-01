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
import android.view.View

/**
 * A mini visual preview of a home layout — draws `cols × rows` rounded rectangles
 * in the upper area and a row of `dockSlots` pills below. Used in Settings so
 * users see what they're picking instead of just reading numbers.
 */
class GridPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var cols: Int = 5
        set(value) { field = value.coerceAtLeast(1); invalidate() }
    var rows: Int = 6
        set(value) { field = value.coerceAtLeast(1); invalidate() }
    var dockSlots: Int = 5
        set(value) { field = value.coerceAtLeast(1); invalidate() }

    private val density = resources.displayMetrics.density
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 255, 255, 255)
    }
    private val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(90, 255, 255, 255)
    }
    private val dockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.accent(context)
        alpha = 180
    }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0D0D1A")
    }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(60, 255, 255, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 6f * density
        val w = width - pad * 2
        val h = height - pad * 2
        val panelRadius = 12f * density
        canvas.drawRoundRect(pad, pad, pad + w, pad + h, panelRadius, panelRadius, panelFill)
        canvas.drawRoundRect(pad, pad, pad + w, pad + h, panelRadius, panelRadius, panelStroke)

        val innerPad = 8f * density
        val dockRowH = 18f * density
        val dockGap = 8f * density

        val gridL = pad + innerPad
        val gridT = pad + innerPad
        val gridR = pad + w - innerPad
        val gridB = pad + h - innerPad - dockRowH - dockGap
        val gridW = gridR - gridL
        val gridH = gridB - gridT

        val spacing = 3f * density
        val cellW = (gridW - spacing * (cols - 1)) / cols
        val cellH = (gridH - spacing * (rows - 1)) / rows
        val cellR = 3f * density
        for (r in 0 until rows) for (c in 0 until cols) {
            val left = gridL + c * (cellW + spacing)
            val top = gridT + r * (cellH + spacing)
            val rect = RectF(left, top, left + cellW, top + cellH)
            canvas.drawRoundRect(rect, cellR, cellR, cellFill)
            canvas.drawRoundRect(rect, cellR, cellR, cellStroke)
        }

        val dockT = gridB + dockGap
        val dockB = dockT + dockRowH
        val dockSpacing = 4f * density
        val slotW = (gridW - dockSpacing * (dockSlots - 1)) / dockSlots
        val slotR = 4f * density
        for (i in 0 until dockSlots) {
            val left = gridL + i * (slotW + dockSpacing)
            val rect = RectF(left, dockT, left + slotW, dockB)
            canvas.drawRoundRect(rect, slotR, slotR, dockFill)
        }
    }
}
