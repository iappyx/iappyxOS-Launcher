/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.ShowcaseFetcher
import com.iappyx.launcher.sharing.ShowcaseInstalledIndex
import com.iappyx.launcher.widget.Palette

/**
 * Browse the public showcase repo (widgets / wallpapers / transitions),
 * preview entry metadata, and install one with a single tap.
 *
 * UI breakdown (top → bottom):
 *
 * 1. **Header** — close button + "Showcase" title
 * 2. **Kind tabs** — Widgets / Wallpapers / Transitions
 * 3. **Filter chip-row** — All / Available / Installed (segmented control)
 * 4. **Status row** — spinner + status text while loading; entry count when ready
 * 5. **List** — one card per entry with status-aware action
 *
 * Each entry shows one of three states:
 *  - **Available** — green "Install" button (download + install path)
 *  - **Installed** — dim "✓ Installed" badge (already in the user's library)
 *  - **Built-in** — accent "Built-in" badge (ships with the APK)
 *
 * The matcher (`ShowcaseInstalledIndex`) handles the slug lookups —
 * bundled set is hardcoded per kind; user-installed slugs come from the
 * `showcaseSlug` field stamped into `meta.json` at install time.
 *
 * Optional `EXTRA_KIND` ("widget" / "wallpaper" / "transition") preselects
 * the corresponding tab. Used by the per-tab trailing CTA cards in the
 * Manage tabs to land users in the right section.
 */
class ShowcaseBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind" // widget | wallpaper | transition
    }

    private enum class Filter { ALL, AVAILABLE, INSTALLED }

    private val main = Handler(Looper.getMainLooper())
    /** Set true in [onDestroy] so any in-flight network thread can skip
     *  its main-thread completion lambda — touching dead views isn't
     *  fatal (Toast tolerates dead contexts) but it's wasteful and
     *  triggers spurious renderList calls on a finishing activity. */
    @Volatile private var destroyed = false
    private lateinit var tabLayout: TabLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var filterRow: LinearLayout
    private lateinit var filterChipAll: TextView
    private lateinit var filterChipAvail: TextView
    private lateinit var filterChipInstalled: TextView
    private lateinit var countLabel: TextView

    private var index: ShowcaseFetcher.ShowcaseIndex? = null
    private var currentKind: ShowcaseFetcher.Kind = ShowcaseFetcher.Kind.WIDGET
    private var currentFilter: Filter = Filter.ALL
    private var installedCache: MutableMap<ShowcaseFetcher.Kind, Set<String>> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        currentKind = when (intent.getStringExtra(EXTRA_KIND)) {
            "wallpaper" -> ShowcaseFetcher.Kind.WALLPAPER
            "transition" -> ShowcaseFetcher.Kind.TRANSITION
            "icon_filter" -> ShowcaseFetcher.Kind.ICON_FILTER
            // PLUGINS: BEGIN
            "plugin" -> ShowcaseFetcher.Kind.PLUGIN
            // PLUGINS: END
            else -> ShowcaseFetcher.Kind.WIDGET
        }
        tabLayout.getTabAt(currentKind.ordinal)?.select()
        loadIndex()
    }

    override fun onResume() {
        super.onResume()
        // Re-scan installed slugs in case the user installed something via
        // another path (Nearby / QR / file import) since this browser was
        // last open.
        installedCache.clear()
        if (index != null) renderList()
    }

    override fun onDestroy() {
        // Flag inflight worker threads so their main.post completion
        // lambdas can early-return instead of touching destroyed views.
        destroyed = true
        super.onDestroy()
    }

    private fun loadIndex() {
        spinner.visibility = View.VISIBLE
        statusText.setText(R.string.showcase_loading)
        statusText.visibility = View.VISIBLE
        listContainer.removeAllViews()
        // Drop the in-memory cache so every browser open pulls a fresh
        // showcase.json. Cost is one ~50 ms HTTP call vs. the user seeing
        // a stale list after we've published a new entry. The Fastly /
        // raw.githubusercontent CDN edge cache (max-age=300) sits in
        // front anyway, so this isn't a hot fetch.
        ShowcaseFetcher.reload()
        Thread {
            try {
                val fetched = ShowcaseFetcher.loadIndex()
                main.post {
                    if (destroyed) return@post
                    index = fetched
                    spinner.visibility = View.GONE
                    statusText.visibility = View.GONE
                    renderList()
                }
            } catch (e: Throwable) {
                main.post {
                    if (destroyed) return@post
                    spinner.visibility = View.GONE
                    val errMsg = e.message ?: getString(R.string.error_unknown)
                    statusText.text = getString(R.string.showcase_load_failed_format, errMsg)
                }
            }
        }.start()
    }

    private fun installedSlugs(kind: ShowcaseFetcher.Kind): Set<String> {
        return installedCache.getOrPut(kind) {
            ShowcaseInstalledIndex.installedSlugs(this, kind)
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val all = index?.byKind(currentKind).orEmpty()
        val installed = installedSlugs(currentKind)

        // Compute status per entry once.
        val statuses = all.associateWith { entry ->
            ShowcaseInstalledIndex.statusOf(this, currentKind, entry.slug, installed)
        }

        val visible = all.filter { entry ->
            val s = statuses[entry] ?: ShowcaseInstalledIndex.Status.AVAILABLE
            when (currentFilter) {
                Filter.ALL -> true
                Filter.AVAILABLE -> s == ShowcaseInstalledIndex.Status.AVAILABLE
                Filter.INSTALLED -> s != ShowcaseInstalledIndex.Status.AVAILABLE
            }
        }

        // Update count label — surfaces the filter's effect at a glance.
        val totalCount = all.size
        val availableCount = statuses.values.count { it == ShowcaseInstalledIndex.Status.AVAILABLE }
        val installedCount = totalCount - availableCount
        countLabel.text = when (currentFilter) {
            Filter.ALL -> "$totalCount total · $availableCount available · $installedCount installed"
            Filter.AVAILABLE -> "$availableCount available"
            Filter.INSTALLED -> "$installedCount installed"
        }

        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                setText(when (currentFilter) {
                    Filter.AVAILABLE -> R.string.showcase_empty_available
                    Filter.INSTALLED -> R.string.showcase_empty_installed
                    Filter.ALL -> R.string.showcase_empty_all
                })
                setTextColor(Palette.textSecondary(this@ShowcaseBrowserActivity))
                textSize = 13f
                setPadding(0, dp(24), 0, 0)
                gravity = Gravity.CENTER
            })
            return
        }
        for (e in visible) listContainer.addView(
            makeEntryRow(e, statuses[e] ?: ShowcaseInstalledIndex.Status.AVAILABLE),
        )
    }

    private fun makeEntryRow(
        entry: ShowcaseFetcher.Entry,
        status: ShowcaseInstalledIndex.Status,
    ): View {
        val accent = Palette.accent(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Palette.bgCell(this@ShowcaseBrowserActivity))
                setStroke(dp(1),
                    ColorUtils.setAlphaComponent(
                        Palette.textPrimary(this@ShowcaseBrowserActivity), 0x22))
            }
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        // Title row: title + status badge on the right.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = entry.title
            setTextColor(Palette.textPrimary(this@ShowcaseBrowserActivity))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        })
        when (status) {
            ShowcaseInstalledIndex.Status.BUNDLED -> {
                titleRow.addView(makeBadge(getString(R.string.showcase_built_in_badge), accent))
            }
            ShowcaseInstalledIndex.Status.INSTALLED -> {
                titleRow.addView(makeBadge("✓ Installed", Color.parseColor("#69F0AE")))
            }
            ShowcaseInstalledIndex.Status.AVAILABLE -> { /* no badge */ }
        }
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "by ${entry.author}"
            setTextColor(accent)
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = entry.description
            setTextColor(Palette.textSecondary(this@ShowcaseBrowserActivity))
            textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        })
        if (entry.attribution.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = getString(R.string.showcase_uses_attribution_format, entry.attribution.joinToString(", "))
                setTextColor(Palette.textSecondary(this@ShowcaseBrowserActivity))
                textSize = 11f
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            })
        }
        // Action button:
        //  - Available     → primary filled "Install"
        //  - Installed     → secondary outlined "Install again" (gets a
        //                    fresh copy alongside the existing one)
        //  - Built-in      → secondary outlined "Install copy" (creates an
        //                    editable user copy without touching the
        //                    bundled original)
        val btnLabel = when (status) {
            ShowcaseInstalledIndex.Status.AVAILABLE -> getString(R.string.showcase_action_install)
            ShowcaseInstalledIndex.Status.INSTALLED -> getString(R.string.showcase_action_install_again)
            ShowcaseInstalledIndex.Status.BUNDLED -> getString(R.string.showcase_action_install_copy)
        }
        val primary = status == ShowcaseInstalledIndex.Status.AVAILABLE
        val btn = TextView(this).apply {
            text = btnLabel
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (primary) {
                    setColor(accent)
                } else {
                    setColor(ColorUtils.setAlphaComponent(accent, 0x1F))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x66))
                }
            }
            setTextColor(if (primary) Color.parseColor("#0D0D1A") else accent)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true; isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(12)
            layoutParams = lp
            setOnClickListener { install(this, entry) }
        }
        card.addView(btn)
        return card
    }

    private fun makeBadge(label: String, accent: Int): TextView {
        return TextView(this).apply {
            text = label; textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(ColorUtils.setAlphaComponent(accent, 0x22))
                setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x66))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }
    }

    private fun install(button: TextView, entry: ShowcaseFetcher.Entry) {
        button.isEnabled = false
        button.alpha = 0.5f
        button.setText(R.string.showcase_action_installing)
        // Progress callback fires from the network thread; bounce updates
        // to the UI thread and throttle to avoid flooding the View. Only
        // shown on entries that declare resources; HTML-only installs are
        // fast enough that "Installing…" is enough.
        val showProgress = entry.resources.isNotEmpty()
        val lastUiUpdate = java.util.concurrent.atomic.AtomicLong(0L)
        val progress = if (showProgress) {
            ShowcaseFetcher.FetchProgress { done, total, _ ->
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate.get() < 100 && done < total) return@FetchProgress
                lastUiUpdate.set(now)
                val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                main.post {
                    if (destroyed) return@post
                    button.text = getString(R.string.showcase_action_installing_pct_format, pct)
                }
            }
        } else null
        Thread {
            try {
                val imported = ShowcaseFetcher.fetchEntry(entry, progress)
                ArtefactBundle.install(this, imported)
                main.post {
                    if (destroyed) return@post
                    Toast.makeText(this,
                        "${entry.title} added to your library",
                        Toast.LENGTH_SHORT).show()
                    // Refresh the cache and re-render so the entry flips
                    // to "✓ Installed" without leaving the browser.
                    installedCache.remove(currentKind)
                    renderList()
                }
            } catch (e: Throwable) {
                main.post {
                    if (destroyed) return@post
                    button.setText(R.string.showcase_action_install)
                    button.isEnabled = true
                    button.alpha = 1f
                    val errMsg = e.message ?: getString(R.string.error_unknown)
                    Toast.makeText(this,
                        getString(R.string.showcase_install_failed_format, errMsg),
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── Filter chip-row ────────────────────────────────────────────

    private fun setFilter(filter: Filter) {
        if (currentFilter == filter) return
        currentFilter = filter
        paintFilterChips()
        renderList()
    }

    private fun paintFilterChips() {
        val accent = Palette.accent(this)
        for ((chip, f) in listOf(
            filterChipAll to Filter.ALL,
            filterChipAvail to Filter.AVAILABLE,
            filterChipInstalled to Filter.INSTALLED,
        )) {
            val active = f == currentFilter
            chip.background = GradientDrawable().apply {
                cornerRadius = 999f
                if (active) setColor(accent)
                else setColor(ColorUtils.setAlphaComponent(accent, 0x1F))
                setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 0x66))
            }
            chip.setTextColor(if (active) Color.parseColor("#0D0D1A") else accent)
        }
    }

    // ── Layout ────────────────────────────────────────────────────

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bgHome(this@ShowcaseBrowserActivity))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding(0, sys.top, 0, sys.bottom)
                    insets
                }
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 18f
            setTextColor(Palette.textPrimary(this@ShowcaseBrowserActivity))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null, null,
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            setText(R.string.showcase_title)
            setTextColor(Palette.textPrimary(this@ShowcaseBrowserActivity))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(12)
            layoutParams = lp
        })
        root.addView(header)

        tabLayout = TabLayout(this).apply {
            tabMode = TabLayout.MODE_FIXED
            setSelectedTabIndicatorColor(Palette.accent(this@ShowcaseBrowserActivity))
            setTabTextColors(Color.parseColor("#80FFFFFF"), Color.WHITE)
            addTab(newTab().setText(R.string.tab_widgets))
            addTab(newTab().setText(R.string.tab_wallpapers))
            addTab(newTab().setText(R.string.tab_transitions))
            addTab(newTab().setText(R.string.tab_icons))
            // PLUGINS: BEGIN
            addTab(newTab().setText(R.string.tab_plugins))
            // PLUGINS: END
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    currentKind = ShowcaseFetcher.Kind.values()[tab.position]
                    if (index != null) renderList()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })
        }
        root.addView(tabLayout)

        // Filter chip-row.
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        filterChipAll = makeFilterChip(getString(R.string.showcase_filter_all)) { setFilter(Filter.ALL) }
        filterChipAvail = makeFilterChip(getString(R.string.showcase_filter_available)) { setFilter(Filter.AVAILABLE) }
        filterChipInstalled = makeFilterChip(getString(R.string.showcase_filter_installed)) { setFilter(Filter.INSTALLED) }
        filterRow.addView(filterChipAll)
        filterRow.addView(filterChipAvail)
        filterRow.addView(filterChipInstalled)
        root.addView(filterRow)
        paintFilterChips()

        // Status / spinner row.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        statusRow.addView(spinner)
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.textSecondary(this@ShowcaseBrowserActivity))
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(8)
            layoutParams = lp
        }
        statusRow.addView(statusText)
        countLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Palette.textSecondary(this@ShowcaseBrowserActivity))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
        }
        statusRow.addView(countLabel)
        root.addView(statusRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            layoutParams = lp
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        scroll.addView(listContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll)
        return root
    }

    private fun makeFilterChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
