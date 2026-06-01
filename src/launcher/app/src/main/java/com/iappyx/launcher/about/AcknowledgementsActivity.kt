/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.about

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed

/**
 * Settings → About → Open-source libraries. One row per [Acknowledgement];
 * tap a row to open a dialog with that library's full license text. The
 * dialog also shows the per-library copyright line, which the bundled
 * canonical license text doesn't carry on its own.
 */
class AcknowledgementsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    private fun buildView(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val palBgHome = Palette.bgHome(this)
        val palBgCell = Palette.bgCell(this)
        val palText = Palette.textPrimary(this)
        val palSecondary = Palette.textSecondary(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palBgHome)
            fitsSystemWindows = true
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Title ────
        content.addView(TextView(this).apply {
            text = getString(R.string.ack_title)
            setTextColor(palText)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        // ── Library rows in one card ────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(palBgCell)
            }
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }
        Acknowledgements.ALL.forEachIndexed { i, ack ->
            if (i > 0) {
                card.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt(),
                    )
                    setBackgroundColor(Color.parseColor("#22FFFFFF"))
                })
            }
            card.addView(makeAckRow(ack, palText, palSecondary, dp))
        }
        content.addView(card)

        return root
    }

    private fun makeAckRow(
        ack: Acknowledgement,
        labelColor: Int,
        secondaryColor: Int,
        dp: Float,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isClickable = true
            isFocusable = true
            background = selectableBackground(this@AcknowledgementsActivity)
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { showLicenseDialog(ack) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = ack.name
            setTextColor(labelColor)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = ack.description
            setTextColor(secondaryColor)
            textSize = 12f
            setLineSpacing(0f, 1.25f)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        row.addView(col)
        // License-kind chip on the right
        row.addView(TextView(this).apply {
            text = licenseLabel(ack.licenseKind)
            setTextColor(secondaryColor)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            val ph = (10 * dp).toInt(); val pv = (4 * dp).toInt()
            setPadding(ph, pv, ph, pv)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (2 * dp).toInt()
            lp.marginStart = (10 * dp).toInt()
            layoutParams = lp
        })
        return row
    }

    private fun licenseLabel(kind: LicenseKind): String = getString(
        when (kind) {
            LicenseKind.APACHE2 -> R.string.ack_license_apache2
            LicenseKind.BSD2 -> R.string.ack_license_bsd2
            LicenseKind.BSD3 -> R.string.ack_license_bsd3
            LicenseKind.LGPL21 -> R.string.ack_license_lgpl21
            LicenseKind.MIT -> R.string.ack_license_mit
            LicenseKind.OFL -> R.string.ack_license_ofl
        },
    )

    private fun showLicenseDialog(ack: Acknowledgement) {
        val dp = resources.displayMetrics.density
        val canonical = when (ack.licenseKind) {
            LicenseKind.APACHE2 -> LicenseTexts.APACHE2
            LicenseKind.BSD2 -> LicenseTexts.BSD2
            LicenseKind.BSD3 -> LicenseTexts.BSD3
            LicenseKind.LGPL21 -> LicenseTexts.LGPL21_SUMMARY
            LicenseKind.MIT -> LicenseTexts.MIT
            LicenseKind.OFL -> LicenseTexts.OFL
        }
        val full = "${ack.copyrightLine}\n\n$canonical"
        val tv = TextView(this).apply {
            text = full
            textSize = 11f
            setTextColor(Palette.textPrimary(this@AcknowledgementsActivity))
            typeface = Typeface.MONOSPACE
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(ack.name)
            .setView(sv)
            .setPositiveButton(R.string.action_ok, null)
            .showThemed()
    }

    private fun selectableBackground(c: Context) =
        c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .let { it.getDrawable(0).also { _ -> it.recycle() } }
}
