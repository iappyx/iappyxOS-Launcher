/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Widget theme editor. Edits a map of --iappyx-* overrides on top of the
 * Material-You palette + design defaults (see GeneratedWidgetCell.effectiveTokens
 * / ThemeOverrides). A live preview renders a sample widget with the working
 * tokens; on close the overrides are saved and LauncherActivity live-pushes them
 * to every widget. Empty overrides = pure Material You.
 *
 * Beyond the built-in presets it supports user-saved presets ([ThemePresets])
 * and import / export of a theme as a shareable string.
 *
 * Uses the shared settings scaffold (toolbar + fitsSystemWindows) so it matches
 * every other settings detail screen — the static chrome is in
 * res/layout/activity_theme_editor.xml; the dynamic controls are added into the
 * theme_controls container programmatically.
 */
package com.iappyx.launcher.theme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.SettingsScaffold
import com.iappyx.launcher.cells.GeneratedWidgetCell
import com.iappyx.launcher.widget.showThemed

class ThemeEditorActivity : AppCompatActivity() {

    private val MORE_FONTS = "Browse all ›"
    private val working = LinkedHashMap<String, String>()
    private lateinit var preview: WebView
    private lateinit var controls: LinearLayout
    private lateinit var presetRow: LinearLayout
    private val d get() = resources.displayMetrics.density

