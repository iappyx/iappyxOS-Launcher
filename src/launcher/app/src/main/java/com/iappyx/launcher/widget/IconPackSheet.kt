/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R
import com.iappyx.launcher.cells.IconPack

/**
 * Bottom sheet for choosing the active icon pack. Lists installed packs (with
 * their own launcher icon as a preview) plus a "None" option that restores the
 * built-in icon treatment. Mirrors [IconFilterSheet]'s look.
 *
 * [onPick] receives the chosen pack's package name, or "" for none.
 */
class IconPackSheet(
    private val activity: Activity,
    private val current: String,
    private val onPick: (String) -> Unit,
) {

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
            text = "Icon pack"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        })

        // "None" — built-in treatment.
        container.addView(makeRow(
            key = "",
            title = "None (built-in)",
            subtitle = "Use the launcher's own icon shaping",
            icon = null,
            dp = dp,
        ) { onPick(""); dialog.dismiss() })

        val packs = try { IconPack.discoverPacks(activity) } catch (_: Throwable) { emptyList() }
        if (packs.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "No icon packs installed. Install one from the Play Store " +
                    "(any Nova / ADW-compatible pack works), then it'll appear here."
                setTextColor(Color.parseColor("#A0A0B8"))
                textSize = 12f
                setPadding(
                    (20 * dp).toInt(), (8 * dp).toInt(),
                    (20 * dp).toInt(), (8 * dp).toInt(),
                )
            })
        } else {
            for (pack in packs) {
                container.addView(makeRow(
                    key = pack.packageName,
                    title = pack.label,
                    subtitle = pack.packageName,
                    icon = pack.icon,
                    dp = dp,
                ) { onPick(pack.packageName); dialog.dismiss() })
            }
        }

        scroll.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        dialog.setContentView(scroll)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }

    private fun makeRow(
        key: String,
        title: String,
        subtitle: String,
        icon: Drawable?,
        dp: Float,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (key == current) GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#22FFFFFF"))
            } else null
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            row.addView(ImageView(activity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                    .apply { marginEnd = (14 * dp).toInt() }
            })
        }
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(activity).apply {
            this.text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(TextView(activity).apply {
            this.text = subtitle
            setTextColor(Color.parseColor("#A0A0B8"))
            textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
            maxLines = 1
        })
        row.addView(text, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        return row
    }
}
