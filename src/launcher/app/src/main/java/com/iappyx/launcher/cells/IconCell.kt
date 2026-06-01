/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import android.content.Intent
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * A home-screen / dock app icon. Its icon sizes dynamically to the cell — the
 * icon takes ~82% of min(width, height), centered. Labels use a soft layered
 * drop shadow for legibility over any wallpaper (no pill background).
 *
 * Icons are rendered through [IconMask] for a unified launcher look: adaptive
 * icons get our rounded-square mask applied, legacy icons sit on a dark
 * hashed-color backplate with a subtle gloss.
 */
class IconCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), com.iappyx.launcher.notify.BadgeStore.Observer {

    private val iconView: ImageView
    private val labelView: TextView
    private var packageName: String? = null
    private val density = resources.displayMetrics.density

    private var badgeCount: Int = 0
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFE53935") // red
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

    /** If false, tap does NOT launch — used by dock when activity handles clicks. */
    var launchOnClick: Boolean = true

    var showLabel: Boolean = true
        set(value) {
            field = value
            labelView.visibility = if (value) View.VISIBLE else View.GONE
        }

    private val padPx = (6 * density).toInt()

    init {
        setPadding(padPx, padPx, padPx, padPx)

        iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        labelView = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            // Follow the theme font (covers recycled drawer/home labels created
            // after the app-wide tree walk has run). Null = system default.
            typeface = android.graphics.Typeface.create(
                com.iappyx.launcher.widget.Palette.themeTypeface(context) ?: android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.NORMAL,
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            // Two-layer shadow: a wider soft bloom + a tight crisp drop.
            // Plus paint-level shadow for the heavy part.
            setShadowLayer(4f, 0f, 1f, Color.argb(0xCC, 0, 0, 0))
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM,
            )
        }
        addView(iconView)
        addView(labelView)

        // Micro-motion on press: scale down to 0.92 on DOWN, spring back on UP.
        isClickable = true
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
            false // never consume — click / long-click listeners still fire
        }

        setOnClickListener {
            if (!launchOnClick) return@setOnClickListener
            val pkg = packageName ?: return@setOnClickListener
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return@setOnClickListener
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            com.iappyx.launcher.LauncherPrefs(context).recordAppLaunch(pkg)
            // APPLOCK: route through the lock manager so locked packages
            // get a biometric / device-credential prompt before launch.
            // Activity context is required for BiometricPrompt — IconCell
            // is always inflated into an Activity, so the cast is safe.
            val act = context as? android.app.Activity
            if (act != null) {
                com.iappyx.launcher.applock.AppLockManager.launchApp(act, pkg, launchIntent)
            } else {
                context.startActivity(launchIntent)
            }
        }
    }

    fun bind(packageName: String, gridPos: GridPos? = null) {
        this.packageName = packageName
        // Re-apply the theme font on every (re)bind so recycled rows / offscreen
        // home pages pick up a font change without needing the activity to be
        // re-walked. Cheap — themeTypeface is cached per theme-generation.
        labelView.typeface = android.graphics.Typeface.create(
            com.iappyx.launcher.widget.Palette.themeTypeface(context) ?: android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL,
        )
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            val raw = pm.getApplicationIcon(info)
            // We don't yet know the final on-screen icon size — render a
            // reasonably large mask-bitmap (128dp) and let ImageView scale.
            val targetPx = (128 * density).toInt()
            val spec = IconFilterRegistry.resolve(
                context, com.iappyx.launcher.LauncherPrefs(context).iconFilter,
            )
            val masked = IconMask.render(packageName, raw, targetPx, spec)
            iconView.setImageBitmap(masked)
            labelView.text = com.iappyx.launcher.LauncherPrefs(context)
                .appLabel(packageName, pm.getApplicationLabel(info))
            applyIconTint(spec, gridPos)
        } catch (e: Exception) {
            iconView.setImageDrawable(null)
            labelView.text = com.iappyx.launcher.LauncherPrefs(context).appLabel(packageName, packageName)
            visibility = View.INVISIBLE
        }
        refreshBadgeCount()
    }

    /** Apply draw-time effects on top of the (already-rendered) icon bitmap.
     *  Delegates to [IconFilterRunner.tintFor] so position-aware filters
     *  (rainbow, wallpaper-themed) and uniform ones (mono-accent) all flow
     *  through the same spec-driven path. */
    private fun applyIconTint(spec: IconFilterSpec, gridPos: GridPos?) {
        val tint = IconFilterRunner.tintFor(context, spec, gridPos)
        if (tint != null) {
            iconView.setColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY)
        } else {
            iconView.clearColorFilter()
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
        // Position the badge overlapping the iconView's top-right corner.
        // ~28% of the icon side; minimum 14dp so it stays readable on tiny
        // dock cells and pinch-overview thumbnails.
        val iconTop = iconView.top.toFloat()
        val iconRight = iconView.right.toFloat()
        val iconSide = iconView.width.toFloat().coerceAtLeast(1f)
        val badgeSide = (iconSide * 0.28f).coerceAtLeast(14f * density)
        val cx = iconRight - badgeSide * 0.35f
        val cy = iconTop + badgeSide * 0.35f
        val text = if (badgeCount > 99) "99+" else badgeCount.toString()
        // Pill shape when text is "99+", circle for 1-2 digit counts.
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
        // Text size scales with badge size; nudged for vertical centering
        // (font ascent/descent put the baseline below the geometric centre).
        badgeTextPaint.textSize = badgeSide * 0.62f
        val baselineY = cy - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
        canvas.drawText(text, cx, baselineY, badgeTextPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth - paddingLeft - paddingRight
        val h = measuredHeight - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        val labelH = (16 * density).toInt()
        val labelTopPad = (2 * density).toInt()
        val minIconForLabel = (32 * density).toInt()
        val effShowLabel = showLabel && h - labelH - labelTopPad >= minIconForLabel
        labelView.visibility = if (effShowLabel) View.VISIBLE else View.GONE

        val availableForIcon = if (effShowLabel) h - labelH - labelTopPad else h
        val target = (kotlin.math.min(w, availableForIcon) * 0.82f).toInt()
        val iconSize = target.coerceIn((28 * density).toInt(), (72 * density).toInt())
        val iconSpec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY)
        iconView.measure(iconSpec, iconSpec)

        if (effShowLabel) {
            labelView.measure(
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
        val showingLabel = labelView.visibility == View.VISIBLE

        // Icon + label form a tight tile (label directly below the icon, small
        // gap). The tile is vertically centered in the cell. Icon and folder
        // cells use the same max content size so tile heights match and labels
        // align across mixed rows.
        val iconSize = iconView.measuredWidth
        val lh = if (showingLabel) labelView.measuredHeight else 0
        val stackH = iconSize + (if (showingLabel) labelTopPad + lh else 0)
        val topOffset = paddingTop + ((availH - stackH) / 2).coerceAtLeast(0)
        val iconLeft = paddingLeft + (availW - iconSize) / 2
        iconView.layout(iconLeft, topOffset, iconLeft + iconSize, topOffset + iconSize)

        if (showingLabel) {
            val lw = labelView.measuredWidth.coerceAtMost(availW)
            val lleft = paddingLeft + (availW - lw) / 2
            val ltop = topOffset + iconSize + labelTopPad
            labelView.layout(lleft, ltop, lleft + lw, ltop + lh)
        }
    }
}