    private val fontPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            res.data?.getStringExtra("stack")?.let {
                working["--iappyx-font"] = it
                rebuildControls()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        working.putAll(ThemeOverrides.get(this))

        setContentView(R.layout.activity_theme_editor)
        SettingsScaffold.attach(this, getString(R.string.settings_widget_theme_label))

        preview = findViewById(R.id.theme_preview)
        preview.setBackgroundColor(Color.TRANSPARENT)
        preview.settings.javaScriptEnabled = false
        // Serve bundled theme fonts to the preview so picked fonts actually show.
        preview.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                val url = request.url
                if (url.host == "widget.local" && url.path?.startsWith("/__themefont/") == true) {
                    val file = url.lastPathSegment ?: return null
                    val stream = ThemeFonts.openFontStream(this@ThemeEditorActivity, file) ?: return null
                    return android.webkit.WebResourceResponse("font/ttf", null, stream)
                }
                return null
            }
        }
        controls = findViewById(R.id.theme_controls)

        buildControls()
        refresh()
    }

    private fun buildControls() {
        controls.removeAllViews()

        controls.addView(label("Preset"))
        controls.addView(scroll(chips(listOf("Material You", "Glass", "Sharp", "Bold"), if (working.isEmpty()) "Material You" else null) { applyPreset(it) }))

        controls.addView(label("My presets"))
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(scroll(presetRow))
        rebuildPresetChips()

        controls.addView(label("Accent"))
        controls.addView(scroll(swatches(listOf("#5b8cff", "#46d39a", "#ff7a59", "#c06bff", "#ffd23f", "#ff5d8f", "#22c1c3", "#cfd3da"), working["--iappyx-primary"]) {
            working["--iappyx-primary"] = it
            working["--iappyx-on-primary"] = if (luminance(it) > 0.6) "#11131a" else "#ffffff"
            refresh()
        }))

        controls.addView(label("Font"))
        val downloaded = FontCatalog.all(this).filter { FontCatalog.isDownloaded(this, it) }.map { it.family }
        val fontChips = listOf("System") + ThemeFonts.ALL.map { it.display } + downloaded + listOf("Condensed", MORE_FONTS)
        controls.addView(scroll(chips(fontChips, fontSel()) {
            if (it == MORE_FONTS) {
                fontPicker.launch(Intent(this@ThemeEditorActivity, FontPickerActivity::class.java))
            } else {
                working["--iappyx-font"] = fontStack(it)
                refresh()
            }
        }))
        controls.addView(moreFontsButton(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * d).toInt() })

        controls.addView(label("Text size"))
        controls.addView(scroll(chips(listOf("Compact", "Normal", "Large"), sizeSel()) {
            val s = when (it) { "Compact" -> listOf(24, 17, 13, 11); "Large" -> listOf(34, 24, 17, 13); else -> listOf(28, 20, 15, 12) }
            working["--iappyx-text-xl"] = "${s[0]}px"; working["--iappyx-text-lg"] = "${s[1]}px"
            working["--iappyx-text-md"] = "${s[2]}px"; working["--iappyx-text-sm"] = "${s[3]}px"
            refresh()
        }))

        controls.addView(label("Density"))
        controls.addView(scroll(chips(listOf("Compact", "Cozy", "Spacious"), densitySel()) {
            val s = when (it) { "Compact" -> listOf(4, 8, 14); "Spacious" -> listOf(10, 18, 28); else -> listOf(6, 12, 20) }
            working["--iappyx-space-sm"] = "${s[0]}px"; working["--iappyx-space-md"] = "${s[1]}px"; working["--iappyx-space-lg"] = "${s[2]}px"
            refresh()
        }))

        controls.addView(label("Corner radius"))
        controls.addView(slider(0, 28, radiusOf()) { v ->
            working["--iappyx-radius"] = "${v}px"; working["--iappyx-radius-sm"] = "${(v * 0.6f).toInt()}px"; refresh()
        })

        controls.addView(label("Glass blur"))
        controls.addView(slider(0, 28, glassOf()) { v -> working["--iappyx-glass-blur"] = "${v}px"; refresh() })

        controls.addView(actionsRow())
        controls.addView(resetButton())
        controls.addView(restartButton())
    }

    /** Rebuild the whole control stack so chip / swatch selection highlights
     *  reflect the current [working] map (after applying a preset / import). */
    private fun rebuildControls() { buildControls(); refresh() }

    private fun applyPreset(name: String) {
        when (name) {
            "Material You" -> working.clear()
            "Glass" -> { working["--iappyx-glass-blur"] = "22px"; working["--iappyx-glass-opacity"] = "0.14"; working["--iappyx-radius"] = "24px"; working["--iappyx-radius-sm"] = "14px" }
            "Sharp" -> { working["--iappyx-radius"] = "6px"; working["--iappyx-radius-sm"] = "4px"; working["--iappyx-glass-blur"] = "0px" }
            "Bold" -> { working["--iappyx-weight-normal"] = "600"; working["--iappyx-text-xl"] = "34px"; working["--iappyx-text-lg"] = "24px"; working["--iappyx-text-md"] = "17px" }
        }
        rebuildControls()
    }

    private fun moreFontsButton() = TextView(this).apply {
        text = "＋ Browse all fonts"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        val pv = (11 * d).toInt(); setPadding(0, pv, 0, pv)
        background = GradientDrawable().apply {
            cornerRadius = 12 * d; setColor(Color.parseColor("#2a3d6b"))
            setStroke((1 * d).toInt(), Color.parseColor("#5b8cff"))
        }
        setOnClickListener {
            fontPicker.launch(Intent(this@ThemeEditorActivity, FontPickerActivity::class.java))
        }
    }

    private fun fontStack(name: String): String {
        ThemeFonts.ALL.firstOrNull { it.display == name }?.let { return ThemeFonts.cssStack(it) }
        FontCatalog.byFamily(this, name)?.let { return FontCatalog.cssStack(it) }
        return when (name) {
            "Condensed" -> "\"Roboto Condensed\", \"sans-serif-condensed\", sans-serif"
            else -> "-apple-system, \"Roboto\", \"Segoe UI\", system-ui, sans-serif"
        }
    }

    private fun radiusOf() = working["--iappyx-radius"]?.removeSuffix("px")?.toIntOrNull() ?: 20
    private fun glassOf() = working["--iappyx-glass-blur"]?.removeSuffix("px")?.toIntOrNull() ?: 14

    private fun refresh() {
        val tokens = GeneratedWidgetCell.effectiveTokens(this, GeneratedWidgetCell.readThemeTokens(this), working)
        val vars = tokens.entries.joinToString("") { "${it.key}:${it.value};" }
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>${ThemeFonts.fontFaceCss(this)}
            :root{$vars}
            html,body{margin:0;height:100%;background:var(--iappyx-background);font-family:var(--iappyx-font);color:var(--iappyx-on-surface)}
            .card{margin:14px;padding:var(--iappyx-space-md);border-radius:var(--iappyx-radius);background:var(--iappyx-surface);
              box-shadow:var(--iappyx-shadow);backdrop-filter:blur(var(--iappyx-glass-blur))}
            .cap{font-size:var(--iappyx-text-sm);color:var(--iappyx-neutral);letter-spacing:.08em}
            .big{font-size:var(--iappyx-text-xl);font-weight:var(--iappyx-weight-bold);margin:2px 0}
            .row{display:flex;gap:var(--iappyx-space-sm);align-items:center;font-size:var(--iappyx-text-sm)}
            .pos{color:var(--iappyx-positive)} .dim{color:var(--iappyx-neutral)}
            .btn{margin-top:var(--iappyx-space-md);background:var(--iappyx-primary);color:var(--iappyx-on-primary);
              border:0;border-radius:var(--iappyx-radius-sm);padding:var(--iappyx-space-sm) var(--iappyx-space-md);
              font-family:inherit;font-weight:var(--iappyx-weight-bold)}
            .dots{display:flex;gap:6px;margin-top:var(--iappyx-space-md)} .dots i{width:14px;height:14px;border-radius:50%}
            </style></head><body><div class="card">
            <div class="cap">WEATHER</div><div class="big">21°</div>
            <div class="row"><span class="pos">▲ 2°</span><span class="dim">Humidity 60%</span></div>
            <button class="btn">Refresh</button>
            <div class="dots"><i style="background:var(--iappyx-data-1)"></i><i style="background:var(--iappyx-data-2)"></i>
            <i style="background:var(--iappyx-data-3)"></i><i style="background:var(--iappyx-data-4)"></i></div>
            </div></body></html>
        """.trimIndent()
        preview.loadDataWithBaseURL("https://widget.local/", html, "text/html", "UTF-8", null)
    }

    override fun onPause() {
        super.onPause()
        if (working.isEmpty()) ThemeOverrides.clear(this) else ThemeOverrides.set(this, working)
    }

    // ── custom presets ──
    private fun rebuildPresetChips() {
        presetRow.removeAllViews()
        val presets = ThemePresets.all(this)
        if (presets.isEmpty()) {
            presetRow.addView(TextView(this).apply {
                text = "Save your current look as a preset →"
                setTextColor(Color.parseColor("#8b93a7")); textSize = 12f
                val pv = (9 * d).toInt(); setPadding(0, pv, 0, pv)
            })
            return
        }
        for (p in presets) presetRow.addView(presetChip(p.name), chipParams())
    }

    private fun presetChip(name: String) = TextView(this).apply {
        text = name; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        val ph = (14 * d).toInt(); val pv = (9 * d).toInt(); setPadding(ph, pv, ph, pv)
        background = chipBg(false)
        setOnClickListener { applyCustomPreset(name) }
        setOnLongClickListener { confirmDeletePreset(name); true }
    }

    private fun applyCustomPreset(name: String) {
        val p = ThemePresets.all(this).firstOrNull { it.name == name } ?: return
        working.clear(); working.putAll(p.overrides)
        rebuildControls()
        toast("Applied “$name”")
    }

    private fun confirmDeletePreset(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete preset")
            .setMessage("Delete “$name”? This doesn't change your current theme.")
            .setPositiveButton("Delete") { _, _ -> ThemePresets.delete(this, name); rebuildPresetChips() }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    private fun savePresetDialog() {
        val input = EditText(this).apply { hint = "Preset name"; setSingleLine() }
        val pad = (20 * d).toInt()
        val wrap = LinearLayout(this).apply { setPadding(pad, (8 * d).toInt(), pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Save preset")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Enter a name"); return@setPositiveButton }
                ThemePresets.save(this, name, working)
                rebuildPresetChips()
                toast("Saved “$name”")
            }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    // ── import / export ──
    private fun exportDialog() {
        val token = ThemePresets.export(working)
        val field = EditText(this).apply {
            setText(token); setTextIsSelectable(true); isSingleLine = false
            setTextColor(Color.WHITE); textSize = 12f
        }
        val pad = (20 * d).toInt()
        val wrap = LinearLayout(this).apply { setPadding(pad, (8 * d).toInt(), pad, 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle("Export theme")
            .setMessage("Copy or share this code to apply your theme on another device.")
            .setView(wrap)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("iappyx theme", token))
                toast("Copied")
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, token)
                }, "Share theme"))
            }
            .setNegativeButton("Close", null)
            .showThemed()
    }

    private fun importDialog() {
        val input = EditText(this).apply { hint = "Paste theme code"; isSingleLine = false }
        val pad = (20 * d).toInt()
        val wrap = LinearLayout(this).apply { setPadding(pad, (8 * d).toInt(), pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Import theme")
            .setView(wrap)
            .setPositiveButton("Apply") { _, _ ->
                val parsed = ThemePresets.parse(input.text.toString())
                if (parsed == null) { toast("Not a valid theme code"); return@setPositiveButton }
                working.clear(); working.putAll(parsed)
                rebuildControls()
                toast("Theme applied")
            }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    private fun actionsRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("Save as preset") { savePresetDialog() }, chipParams())
        row.addView(actionButton("Export") { exportDialog() }, chipParams())
        row.addView(actionButton("Import") { importDialog() }, chipParams())
        return scroll(row).apply { setPadding(0, (20 * d).toInt(), 0, 0) }
    }

    private fun actionButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor("#5b8cff")); textSize = 13f; gravity = Gravity.CENTER
        val ph = (14 * d).toInt(); val pv = (10 * d).toInt(); setPadding(ph, pv, ph, pv)
        background = GradientDrawable().apply {
            cornerRadius = 12 * d; setColor(Color.parseColor("#1a1f2e"))
            setStroke((1 * d).toInt(), Color.parseColor("#5b8cff"))
        }
        setOnClickListener { onClick() }
    }

    // ── tiny UI helpers ──
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun scroll(v: View): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(v)
    }
    private fun chipParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = (8 * d).toInt() }

    private fun label(t: String) = TextView(this).apply {
        text = t.uppercase(); setTextColor(Color.parseColor("#9aa3b8")); textSize = 11f; letterSpacing = 0.08f
        setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
    }
    private fun chips(items: List<String>, selected: String?, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (item in items) row.addView(TextView(this).apply {
            text = item; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            val ph = (14 * d).toInt(); val pv = (9 * d).toInt(); setPadding(ph, pv, ph, pv)
            background = chipBg(item == selected)
            setOnClickListener {
                for (i in 0 until row.childCount) row.getChildAt(i).background = chipBg(false)
                background = chipBg(true)
                onPick(item)
            }
        }, chipParams())
        return row
    }
    private fun chipBg(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = 12 * d
        setColor(Color.parseColor(if (selected) "#2a3d6b" else "#1e1e2a"))
        setStroke((if (selected) 2 * d else 1 * d).toInt(), Color.parseColor(if (selected) "#5b8cff" else "#33ffffff"))
    }

    private fun swatches(colors: List<String>, selected: String?, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s = (34 * d).toInt()
        fun swatchBg(c: String, sel: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.parseColor(c))
            setStroke((if (sel) 3 * d else 1 * d).toInt(), Color.parseColor(if (sel) "#ffffff" else "#44ffffff"))
        }
        for (c in colors) row.addView(View(this).apply {
            background = swatchBg(c, c.equals(selected, ignoreCase = true))
            setOnClickListener {
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i); val cc = colors[i]
                    child.background = swatchBg(cc, cc == c)
                }
                onPick(c)
            }
        }, LinearLayout.LayoutParams(s, s).apply { marginEnd = (10 * d).toInt() })
        return row
    }
    private fun fontSel(): String {
        val stack = working["--iappyx-font"] ?: return "System"
        ThemeFonts.fromStack(stack)?.let { return it.display }
        FontCatalog.fromStack(this, stack)?.let { return it.family }
        return if (stack.lowercase().contains("condensed")) "Condensed" else "System"
    }
    private fun sizeSel(): String = when (working["--iappyx-text-xl"]) {
        "24px" -> "Compact"; "34px" -> "Large"; null -> "Normal"; else -> "Normal"
    }
    private fun densitySel(): String = when (working["--iappyx-space-sm"]) {
        "4px" -> "Compact"; "10px" -> "Spacious"; null -> "Cozy"; else -> "Cozy"
    }
    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View =
        SeekBar(this).apply {
            this.max = max - min; progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(min + p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    private fun resetButton() = TextView(this).apply {
        text = "Reset to Material You"; setTextColor(Color.parseColor("#5b8cff")); textSize = 14f
        setPadding(0, (24 * d).toInt(), 0, 0)
        setOnClickListener { working.clear(); rebuildControls() }
    }

    /** Optional full-apply: most of the launcher follows theme changes live,
     *  but a few canvas-drawn glyphs / off-screen surfaces only refresh when
     *  recreated. This restarts the launcher so everything comes back fresh. */
    private fun restartButton() = TextView(this).apply {
        text = "Restart launcher to apply everywhere"
        setTextColor(Color.parseColor("#9aa3b8")); textSize = 13f
        setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
        setOnClickListener { confirmRestart() }
    }

    private fun confirmRestart() {
        // Persist SYNCHRONOUSLY — onPause/onDestroy won't run and an async
        // apply() could be lost when the process is killed in doRestart().
        if (working.isEmpty()) ThemeOverrides.clear(this, sync = true)
        else ThemeOverrides.set(this, working, sync = true)
        AlertDialog.Builder(this)
            .setTitle("Restart launcher")
            .setMessage(
                "Restart now so the theme applies everywhere? Anything a widget is " +
                    "actively doing (radio playback, a running timer) will stop.",
            )
            .setPositiveButton("Restart") { _, _ -> doRestart() }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    private fun doRestart() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        Runtime.getRuntime().exit(0)
    }
    private fun luminance(hex: String): Float {
        val c = Color.parseColor(hex)
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
    }
}
