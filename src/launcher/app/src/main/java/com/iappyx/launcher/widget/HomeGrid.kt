/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.Placement

/**
 * Home-screen grid with a Microsoft-Launcher-style edit mode:
 *  - Outlined rectangle around every widget in edit mode
 *  - Selected widget shows 8 handles (4 edge midpoints + 4 corners)
 *  - Drag edge handle → resize in that direction (top/left adjust row/col)
 *  - Drag body → move
 *  - Ghost preview rectangle at snap target (green) or collision (red)
 *  - Faint dotted grid overlay during interaction
 *  - Activity handles reflow, remove-zone, top-bar — this view emits callbacks
 */
class HomeGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var cols: Int = 4
    var rows: Int = 5

    // ── Callbacks (wired by LauncherActivity) ─────────────────
    var onEmptyLongPress: ((row: Int, col: Int) -> Unit)? = null
    var onEmptyTap: ((row: Int, col: Int) -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onEnterEditMode: (() -> Unit)? = null
    /** Observer for every DragEvent that flows through this grid — fires
     *  from dispatchDragEvent so it sees events even when a child cell
     *  (WebView in a generated widget, AppWidgetHostView in a stock
     *  widget) consumes them and blocks the grid's setOnDragListener
     *  from firing. The activity uses this for edge-swipe so a long-
     *  press → drag over a widget at the page edge can still auto-flip
     *  to the next page. */
    var onAnyDragEvent: ((android.view.DragEvent) -> Unit)? = null

    /** Long-press on a filled cell (icon, folder, widget) outside edit mode.
     *  When set, invoking this callback REPLACES the default "enter edit mode
     *  + select the cell" behaviour — the activity is expected to show its
     *  own context popup anchored at [view]. Empty-cell long-press still
     *  routes to [onEnterEditMode] regardless, so the user always has a way
     *  to start editing the grid.
     *
     *  Returning a [PopupWindow] arms the iOS-style "keep holding to drag"
     *  flow — if the user moves their finger past tap-slop while still held,
     *  HomeGrid dismisses the popup and starts a system DnD via
     *  [onStartSystemMoveDrag]. Returning null disables that flow. */
    var onCellLongPress: ((Placement, view: View) -> android.widget.PopupWindow?)? = null
    /** Selection changed — activity updates the top bar. */
    var onSelectionChanged: ((Placement?) -> Unit)? = null
    /** Request to move a widget to a new position. Activity runs reflow + validates. */
    var onCellMoveRequest: ((Placement, newRow: Int, newCol: Int) -> Boolean)? = null
    /** Request to resize (can also change row/col for top/left handles). */
    var onCellResizeRequest: ((Placement, newRow: Int, newCol: Int, newW: Int, newH: Int) -> Boolean)? = null
    /** Request to merge [src] into [dst] — fires when a 1×1 icon/folder is dropped
     *  on top of another 1×1 icon/folder. Activity creates or updates a folder. */
    var onCellMergeRequest: ((src: Placement, dst: Placement) -> Boolean)? = null
    /** Tap on the floating "Edit" badge over the selected generated widget.
     *  Only fired for cells whose placement type is [CellType.GENERATED_WIDGET]. */
    var onEditBadgeTap: ((Placement) -> Unit)? = null
    /** Hand-off to the activity: the user has dragged the selected widget
     *  past tap-slop in edit mode, so we want a system drag-and-drop to
     *  start (so the gesture can cross page boundaries). The activity's
     *  implementation calls [View.startDragAndDrop] on the source view with
     *  the appropriate ClipData / shadow / flags. */
    var onStartSystemMoveDrag: ((Placement, View) -> Unit)? = null
    /** True while a system DnD originating from this grid is in flight — the
     *  source cell is rendered transparent and we don't keep tracking
     *  MotionEvent. The activity flips this to false on DRAG_ENDED. */
    var systemMoveInFlight: Boolean = false
        set(value) { field = value; invalidate() }

    /** True while ANY page-widget drag is in flight, sourced from this grid
     *  or another. We use this to suppress "+" chips on empty cells during
     *  the drag — the ghost preview shows the actual valid landing area for
     *  the source's full footprint, so the per-cell "+" hints are misleading
     *  (a 1×1 empty cell doesn't necessarily fit a 4×2 widget). */
    var pageDragInFlight: Boolean = false
        set(value) { field = value; invalidate() }

    var editMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    selectPlacement(null)
                    breatheAnimator.cancel()
                    stopWiggle()
                    restoreCellAlphas()
                } else {
                    if (!breatheAnimator.isStarted) breatheAnimator.start()
                    startWiggle()
                }
                invalidate()
            }
        }

    private var selected: Placement? = null
    private var selectedView: View? = null

    fun selectPlacement(p: Placement?) {
        if (selected?.id == p?.id) return
        selected = p
        selectedView = if (p != null) findViewByPlacementId(p.id) else null
        onSelectionChanged?.invoke(p)
        applyDimForSelection()
        invalidate()
    }

    /** Walk every child and animate alpha — selected → 1.0, others → 0.55,
     *  spotlighting the focus cell. When nothing is selected, restore all to 1.0. */
    private fun applyDimForSelection() {
        if (!editMode) { restoreCellAlphas(); return }
        val selId = selected?.id
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val target = if (selId == null || (c.tag as? Placement)?.id == selId) 1f else 0.55f
            if (kotlin.math.abs(c.alpha - target) < 0.01f) continue
            c.animate().alpha(target).setDuration(140L).start()
        }
    }

    private fun restoreCellAlphas() {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.alpha < 0.999f) c.animate().alpha(1f).setDuration(140L).start()
        }
    }

    /** After the activity re-renders this grid (e.g. after a resize/move/remove
     *  commit replaces the children), [selectedView] points at a detached View
     *  that still paints the blue outline at stale coordinates. This call either
     *  rebinds the selection to the new View with the same placement id, or
     *  clears the selection entirely if the placement is gone. */
    fun rebindSelection() {
        val sel = selected ?: return
        val fresh = findViewByPlacementId(sel.id)
        if (fresh == null) {
            // Placement was removed — clear selection so the outline stops drawing.
            selected = null
            selectedView = null
            onSelectionChanged?.invoke(null)
            invalidate()
        } else {
            // Same placement, new View instance — swap in and refresh. Keep the
            // cached placement up to date too (size may have changed on resize).
            val updated = fresh.tag as? Placement
            if (updated != null) selected = updated
            selectedView = fresh
            // Post the invalidate so it runs after the newly-added children
            // finish their first measure/layout pass — otherwise left/top/right
            // are still 0 and the 8 handles would draw on top of each other at
            // the origin (appearing as if they vanished).
            post { invalidate() }
        }
    }

    private fun findViewByPlacementId(id: String): View? {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if ((v.tag as? Placement)?.id == id) return v
        }
        return null
    }

    // ── Geometry ──────────────────────────────────────────────
    /** Inter-cell spacing in pixels. Public so [LayoutSerializer] can match
     *  per-cell pixel positions when broadcasting the layout to the wallpaper
     *  process. Same constant the layout pass uses. */
    val spacingPx: Int by lazy { (4 * resources.displayMetrics.density).toInt() }

    /** Live cell width in pixels (excluding inter-cell spacing). 0 until the
     *  first measure pass — callers must check `measuredWidth > 0` first. */
    fun cellWidthPx(): Float = cellWidth()
    /** Live cell height in pixels (excluding inter-cell spacing). */
    fun cellHeightPx(): Float = cellHeight()
    private val handleRadiusPx: Int by lazy { (7 * resources.displayMetrics.density).toInt() }
    private val handleInsetPx: Int by lazy { (8 * resources.displayMetrics.density).toInt() }
    private val handleHitPx: Int by lazy { (28 * resources.displayMetrics.density).toInt() }
    private val cornerHitPx: Int by lazy { (36 * resources.displayMetrics.density).toInt() }
    private val tapSlopPx: Int by lazy { (8 * resources.displayMetrics.density).toInt() }

    // ── iOS-style "long-press → popup → keep holding to drag" state ─────
    // Captured on ACTION_DOWN so we can measure post-long-press finger
    // movement. Re-armed every gesture; cleared on UP/CANCEL/drag-start.
    private var longPressDownX: Float = 0f
    private var longPressDownY: Float = 0f
    private var longPressDragPlacement: Placement? = null
    private var longPressDragView: View? = null
    private var longPressDragPopup: android.widget.PopupWindow? = null

    private fun clearLongPressDragArm() {
        longPressDragPlacement = null
        longPressDragView = null
        longPressDragPopup = null
    }

    // ── Paints ────────────────────────────────────────────────
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(180, 160, 160, 200)
    }
    private val selectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        color = Palette.accent(context)
        strokeJoin = Paint.Join.ROUND
    }
    /** Wider, low-alpha stroke drawn UNDER the selection outline for a Material
     *  glow / depth effect. Reads as "this widget is lifted off the page". */
    private val selectedGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * resources.displayMetrics.density
        color = Color.argb(70, 79, 195, 247)
        strokeJoin = Paint.Join.ROUND
    }
    /** Floating "Edit" badge — only on selected GENERATED_WIDGET. */
    private val editBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.accent(context)
        setShadowLayer(6f * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, Color.argb(120, 0, 0, 0))
    }
    private val editBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.argb(160, 255, 255, 255)
    }
    private val editBadgeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0D0D1A")
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** Slow pulse driving selected outline alpha while editMode is on. */
    private val breatheAnimator = android.animation.ValueAnimator.ofFloat(180f, 255f).apply {
        duration = 1400L
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { invalidate() }
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Palette.accent(context)
    }
    private val ghostValidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 79, 195, 247) // translucent blue
    }
    private val ghostInvalidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 255, 107, 107) // translucent red
    }
    private val gridOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.argb(40, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val emptyCellCircleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 255, 255, 255)
    }
    private val emptyCellCircleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * resources.displayMetrics.density
        color = Color.argb(90, 255, 255, 255)
    }
    private val emptyCellPlusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * resources.displayMetrics.density
        color = Color.argb(200, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val emptyPagePulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.argb(90, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }
    private val emptyPagePulseAnim = android.animation.ValueAnimator.ofFloat(0.6f, 1.0f).apply {
        duration = 1600L
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { invalidate() }
    }

    // ── Drag state ────────────────────────────────────────────
    private enum class DragMode { NONE, MOVE, RESIZE_T, RESIZE_B, RESIZE_L, RESIZE_R, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var dragMode = DragMode.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var ghostRow = 0
    private var ghostCol = 0
    private var ghostW = 1
    private var ghostH = 1
    private var ghostValid = true
    private var showGhost = false

    init {
        clipChildren = false
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The grid may be re-attached after a transient pager detach (off-
        // screen recycler holder). If editMode is still true, the editMode
        // setter won't re-fire (no value change), so manually restart the
        // animations the setter would have started.
        if (editMode) {
            if (!breatheAnimator.isStarted) breatheAnimator.start()
            startWiggle()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Cancel all per-frame work tied to this attached state. The wiggle
        // animators in particular keep references to children via their
        // update-listener lambdas — a WeakHashMap key alone can't evict them.
        // [onAttachedToWindow] restarts them if we re-attach.
        breatheAnimator.cancel()
        emptyPagePulseAnim.cancel()
        stopWiggle()
    }

    // ── Wiggle (iOS-style) ─────────────────────────────────────
    /** Per-child wiggle animator. Restarted whenever new children appear (e.g.
     *  after a layout commit) while editMode is on. Cleared on exit. */
    private val wiggleAnimators = java.util.WeakHashMap<View, android.animation.ValueAnimator>()

    private fun startWiggle() {
        // Kill any animators left over from a previous batch of children — when
        // renderPage rebuilds the grid, the old map references detached views
        // via lambda capture and would otherwise leak forever.
        stopWiggle()
        for (i in 0 until childCount) ensureWiggle(getChildAt(i))
    }

    private fun ensureWiggle(child: View) {
        if (wiggleAnimators[child] != null) return
        val phase = (Math.random() * 220).toLong()
        val anim = android.animation.ValueAnimator.ofFloat(-1.4f, 1.4f).apply {
            duration = 220L
            startDelay = phase
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { v -> child.rotation = v.animatedValue as Float }
        }
        wiggleAnimators[child] = anim
        anim.start()
    }

    private fun stopWiggle() {
        for ((view, anim) in wiggleAnimators.entries) {
            anim.cancel()
            view.animate().rotation(0f).setDuration(180L).start()
        }
        wiggleAnimators.clear()
    }

    // ── Gesture detector (long-press, swipe-up, tap-to-select) ─
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (editMode) return
            val child = findChildAtPoint(e.x, e.y)
            val placement = child?.tag as? Placement
            // Filled cell + a context-menu handler is wired → defer to the
            // activity, do NOT enter edit mode. Activity's popup includes a
            // "Customize home" entry that re-routes to onEnterEditMode for
            // users who want the legacy behaviour on a per-press basis.
            val cellHandler = onCellLongPress
            if (placement != null && child != null && cellHandler != null) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val popup = cellHandler.invoke(placement, child)
                // Arm the held-then-drag transition. If the activity returned
                // a popup, we'll dismiss it on the first move beyond slop and
                // start a system DnD so the user can carry the icon to a new
                // page in one fluid gesture (matches iOS Springboard / the
                // app drawer's behaviour).
                if (popup != null) {
                    longPressDragPlacement = placement
                    longPressDragView = child
                    longPressDragPopup = popup
                }
                return
            }
            onEnterEditMode?.invoke()
            // setEditMode triggers a RecyclerView rebind that detaches+re-adds
            // every cell on this page. Running selectPlacement immediately would
            // operate on the stale children (and stale alphas). Defer one frame
            // so the rebind + rebindSelection settle first; then dim correctly.
            if (placement != null) post { selectPlacement(placement) }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (editMode) {
                val target = findChildAtPoint(e.x, e.y)
                if (target != null) {
                    selectPlacement(target.tag as? Placement)
                } else {
                    if (selected != null) {
                        selectPlacement(null)
                    } else {
                        // Tap empty → add
                        val col = ((e.x - spacingPx) / (cellWidth() + spacingPx)).toInt().coerceIn(0, cols - 1)
                        val row = ((e.y - spacingPx) / (cellHeight() + spacingPx)).toInt().coerceIn(0, rows - 1)
                        if (isCellEmpty(row, col)) onEmptyTap?.invoke(row, col)
                    }
                }
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velX: Float, velY: Float): Boolean {
            if (editMode || e1 == null) return false
            // If a scrollable widget cell claimed this gesture, its UP event
            // can have substantial velY (the user just flicked the WebView's
            // scroll). Don't translate that into a swipe-up-to-drawer.
            val activity = context as? com.iappyx.launcher.LauncherActivity
            if (activity?.gestureClaimedByWidget == true) return false
            val dy = e2.y - e1.y
            if (dy < -150 && velY < -500 && kotlin.math.abs(dy) > kotlin.math.abs(e2.x - e1.x) * 2) {
                onSwipeUp?.invoke()
                return true
            }
            return false
        }
    })

    // ── Touch routing ─────────────────────────────────────────
    override fun dispatchDragEvent(event: android.view.DragEvent): Boolean {
        // Observe every drag event before the deepest-listener routing
        // hands it to a child. Lets the activity wire edge-swipe / drop
        // preview logic that needs to see events even when a widget cell
        // (WebView, AppWidgetHostView) consumes the event itself.
        onAnyDragEvent?.invoke(event)
        return super.dispatchDragEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Feed the gestureDetector at DISPATCH level (always called) instead
        // of from onIntercept/onTouchEvent (suppressed when descendants
        // disallow). This keeps long-press detection alive when a scrollable
        // widget cell takes ownership of the gesture: the detector sees DOWN
        // and starts the timer, MOVEs cancel it naturally if motion exceeds
        // its TouchSlop (so a scroll doesn't pop a context menu), and a true
        // stationary hold still fires onLongPress at 500ms. onFling checks
        // gestureClaimedByWidget so a scroll-fling inside the widget doesn't
        // get translated into a swipe-up-to-drawer.
        gestureDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressDownX = ev.x
                longPressDownY = ev.y
                clearLongPressDragArm()
            }
            MotionEvent.ACTION_MOVE -> {
                val placement = longPressDragPlacement
                val view = longPressDragView
                if (placement != null && view != null) {
                    val dx = ev.x - longPressDownX
                    val dy = ev.y - longPressDownY
                    if (kotlin.math.sqrt(dx * dx + dy * dy) > tapSlopPx) {
                        longPressDragPopup?.dismiss()
                        clearLongPressDragArm()
                        // System DnD will fire ACTION_CANCEL at any child
                        // currently tracking the gesture, so we don't need
                        // to consume the event ourselves.
                        onStartSystemMoveDrag?.invoke(placement, view)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Finger released without dragging — keep popup up so the
                // user can tap menu items; just clear our arm state.
                clearLongPressDragArm()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // No requestDisallowInterceptTouchEvent override needed: the dispatch-
    // level feed of gestureDetector cancels its long-press timer naturally
    // on MOVE > TouchSlop. Clearing the longPressDragArm here would kill
    // drag-to-move — after onLongPress fires and arms the placement, the
    // very next claim by a cell's NestedScrollableHost dispatch would
    // unset it before the dispatchTouchEvent.MOVE block could check it.

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return false
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val sel = selected
            val selView = selectedView
            // 0) Edit-badge tap on selected GENERATED_WIDGET — fire callback,
            //    consume the event so it doesn't fall through to a move drag.
            if (sel != null && selView != null && isInsideEditBadge(selView, ev.x, ev.y)) {
                onEditBadgeTap?.invoke(sel)
                return true
            }
            // 1) Handle hit on selected widget
            if (sel != null && selView != null) {
                val handle = hitHandle(selView, ev.x, ev.y)
                if (handle != DragMode.NONE) {
                    dragMode = handle
                    dragStartX = ev.x
                    dragStartY = ev.y
                    showGhost = true
                    updateGhostForResize(sel, selView, ev.x, ev.y)
                    return true
                }
                // 2) Body tap on selected → start move
                if (ev.x in selView.left.toFloat()..selView.right.toFloat() &&
                    ev.y in selView.top.toFloat()..selView.bottom.toFloat()) {
                    dragMode = DragMode.MOVE
                    dragStartX = ev.x
                    dragStartY = ev.y
                    showGhost = true
                    ghostRow = sel.row; ghostCol = sel.col
                    ghostW = sel.wSpan; ghostH = sel.hSpan
                    ghostValid = true
                    selView.bringToFront()
                    return true
                }
            }
        }
        // In edit mode, always intercept so children (IconCell.onClick → launchApp,
        // StockWidget inner handlers, WebView) never receive the event. The gesture
        // detector routes taps back to selectPlacement / onEmptyTap.
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // gestureDetector is already fed at dispatch level; don't double-feed.
        if (!editMode || dragMode == DragMode.NONE) return true
        val sel = selected ?: run { dragMode = DragMode.NONE; return true }
        val view = selectedView ?: run { dragMode = DragMode.NONE; return true }
        val dx = ev.x - dragStartX
        val dy = ev.y - dragStartY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.MOVE) {
                    // Escalate to a SYSTEM drag-and-drop the moment movement
                    // crosses tap-slop. The system handles the floating
                    // shadow + edge swipes for cross-page moves; resize keeps
                    // the local MotionEvent flow because the gesture is
                    // "drag this corner", not "carry this view".
                    val moved = kotlin.math.sqrt(dx * dx + dy * dy) > tapSlopPx
                    if (moved) {
                        // Reset any residual translation we might have applied
                        // before this frame so the source view isn't half-shifted
                        // when system drag takes over (it'll be alpha=0 anyway).
                        view.translationX = 0f
                        view.translationY = 0f
                        showGhost = false
                        dragMode = DragMode.NONE
                        onStartSystemMoveDrag?.invoke(sel, view)
                        invalidate()
                        return true
                    }
                    // Below slop — don't move the view yet (avoid jitter).
                } else {
                    updateGhostForResize(sel, view, ev.x, ev.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDrag = kotlin.math.sqrt(dx * dx + dy * dy) > tapSlopPx || dragMode != DragMode.MOVE
                if (wasDrag) {
                    if (dragMode == DragMode.MOVE) {
                        // Merge: source is a 1×1 icon/folder dropped ONTO another 1×1 icon/folder
                        val merged = if (!ghostValid) tryMerge(sel) else false
                        val ok = merged || (ghostValid &&
                            (onCellMoveRequest?.invoke(sel, ghostRow, ghostCol) ?: false))
                        if (ok) {
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            springBack(view)
                        }
                    } else {
                        val ok = if (ghostValid) {
                            onCellResizeRequest?.invoke(sel, ghostRow, ghostCol, ghostW, ghostH) ?: false
                        } else false
                        if (ok) {
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            requestLayout() // revert to current placement dimensions
                        }
                    }
                }
                dragMode = DragMode.NONE
                showGhost = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun springBack(view: View) {
        view.animate().translationX(0f).translationY(0f).setDuration(200L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    // ── Handle hit testing ────────────────────────────────────
    /** Positions of the 8 handles inset inside the widget bounds so the whole
     *  hit area is reachable even for widgets at the grid's edges. */
    private fun handlePositions(v: View): Handles {
        val ins = handleInsetPx
        val l = v.left.toFloat() + ins
        val t = v.top.toFloat() + ins
        val r = v.right.toFloat() - ins
        val b = v.bottom.toFloat() - ins
        val cx = (v.left + v.right) / 2f
        val cy = (v.top + v.bottom) / 2f
        return Handles(l, t, r, b, cx, cy)
    }

    private data class Handles(
        val l: Float, val t: Float, val r: Float, val b: Float,
        val cx: Float, val cy: Float,
    )

    private fun hitHandle(v: View, x: Float, y: Float): DragMode {
        // Folders are not resizable — skip handle hit-test entirely so a tap
        // near the corner doesn't accidentally start a resize drag.
        if (selected?.type == CellType.FOLDER) return DragMode.NONE
        val h = handleHitPx / 2f
        val cornerH = cornerHitPx / 2f
        val H = handlePositions(v)
        fun near(hx: Float, hy: Float, rad: Float) =
            kotlin.math.abs(x - hx) < rad && kotlin.math.abs(y - hy) < rad
        return when {
            near(H.l, H.t, cornerH) -> DragMode.RESIZE_TL
            near(H.r, H.t, cornerH) -> DragMode.RESIZE_TR
            near(H.l, H.b, cornerH) -> DragMode.RESIZE_BL
            near(H.r, H.b, cornerH) -> DragMode.RESIZE_BR
            near(H.cx, H.t, h) -> DragMode.RESIZE_T
            near(H.cx, H.b, h) -> DragMode.RESIZE_B
            near(H.l, H.cy, h) -> DragMode.RESIZE_L
            near(H.r, H.cy, h) -> DragMode.RESIZE_R
            else -> DragMode.NONE
        }
    }

    // ── Edit-badge geometry + hit ─────────────────────────────
    /** Top-right circular "✎" badge over the selected GENERATED_WIDGET only.
     *  Returns null when no badge should be drawn / hit. */
    private fun editBadgeRect(v: View): RectF? {
        val sel = selected ?: return null
        if (sel.type != CellType.GENERATED_WIDGET) return null
        val dp = resources.displayMetrics.density
        val r = 18f * dp                   // visual radius
        val inset = 6f * dp                // distance from cell corner
        val cx = v.right.toFloat() - inset - r
        val cy = v.top.toFloat() + inset + r
        return RectF(cx - r, cy - r, cx + r, cy + r)
    }

    // ── Drop targeting (system DnD from another grid) ─────────
    /** Snap a drop point in this grid's coordinate system to a (row, col) the
     *  current source can occupy. Returns null if no valid landing spot. The
     *  ghost rectangle below is updated as a side-effect so the user sees a
     *  preview during drag. */
    fun computeDropTarget(localX: Float, localY: Float, srcW: Int, srcH: Int, srcId: String?): Pair<Int, Int>? {
        val cw = cellWidth() + spacingPx
        val ch = cellHeight() + spacingPx
        val col = ((localX - spacingPx) / cw).toInt()
            .coerceIn(0, (cols - srcW).coerceAtLeast(0))
        val row = ((localY - spacingPx) / ch).toInt()
            .coerceIn(0, (rows - srcH).coerceAtLeast(0))
        // Show a ghost preview while dragging.
        ghostRow = row; ghostCol = col; ghostW = srcW; ghostH = srcH
        ghostValid = !collidesAt(row, col, srcW, srcH, ignoreId = srcId)
        showGhost = true
        invalidate()
        return if (ghostValid) row to col else null
    }

    fun clearDropPreview() {
        showGhost = false
        invalidate()
    }

    /** Local-coordinate (x,y) → snapped (row, col) on this grid, ignoring any
     *  collision check. Used to detect "drop on top of an existing icon for
     *  merge-into-folder". */
    fun cellAt(localX: Float, localY: Float): Pair<Int, Int> {
        val cw = cellWidth() + spacingPx
        val ch = cellHeight() + spacingPx
        val col = ((localX - spacingPx) / cw).toInt().coerceIn(0, cols - 1)
        val row = ((localY - spacingPx) / ch).toInt().coerceIn(0, rows - 1)
        return row to col
    }

    /** Returns the placement covering (row, col), or null if the cell is
     *  empty. Used by the activity's drop handler to detect merges. */
    fun placementAt(row: Int, col: Int): Placement? {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val p = v.tag as? Placement ?: continue
            if (row in p.row until (p.row + p.hSpan) &&
                col in p.col until (p.col + p.wSpan)) return p
        }
        return null
    }

    private fun collidesAt(row: Int, col: Int, w: Int, h: Int, ignoreId: String?): Boolean {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val p = v.tag as? Placement ?: continue
            if (p.id == ignoreId) continue
            val r1 = p.row; val r2 = p.row + p.hSpan - 1
            val c1 = p.col; val c2 = p.col + p.wSpan - 1
            val sr2 = row + h - 1
            val sc2 = col + w - 1
            if (row <= r2 && sr2 >= r1 && col <= c2 && sc2 >= c1) return true
        }
        return false
    }

    /** Generous hit zone — 40dp around the badge centre. */
    private fun isInsideEditBadge(v: View, x: Float, y: Float): Boolean {
        val rc = editBadgeRect(v) ?: return false
        val cx = (rc.left + rc.right) / 2f
        val cy = (rc.top + rc.bottom) / 2f
        val dp = resources.displayMetrics.density
        val hit = 22f * dp
        return kotlin.math.abs(x - cx) < hit && kotlin.math.abs(y - cy) < hit
    }

    // ── Ghost computation ────────────────────────────────────
    private fun updateGhostForResize(p: Placement, v: View, x: Float, y: Float) {
        val cw = cellWidth() + spacingPx
        val ch = cellHeight() + spacingPx
        var row = p.row; var col = p.col
        var w = p.wSpan; var h = p.hSpan
        when (dragMode) {
            DragMode.RESIZE_R, DragMode.RESIZE_TR, DragMode.RESIZE_BR -> {
                w = ((x - v.left + cw * 0.5f) / cw).toInt().coerceIn(1, cols - col)
            }
            DragMode.RESIZE_L, DragMode.RESIZE_TL, DragMode.RESIZE_BL -> {
                val originalRight = p.col + p.wSpan
                val newCol = ((x - cw * 0.5f) / cw).toInt().coerceIn(0, originalRight - 1)
                w = originalRight - newCol
                col = newCol
            }
            else -> {}
        }
        when (dragMode) {
            DragMode.RESIZE_B, DragMode.RESIZE_BL, DragMode.RESIZE_BR -> {
                h = ((y - v.top + ch * 0.5f) / ch).toInt().coerceIn(1, rows - row)
            }
            DragMode.RESIZE_T, DragMode.RESIZE_TL, DragMode.RESIZE_TR -> {
                val originalBottom = p.row + p.hSpan
                val newRow = ((y - ch * 0.5f) / ch).toInt().coerceIn(0, originalBottom - 1)
                h = originalBottom - newRow
                row = newRow
            }
            else -> {}
        }
        ghostRow = row; ghostCol = col; ghostW = w; ghostH = h
        ghostValid = !collides(p, row, col, w, h)
    }

    /** Attempts a folder merge when [src] was dropped on another 1×1 icon/folder. */
    private fun tryMerge(src: Placement): Boolean {
        if (src.wSpan != 1 || src.hSpan != 1) return false
        if (src.type != com.iappyx.launcher.model.CellType.ICON &&
            src.type != com.iappyx.launcher.model.CellType.FOLDER) return false
        // Find a child at (ghostRow, ghostCol) that is a 1×1 ICON or FOLDER and is not [src].
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as? GridLayoutParams ?: continue
            val other = getChildAt(i).tag as? Placement ?: continue
            if (other.id == src.id) continue
            if (lp.wSpan != 1 || lp.hSpan != 1) continue
            if (lp.row != ghostRow || lp.col != ghostCol) continue
            val t = other.type
            if (t != com.iappyx.launcher.model.CellType.ICON &&
                t != com.iappyx.launcher.model.CellType.FOLDER) continue
            return onCellMergeRequest?.invoke(src, other) ?: false
        }
        return false
    }

    private fun collides(me: Placement, row: Int, col: Int, w: Int, h: Int): Boolean {
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as? GridLayoutParams ?: continue
            val other = getChildAt(i).tag as? Placement ?: continue
            if (other.id == me.id) continue
            if (col + w <= lp.col || col >= lp.col + lp.wSpan) continue
            if (row + h <= lp.row || row >= lp.row + lp.hSpan) continue
            return true
        }
        return false
    }

    // ── Layout ────────────────────────────────────────────────
    private fun cellWidth(): Float = (measuredWidth - spacingPx * (cols + 1)).toFloat() / cols
    private fun cellHeight(): Float = (measuredHeight - spacingPx * (rows + 1)).toFloat() / rows

    private fun findChildAtPoint(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
        }
        return null
    }

    private fun isCellEmpty(row: Int, col: Int): Boolean {
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as? GridLayoutParams ?: continue
            val r2 = lp.row + lp.hSpan
            val c2 = lp.col + lp.wSpan
            if (row >= lp.row && row < r2 && col >= lp.col && col < c2) return false
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cw = cellWidth(); val ch = cellHeight()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? GridLayoutParams ?: continue
            val w = (cw * lp.wSpan + spacingPx * (lp.wSpan - 1)).toInt()
            val h = (ch * lp.hSpan + spacingPx * (lp.hSpan - 1)).toInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth(); val ch = cellHeight()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? GridLayoutParams ?: continue
            val x = (spacingPx + lp.col * (cw + spacingPx)).toInt()
            val y = (spacingPx + lp.row * (ch + spacingPx)).toInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            // Set a sensible pivot so wiggle/scale rotates around the cell centre.
            child.pivotX = child.measuredWidth / 2f
            child.pivotY = child.measuredHeight / 2f
        }
        // After every layout pass, reconcile wiggle and dim with the current
        // children. New views added by the activity's renderPage need to start
        // wiggling and pick up the dim alpha if a placement is selected.
        if (editMode) {
            for (i in 0 until childCount) ensureWiggle(getChildAt(i))
            applyDimForSelection()
        }
    }

    // ── Draw ──────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background-only painting. Edit-mode overlays (outlines, handles,
        // edit badge, ghost) are drawn AFTER children in [dispatchDraw] so
        // they sit on top of the WebView content and the rounded cell clip.

        // Empty-page pulse: page has no children AND we're not in edit mode.
        if (!editMode && childCount == 0) {
            if (!emptyPagePulseAnim.isStarted) emptyPagePulseAnim.start()
            val cx = width / 2f; val cy = height / 2f
            val scale = emptyPagePulseAnim.animatedValue as? Float ?: 1f
            val arm = 22f * resources.displayMetrics.density * scale
            val radius = 40f * resources.displayMetrics.density * scale
            canvas.drawCircle(cx, cy, radius, emptyPagePulsePaint)
            canvas.drawLine(cx - arm, cy, cx + arm, cy, emptyPagePulsePaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, emptyPagePulsePaint)
        } else if (emptyPagePulseAnim.isStarted) {
            emptyPagePulseAnim.cancel()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editMode) return
        // Re-read accent on each edit-mode draw so a theme change applies live
        // to the selection outline / edit badge / resize handle. Cheap (Palette
        // caches per override-generation) and only runs in edit mode.
        val accent = Palette.accent(context)
        selectedOutlinePaint.color = accent
        editBadgeFillPaint.color = accent
        handleStrokePaint.color = accent

        // Grid overlay during drag — under everything else in the overlay layer
        // but on top of children so dropped-on cells are visible.
        if (dragMode != DragMode.NONE) {
            val cw = cellWidth(); val ch = cellHeight()
            for (r in 0..rows) {
                val y = spacingPx + r * (ch + spacingPx)
                canvas.drawLine(0f, y, width.toFloat(), y, gridOverlayPaint)
            }
            for (c in 0..cols) {
                val x = spacingPx + c * (cw + spacingPx)
                canvas.drawLine(x, 0f, x, height.toFloat(), gridOverlayPaint)
            }
        }

        // "+" chip in every empty 1×1 cell — skipped during a drag (local OR
        // a cross-page system drag) so it doesn't compete with the ghost
        // preview, and so we don't suggest you can drop into a 1×1 empty cell
        // when the source widget actually needs e.g. 4×2 of contiguous space.
        if (dragMode == DragMode.NONE && !pageDragInFlight) {
            val cw = cellWidth(); val ch = cellHeight()
            val dp = resources.displayMetrics.density
            val plusArm = 11f * dp
            val chipRadius = 22f * dp
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (!isCellEmpty(r, c)) continue
                    val left = spacingPx + c * (cw + spacingPx)
                    val top = spacingPx + r * (ch + spacingPx)
                    val cx = left + cw / 2f
                    val cy = top + ch / 2f
                    canvas.drawCircle(cx, cy, chipRadius, emptyCellCircleFill)
                    canvas.drawCircle(cx, cy, chipRadius, emptyCellCircleStroke)
                    canvas.drawLine(cx - plusArm, cy, cx + plusArm, cy, emptyCellPlusStroke)
                    canvas.drawLine(cx, cy - plusArm, cx, cy + plusArm, emptyCellPlusStroke)
                }
            }
        }

        // Unselected outlines on all non-selected widgets — thin, faint.
        val selId = selected?.id
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val p = c.tag as? Placement ?: continue
            if (p.id == selId) continue
            canvas.drawRect(
                c.left.toFloat() + 1, c.top.toFloat() + 1,
                c.right.toFloat() - 1, c.bottom.toFloat() - 1,
                outlinePaint,
            )
        }

        // Selected widget: glow + bold outline (breathing) + handles (skipped
        // for folders, which aren't resizable) + Edit badge (only on AI widgets).
        selectedView?.let { sv ->
            val sel = selected ?: return@let
            val tx = sv.translationX; val ty = sv.translationY
            val l = sv.left.toFloat() + tx
            val t = sv.top.toFloat() + ty
            val r = sv.right.toFloat() + tx
            val b = sv.bottom.toFloat() + ty
            val dp = resources.displayMetrics.density
            val rectRadius = 16f * dp
            // Glow under the outline
            canvas.drawRoundRect(l, t, r, b, rectRadius, rectRadius, selectedGlowPaint)
            // Breathing outline
            selectedOutlinePaint.alpha = (breatheAnimator.animatedValue as? Float)?.toInt() ?: 255
            canvas.drawRoundRect(l, t, r, b, rectRadius, rectRadius, selectedOutlinePaint)

            // Resize handles — only when the placement is actually resizable.
            val resizable = sel.type != CellType.FOLDER
            if (resizable) {
                val ins = handleInsetPx
                val hl = l + ins; val ht = t + ins
                val hr = r - ins; val hb = b - ins
                val hcx = (l + r) / 2f; val hcy = (t + b) / 2f
                val pts = listOf(
                    hl to ht, hcx to ht, hr to ht,
                    hl to hcy, hr to hcy,
                    hl to hb, hcx to hb, hr to hb,
                )
                for ((hx, hy) in pts) {
                    canvas.drawCircle(hx, hy, handleRadiusPx.toFloat(), handleFillPaint)
                    canvas.drawCircle(hx, hy, handleRadiusPx.toFloat(), handleStrokePaint)
                }
            }

            // Edit badge — only on AI / generated widgets, not on icons /
            // stock widgets / folders (none of those have an Edit flow).
            if (sel.type == CellType.GENERATED_WIDGET) {
                val rc = editBadgeRect(sv)
                if (rc != null) {
                    val cx = (rc.left + rc.right) / 2f
                    val cy = (rc.top + rc.bottom) / 2f
                    val rad = (rc.right - rc.left) / 2f
                    canvas.drawCircle(cx, cy, rad, editBadgeFillPaint)
                    canvas.drawCircle(cx, cy, rad, editBadgeStrokePaint)
                    editBadgeIconPaint.textSize = rad * 1.2f
                    val baseline = cy - (editBadgeIconPaint.descent() + editBadgeIconPaint.ascent()) / 2f
                    canvas.drawText("✎", cx, baseline, editBadgeIconPaint)
                }
            }
        }

        // Ghost preview — top-most so it always reads as "this is where it lands".
        if (showGhost) {
            val cw = cellWidth(); val ch = cellHeight()
            val left = (spacingPx + ghostCol * (cw + spacingPx))
            val top = (spacingPx + ghostRow * (ch + spacingPx))
            val right = left + (cw * ghostW + spacingPx * (ghostW - 1))
            val bottom = top + (ch * ghostH + spacingPx * (ghostH - 1))
            val rect = RectF(left, top, right, bottom)
            val radius = 8f * resources.displayMetrics.density
            val paint = if (ghostValid) ghostValidPaint else ghostInvalidPaint
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    // ── LayoutParams ─────────────────────────────────────────
    override fun generateDefaultLayoutParams(): LayoutParams = GridLayoutParams(0, 0, 1, 1)
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is GridLayoutParams
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is GridLayoutParams) p else GridLayoutParams(0, 0, 1, 1)

    class GridLayoutParams(
        var row: Int,
        var col: Int,
        var wSpan: Int,
        var hSpan: Int,
    ) : LayoutParams(MATCH_PARENT, MATCH_PARENT)
}
