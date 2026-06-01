/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import com.iappyx.launcher.cells.GeneratedWidgetCell

/**
 * Zooms a widget's WebView from its home-grid cell to a near-full-screen card.
 * Reparents the live WebView so JS state, scroll position, and subscriptions
 * all survive the transition. A "Done" button closes and reparents back.
 *
 * The widget sees the resize via standard window.resize events and can re-lay
 * out its UI for the larger area — matching the "widgets are full apps"
 * philosophy.
 */
class WidgetZoomOverlay(
    private val activity: Activity,
    private val cell: GeneratedWidgetCell,
) {

    fun show() {
        val webView = cell.webView ?: return
        val dp = activity.resources.displayMetrics.density
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )

        val scrim = View(activity).apply {
            setBackgroundColor(Color.parseColor("#AA000000"))
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Host that the WebView gets reparented into. 92% of screen width,
        // centered; the WebView fills it.
        val host = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(Color.parseColor("#FF0D0D1A"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = 16 * dp
            layoutParams = FrameLayout.LayoutParams(
                (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.82f).toInt(),
                Gravity.CENTER,
            )
        }
        val done = TextView(activity).apply {
            text = "Done"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Palette.accentAlpha(activity, 0x66))
            }
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            )
            lp.topMargin = (12 * dp).toInt()
            lp.rightMargin = (12 * dp).toInt()
            layoutParams = lp
            elevation = 18 * dp
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }

        // Bottom-centre close pill, matching the height of the widget card.
        // Offset down from the card by (1 - 0.82)/2 of the screen height plus
        // a bit so it sits clearly *below* the widget edge.
        val closeBelow = TextView(activity).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            textSize = 14f
            val d = androidx.core.content.ContextCompat.getDrawable(
                activity, com.iappyx.launcher.R.drawable.ic_close,
            )?.mutate()
            d?.let {
                val sz = (16 * dp).toInt()
                it.setBounds(0, 0, sz, sz)
            }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            compoundDrawablePadding = (8 * dp).toInt()
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(Color.parseColor("#FF1A1A2C"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
            elevation = 18 * dp
            isClickable = true
            // Below the host card (which is 82% screen height, centered): the
            // bottom of the card sits at screenH * (0.5 + 0.82/2) = 0.91. We
            // place the pill ~5% screen-h below that, clamped to the visible area.
            val screenH = activity.resources.displayMetrics.heightPixels
            val pillTopMargin = (screenH * 0.93f).toInt()
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
            lp.topMargin = pillTopMargin
            layoutParams = lp
            setOnClickListener { dialog.dismiss() }
        }

        val root = FrameLayout(activity).apply {
            addView(scrim)
            addView(host)
            addView(done)
            addView(closeBelow)
        }
        dialog.setContentView(root)

        // Reparent the live WebView from the cell into our host. Save its
        // original LayoutParams so we can restore them on close.
        val originalParent = webView.parent as? ViewGroup
        val originalLp = webView.layoutParams
        originalParent?.removeView(webView)
        host.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // Reparent helper used both by the animated and non-animated paths
        // below. Wrapped in try/catch because if the cell's RecyclerView
        // recycled the holder while we were open (zoom on a clipping
        // card, then scroll the inner list off-screen), `cell.destroyWidget()`
        // will have been called already — webView's underlying renderer
        // is dead and `originalParent` may also be detached. Better to
        // remove cleanly and let the cell rebind on next bind than to
        // crash.
        fun reparentBack() {
            try {
                if (webView.parent === host) host.removeView(webView)
            } catch (_: Throwable) {}
            try {
                if (originalParent != null && webView.parent == null) {
                    originalParent.addView(webView, 0, originalLp)
                }
            } catch (_: Throwable) {
                // Cell was destroyed under us; the WebView is now
                // orphaned but harmless — its host will GC it.
            }
        }

        dialog.setOnDismissListener {
            // Reverse animation, then put the WebView back.
            val sourceRect = Rect().also { cell.getGlobalVisibleRect(it) }
            val targetRect = Rect().also { host.getGlobalVisibleRect(it) }
            if (sourceRect.width() > 0 && targetRect.width() > 0) {
                val sx = sourceRect.width().toFloat() / targetRect.width()
                val sy = sourceRect.height().toFloat() / targetRect.height()
                val tx = (sourceRect.centerX() - targetRect.centerX()).toFloat()
                val ty = (sourceRect.centerY() - targetRect.centerY()).toFloat()
                host.animate().scaleX(sx).scaleY(sy).translationX(tx).translationY(ty)
                    .setDuration(180L).setInterpolator(AccelerateInterpolator(1.4f))
                    .withEndAction { reparentBack() }.start()
                scrim.animate().alpha(0f).setDuration(160L).start()
            } else {
                reparentBack()
            }
        }
        dialog.show()

        // Enter animation: from source cell rect out to centered.
        host.post {
            val sourceRect = Rect().also { cell.getGlobalVisibleRect(it) }
            val targetRect = Rect().also { host.getGlobalVisibleRect(it) }
            if (sourceRect.width() == 0 || targetRect.width() == 0) return@post
            val sx = sourceRect.width().toFloat() / targetRect.width()
            val sy = sourceRect.height().toFloat() / targetRect.height()
            val tx = (sourceRect.centerX() - targetRect.centerX()).toFloat()
            val ty = (sourceRect.centerY() - targetRect.centerY()).toFloat()
            host.scaleX = sx; host.scaleY = sy
            host.translationX = tx; host.translationY = ty
            host.pivotX = host.width / 2f
            host.pivotY = host.height / 2f
            host.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(260L).setInterpolator(DecelerateInterpolator(1.8f)).start()
            scrim.animate().alpha(1f).setDuration(220L).start()
        }
    }
}
