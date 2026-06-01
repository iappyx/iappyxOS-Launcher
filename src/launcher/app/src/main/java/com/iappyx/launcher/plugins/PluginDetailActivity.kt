// PLUGINS: BEGIN
package com.iappyx.launcher.plugins

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed

/**
 * Full details for one plugin: description, capabilities, network
 * restrictions (inline, no dialog), Configure + Uninstall actions.
 *
 * Auto-save model — every radio / SSID change writes to PluginPrefs
 * immediately, no "Save" button. Mirrors how Android's own Settings
 * detail screens behave.
 */
class PluginDetailActivity : AppCompatActivity() {

    private lateinit var pluginId: String
    private lateinit var entry: PluginRegistry.Entry
    private var ssidList: LinearLayout? = null
    private var stateHint: TextView? = null
    private var summaryLine: TextView? = null

    companion object { const val EXTRA_PLUGIN_ID = "plugin_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val e = PluginRegistry.get(this, pluginId)
        if (e == null) { finish(); return }
        entry = e
        setContentView(build())
        com.iappyx.launcher.SettingsScaffold.attach(this, e.manifest.name)
    }

    override fun onResume() {
        super.onResume()
        // If onCreate called finish() because the plugin id was bad,
        // Android STILL dispatches onResume on some versions before
        // tearing down — `entry` is a lateinit that was never assigned,
        // so any access throws UninitializedPropertyAccessException.
        // Guard both finishing state and the lateinit's init flag.
        if (isFinishing || !::entry.isInitialized) return
        // Phone may have moved between Wi-Fi/VPN; refresh the state hint.
        refreshSummaryAndState()
    }

    private fun build(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val bgHome = Palette.bgHome(this)
        val cell = Palette.bgCell(this)
        val text = Palette.textPrimary(this)
        val hint = Palette.textSecondary(this)
        val accent = Palette.accent(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgHome)
            fitsSystemWindows = true
        }
        // Shared toolbar at top — title set by SettingsScaffold.attach
        // after build() returns. Inflate as a peer of the ScrollView so
        // it doesn't scroll with content.
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

        // Version + source line (subtitle under the toolbar title).
        val sourceTag = if (entry.source == PluginRegistry.Source.BUNDLED) "bundled" else "installed by you"
        content.addView(TextView(this).apply {
            this.text = "v${entry.manifest.version} · $sourceTag"
            setTextColor(hint)
            textSize = 13f
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        summaryLine = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, (14 * dp).toInt())
        }
        content.addView(summaryLine)

