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
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Page

/**
 * Stylized mini-grid showing the placements on a single home page. Used by
 * [OverviewPanel] for the pinch-to-overview UI. Each placement is a rounded
 * rect at its grid position, color-coded by type so users can recognise their
 * pages at a glance:
 *  - Icon: small filled disc, density depending on cell size
 *  - Folder: 2×2 mini-dots inside the cell
 *  - Stock widget: solid accent-tinted block
 *  - Generated widget: dashed accent-tinted block
 */
class PageThumbnail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var page: Page? = null
    private var layoutCols: Int = 4
    private var layoutRows: Int = 5

    private val density = resources.displayMetrics.density
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A1A2E")
    }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#33FFFFFF")
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFFFFFF")
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88FFFFFF")
    }
    private val stockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.accentAlpha(context, 0x66)
    }
    private val genPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Palette.accentAlpha(context, 0xCC)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val genFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.accentAlpha(context, 0x33)
    }

    fun bind(layout: HomeLayout, page: Page) {
        this.page = page
        this.layoutCols = layout.cols
        this.layoutRows = layout.rows
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6f * density
        val panelRadius = 12f * density
        canvas.drawRoundRect(pad, pad, w - pad, h - pad, panelRadius, panelRadius, panelFill)
        canvas.drawRoundRect(pad, pad, w - pad, h - pad, panelRadius, panelRadius, panelStroke)

        val p = page ?: return
        val innerL = pad + 4f * density
        val innerT = pad + 4f * density
        val innerR = w - pad - 4f * density
        val innerB = h - pad - 4f * density
        val gw = innerR - innerL
        val gh = innerB - innerT
        val spacing = 2f * density
        val cellW = (gw - spacing * (layoutCols - 1)) / layoutCols
        val cellH = (gh - spacing * (layoutRows - 1)) / layoutRows
        val cellR = 2.5f * density

        for (placement in p.placements) {
            val cx = innerL + placement.col * (cellW + spacing)
            val cy = innerT + placement.row * (cellH + spacing)
            val cw = cellW * placement.wSpan + spacing * (placement.wSpan - 1)
            val ch = cellH * placement.hSpan + spacing * (placement.hSpan - 1)
            val rect = RectF(cx, cy, cx + cw, cy + ch)
            when (placement.type) {
                CellType.ICON -> {
                    val r = kotlin.math.min(cw, ch) * 0.4f
                    canvas.drawCircle(rect.centerX(), rect.centerY(), r, iconPaint)
                }
                CellType.FOLDER -> {
                    val miniR = kotlin.math.min(cw, ch) * 0.16f
                    val ox = rect.centerX() - cw * 0.18f
                    val oy = rect.centerY() - ch * 0.18f
                    val gx = rect.centerX() + cw * 0.18f
                    val gy = rect.centerY() + ch * 0.18f
                    canvas.drawCircle(ox, oy, miniR, folderPaint)
                    canvas.drawCircle(gx, oy, miniR, folderPaint)
                    canvas.drawCircle(ox, gy, miniR, folderPaint)
                    canvas.drawCircle(gx, gy, miniR, folderPaint)
                }
                CellType.STOCK_WIDGET -> {
                    canvas.drawRoundRect(rect, cellR, cellR, stockPaint)
                }
                CellType.GENERATED_WIDGET -> {
                    canvas.drawRoundRect(rect, cellR, cellR, genFill)
                    canvas.drawRoundRect(rect, cellR, cellR, genPaint)
                }
                CellType.APP_DRAWER -> {
                    // Render as a small dot — same visual class as an icon.
                    val r = kotlin.math.min(cw, ch) * 0.4f
                    canvas.drawCircle(rect.centerX(), rect.centerY(), r, iconPaint)
                }
            }
        }
    }
}
