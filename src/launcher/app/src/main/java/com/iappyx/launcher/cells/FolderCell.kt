/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.iappyx.launcher.model.FolderItem

/**
 * Home-screen folder cell. A square rounded dark panel with a 2×2 grid of the
 * first 4 app icons, and the folder name below.
 *
 * Custom measure/layout keeps the panel square regardless of the cell's
 * aspect ratio — matching IconCell — so icons and folders read as a
 * consistent grid of square tiles.
 */
class FolderCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), com.iappyx.launcher.notify.BadgeStore.Observer {

    private val density = resources.displayMetrics.density
    private val panel: FrameLayout
    private val grid: androidx.gridlayout.widget.GridLayout
    private val label: TextView
    private val padPx = (6 * density).toInt()

    private var folderPackages: List<String> = emptyList()
    private var badgeCount: Int = 0
    private val badgeFillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = Color.parseColor("#FFE53935")
    }
    private val badgeStrokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val badgeTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        setPadding(padPx, padPx, padPx, padPx)

        grid = androidx.gridlayout.widget.GridLayout(context).apply {
            rowCount = 2; columnCount = 2
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
        }
        panel = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#44000000"))
                setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
            }
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(panel)

        label = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            // Soft drop shadow for legibility — matches IconCell labels.
            setShadowLayer(4f, 0f, 1f, Color.argb(0xCC, 0, 0, 0))
        }
        addView(label)
    }

    fun bind(name: String, items: List<FolderItem>, gridPos: GridPos? = null) {
        label.text = name
        // Match the theme font on every (re)bind, like IconCell labels.
        label.typeface = android.graphics.Typeface.create(
            com.iappyx.launcher.widget.Palette.themeTypeface(context) ?: android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL,
        )
        folderPackages = items.map { it.packageName }
        refreshBadgeCount()
        grid.removeAllViews()
        val pm = context.packageManager
        val shown = items.take(4)
        val miniSize = (48 * density).toInt()
        val spec = IconFilterRegistry.resolve(
            context, com.iappyx.launcher.LauncherPrefs(context).iconFilter,
        )
        // Tint applied to all 4 mini-icons matches the folder cell's own
        // position, so a folder reads as one tinted tile rather than four
        // mismatched fragments. Same set of cell-level filters as IconCell.
        val tint: Int? = IconFilterRunner.tintFor(context, spec, gridPos)
        for (item in shown) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                try {
                    val raw = pm.getApplicationIcon(item.packageName)
                    setImageBitmap(IconMask.render(item.packageName, raw, miniSize, spec))
                    if (tint != null) {
                        setColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY)
                    }
                } catch (_: Exception) { /* missing app */ }
            }
            val lp = androidx.gridlayout.widget.GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = androidx.gridlayout.widget.GridLayout.spec(
                    androidx.gridlayout.widget.GridLayout.UNDEFINED, 1f)
                rowSpec = androidx.gridlayout.widget.GridLayout.spec(
                    androidx.gridlayout.widget.GridLayout.UNDEFINED, 1f)
                val m = (3 * density).toInt()
                setMargins(m, m, m, m)
            }
            grid.addView(iv, lp)
        }
        for (i in shown.size until 4) {
            val spacer = View(context)
            val lp = androidx.gridlayout.widget.GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = androidx.gridlayout.widget.GridLayout.spec(
                    androidx.gridlayout.widget.GridLayout.UNDEFINED, 1f)
                rowSpec = androidx.gridlayout.widget.GridLayout.spec(
                    androidx.gridlayout.widget.GridLayout.UNDEFINED, 1f)
            }
            grid.addView(spacer, lp)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth - paddingLeft - paddingRight
        val h = measuredHeight - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val labelH = (16 * density).toInt()
        val labelTopPad = (2 * density).toInt()
        val minPanelForLabel = (32 * density).toInt()
        val effShowLabel = h - labelH - labelTopPad >= minPanelForLabel
        label.visibility = if (effShowLabel) View.VISIBLE else View.GONE

        val availableForPanel = if (effShowLabel) h - labelH - labelTopPad else h
        // Same formula as IconCell: 82% of min dimension, capped 28–72 dp, so
        // folders and icons form tiles of identical size → labels align.
        val target = (kotlin.math.min(w, availableForPanel) * 0.82f).toInt()
        val side = target.coerceIn((28 * density).toInt(), (72 * density).toInt())
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        panel.measure(spec, spec)

        if (effShowLabel) {
            label.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(labelH, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availW = r - l - paddingLeft - paddingRight
        val availH = b - t - paddingTop - paddingBottom
        if (availW <= 0 || availH <= 0) return
        val labelTopPad = (2 * density).toInt()
        val showingLabel = label.visibility == View.VISIBLE

        // Panel + label form a tight tile, label directly below the panel.
        // The tile is vertically centered in the cell — same layout formula
        // as IconCell so icons and folders align across rows.
        val side = panel.measuredWidth
        val lh = if (showingLabel) label.measuredHeight else 0
        val stackH = side + (if (showingLabel) labelTopPad + lh else 0)
        val topOffset = paddingTop + ((availH - stackH) / 2).coerceAtLeast(0)
        val panelLeft = paddingLeft + (availW - side) / 2
        panel.layout(panelLeft, topOffset, panelLeft + side, topOffset + side)

        if (showingLabel) {
            val lw = label.measuredWidth.coerceAtMost(availW)
            val lleft = paddingLeft + (availW - lw) / 2
            val ltop = topOffset + side + labelTopPad
            label.layout(lleft, ltop, lleft + lw, ltop + lh)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        com.iappyx.launcher.notify.BadgeStore.addObserver(this)
        refreshBadgeCount()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        com.iappyx.launcher.notify.BadgeStore.removeObserver(this)
    }

    override fun onBadgesChanged() { refreshBadgeCount() }

    private fun refreshBadgeCount() {
        val newCount = folderPackages.sumOf { com.iappyx.launcher.notify.BadgeStore.get(it) }
        if (newCount != badgeCount) {
            badgeCount = newCount
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (badgeCount <= 0) return
        // Anchor on the folder panel's top-right corner.
        val pTop = panel.top.toFloat()
        val pRight = panel.right.toFloat()
        val pSide = panel.width.toFloat().coerceAtLeast(1f)
        val badgeSide = (pSide * 0.28f).coerceAtLeast(14f * density)
        val cx = pRight - badgeSide * 0.35f
        val cy = pTop + badgeSide * 0.35f
        val text = if (badgeCount > 99) "99+" else badgeCount.toString()
        if (text.length <= 2) {
            canvas.drawCircle(cx, cy, badgeSide / 2f, badgeFillPaint)
            canvas.drawCircle(cx, cy, badgeSide / 2f, badgeStrokePaint)
        } else {
            val pillW = badgeSide * 1.45f
            val rect = android.graphics.RectF(
                cx - pillW / 2f, cy - badgeSide / 2f,
                cx + pillW / 2f, cy + badgeSide / 2f,
            )
            canvas.drawRoundRect(rect, badgeSide / 2f, badgeSide / 2f, badgeFillPaint)
            canvas.drawRoundRect(rect, badgeSide / 2f, badgeSide / 2f, badgeStrokePaint)
        }
        badgeTextPaint.textSize = badgeSide * 0.62f
        val baselineY = cy - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
        canvas.drawText(text, cx, baselineY, badgeTextPaint)
    }
}
