// PLUGINS: BEGIN
package com.iappyx.launcher.plugins

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.ShowcaseBrowserActivity
import com.iappyx.launcher.widget.Palette

/**
 * Top-level Plugins screen, opened from Settings → Plugins.
 *
 * Designed as a slim list: each row shows name + version + restriction
 * summary + enable switch. Tap a row → [PluginDetailActivity] for the
 * full controls (description, capabilities, network restrictions,
 * configure, uninstall).
 *
 * The slim-row design replaced the older inline plugin section that
 * crammed every plugin's full details into Settings. With a few plugins
 * installed, the new list fits on one screen.
 */
class PluginsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        com.iappyx.launcher.SettingsScaffold.attach(
            this, getString(com.iappyx.launcher.R.string.settings_plugins_label),
        )
    }

    override fun onResume() {
        super.onResume()
        // Re-render so installs/uninstalls/configures done elsewhere (or
        // showcase browser returning) are reflected immediately.
        renderList()
    }

    private fun build(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val bgHome = Palette.bgHome(this)
        val text = Palette.textPrimary(this)
        val hint = Palette.textSecondary(this)
        val accent = Palette.accent(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgHome)
            fitsSystemWindows = true
        }
        // Shared toolbar — back arrow + title in the bar, same as every
        // XML-based settings screen.
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

        content.addView(TextView(this).apply {
            this.text = "Extend the launcher with installable JavaScript modules. " +
                "Plugins run sandboxed; capabilities are granted at install."
            setTextColor(hint)
            textSize = 13f
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)

        // Bottom action — Browse showcase.
        content.addView(actionRow(dp, accent))

        return root
    }

    private fun actionRow(dp: Float, accent: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (18 * dp).toInt(), 0, 0)
            addView(com.iappyx.launcher.SettingsButtons.build(
                this@PluginsActivity,
                com.iappyx.launcher.SettingsButtonKind.PRIMARY,
                "Browse showcase",
            ) {
                val intent = Intent(this@PluginsActivity, ShowcaseBrowserActivity::class.java)
                    .putExtra(ShowcaseBrowserActivity.EXTRA_KIND, "plugin")
                startActivity(intent)
            })
        }
    }

    private fun renderList() {
        val dp = resources.displayMetrics.density
        val text = Palette.textPrimary(this)
        val hint = Palette.textSecondary(this)
        val cell = Palette.bgCell(this)

        listContainer.removeAllViews()
        val entries = PluginRegistry.all(this)
        if (entries.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                this.text = "No plugins installed yet."
                setTextColor(hint)
                textSize = 14f
                setPadding(0, 0, 0, 0)
            })
            return
        }
        for (entry in entries) {
            listContainer.addView(buildRow(entry, dp, cell, text, hint))
            listContainer.addView(Space(this, (10 * dp).toInt()))
        }
    }

    /** Slim row: name, version, restriction summary, enable switch.
     *  Whole row is clickable → opens detail. The switch consumes its
     *  own touches so toggling doesn't navigate. */
    private fun buildRow(
        entry: PluginRegistry.Entry,
        dp: Float,
        cellBg: Int,
        textColor: Int,
        hintColor: Int,
    ): View {
        val m = entry.manifest
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(cellBg)
                setStroke((1 * dp).toInt(), Color.parseColor("#2A2A36"))
            }
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            setOnClickListener {
                val i = Intent(this@PluginsActivity, PluginDetailActivity::class.java)
                    .putExtra(PluginDetailActivity.EXTRA_PLUGIN_ID, m.id)
                startActivity(i)
            }
        }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mainCol.addView(TextView(this).apply {
            this.text = m.name
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        })
        val sourceTag = if (entry.source == PluginRegistry.Source.BUNDLED) "bundled" else "installed"
        mainCol.addView(TextView(this).apply {
            this.text = "v${m.version} · $sourceTag"
            setTextColor(hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, (1 * dp).toInt(), 0, 0)
        })
        // Restriction summary (always rendered, subdued for "always").
        val mode = PluginPrefs.networkRestriction(this, m.id, m.defaultNetworkRestriction)
        val (summaryText, summaryColor) = restrictionSummary(this, m, mode)
        mainCol.addView(TextView(this).apply {
            this.text = summaryText
            setTextColor(Color.parseColor(summaryColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        row.addView(mainCol)

        // Enable switch — its own touch target so toggling doesn't open detail.
        val sw = Switch(this).apply {
            isChecked = entry.enabled
            isClickable = true; isFocusable = true
            setOnCheckedChangeListener { _, checked ->
                PluginRegistry.setEnabled(this@PluginsActivity, m.id, checked)
                if (!checked) {
                    try { PluginHost.shutdownPlugin(this@PluginsActivity, m.id) } catch (_: Throwable) {}
                }
            }
        }
        row.addView(sw)

        // Chevron.
        row.addView(TextView(this).apply {
            this.text = "›"
            setTextColor(hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })

        return row
    }

    /** Shared helper for the restriction summary text + color. Used by
     *  this list view AND [PluginDetailActivity]'s header so they stay
     *  identical when restrictions change. */
    internal companion object {
        fun restrictionSummary(
            ctx: android.content.Context,
            manifest: PluginManifest,
            mode: String,
        ): Pair<String, String> {
            if (mode == "always") {
                return "🌐 Always on (no network restriction)" to "#8E8E97"
            }
            val verdict = PluginNetworkTrust.evaluate(ctx, manifest)
            val ssidCount = PluginPrefs.trustedSsids(ctx, manifest.id).size
            val modeLabel = when (mode) {
                "trusted_wifi" -> "Trusted Wi-Fi only"
                "vpn" -> "VPN only"
                "trusted_wifi_or_vpn" -> "Trusted Wi-Fi or VPN"
                else -> mode
            }
            val parts = mutableListOf(modeLabel)
            if (mode == "trusted_wifi" || mode == "trusted_wifi_or_vpn") {
                parts.add(if (ssidCount == 1) "1 network" else "$ssidCount networks")
            }
            parts.add(if (verdict.allowed) "allowed now" else "blocked now")
            val color = if (verdict.allowed) "#66BB6A" else "#FF5252"
            return "🔒 " + parts.joinToString(" · ") to color
        }
    }

    private class Space(ctx: android.content.Context, h: Int) : View(ctx) {
        init {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h
            )
        }
    }
}
// PLUGINS: END
