/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Native Field: an opt-in app-drawer alternative built from real Android views
 * (FieldView + the launcher's AlphabetRail), reusing AppRegistry, the icon-pack
 * pipeline, app-lock, and a long-press context menu. The web Field
 * (FieldActivity) is unaffected. Removing the fieldnative/ package + the
 * manifest entry + the settings toggle excises it cleanly.
 */
package com.iappyx.launcher.fieldnative

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.applock.AppLockManager
import com.iappyx.launcher.widget.AlphabetRail

class FieldNativeActivity : FragmentActivity() {

    private lateinit var fieldView: FieldView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kill the activity slide animations (open + close) so the only motion is
        // the bubble morph. overridePendingTransition is ignored on API 34+.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val density = resources.displayMetrics.density
        // Transparent — FieldView fades its own dark backdrop in over the home page (morph).
        val root = FrameLayout(this)

        fieldView = FieldView(this)
        root.addView(fieldView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // A–Z scrub rail — the SAME component the standard drawer uses.
        val rail = AlphabetRail(this)
        rail.onLetterTouch = { c: Char ->
            rail.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            fieldView.setFilter(c)   // '#' filters apps starting with a digit
        }
        root.addView(rail, FrameLayout.LayoutParams(
            (30 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = (70 * density).toInt(); bottomMargin = (36 * density).toInt(); marginEnd = (2 * density).toInt()
        })
        // Dim letters that lead nowhere, like the standard drawer rail.
        root.post { rail.activeLetters = fieldView.availableFirstLetters() }

        // Clear (✕) — appears only when a letter filter is active.
        val clear = TextView(this).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.argb(40, 255, 255, 255))
                setStroke((1 * density).toInt(), Color.argb(100, 255, 255, 255))
            }
            visibility = View.GONE
            setOnClickListener { fieldView.clearFilter() }
        }
        val cs = (40 * density).toInt()
        root.addView(clear, FrameLayout.LayoutParams(cs, cs, Gravity.START or Gravity.TOP).apply {
            leftMargin = (24 * density).toInt(); topMargin = (18 * density).toInt() + statusBarInset()
        })

        // Current-letter indicator (glass capsule), top-centre.
        val indicator = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.18f
            val ph = (20 * density).toInt(); val pv = (6 * density).toInt(); setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                cornerRadius = 18 * density; setColor(Color.argb(40, 255, 255, 255))
                setStroke((1 * density).toInt(), Color.argb(110, 255, 255, 255))
            }
            visibility = View.GONE
        }
        root.addView(indicator, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (14 * density).toInt() + statusBarInset() })

        // Stroke guide overlay (how to draw letters) — toggled by ✎.
        val guide = StrokeGuideView(this).apply { visibility = View.GONE; setOnClickListener { visibility = View.GONE } }
        root.addView(guide, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ✎ help button (top-right) opens the stroke guide.
        val help = TextView(this).apply {
            text = "✎"; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.argb(40, 255, 255, 255))
                setStroke((1 * density).toInt(), Color.argb(100, 255, 255, 255))
            }
            setOnClickListener { guide.visibility = if (guide.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        root.addView(help, FrameLayout.LayoutParams(cs, cs, Gravity.END or Gravity.TOP).apply {
            rightMargin = (44 * density).toInt(); topMargin = (18 * density).toInt() + statusBarInset()
        })


        fieldView.listener = object : FieldView.Listener {
            override fun onLaunch(pkg: String) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                try { LauncherPrefs(this@FieldNativeActivity).recordAppLaunch(pkg) } catch (_: Throwable) {}
                AppLockManager.launchApp(this@FieldNativeActivity, pkg, intent) { finish() }
            }
            override fun onClose() { finish() }
            override fun onFilterChanged(letter: String) {
                val active = letter.isNotEmpty()
                clear.visibility = if (active) View.VISIBLE else View.GONE
                indicator.visibility = if (active) View.VISIBLE else View.GONE
                if (active) indicator.text = letter.uppercase()
                // visibleLetters persists after the finger lifts (highlight is the
                // transient finger pill the rail clears on touch-up).
                rail.visibleLetters = if (active) setOf(letter.uppercase()[0]) else emptySet()
            }
            override fun onContextRequested(pkg: String, label: String, sx: Int, sy: Int) {
                FieldContextMenu.show(this@FieldNativeActivity, root, sx, sy, pkg, label) {
                    fieldView.refreshLabel(pkg)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { fieldView.startClose() }
        })

        setContentView(root)
    }

    private fun statusBarInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun finish() {
        super.finish()
        // No slide for the Field — it appears/leaves in place (the morph is the motion).
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
