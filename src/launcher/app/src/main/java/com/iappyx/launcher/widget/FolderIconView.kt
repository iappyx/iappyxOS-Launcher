/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The icon shown for an item inside an opened [FolderOverlay]. Mirrors the
 * notification-badge rendering of [com.iappyx.launcher.cells.IconCell] (red
 * bubble in the top-right, "99+" pill for high counts) but stays passive —
 * not clickable, not focusable, no click-listener — so the FolderOverlay's
 * own touch handler can keep driving launch + drag-out without the icon
 * intercepting events.
 *
 * Subscribes to [com.iappyx.launcher.notify.BadgeStore] when attached so
 * counts update live: receive a new push notification while the folder is
 * open, and the badge appears without re-opening.
 */
class FolderIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), com.iappyx.launcher.notify.BadgeStore.Observer {

    private val iconView: ImageView
    private val density = resources.displayMetrics.density
    private var packageName: String? = null
    private var badgeCount: Int = 0

    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFE53935")
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f * density
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        // Stay passive: the FolderOverlay's RecyclerView item-touch handler
        // is what launches apps + starts drag-out. If we became clickable,
        // ACTION_DOWN would be consumed here and the row's tap/long-press
        // logic would never see it.
        isClickable = false
        isFocusable = false
        // Inset the ImageView so the badge has headroom to paint past the
        // icon's top-right corner without being clipped by this view's own
        // bounds. The badge sits at `iconRight + 0.15·badgeSide` and
        // `iconTop − 0.15·badgeSide`; an 8dp inset is comfortably more than
        // half the badge size at any sensible icon size.
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // FrameLayout respects padding for MATCH_PARENT children, so the
            // ImageView ends up at (pad, pad)–(width-pad, height-pad).
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER,
            )
        }
        addView(iconView)
        // Custom-draw badge after children render.
        setWillNotDraw(false)
    }

    /** Bind the icon bitmap + the package this cell represents. The package
     *  is what we look up in [BadgeStore] for counts; the bitmap is whatever
     *  the caller already rendered (typically through `IconMask`). */
    fun bind(packageName: String, iconBitmap: Bitmap) {
        this.packageName = packageName
        iconView.setImageBitmap(iconBitmap)
        refreshBadgeCount()
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
        val pkg = packageName ?: return
        val newCount = com.iappyx.launcher.notify.BadgeStore.get(pkg)
        if (newCount != badgeCount) {
            badgeCount = newCount
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (badgeCount <= 0) return
        // Geometry mirrors IconCell.dispatchDraw — same look so a folder
        // and the home-grid render the same notification UI.
        val iconTop = iconView.top.toFloat()
        val iconRight = iconView.right.toFloat()
        val iconSide = iconView.width.toFloat().coerceAtLeast(1f)
        val badgeSide = (iconSide * 0.28f).coerceAtLeast(14f * density)
        val cx = iconRight - badgeSide * 0.35f
        val cy = iconTop + badgeSide * 0.35f
        val text = if (badgeCount > 99) "99+" else badgeCount.toString()
        if (text.length <= 2) {
            canvas.drawCircle(cx, cy, badgeSide / 2f, badgeFillPaint)
            canvas.drawCircle(cx, cy, badgeSide / 2f, badgeStrokePaint)
        } else {
            val pillW = badgeSide * 1.45f
            val rect = RectF(
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
