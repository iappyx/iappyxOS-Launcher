/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.content.Intent
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
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.NearbyReceiveActivity
import com.iappyx.launcher.NearbySendActivity
import com.iappyx.launcher.QRReceiveActivity
import com.iappyx.launcher.QRSendActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.dismissOnActivityDestroy
import com.iappyx.launcher.widget.expandFully
import com.iappyx.launcher.widget.themeContent

/**
 * Bottom-sheet pickers for the three sharing flows. Same Material-You row
 * shape as [com.iappyx.launcher.widget.AddToHomeSheet] so the sharing UX
 * matches the rest of the launcher's bottom-sheet language.
 *
 *  - [ShareSheet]   — pick "Save to file", "Share Nearby", or "Share via QR"
 *  - [ReceiveSheet] — pick "From file", "Receive Nearby", or "Receive via QR"
 *
 * Both sheets are kind-agnostic; the manage tabs pass the artefact id +
 * kind in, the sheets dispatch to the right activity / helper.
 */

private data class Option(
    @DrawableRes val iconRes: Int,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

class ShareSheet(
    private val activity: LauncherActivity,
    private val kind: ArtefactBundle.Kind,
    private val artefactId: String,
) {
    fun show() {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * dp).toInt(), (16 * dp).toInt(),
                (20 * dp).toInt(), (24 * dp).toInt(),
            )
            setBackgroundColor(Palette.bgHome(activity))
        }
        addGrabber(root, dp)
        addHeader(root, dp,
            title = activity.getString(com.iappyx.launcher.R.string.share_sheet_title_format, kind.label),
            subtitle = activity.getString(com.iappyx.launcher.R.string.share_sheet_subtitle),
        )

        val options = listOf(
            Option(R.drawable.ic_file_upload,
                activity.getString(com.iappyx.launcher.R.string.share_save_to_file),
                subtitle = activity.getString(com.iappyx.launcher.R.string.share_save_to_file_subtitle),
            ) {
                dialog.dismiss()
                try {
                    when (kind) {
                        ArtefactBundle.Kind.WIDGET       -> ShareHelper.shareWidget(activity, artefactId)
                        ArtefactBundle.Kind.WALLPAPER    -> ShareHelper.shareWallpaper(activity, artefactId)
                        ArtefactBundle.Kind.TRANSITION   -> ShareHelper.shareTransition(activity, artefactId)
                        ArtefactBundle.Kind.ICON_FILTER  -> ShareHelper.shareIconFilter(activity, artefactId)
                        // PLUGINS: BEGIN — share-to-file for plugins
                        // not surfaced from these sheets (user installs
                        // via .iappyxplugin file directly or via the
                        // editor's showcase).
                        ArtefactBundle.Kind.PLUGIN       -> throw IllegalArgumentException(
                            "Plugin sharing via the share sheet isn't supported."
                        )
                        // PLUGINS: END
                    }
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(activity,
                        activity.getString(com.iappyx.launcher.R.string.share_couldnt_share_format, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG).show()
                }
            },
            Option(R.drawable.ic_smartphone,
                activity.getString(com.iappyx.launcher.R.string.share_nearby_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.share_nearby_subtitle),
            ) {
                dialog.dismiss()
                activity.startActivity(Intent(activity, NearbySendActivity::class.java)
                    .putExtra(NearbySendActivity.EXTRA_KIND, kind.label)
                    .putExtra(NearbySendActivity.EXTRA_ID, artefactId))
            },
            Option(R.drawable.ic_grid_view,
                activity.getString(com.iappyx.launcher.R.string.share_via_qr_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.share_via_qr_subtitle),
            ) {
                dialog.dismiss()
                activity.startActivity(Intent(activity, QRSendActivity::class.java)
                    .putExtra(QRSendActivity.EXTRA_KIND, kind.label)
                    .putExtra(QRSendActivity.EXTRA_ID, artefactId))
            },
            Option(R.drawable.ic_auto_awesome,
                activity.getString(com.iappyx.launcher.R.string.share_submit_showcase_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.share_submit_showcase_subtitle_format, kind.label),
            ) {
                dialog.dismiss()
                ShowcaseSubmitDialog(activity, kind, artefactId).show()
            },
        )
        addOptionList(root, dp, options)
        dialog.setContentView(root)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }
}

