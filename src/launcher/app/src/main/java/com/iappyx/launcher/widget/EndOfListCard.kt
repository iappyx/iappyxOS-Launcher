/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.iappyx.launcher.R

/**
 * Trailing card appended to every Manage tab's carousel after the last
 * real entry. Two CTAs:
 *
 *  - **Generate with AI** — switches to the AI tab with a kind-specific
 *    prefill, the same way the per-tab "Generate new …" header button does.
 *  - **Browse Showcase** — opens [com.iappyx.launcher.ShowcaseBrowserActivity]
 *    pre-filtered to this kind so the user lands on the right tab.
 *
 * Discovery in-context: when the user swipes past the last widget /
 * wallpaper / transition, they see options to add more without hunting
 * for a menu. ReceiveSheet keeps a "Browse Showcase" entry for the
 * "I want to install something specific" path.
 */
class EndOfListCard(activity: Activity, kindLabel: String) : FrameLayout(activity) {

    /** Tap handler for the "Generate with AI" pill. */
    var onGenerate: (() -> Unit)? = null

    /** Tap handler for the "Browse Showcase" pill. */
    var onBrowse: (() -> Unit)? = null

    init {
        val dp = resources.displayMetrics.density
        setBackgroundColor(Palette.bgHome(activity))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(Palette.bgCell(activity))
                setStroke((1 * dp).toInt(),
                    ColorUtils.setAlphaComponent(Palette.textPrimary(activity), 0x22))
            }
            val pad = (28 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val side = (16 * dp).toInt()
            lp.setMargins(side, side, side, side)
            layoutParams = lp
        }

        // Sparkle badge to mirror the AI tab's icon — signals "more is one
        // tap away" without needing a separate hero image per kind.
        card.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_auto_awesome)
            imageTintList = ColorStateList.valueOf(Palette.accent(activity))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(Palette.accent(activity), 0x33))
            }
            val s = (72 * dp).toInt(); val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s)
        })

        card.addView(TextView(activity).apply {
            text = "Want more $kindLabel?"
            setTextColor(Palette.textPrimary(activity))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        })

        card.addView(TextView(activity).apply {
            text = "Generate one with AI, or pick from the community Showcase."
            setTextColor(Palette.textSecondary(activity))
            textSize = 13f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        })

        card.addView(makeButton(activity, "Generate with AI", primary = true) {
            onGenerate?.invoke()
        })
        card.addView(makeButton(activity, "Browse Showcase", primary = false) {
            onBrowse?.invoke()
        })

        addView(card)
    }

    private fun makeButton(
        activity: Activity, label: String, primary: Boolean, onClick: () -> Unit,
    ): TextView {
        val dp = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label; textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (primary) {
                    setColor(Palette.accent(activity))
                } else {
                    setColor(ColorUtils.setAlphaComponent(Palette.accent(activity), 0x1F))
                    setStroke((1 * dp).toInt(),
                        ColorUtils.setAlphaComponent(Palette.accent(activity), 0x66))
                }
            }
            setTextColor(if (primary) Color.parseColor("#0D0D1A") else Palette.accent(activity))
            val hp = (28 * dp).toInt(); val vp = (12 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
    }
}
