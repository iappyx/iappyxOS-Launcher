/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed

/**
 * Settings → About. Header + license + source + acknowledgements + privacy.
 * Programmatic UI to match the other leaf activities in this codebase
 * (NearbyReceive, ShowcaseBrowser etc.) — keeps it diff-light and consistent.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        com.iappyx.launcher.SettingsScaffold.attach(
            this, getString(com.iappyx.launcher.R.string.settings_about_label),
        )
    }

    private fun buildView(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val palBgHome = Palette.bgHome(this)
        val palBgCell = Palette.bgCell(this)
        val palText = Palette.textPrimary(this)
        val palSecondary = Palette.textSecondary(this)
        val palAccent = Palette.accent(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palBgHome)
            fitsSystemWindows = true
        }
        // Shared settings toolbar — back arrow + title position
        // matches every other settings screen.
        val toolbar = android.view.LayoutInflater.from(this)
            .inflate(com.iappyx.launcher.R.layout.settings_toolbar, root, false)
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).apply { weight = 1f }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Header card: icon + name + version + tagline ─────────
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(palBgCell)
            }
            setPadding(pad, pad, pad, pad)
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconRow.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt())
        })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        nameCol.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(palText)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        nameCol.addView(TextView(this).apply {
            // Read versionName/versionCode from PackageInfo rather than
            // BuildConfig so we don't depend on `buildFeatures.buildConfig
            // true` in build.gradle. PackageInfo is the authoritative
            // source either way.
            val info = packageManager.getPackageInfo(packageName, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            text = getString(
                R.string.about_version_format,
                info.versionName ?: "?", versionCode,
            )
            setTextColor(palSecondary)
            textSize = 13f
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        iconRow.addView(nameCol)
        headerCard.addView(iconRow)

        headerCard.addView(TextView(this).apply {
            text = getString(R.string.about_tagline)
            setTextColor(palSecondary)
            textSize = 14f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })
        content.addView(headerCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (4 * dp).toInt(); bottomMargin = (16 * dp).toInt() })

        // ── License + source + issues + acknowledgements ─────────
        val rowsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(palBgCell)
            }
            setPadding(pad, (8 * dp).toInt(), pad, (8 * dp).toInt())
        }
        rowsCard.addView(makeRow(
            getString(R.string.about_license_label),
            getString(R.string.about_license_value),
            palText, palSecondary, palAccent, dp,
        ) { showLicenseDialog(getString(R.string.about_license_label), LicenseTexts.MIT) })
        rowsCard.addView(makeDivider(dp))
        rowsCard.addView(makeRow(
            getString(R.string.about_source_label),
            getString(R.string.about_source_url),
            palText, palSecondary, palAccent, dp,
        ) { copyOrOpenUrl("https://" + getString(R.string.about_source_url)) })
        rowsCard.addView(makeDivider(dp))
        rowsCard.addView(makeRow(
            getString(R.string.about_acknowledgements_label),
            getString(
                R.string.about_acknowledgements_count_format,
                Acknowledgements.ALL.size,
            ),
            palText, palSecondary, palAccent, dp,
        ) {
            startActivity(Intent(this, AcknowledgementsActivity::class.java))
        })
        rowsCard.addView(makeDivider(dp))
        rowsCard.addView(makeRow(
            getString(R.string.about_support_label),
            getString(R.string.about_support_value),
            palText, palSecondary, palAccent, dp,
        ) { copyOrOpenUrl(getString(R.string.about_support_url)) })
        content.addView(rowsCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (16 * dp).toInt() })

        // ── Privacy section ──────────────────────────────────────
        val privacyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(palBgCell)
            }
            setPadding(pad, pad, pad, pad)
        }
        privacyCard.addView(TextView(this).apply {
            text = getString(R.string.about_privacy_title)
            setTextColor(palText)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        privacyCard.addView(TextView(this).apply {
            text = getString(R.string.about_privacy_body)
            setTextColor(palSecondary)
            textSize = 13f
            setLineSpacing(0f, 1.25f)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        content.addView(privacyCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (24 * dp).toInt() })

        // ── Footer ───────────────────────────────────────────────
        content.addView(TextView(this).apply {
            text = getString(R.string.about_footer)
            setTextColor(palSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        return root
    }

    private fun makeRow(
        label: String,
        value: String,
        labelColor: Int,
        valueColor: Int,
        accentColor: Int,
        dp: Float,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = ContextCompat_selectable(this@AboutActivity)
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = label
            setTextColor(labelColor)
            textSize = 14f
        })
        col.addView(TextView(this).apply {
            text = value
            setTextColor(valueColor)
            textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = getString(R.string.chevron_right)
            setTextColor(valueColor)
            textSize = 22f
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        })
        return row
    }

    private fun makeDivider(dp: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt(),
        )
        setBackgroundColor(Color.parseColor("#22FFFFFF"))
    }

    private fun showLicenseDialog(title: String, text: String) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Palette.textPrimary(this@AboutActivity))
            typeface = Typeface.MONOSPACE
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton(R.string.action_ok, null)
            .showThemed()
    }

    private fun copyOrOpenUrl(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            // No browser? Copy to clipboard as a fallback so the URL
            // doesn't just vanish on tap.
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("iappyx URL", url))
            Toast.makeText(this, R.string.about_url_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ContextCompat_selectable(c: Context) =
        c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .let { it.getDrawable(0).also { _ -> it.recycle() } }
}
