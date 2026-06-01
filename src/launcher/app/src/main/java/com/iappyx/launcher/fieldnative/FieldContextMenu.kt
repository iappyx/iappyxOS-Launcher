/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Long-press context menu for an app bubble in the native Field. Self-contained
 * (no coupling to AppDrawerPanel internals): Rename, Lock/Unlock, App info,
 * Uninstall. Reuses LauncherPrefs for custom labels + app-lock state.
 */
package com.iappyx.launcher.fieldnative

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed
import com.iappyx.launcher.widget.themeContent

object FieldContextMenu {

    fun show(activity: Activity, anchor: View, x: Int, y: Int, pkg: String, label: String, onChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val prefs = LauncherPrefs(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Palette.bgCell(activity))
                setStroke((1 * density).toInt(), Palette.separator(activity))
            }
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            elevation = 12 * density
        }

        val popup = PopupWindow(container, (220 * density).toInt(), LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * density
        }

        fun item(text: String, onClick: () -> Unit) {
            val tv = TextView(activity).apply {
                this.text = text
                setTextColor(Palette.textPrimary(activity))
                textSize = 15f
                val pad = (12 * density).toInt()
                setPadding(pad, (11 * density).toInt(), pad, (11 * density).toInt())
                isClickable = true
                setOnClickListener { popup.dismiss(); onClick() }
            }
            container.addView(tv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        item("Rename") { showRenameDialog(activity, prefs, pkg, label, onChanged) }

        val locked = prefs.lockedPackages.contains(pkg)
        item(if (locked) "Unlock app" else "Lock app") {
            prefs.setLocked(pkg, !locked)
            onChanged()
        }

        item("App info") {
            try {
                activity.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {}
        }

        item("Uninstall") {
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {}
        }

        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = (220 * density).toInt()
        val h = container.measuredHeight
        val sw = activity.resources.displayMetrics.widthPixels
        val sh = activity.resources.displayMetrics.heightPixels
        val px = x.coerceIn((8 * density).toInt(), sw - w - (8 * density).toInt())
        val py = (y - h / 2).coerceIn((8 * density).toInt(), sh - h - (8 * density).toInt())
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, px, py)
        popup.themeContent()
    }

    private fun showRenameDialog(activity: Activity, prefs: LauncherPrefs, pkg: String, current: String, onChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            setText(current)
            setSelection(text.length)
            val p = (20 * density).toInt()
            setPadding(p, (12 * density).toInt(), p, (12 * density).toInt())
        }
        AlertDialog.Builder(activity)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                prefs.setAppLabel(pkg, if (t.isEmpty()) null else t)
                onChanged()
            }
            .setNeutralButton("Reset") { _, _ -> prefs.setAppLabel(pkg, null); onChanged() }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }
}