        // ── Primary actions (Configure / Uninstall) — top-of-screen so
        // the most-common operations are visible immediately, not buried
        // at the bottom of a long scroll.
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (20 * dp).toInt())
        }
        if (entry.manifest.settingsUi != null) {
            actionsRow.addView(com.iappyx.launcher.SettingsButtons.build(
                this, com.iappyx.launcher.SettingsButtonKind.PRIMARY, "Configure",
            ) {
                val i = Intent(this, PluginSettingsActivity::class.java)
                    .putExtra(PluginSettingsActivity.EXTRA_PLUGIN_ID, pluginId)
                startActivity(i)
            })
        }
        if (entry.source == PluginRegistry.Source.USER) {
            if (actionsRow.childCount > 0) {
                actionsRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams((10 * dp).toInt(), 1)
                })
            }
            actionsRow.addView(com.iappyx.launcher.SettingsButtons.build(
                this, com.iappyx.launcher.SettingsButtonKind.DANGER, "Uninstall",
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Uninstall ${entry.manifest.name}?")
                    .setMessage("This removes the plugin and wipes all of its storage (including any credentials it stored).")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Uninstall") { _, _ ->
                        val ok = PluginsModule.uninstall(this, pluginId)
                        Toast.makeText(this,
                            if (ok) "Uninstalled." else "Uninstall failed.",
                            Toast.LENGTH_SHORT).show()
                        if (ok) finish()
                    }
                    .showThemed()
            })
        }
        if (actionsRow.childCount > 0) content.addView(actionsRow)

        // ── Description ──────────────────────────────────────
        if (entry.manifest.description.isNotBlank()) {
            content.addView(sectionHeader("DESCRIPTION", hint, dp))
            content.addView(card(cell, dp).apply {
                addView(TextView(this@PluginDetailActivity).apply {
                    this.text = entry.manifest.description
                    setTextColor(text); textSize = 13f
                    setLineSpacing(0f, 1.35f)
                })
            })
            content.addView(spacer(dp, 16))
        }

        // ── Capabilities ─────────────────────────────────────
        if (entry.manifest.capabilities.isNotEmpty()) {
            content.addView(sectionHeader("CAPABILITIES", hint, dp))
            content.addView(card(cell, dp).apply {
                val capsRow = LinearLayout(this@PluginDetailActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (cap in entry.manifest.capabilities) {
                    capsRow.addView(capChip(cap, dp))
                }
                addView(capsRow)
            })
            content.addView(spacer(dp, 16))
        }

        // ── Universal search exposure ───────────────────────
        // Only relevant when the plugin actually declares `search` in
        // its manifest — otherwise the toggle would be meaningless and
        // the row just clutters the detail screen.
        // Show the "Expose to universal search" toggle only for plugins
        // that actually declare `universalSearch` (the renamed convention
        // — separate from GitHub-style REST-API `search`).
        if (entry.manifest.exposes.contains("universalSearch")) {
            content.addView(sectionHeader("UNIVERSAL SEARCH", hint, dp))
            content.addView(buildSearchExposureCard(cell, text, hint, accent, dp))
            content.addView(spacer(dp, 16))
        }

        // ── Network restrictions ─────────────────────────────
        content.addView(sectionHeader("NETWORK RESTRICTIONS", hint, dp))
        content.addView(buildNetworkCard(cell, text, hint, accent, dp))

        refreshSummaryAndState()
        return root
    }

    /** Filled accent-colored button for the primary action (Configure).
     *  Visually heavier than the outline [actionButton] so users see it
     *  first when they land on the detail screen. */
    private fun primaryActionButton(text: String, accent: Int, dp: Float,
                                    onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(accent)
            }
            val h = (20 * dp).toInt(); val v = (12 * dp).toInt()
            setPadding(h, v, h, v)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /** Universal-search exposure card. One toggle: "Expose to universal
     *  search". When unchecked, the plugin is excluded from search-panel
     *  fan-outs (its results stop appearing when the user types in the
     *  home-screen search). Default is exposed-on, stored as an EXCLUSION
     *  set in LauncherPrefs so the common case (everyone participates) is
     *  represented by an empty set. */
    private fun buildSearchExposureCard(
        cellBg: Int, textColor: Int, hintColor: Int, accent: Int, dp: Float,
    ): View {
        val card = card(cellBg, dp)
        val prefs = com.iappyx.launcher.LauncherPrefs(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { weight = 1f }
        }
        labels.addView(TextView(this).apply {
            text = "Expose to universal search"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        labels.addView(TextView(this).apply {
            text = "Allow this plugin's items to appear when you type " +
                "in the home-screen search panel. Disabling stops " +
                "the launcher from querying this plugin during search."
            setTextColor(hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        row.addView(labels)
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = prefs.isSearchExposed(pluginId)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setSearchExposed(pluginId, isChecked)
            }
        }
        row.addView(sw)
        card.addView(row)
        return card
    }


    /** Build the network-restrictions card with mode picker + SSID
     *  management + live phone state. Auto-save: every change writes to
     *  PluginPrefs immediately. */
    private fun buildNetworkCard(
        cellBg: Int, textColor: Int, hintColor: Int, accent: Int, dp: Float,
    ): View {
        val card = card(cellBg, dp)

        // Mode label + radio group
        card.addView(TextView(this).apply {
            this.text = "Run on"
            setTextColor(hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, (6 * dp).toInt())
        })
        val modes = listOf(
            "always" to "Always (no restriction)",
            "trusted_wifi" to "Trusted Wi-Fi only",
            "vpn" to "VPN connection active",
            "trusted_wifi_or_vpn" to "Trusted Wi-Fi or VPN",
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val radioMap = mutableMapOf<String, RadioButton>()
        for ((value, label) in modes) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                this.text = label
                setTextColor(textColor)
            }
            radioMap[value] = rb
            group.addView(rb)
        }
        val currentMode = PluginPrefs.networkRestriction(
            this, pluginId, entry.manifest.defaultNetworkRestriction)
        (radioMap[currentMode] ?: radioMap["always"])?.let { group.check(it.id) }
        group.setOnCheckedChangeListener { _, checkedId ->
            val match = radioMap.entries.firstOrNull { it.value.id == checkedId }
            if (match != null) {
                PluginsModule.setNetworkRestrictionMode(this, pluginId, match.key)
                refreshSummaryAndState()
            }
        }
        card.addView(group)

        // Trusted SSID label + list
        card.addView(TextView(this).apply {
            this.text = "Trusted Wi-Fi networks"
            setTextColor(hintColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ssidList = list
        card.addView(list)
        renderSsids(textColor, hintColor, dp)

        // Manual SSID input + Add current.
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val input = EditText(this).apply {
            this.hint = "Type SSID and press Enter"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setTextColor(textColor)
            setHintTextColor(hintColor)
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#0A0A10"))
                setStroke((1 * dp).toInt(), Color.parseColor("#2A2A36"))
            }
            val h = (12 * dp).toInt(); val v = (10 * dp).toInt()
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val s = this.text.toString().trim()
                    if (s.isNotEmpty()) {
                        addSsid(s)
                        this.text.clear()
                    }
                    true
                } else false
            }
        }
        addRow.addView(input)
        val addBtn = com.iappyx.launcher.SettingsButtons.build(
            this, com.iappyx.launcher.SettingsButtonKind.SECONDARY, "Add",
        ) {
            val s = input.text.toString().trim()
            if (s.isNotEmpty()) {
                addSsid(s)
                input.text.clear()
            }
        }
        (addBtn.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.leftMargin = (8 * dp).toInt()
        }
        addRow.addView(addBtn)
        card.addView(addRow)

        // "Add current SSID" affordance.
        val currentSsid = PluginNetworkTrust.currentSsid(this)
        if (currentSsid != null) {
            card.addView(TextView(this).apply {
                this.text = "Currently on: $currentSsid"
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
            })
            card.addView(com.iappyx.launcher.SettingsButtons.build(
                this, com.iappyx.launcher.SettingsButtonKind.SECONDARY, "Add current",
            ) { addSsid(currentSsid) })
        } else {
            card.addView(TextView(this).apply {
                this.text = "Not connected to Wi-Fi (or location permission denied)."
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (10 * dp).toInt(), 0, 0)
            })
        }

        // Live phone state hint.
        stateHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        card.addView(stateHint)

        return card
    }

    private fun renderSsids(textColor: Int, hintColor: Int, dp: Float) {
        val list = ssidList ?: return
        list.removeAllViews()
        val ssids = PluginPrefs.trustedSsids(this, pluginId)
        if (ssids.isEmpty()) {
            list.addView(TextView(this).apply {
                this.text = "No trusted networks yet — add one below."
                setTextColor(hintColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            })
            return
        }
        for (ssid in ssids) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                this.text = ssid
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                this.text = "Remove"
                setTextColor(Color.parseColor("#FF5252"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isClickable = true; isFocusable = true
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                setOnClickListener {
                    val next = PluginPrefs.trustedSsids(this@PluginDetailActivity, pluginId)
                        .toMutableList().apply { remove(ssid) }
                    PluginsModule.setTrustedSsids(this@PluginDetailActivity, pluginId, next)
                    renderSsids(textColor, hintColor, dp)
                    refreshSummaryAndState()
                }
            })
            list.addView(row)
        }
    }

    private fun addSsid(s: String) {
        val current = PluginPrefs.trustedSsids(this, pluginId).toMutableList()
        if (!current.contains(s)) current.add(s)
        PluginsModule.setTrustedSsids(this, pluginId, current)
        renderSsids(Palette.textPrimary(this), Palette.textSecondary(this),
            resources.displayMetrics.density)
        refreshSummaryAndState()
    }

    /** Update the title-area restriction summary + the bottom phone-state
     *  hint. Called on every state change so the user sees the effect
     *  instantly. */
    private fun refreshSummaryAndState() {
        val mode = PluginPrefs.networkRestriction(
            this, pluginId, entry.manifest.defaultNetworkRestriction)
        val (text, color) = PluginsActivity.restrictionSummary(this, entry.manifest, mode)
        summaryLine?.text = text
        summaryLine?.setTextColor(Color.parseColor(color))

        val ssid = PluginNetworkTrust.currentSsid(this)
        val onVpn = PluginNetworkTrust.onVpn(this)
        val verdict = PluginNetworkTrust.evaluate(this, entry.manifest)
        val parts = listOf(
            "Wi-Fi: ${ssid ?: "not connected"}",
            "VPN: " + if (onVpn) "active" else "inactive",
            "Plugin allowed now: " + if (verdict.allowed) "yes" else "no",
        )
        stateHint?.text = parts.joinToString("\n")
        stateHint?.setTextColor(
            if (verdict.allowed) Color.parseColor("#66BB6A")
            else Color.parseColor("#8E8E97")
        )
    }

    // ── Builders ───────────────────────────────────────────

    private fun sectionHeader(label: String, hint: Int, dp: Float): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.07f
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
    }

    private fun card(bg: Int, dp: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(bg)
                setStroke((1 * dp).toInt(), Color.parseColor("#2A2A36"))
            }
        }
    }

    private fun capChip(capability: String, dp: Float): View {
        return TextView(this).apply {
            text = PluginCapability.chipLabel(capability)
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@PluginDetailActivity))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setStroke((1 * dp).toInt(), com.iappyx.launcher.widget.Palette.accentAlpha(this@PluginDetailActivity, 0x66))
                setColor(com.iappyx.launcher.widget.Palette.accentAlpha(this@PluginDetailActivity, 0x11))
            }
            val hp = (8 * dp).toInt(); val vp = (3 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = (6 * dp).toInt() }
        }
    }

    private fun actionButton(
        text: String, color: Int, dp: Float,
        stroke: String = "#2A2A36",
        onClick: () -> Unit,
    ): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setStroke((1 * dp).toInt(), Color.parseColor(stroke))
            }
            val h = (16 * dp).toInt(); val v = (10 * dp).toInt()
            setPadding(h, v, h, v)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun spacer(dp: Float, h: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (h * dp).toInt(),
            )
        }
    }
}
// PLUGINS: END
