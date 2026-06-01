/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R

/**
 * "Add to home" picker — Material-You-styled bottom sheet matching
 * [PageTransitionSheet]. Full-width selectable rows with circular icon
 * badges; colors come from [Palette] so they follow the user's wallpaper /
 * theme.
 *
 * Has an in-place submenu wizard for the manual-AI option: tap "Generate
 * with external AI" and the sheet's content swaps to a three-row picker
 * (Widget / Wallpaper / Page transition) with a back arrow back to the
 * main menu. Same dialog throughout — no slide-out + slide-in flicker.
 */
class AddToHomeSheet(
    private val activity: Activity,
    private val onUseExistingWidget: () -> Unit,
    private val onAskIappyx: () -> Unit,
    private val onAiWidgetManual: () -> Unit,
    private val onAiWallpaperManual: () -> Unit,
    private val onAiTransitionManual: () -> Unit,
    private val onApp: () -> Unit,
    private val onStockWidget: () -> Unit,
    private val onAppDrawer: () -> Unit,
) {

    private data class Option(
        @DrawableRes val iconRes: Int,
        val title: String,
        /** Optional small italic-grey tagline shown under [title] (e.g.
         *  "(automated AI flow)"). Above [subtitle], smaller and dimmer. */
        val tagline: String? = null,
        val subtitle: String,
        val onClick: () -> Unit,
    )

    private lateinit var dialog: BottomSheetDialog
    private lateinit var contentRoot: LinearLayout

    fun show() {
        val dp = activity.resources.displayMetrics.density
        dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)

        contentRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Palette.bgHome(activity))
        }
        renderMain(dp)

        dialog.setContentView(contentRoot)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }

    /** Top-level menu — the six entries the user lands on. */
    private fun renderMain(dp: Float) {
        contentRoot.removeAllViews()
        addGrabber(dp)
        addHeader(
            dp,
            title = "Add to home",
            subtitle = "Pick what to drop into the empty cell",
        )

        val options = listOf(
            Option(R.drawable.ic_widgets, "Use existing widget",
                subtitle = "Place a widget you've already generated, imported, or bundled",
            ) { dialog.dismiss(); onUseExistingWidget() },
            Option(R.drawable.ic_auto_awesome, "Ask iappyxOS Launcher",
                tagline = "automated AI flow",
                subtitle = "Describe a widget, wallpaper, or page transition. The AI builds it directly.",
            ) { dialog.dismiss(); onAskIappyx() },
            Option(R.drawable.ic_content_paste, "Generate with external AI",
                tagline = "manual AI flow",
                subtitle = "Get a prompt to paste into ChatGPT, Claude, or any AI; bring the result back.",
            ) { renderManualSubmenu(dp) },
            Option(R.drawable.ic_smartphone, "App icon",
                subtitle = "Pick from installed apps",
            ) { dialog.dismiss(); onApp() },
            Option(R.drawable.ic_widgets, "Built-in Android widgets",
                subtitle = "System widgets from other apps — clock, calendar, music, etc.",
            ) { dialog.dismiss(); onStockWidget() },
            Option(R.drawable.ic_apps, "All apps",
                subtitle = "Tile that opens the app drawer",
            ) { dialog.dismiss(); onAppDrawer() },
        )
        addOptionList(dp, options)
    }

    /** Step 2 wizard for the manual-AI option — three sub-targets. */
    private fun renderManualSubmenu(dp: Float) {
        contentRoot.removeAllViews()
        addGrabber(dp)
        addBackHeader(
            dp,
            title = "Generate with external AI",
            subtitle = "What should the AI generate?",
        ) { renderMain(dp) }

        val options = listOf(
            Option(R.drawable.ic_widgets, "Widget",
                subtitle = "A small interactive cell on your home screen",
            ) { dialog.dismiss(); onAiWidgetManual() },
            Option(R.drawable.ic_image, "Wallpaper",
                subtitle = "A live, animated background — runs behind the home screen",
            ) { dialog.dismiss(); onAiWallpaperManual() },
            Option(R.drawable.ic_swap_horiz, "Page transition",
                subtitle = "How home pages animate when you swipe between them",
            ) { dialog.dismiss(); onAiTransitionManual() },
        )
        addOptionList(dp, options)
    }

    private fun addGrabber(dp: Float) {
        contentRoot.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(ColorUtils.setAlphaComponent(Palette.textPrimary(activity), 0x44))
            }
            val lp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (16 * dp).toInt()
            layoutParams = lp
        })
    }

    private fun addHeader(dp: Float, title: String, subtitle: String) {
        contentRoot.addView(TextView(activity).apply {
            text = title
            setTextColor(Palette.textPrimary(activity))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        contentRoot.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(Palette.textSecondary(activity))
            textSize = 13f
            setPadding(0, 0, 0, (16 * dp).toInt())
        })
    }

    /** Same shape as [addHeader] but with a tappable back chevron on the left
     *  that returns to the top-level menu without dismissing the sheet. */
    private fun addBackHeader(dp: Float, title: String, subtitle: String, onBack: () -> Unit) {
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        titleRow.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(Palette.textPrimary(activity))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val s = (32 * dp).toInt()
            val pad = (4 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (8 * dp).toInt()
            }
            isClickable = true; isFocusable = true
            setOnClickListener { onBack() }
        })
        titleRow.addView(TextView(activity).apply {
            text = title
            setTextColor(Palette.textPrimary(activity))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        contentRoot.addView(titleRow)
        contentRoot.addView(TextView(activity).apply {
            text = subtitle
            setTextColor(Palette.textSecondary(activity))
            textSize = 13f
            setPadding((40 * dp).toInt(), 0, 0, (16 * dp).toInt())
        })
    }

    private fun addOptionList(dp: Float, options: List<Option>) {
        for ((idx, opt) in options.withIndex()) {
            contentRoot.addView(makeOptionRow(dp, opt))
            if (idx < options.lastIndex) {
                contentRoot.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt(),
                    )
                })
            }
        }
    }

    private fun makeOptionRow(dp: Float, opt: Option): LinearLayout {
        val accent = Palette.accent(activity)
        val cellBg = Palette.bgCell(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(cellBg)
                setStroke((1 * dp).toInt(),
                    ColorUtils.setAlphaComponent(Palette.textPrimary(activity), 0x22))
            }
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            setOnClickListener { opt.onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(ImageView(activity).apply {
            setImageResource(opt.iconRes)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(accent, 0x33))
            }
            val s = (44 * dp).toInt()
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (14 * dp).toInt()
            }
        })
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(activity).apply {
            this.text = opt.title
            setTextColor(Palette.textPrimary(activity))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        // Optional small grey italic tagline (e.g. "automated AI flow") sits
        // between title and subtitle. Only rendered when present so other
        // rows keep their compact two-line shape.
        if (opt.tagline != null) {
            text.addView(TextView(activity).apply {
                this.text = opt.tagline
                setTextColor(ColorUtils.setAlphaComponent(Palette.textSecondary(activity), 0xCC))
                textSize = 11f
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(0, (1 * dp).toInt(), 0, 0)
            })
        }
        text.addView(TextView(activity).apply {
            this.text = opt.subtitle
            setTextColor(Palette.textSecondary(activity))
            textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        card.addView(text)
        card.addView(TextView(activity).apply {
            this.text = "›"
            setTextColor(ColorUtils.setAlphaComponent(Palette.textPrimary(activity), 0x77))
            textSize = 22f
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        })
        return card
    }
}
