/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R
import com.iappyx.launcher.wallpaper.WallpaperGenerator
import com.iappyx.launcher.wallpaper.WallpaperLibrary

/**
 * Bottom sheet for picking the live-wallpaper HTML payload — same shape as
 * [IconFilterSheet] / [PageTransitionSheet].
 *
 * Layout, top to bottom:
 *  - Status banner (active-state or "set as wallpaper" CTA)
 *  - "Bundled" section: app-bundled wallpapers
 *  - "Generated" section: AI-generated wallpapers from `filesDir`
 *  - "+ Generate new…" row → opens the prompt dialog
 *
 * Picking a row writes the pref + broadcasts the change so the running
 * `:wallpaper` engine hot-reloads. Generation runs on a worker thread; while
 * in flight the sheet shows a small modal progress dialog.
 */
class WallpaperSheet(
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
        // Drag handle
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
            text = "Live wallpaper"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        })

        container.addView(makeStatusBanner(dp) { dialog.dismiss() })

        val (bundled, user) = WallpaperLibrary.all(activity).partition { !it.isUserGenerated }

        container.addView(sectionHeading("Bundled", dp))
        for (entry in bundled) {
            container.addView(makeRow(entry, dp) {
                onPick(entry.id); dialog.dismiss()
            })
        }

        container.addView(sectionHeading("Generated", dp))
        if (user.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "No AI wallpapers yet — generate one in the Wallpapers tab."
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 13f
                setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
            })
        } else {
            for (entry in user) {
                container.addView(makeRow(entry, dp) {
                    onPick(entry.id); dialog.dismiss()
                })
            }
        }

        // Footer: "Manage wallpapers →" deep-link. Generate / rename / delete
        // moved out of this sheet (sheet is now a quick-switcher only); the
        // Wallpapers tab in the command panel is the home for management.
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

    private fun sectionHeading(text: String, dp: Float): View = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#A0A0B8"))
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
    }

    /** Banner that flips between "active" and a CTA to set the system wallpaper.
     *  We can't programmatically set ourselves as wallpaper without the
     *  system-only `SET_WALLPAPER_COMPONENT` permission, so the CTA deep-links
     *  to the system live-wallpaper preview screen.
     *
     *  [dismissParent] is invoked before we leave the activity for the system
     *  picker — without it, the BottomSheetDialog leaks the host activity
     *  (WindowLeaked) when SettingsActivity is paused. */
    private fun makeStatusBanner(dp: Float, dismissParent: () -> Unit): View {
        val wm = WallpaperManager.getInstance(activity)
        val info = wm.wallpaperInfo
        val active = info?.packageName == activity.packageName &&
            info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"

        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.parseColor(if (active) "#1F2BC07A" else "#1FFFFFFF"))
                setStroke((1 * dp).toInt(),
                    Color.parseColor(if (active) "#332BC07A" else "#33FFFFFF"))
            }
            val m = (16 * dp).toInt()
            setPadding(m, (12 * dp).toInt(), m, (12 * dp).toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (16 * dp).toInt(); lp.rightMargin = (16 * dp).toInt()
            layoutParams = lp
        }
        banner.addView(TextView(activity).apply {
            text = if (active) "iappyxOS Live is your wallpaper"
                   else "iappyxOS Live isn't set yet"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
        })
        banner.addView(TextView(activity).apply {
            text = if (active) "Pick a payload below to swap it instantly"
                   else "Tap to open the system wallpaper picker"
            setTextColor(Color.parseColor("#A0A0B8"))
            textSize = 12f
            setPadding(0, (3 * dp).toInt(), 0, 0)
        })
        if (!active) {
            banner.isClickable = true; banner.isFocusable = true
            banner.setOnClickListener {
                // Dismiss the sheet BEFORE starting the system picker — leaving
                // the dialog attached while the activity goes inactive triggers
                // a WindowLeaked log on every tap.
                dismissParent()
                val component = ComponentName(
                    activity, "com.iappyx.launcher.wallpaper.IappyxWallpaperService",
                )
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { activity.startActivity(intent) } catch (_: Throwable) {
                    activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        return banner
    }

    private fun makeRow(
        opt: WallpaperLibrary.Entry,
        dp: Float,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (opt.id == current) GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#22FFFFFF"))
            } else null
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        // No leading badge — the AI-generated section heading already
        // separates user wallpapers from the bundled one, so a per-row glyph
        // is redundant noise.
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
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(text, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        return row
    }

    /** Footer link that bounces back to LauncherActivity with an extra
     *  asking it to open the Wallpapers tab — for users who want full
     *  management (rename, delete, generate, import, export). The launcher's
     *  `singleTask` launchMode + onNewIntent handler reads the extra and
     *  swaps the visible tab. */
    private fun makeManageFooter(dp: Float, dismissParent: () -> Unit): View {
        return TextView(activity).apply {
            text = "Manage wallpapers →"
            setTextColor(com.iappyx.launcher.widget.Palette.accent(activity))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
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
                    putExtra(com.iappyx.launcher.LauncherActivity.EXTRA_OPEN_TAB, "wallpapers")
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }

}
