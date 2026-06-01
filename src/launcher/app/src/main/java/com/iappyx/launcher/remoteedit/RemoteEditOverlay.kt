/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * REMOTE EDIT FEATURE — optional floating overlay shown while a
 * laptop session is active. A small colored dot anchored to the
 * right edge of the screen, visible over other apps. Tap to open
 * [RemoteEditActivity] (where the user can see URL / pairing code
 * / disconnect). Stop comes from the persistent notification's
 * action — the overlay is purely an at-a-glance "session is alive"
 * affordance.
 *
 * Requires SYSTEM_ALERT_WINDOW. The launcher already declares the
 * permission; the user must grant via system Settings. The
 * activity offers a link to that screen if not yet granted.
 *
 * To remove: delete this file + the show/hide hooks in
 * [RemoteEditService].
 */
package com.iappyx.launcher.remoteedit

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.iappyx.launcher.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RemoteEditOverlay(private val context: Context) {

    private val wm: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    /** Heartbeat animator. Held so [hide] can cancel it — leaving it
     *  running on a detached view would leak the View ref through the
     *  animator's listener chain. */
    private var animator: ObjectAnimator? = null

    /** True iff overlay is currently visible on screen. */
    val isShowing: Boolean get() = view != null

    /** Static permission check the activity uses before offering the
     *  feature. Cheap, just delegates to Android's API. */
    companion object {
        fun canShow(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /** SharedPreferences file persisting the user's drag position so
         *  the dot reappears where they left it after a service restart
         *  or reboot. Cleared whenever the feature is uninstalled — the
         *  remoteedit/ removal procedure wipes app data anyway. */
        private const val PREFS_NAME = "iappyx_remoteedit_overlay"
        private const val KEY_X = "x_px"
        private const val KEY_Y = "y_px"
        private const val UNSET = Int.MIN_VALUE
    }

    fun show() {
        if (view != null || wm == null) return
        if (!canShow(context)) return
        val density = context.resources.displayMetrics.density
        // 36dp icon + 12dp halo room on each side = 60dp window. The halo
        // animates outward to 1.25× the icon size; the container has to
        // accommodate the peak scale or the halo gets clipped to a square.
        val iconSize = (36 * density).toInt()
        val containerSize = (60 * density).toInt()
        val cornerRadius = 9f * density  // matches the launcher icon's adaptive-icon corner

        // ── Halo: rounded-square accent glow that pulses behind the icon.
        // Sized to match the icon initially; the animator scales it out
        // and fades it down each cycle so it reads as a soft heartbeat
        // rather than a solid ring. Brand accent colour at low alpha
        // (#4FC3F7 @ 60%) so it picks up on both dark and light backdrops.
        val halo = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(0x994FC3F7.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }

        // ── Icon: the iappyxOS launcher icon (rounded-square 3×3 grid of
        // coloured cells). Using @mipmap/ic_launcher gives us the dark
        // backdrop + bevelled corners + brand colours in one drawable,
        // already provided at every density.
        val icon = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }

        val container = FrameLayout(context).apply {
            addView(halo)
            addView(icon)
        }

        val layerType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Gravity is TOP|START so x/y are simple top-left absolute
        // pixel coords — easiest to clamp + persist after a drag.
        // Default position is "12 dp from the right edge, 96 dp from
        // the top" (the same visual anchor v1 used, now expressed as
        // an absolute coord).
        val dm = context.resources.displayMetrics
        val defaultX = dm.widthPixels - containerSize - (12 * density).toInt()
        val defaultY = (96 * density).toInt()
        val (initialX, initialY) = loadPosition(defaultX, defaultY)
        val lp = WindowManager.LayoutParams(
            containerSize, containerSize, layerType,
            // FLAG_NOT_FOCUSABLE so the overlay never steals input
            // focus from the underlying app; FLAG_LAYOUT_NO_LIMITS
            // lets us position past safe-area into the status bar
            // gutter if needed (we don't, but it's defensive).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(initialX, containerSize)
            y = clampY(initialY, containerSize)
        }

        // Touch handler: distinguishes drag from tap via the system's
        // scaledTouchSlop. While dragging, updates the WindowManager
        // LayoutParams live (instant follow-finger). On UP we persist
        // the position if we actually dragged; if not, the touch was
        // a tap → open RemoteEditActivity (the v1 click behaviour).
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f; var downRawY = 0f
        var initialLpX = 0; var initialLpY = 0
        var dragged = false
        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    initialLpX = lp.x; initialLpY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragged = true
                    }
                    if (dragged) {
                        lp.x = clampX((initialLpX + dx).toInt(), containerSize)
                        lp.y = clampY((initialLpY + dy).toInt(), containerSize)
                        try { wm.updateViewLayout(container, lp) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        savePosition(lp.x, lp.y)
                    } else {
                        // Pure tap → open the editor activity. Single-top
                        // launchMode in the manifest means we reuse the
                        // existing instance rather than stacking duplicates.
                        val i = Intent(context, RemoteEditActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            )
                        }
                        try { context.startActivity(i) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                else -> false
            }
        }
        try {
            wm.addView(container, lp)
            view = container
            // Heartbeat: icon does a soft 1.0 → 1.05 → 1.0 breathe; halo
            // simultaneously scales out from 1.0 to 1.55 and fades to 0
            // so it reads as a pulse-ring radiating from behind the icon.
            // 1800 ms feels alive without being distracting at the edge
            // of vision; AccelerateDecelerate keeps both extremes soft.
            val haloScale = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.55f)
            val haloScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.55f)
            val haloAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0.0f)
            animator = ObjectAnimator.ofPropertyValuesHolder(
                halo, haloScale, haloScaleY, haloAlpha,
            ).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            // Light icon-side breathe, runs alongside the halo on the
            // same cadence. Stored locally; cancelled implicitly when
            // the View is detached in [hide].
            ObjectAnimator.ofPropertyValuesHolder(
                icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.05f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.05f, 1.0f),
            ).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } catch (t: Throwable) {
            android.util.Log.w("iappyxRemoteEdit", "overlay add failed: ${t.message}")
            view = null
        }
    }

    fun hide() {
        val v = view ?: return
        animator?.cancel()
        animator = null
        try { wm?.removeViewImmediate(v) } catch (_: Throwable) {}
        view = null
    }

    private fun loadPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val x = p.getInt(KEY_X, UNSET)
        val y = p.getInt(KEY_Y, UNSET)
        return if (x == UNSET || y == UNSET) Pair(defaultX, defaultY) else Pair(x, y)
    }

    private fun savePosition(x: Int, y: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /** Keep the overlay on-screen. Status bar / nav bar overlap is fine
     *  (FLAG_LAYOUT_NO_LIMITS is set), but going past the actual screen
     *  edges hides the icon entirely. */
    private fun clampX(x: Int, size: Int): Int {
        val w = context.resources.displayMetrics.widthPixels
        return max(0, min(w - size, x))
    }

    private fun clampY(y: Int, size: Int): Int {
        val h = context.resources.displayMetrics.heightPixels
        return max(0, min(h - size, y))
    }
}
