/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Overlay showing how to draw each letter in one stroke (the native Field's
 * equivalent of the web ✎ guide). Renders all 26 single-stroke templates in a
 * grid, with a dot marking each stroke's start. Tap anywhere to dismiss.
 */
package com.iappyx.launcher.fieldnative

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class StrokeGuideView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.argb(238, 12, 12, 22) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(235, 255, 255, 255)
        strokeWidth = 2.4f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#78FFC2") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 11f * density
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 15f * density; isFakeBoldText = true
    }
    private val path = Path()
    private val letters = ('a'..'z').toList()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val w = width.toFloat()
        val cols = (w / (62f * density)).toInt().coerceIn(4, 6)
        val cell = w * 0.92f / cols
        val left = (w - cell * cols) / 2f
        val rows = Math.ceil(letters.size / cols.toDouble()).toInt()
        val gridH = rows * (cell + 8f * density)
        val top = (height - gridH) / 2f + 10f * density
        canvas.drawText("Draw each letter in one stroke — dot = start", w / 2f, top - 22f * density, title)

        val glyph = cell - 18f * density
        for (i in letters.indices) {
            val c = letters[i]
            val tpl = Unistroke.template(c) ?: continue
            val col = i % cols; val row = i / cols
            val cx = left + col * cell + (cell - glyph) / 2f
            val cy = top + row * (cell + 8f * density)
            path.reset()
            for (k in tpl.indices) {
                val px = cx + tpl[k][0] * glyph
                val py = cy + tpl[k][1] * glyph
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, stroke)
            canvas.drawCircle(cx + tpl[0][0] * glyph, cy + tpl[0][1] * glyph, 3.4f * density, startDot)
            canvas.drawText(c.toString(), cx + glyph / 2f, cy + glyph + 14f * density, label)
        }
    }
}
