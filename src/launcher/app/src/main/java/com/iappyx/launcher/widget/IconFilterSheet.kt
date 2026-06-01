/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R

/**
 * Material-style bottom sheet for picking the icon filter. Mirrors the layout
 * of [PageTransitionSheet] (grabber, dark surface, rounded option rows).
 */
class IconFilterSheet(
    private val activity: Activity,
    private val current: String,
    private val onPick: (String) -> Unit,
) {

    private data class Option(val key: String, val title: String, val subtitle: String)

    private val options = listOf(
        Option("none",             "None",
            "Icons render in their full original colour"),
        Option("greyscale",        "Greyscale",
            "Pure black-and-white. Minimalist, no tint."),
        Option("sepia",            "Sepia",
            "Warm brown vintage tones, like an old photo print"),
        Option("vintage",          "Vintage",
            "Faded saturation with a warm shift — old polaroid feel"),
        Option("mono_accent",      "Mono accent",
            "Greyscale icons tinted to your system accent (Material You)"),
        Option("rainbow_matrix",   "Rainbow matrix",
            "Greyscale, tinted by grid position — radial rainbow"),
        Option("wallpaper_themed", "Wallpaper themed",
            "Tints sampled from your wallpaper, distributed across the grid"),
        Option("pixelate",         "Pixelate",
            "8-bit retro: icons downsampled to chunky pixels"),
        Option("tinted_mono",      "Tinted mono",
            "Each icon recoloured with its own dominant colour"),
        Option("aurora",           "Aurora",
            "Holographic cyan→magenta→gold→teal sheen over each icon"),
        Option("sweetheart",       "Sweetheart",
            "Heart-shaped icons in soft pink duotone"),
        Option("squircle",         "Squircle",
            "iOS-style squircle silhouette — softer than rounded, flatter than round"),
        Option("star",             "Star",
            "Five-pointed star silhouette via custom SVG path"),
    )

    fun show() {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0D0D1A"))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        // Drag-handle pill
        container.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
                .apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (10 * dp).toInt() }
        })
        // Title
        container.addView(TextView(activity).apply {
            text = "Icon style"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        })
        for (opt in options) container.addView(makeRow(opt, dp) {
            onPick(opt.key); dialog.dismiss()
        })
        // Footer: "Manage icon styles →" deep-link, mirrors the wallpaper /
        // transition sheets. The chooser stays a quick-switcher; full
        // generation / rename / delete / import lives in the Icons tab.
        container.addView(makeManageFooter(dp) { dialog.dismiss() })
        scroll.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        dialog.setContentView(scroll)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }

    private fun makeManageFooter(dp: Float, dismissParent: () -> Unit): View {
        return TextView(activity).apply {
            text = "Manage icon styles →"
            setTextColor(Palette.accent(activity))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(
                (20 * dp).toInt(), (16 * dp).toInt(),
                (20 * dp).toInt(), (8 * dp).toInt(),
            )
            isClickable = true; isFocusable = true
            setOnClickListener {
                dismissParent()
                val intent = android.content.Intent(activity,
                    com.iappyx.launcher.LauncherActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(com.iappyx.launcher.LauncherActivity.EXTRA_OPEN_TAB, "icons")
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }

    private fun makeRow(opt: Option, dp: Float, onClick: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (opt.key == current) GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#22FFFFFF"))
            } else null
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        // No leading badge — title + one-line subtitle reads cleanly without
        // a generic glyph, and the (un)selected state is shown by the row's
        // background tint.
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(activity).apply {
            this.text = opt.title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(TextView(activity).apply {
            this.text = opt.subtitle
            setTextColor(Color.parseColor("#A0A0B8"))
            textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        return row
    }
}
