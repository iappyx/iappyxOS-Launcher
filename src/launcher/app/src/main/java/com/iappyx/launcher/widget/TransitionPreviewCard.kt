/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.transitions.TransitionSpec
import kotlin.math.sin

/**
 * Carousel page for the transitions manage tab. Shows a fake mini-grid of
 * dot-icons running the spec on a continuous RAF loop (`p = sin(time)`),
 * so the user sees the transition animate live without having to leave
 * the manage tab.
 *
 * Architecturally similar to [LivePreviewCard] but lighter — no WebView,
 * no bridges, no sandbox. Just a layout + a Choreographer-driven loop
 * applying [TransitionSpec.applyPreview] every frame.
 */
class TransitionPreviewCard(
    private val activity: LauncherActivity,
) : LinearLayout(activity) {

    /** Per-card action chip. Mirrors [LivePreviewCard.Action]: when
     *  [iconRes] is non-zero, renders as icon-stacked-above-label; when 0,
     *  falls back to a text pill. [destructive] flips the tint to soft red
     *  for the Delete action. */
    data class Action(
        val label: String,
        val enabled: Boolean = true,
        @androidx.annotation.DrawableRes val iconRes: Int = 0,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    )
    data class Tag(val label: String, val accent: String = "#FF8FE3A0")

    private val dp = resources.displayMetrics.density
    private val titleLabel: TextView
    private val subtitleLabel: TextView
    private val tagRow: LinearLayout
    val previewSurface: FrameLayout
    private val fakePage: FrameLayout
    private val fakeCells: List<TransitionSpec.PreviewCell>
    private val cols = 4
    private val rows = 6
    private val actionRow: LinearLayout

    private var spec: TransitionSpec? = null
    private var running = false
    private var startNanos: Long = 0L
    // Initialized at the end of init {} once fakePage / fakeCells exist.
    // Kotlin's property-order flow analysis can't prove the field captures
    // resolve later, so a lateinit var sidesteps the check entirely.
    private lateinit var frameCallback: Choreographer.FrameCallback

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(
            (16 * dp).toInt(), (12 * dp).toInt(),
            (16 * dp).toInt(), (12 * dp).toInt(),
        )

        titleLabel = TextView(activity).apply {
            setTextColor(Color.WHITE); textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(titleLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        subtitleLabel = TextView(activity).apply {
            setTextColor(Color.parseColor("#A0A0B8")); textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(subtitleLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        tagRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        addView(tagRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        previewSurface = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.parseColor("#0F0F1F"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            clipToOutline = true
        }
        addView(previewSurface, LayoutParams(0, 0).apply {
            topMargin = (12 * dp).toInt()
        })

        fakePage = FrameLayout(activity)
        previewSurface.addView(
            fakePage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Build a 4×6 grid of dot icons inside fakePage. Positions are set
        // post-layout via post {} since fakePage's measured size isn't known
        // until first layout pass.
        val cellsList = mutableListOf<TransitionSpec.PreviewCell>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val dot = View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dotColorFor(r, c))
                    }
                }
                fakePage.addView(dot, FrameLayout.LayoutParams(0, 0))
                cellsList.add(TransitionSpec.PreviewCell(dot, c, r))
            }
        }
        fakeCells = cellsList
        fakePage.post { layoutFakeCells() }
        fakePage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFakeCells() }

        actionRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actionScroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.widget.HorizontalScrollView.OVER_SCROLL_NEVER
            setPadding(0, (12 * dp).toInt(), 0, 0)
            addView(actionRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        addView(actionScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val s = spec ?: return
                if (startNanos == 0L) startNanos = frameTimeNanos
                val tSec = (frameTimeNanos - startNanos) / 1_000_000_000.0
                // Slow oscillation between -0.85 and +0.85 — full transition
                // both directions, with a brief pause at p≈0 so the user sees
                // the resting state too.
                val p = (sin(tSec * 1.4) * 0.85).toFloat()
                try {
                    s.applyPreview(fakePage, p, fakeCells, cols, rows)
                } catch (_: Throwable) { /* ignore single-frame eval errors */ }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun bind(
        title: String,
        subtitle: String,
        spec: TransitionSpec?,
        tags: List<Tag>,
        actions: List<Action>,
    ) {
        unbind()
        titleLabel.text = title
        subtitleLabel.text = subtitle
        tagRow.removeAllViews()
        for (t in tags) tagRow.addView(makeTagChip(t))
        actionRow.removeAllViews()
        for (a in actions) actionRow.addView(makeActionButton(a))
        previewSurface.post { resizePreviewSurface() }
        this.spec = spec
        // Don't auto-start — onPageVisible/Hidden controls the loop.
    }

    fun unbind() {
        running = false
        // Reset to neutral so the next bind starts from clean state.
        spec?.applyPreview(fakePage, 0f, fakeCells, cols, rows)
        spec = null
        startNanos = 0L
    }

    fun onPageVisible() {
        if (running) return
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun onPageHidden() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Belt-and-braces leak guard. The pager's onPageHidden() is the
     *  intended teardown path, but if the host RecyclerView discards the
     *  view (recycle, scroll past, activity finish) without first calling
     *  onPageHidden, the frame callback keeps re-posting forever and pins
     *  this card alive through the Choreographer's reference. Tear down on
     *  detach so the callback chain terminates regardless. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun resizePreviewSurface() {
        val parentWidth = (parent as? ViewGroup)?.width ?: width
        if (parentWidth <= 0) return
        val chevronReserve = (52 * dp).toInt()
        val availW = parentWidth - paddingLeft - paddingRight - 2 * chevronReserve
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        // Match the device's current screen orientation so the fake home
        // page in the preview reads as a true phone/tablet shape, not
        // always-portrait. Was hardcoded 9:16 portrait, which made the
        // transition's motion preview look stretched on landscape tablets.
        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val (w, h) = if (isLandscape) {
            val byHeight = (maxH * 16f / 9f).toInt()
            if (byHeight <= availW) byHeight to maxH
            else availW to (availW * 9f / 16f).toInt()
        } else {
            val byHeight = (maxH * 9f / 16f).toInt()
            if (byHeight <= availW) byHeight to maxH
            else availW to (availW * 16f / 9f).toInt()
        }
        val lp = previewSurface.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            lp.gravity = Gravity.CENTER_HORIZONTAL
            previewSurface.layoutParams = lp
        }
    }

    private fun layoutFakeCells() {
        val pw = fakePage.width
        val ph = fakePage.height
        if (pw <= 0 || ph <= 0) return
        val pad = (8 * dp).toInt()
        val gridW = pw - 2 * pad
        val gridH = ph - 2 * pad
        val cellW = gridW / cols
        val cellH = gridH / rows
        val dotSize = (kotlin.math.min(cellW, cellH) * 0.55f).toInt().coerceAtLeast((6 * dp).toInt())
        for (cell in fakeCells) {
            val v = cell.view
            val left = pad + cell.col * cellW + (cellW - dotSize) / 2
            val top = pad + cell.row * cellH + (cellH - dotSize) / 2
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = dotSize; lp.height = dotSize
            lp.leftMargin = left; lp.topMargin = top
            v.layoutParams = lp
        }
    }

    /** Soft tonal palette for the fake icons so the grid reads as a typical
     *  home page (not a uniform field of identical dots). Hash by (r, c)
     *  for stable colour per cell. */
    private fun dotColorFor(row: Int, col: Int): Int {
        val palette = listOf(
            Color.parseColor("#FF4FC3F7"), Color.parseColor("#FFFFB74D"),
            Color.parseColor("#FFAED581"), Color.parseColor("#FFE57373"),
            Color.parseColor("#FFBA68C8"), Color.parseColor("#FF4DD0E1"),
            Color.parseColor("#FFFF8A65"), Color.parseColor("#FF9575CD"),
        )
        return palette[(row * 7 + col * 3) % palette.size]
    }

    private fun makeTagChip(tag: Tag): TextView = TextView(activity).apply {
        text = tag.label
        setTextColor(Color.parseColor(tag.accent))
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor("#22FFFFFF"))
        }
        setPadding(
            (8 * dp).toInt(), (2 * dp).toInt(),
            (8 * dp).toInt(), (2 * dp).toInt(),
        )
        val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.rightMargin = (6 * dp).toInt()
        layoutParams = lp
    }

    private fun makeActionButton(action: Action): android.view.View =
        if (action.iconRes != 0) makeIconAction(action) else makeTextAction(action)

    /** Icon + label vertical stack — same recipe as LivePreviewCard's
     *  icon-action form so chips look identical across all manage tabs. */
    private fun makeIconAction(action: Action): android.view.View {
        val activeTint = if (action.destructive) Color.parseColor("#FF6B6B") else Color.WHITE
        val tint = if (action.enabled) activeTint else Color.parseColor("#66FFFFFF")
        val image = android.widget.ImageView(activity).apply {
            setImageResource(action.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        val label = TextView(activity).apply {
            text = action.label
            textSize = 10f
            setTextColor(tint)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            lp.topMargin = (1 * dp).toInt()
            layoutParams = lp
        }
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            gravity = android.view.Gravity.CENTER
            background = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#22FFFFFF"),
            ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
            val padH = (8 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = action.enabled
            isFocusable = action.enabled
            alpha = if (action.enabled) 1f else 0.4f
            if (action.enabled) setOnClickListener { action.onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (4 * dp).toInt(); lp.rightMargin = (4 * dp).toInt()
            layoutParams = lp
            addView(image); addView(label)
        }
    }

    /** Legacy text pill — kept so callers that haven't migrated to the
     *  icon form still work. */
    private fun makeTextAction(action: Action): TextView = TextView(activity).apply {
        text = action.label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (action.enabled) Color.WHITE else Color.parseColor("#66FFFFFF"))
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor(if (action.enabled) "#22FFFFFF" else "#11FFFFFF"))
            setStroke((1 * dp).toInt(),
                Color.parseColor(if (action.enabled) "#33FFFFFF" else "#22FFFFFF"))
        }
        setPadding(
            (12 * dp).toInt(), (8 * dp).toInt(),
            (12 * dp).toInt(), (8 * dp).toInt(),
        )
        isClickable = action.enabled
        isFocusable = action.enabled
        if (action.enabled) setOnClickListener { action.onClick() }
        val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (4 * dp).toInt(); lp.rightMargin = (4 * dp).toInt()
        layoutParams = lp
    }
}

class TransitionPreviewViewHolder(
    val card: TransitionPreviewCard,
) : androidx.recyclerview.widget.RecyclerView.ViewHolder(card)
