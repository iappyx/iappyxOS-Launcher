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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R

/**
 * Bottom-sheet picker for the page-transition style. Mirrors the look of
 * [AddToHomeSheet] (grabber, dark surface, rounded option rows). Each option
 * shows an icon, name, and one-line description; tap to select and dismiss.
 */
class PageTransitionSheet(
    private val activity: Activity,
    private val current: String,
    private val onPick: (String) -> Unit,
) {

    private data class Option(val key: String, val title: String, val subtitle: String)

    fun show() {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0D0D1A"))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }

        // Grabber
        root.addView(View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(Color.parseColor("#44FFFFFF"))
            }
            val lp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (16 * dp).toInt()
            layoutParams = lp
        })

        root.addView(TextView(activity).apply {
            text = "Page transition"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        root.addView(TextView(activity).apply {
            text = "Pick how home pages animate when you swipe"
            setTextColor(Color.parseColor("#A0A0B8"))
            textSize = 13f
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        // All transitions — the originals (now JSON specs in
        // assets/transitions/) and user-generated ones — flow through
        // TransitionLibrary. The hand-coded fallback in LauncherActivity
        // handles any id without a spec.
        val allEntries = com.iappyx.launcher.transitions.TransitionLibrary.all(activity)
        val (bundled, user) = allEntries.partition { !it.isUserGenerated }
        for (e in bundled) {
            val opt = Option(key = e.id, title = e.title, subtitle = e.subtitle)
            root.addView(makeOptionRow(dp, opt) {
                dialog.dismiss()
                onPick(opt.key)
            })
            root.addView(spacer(dp, 8f))
        }
        if (user.isNotEmpty()) {
            root.addView(sectionHeading(dp, "AI-generated"))
            for (e in user) {
                val opt = Option(key = e.id, title = e.title, subtitle = e.subtitle)
                root.addView(makeOptionRow(dp, opt) {
                    dialog.dismiss()
                    onPick(opt.key)
                })
                root.addView(spacer(dp, 8f))
            }
        }

        root.addView(makeManageFooter(dp) { dialog.dismiss() })

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }

    /** Footer link that bounces back to LauncherActivity asking it to open the
     *  Transitions tab in the command panel — same pattern as
     *  [WallpaperSheet.makeManageFooter]. */
    private fun makeManageFooter(dp: Float, dismissParent: () -> Unit): View =
        TextView(activity).apply {
            text = "Manage transitions →"
            setTextColor(Palette.accent(activity))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener {
                dismissParent()
                val intent = android.content.Intent(activity,
                    com.iappyx.launcher.LauncherActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(com.iappyx.launcher.LauncherActivity.EXTRA_OPEN_TAB, "transitions")
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }

    private fun sectionHeading(dp: Float, label: String): TextView = TextView(activity).apply {
        text = label
        setTextColor(Color.parseColor("#A0A0B8"))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun spacer(dp: Float, heightDp: Float): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (heightDp * dp).toInt(),
        )
    }

    private fun makeOptionRow(dp: Float, opt: Option, onClick: () -> Unit): LinearLayout {
        val isSelected = opt.key == current
        val accent = activity.resources.getColor(R.color.accent, activity.theme)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.parseColor(if (isSelected) "#222244" else "#1A1A2E"))
                setStroke((if (isSelected) 2 else 1).times(dp).toInt(),
                    if (isSelected) accent else Color.parseColor("#22FFFFFF"))
            }
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        // No leading badge — the AI-generated section heading already
        // separates user transitions from the bundled set, and the manage
        // tab carousel has live previews. The picker is a plain title +
        // subtitle list.
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
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
        card.addView(text)
        // Selected indicator: Material check icon, accent-tinted.
        if (isSelected) {
            card.addView(android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val s = (24 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = (8 * dp).toInt()
                }
            })
        }
        return card
    }
}