class ReceiveSheet(
    private val activity: LauncherActivity,
    private val kindLabel: String,
    private val onFromFile: () -> Unit,
) {
    fun show() {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * dp).toInt(), (16 * dp).toInt(),
                (20 * dp).toInt(), (24 * dp).toInt(),
            )
            setBackgroundColor(Palette.bgHome(activity))
        }
        addGrabber(root, dp)
        addHeader(root, dp,
            title = activity.getString(com.iappyx.launcher.R.string.receive_sheet_title_format, kindLabel),
            subtitle = activity.getString(com.iappyx.launcher.R.string.receive_sheet_subtitle),
        )
        val options = listOf(
            Option(R.drawable.ic_file_download,
                activity.getString(com.iappyx.launcher.R.string.receive_from_file_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.receive_from_file_subtitle),
            ) { dialog.dismiss(); onFromFile() },
            Option(R.drawable.ic_smartphone,
                activity.getString(com.iappyx.launcher.R.string.receive_nearby_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.receive_nearby_subtitle),
            ) {
                dialog.dismiss()
                activity.startActivity(Intent(activity, NearbyReceiveActivity::class.java))
            },
            Option(R.drawable.ic_grid_view,
                activity.getString(com.iappyx.launcher.R.string.receive_via_qr_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.receive_via_qr_subtitle),
            ) {
                dialog.dismiss()
                activity.startActivity(Intent(activity, QRReceiveActivity::class.java))
            },
            Option(R.drawable.ic_auto_awesome,
                activity.getString(com.iappyx.launcher.R.string.receive_browse_showcase_title),
                subtitle = activity.getString(com.iappyx.launcher.R.string.receive_browse_showcase_subtitle_format, kindLabel),
            ) {
                dialog.dismiss()
                val kindKey = when (kindLabel.lowercase()) {
                    "wallpaper" -> "wallpaper"
                    "transition" -> "transition"
                    "icon style" -> "icon_filter"
                    else -> "widget"
                }
                activity.startActivity(Intent(activity,
                    com.iappyx.launcher.ShowcaseBrowserActivity::class.java)
                    .putExtra(com.iappyx.launcher.ShowcaseBrowserActivity.EXTRA_KIND, kindKey))
            },
        )
        addOptionList(root, dp, options)
        dialog.setContentView(root)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }
}

// ── Shared bottom-sheet helpers ─────────────────────────────────────

private fun addGrabber(root: LinearLayout, dp: Float) {
    root.addView(View(root.context).apply {
        background = GradientDrawable().apply {
            cornerRadius = 2 * dp
            setColor(ColorUtils.setAlphaComponent(Palette.textPrimary(root.context), 0x44))
        }
        val lp = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = (16 * dp).toInt()
        layoutParams = lp
    })
}

private fun addHeader(root: LinearLayout, dp: Float, title: String, subtitle: String) {
    root.addView(TextView(root.context).apply {
        text = title
        setTextColor(Palette.textPrimary(root.context))
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (4 * dp).toInt())
    })
    root.addView(TextView(root.context).apply {
        text = subtitle
        setTextColor(Palette.textSecondary(root.context))
        textSize = 13f
        setPadding(0, 0, 0, (16 * dp).toInt())
    })
}

private fun addOptionList(root: LinearLayout, dp: Float, options: List<Option>) {
    for ((idx, opt) in options.withIndex()) {
        root.addView(makeOptionRow(root, dp, opt))
        if (idx < options.lastIndex) {
            root.addView(View(root.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt(),
                )
            })
        }
    }
}

private fun makeOptionRow(root: LinearLayout, dp: Float, opt: Option): LinearLayout {
    val context = root.context
    val accent = Palette.accent(context)
    val cellBg = Palette.bgCell(context)
    val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(cellBg)
            setStroke((1 * dp).toInt(),
                ColorUtils.setAlphaComponent(Palette.textPrimary(context), 0x22))
        }
        val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        isClickable = true; isFocusable = true
        setOnClickListener { opt.onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
    card.addView(ImageView(context).apply {
        setImageResource(opt.iconRes)
        imageTintList = ColorStateList.valueOf(accent)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accent, 0x33))
        }
        val s = (44 * dp).toInt(); val pad = (10 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            marginEnd = (14 * dp).toInt()
        }
    })
    val text = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    text.addView(TextView(context).apply {
        this.text = opt.title
        setTextColor(Palette.textPrimary(context))
        textSize = 15f; setTypeface(typeface, Typeface.BOLD)
    })
    text.addView(TextView(context).apply {
        this.text = opt.subtitle
        setTextColor(Palette.textSecondary(context))
        textSize = 12f
        setPadding(0, (2 * dp).toInt(), 0, 0)
    })
    card.addView(text)
    return card
}
