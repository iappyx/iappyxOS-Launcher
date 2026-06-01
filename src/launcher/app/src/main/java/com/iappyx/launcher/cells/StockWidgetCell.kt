/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import com.iappyx.launcher.widget.AppWidgetHostManager

/**
 * Hosts a native Android AppWidget (stock system widget: clock, weather, etc.)
 * via AppWidgetHost. The widget view is a cached instance managed by the
 * host manager so we don't lose RemoteViews state on page rebind.
 */
class StockWidgetCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var hostView: AppWidgetHostView? = null
    private var hostManagerRef: AppWidgetHostManager? = null
    var appWidgetId: Int = -1
        private set

    private var onDoubleTapCallback: (() -> Unit)? = null

    /** Gesture detector for double-tap → expand. Single taps & internal widget
     *  interactions still flow through the AppWidgetHostView untouched. */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapCallback?.invoke()
            return true
        }
    })

    fun setOnCellDoubleTap(callback: () -> Unit) { onDoubleTapCallback = callback }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Block the pager from grabbing the gesture the moment a second
        // finger lands — pinch-zoom inside Maps / image / similar AppWidgets
        // would otherwise get hijacked into a page swipe by ViewPager2.
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }

    fun bind(hostManager: AppWidgetHostManager, appWidgetId: Int) {
        this.appWidgetId = appWidgetId
        this.hostManagerRef = hostManager
        val v = hostManager.createView(context, appWidgetId) ?: return
        v.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        removeAllViews()
        addView(v)
        hostView = v
    }

    /** Report the current cell size (in dp) to the widget provider so its
     *  RemoteViews can adapt. Called from onSizeChanged once the cell has a
     *  real measured size — the info a provider typically needs to render. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val manager = hostManagerRef ?: return
        if (appWidgetId < 0 || w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        manager.updateSize(appWidgetId, (w / density).toInt(), (h / density).toInt())
    }

    fun release() {
        // Leave the cached hostView in AppWidgetHostManager alive — it's reused
        // on rebind. Just detach it from our own child list.
        hostView = null
        removeAllViews()
    }
}
