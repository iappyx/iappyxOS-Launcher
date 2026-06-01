/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.tabs.TabLayout
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.SettingsActivity
import com.iappyx.launcher.command.CommandSession

/**
 * The top-level view that lives at pager position 0. Wraps the AI chat
 * (`CommandPanel`), the widgets manage tab, and the wallpapers manage tab in
 * a single tabbed surface — plus a gear icon top-right that opens
 * [SettingsActivity].
 *
 * Layout, top to bottom:
 *  - Header row: [TabLayout: AI · Widgets · Wallpapers] [gear icon → Settings]
 *  - FrameLayout content area: shows whichever pane the active tab selects.
 *
 * AI is always the default tab on (re)open — generate-from-other-tabs flows
 * call [switchToAiWithPrefill] which selects the AI tab and seeds the input.
 */
class CommandPanelHost(
    private val activity: LauncherActivity,
) : LinearLayout(activity) {

    private val tabLayout: TabLayout
    private val contentFrame: FrameLayout
    private val chatPane: CommandPanel
    private val widgetsPane: ManageWidgetsTab
    private val wallpapersPane: ManageWallpapersTab
    private val transitionsPane: ManageTransitionsTab
    private val iconsPane: ManageIconFiltersTab

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density
        // Soft separator under the header — keeps the panel feeling like a
        // proper chrome surface even when the wallpaper is busy behind it.

        // ── Header row: tabs + gear ──────────────────────────────
        val headerRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }

        tabLayout = TabLayout(activity).apply {
            // MODE_AUTO sizes each tab to its content (no truncation) and
            // falls back to a scrollable strip if the total width exceeds
            // the screen. On a phone-width device the four tabs typically
            // fit inline; on narrower screens the user can swipe the strip.
            tabMode = TabLayout.MODE_AUTO
            // Trim a little of the default tab padding so all four still
            // fit inline on common phone widths without forcing scroll.
            setSelectedTabIndicatorColor(Palette.accent(activity))
            setTabTextColors(
                Palette.textSecondary(activity),
                Palette.textPrimary(activity),
            )
            val tabPad = (12 * dp).toInt()
            setPadding(0, 0, 0, 0)
            addTab(newTab().setText(activity.getString(com.iappyx.launcher.R.string.settings_ai_section)))
            addTab(newTab().setText(activity.getString(com.iappyx.launcher.R.string.tab_widgets)))
            addTab(newTab().setText(activity.getString(com.iappyx.launcher.R.string.tab_wallpapers)))
            addTab(newTab().setText(activity.getString(com.iappyx.launcher.R.string.tab_transitions)))
            addTab(newTab().setText(activity.getString(com.iappyx.launcher.R.string.tab_icons)))
            // Apply the trimmed padding to each tab view AFTER tabs are
            // added — TabLayout applies its default padding at addTab time.
            for (i in 0 until tabCount) {
                val tabView = (getChildAt(0) as android.view.ViewGroup).getChildAt(i)
                tabView.setPadding(tabPad, tabView.paddingTop, tabPad, tabView.paddingBottom)
                tabView.minimumWidth = 0
            }
        }

        // Settings gear used to live here; it now sits in each manage tab's
        // header bar (next to Generate / Import) so the tab strip can use
        // the freed-up width for full labels — "Wallpapers" and "Transitions"
        // were getting truncated.
        headerRow.addView(
            tabLayout,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            headerRow,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )

        // ── Content frame ───────────────────────────────────────
        contentFrame = FrameLayout(activity)
        addView(
            contentFrame,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // Build the three panes once. Cheap to keep them all in memory; lets
        // tab switches feel instant. Visibility is toggled per tab.
        chatPane = CommandPanel(activity)
        widgetsPane = ManageWidgetsTab(activity, host = this)
        wallpapersPane = ManageWallpapersTab(activity, host = this)
        transitionsPane = ManageTransitionsTab(activity, host = this)
        iconsPane = ManageIconFiltersTab(activity, host = this)
        contentFrame.addView(chatPane, frameMatch())
        contentFrame.addView(widgetsPane, frameMatch())
        contentFrame.addView(wallpapersPane, frameMatch())
        contentFrame.addView(transitionsPane, frameMatch())
        contentFrame.addView(iconsPane, frameMatch())

        showPane(0)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showPane(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun frameMatch() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun showPane(index: Int) {
        chatPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        widgetsPane.visibility = if (index == 1) View.VISIBLE else View.GONE
        wallpapersPane.visibility = if (index == 2) View.VISIBLE else View.GONE
        transitionsPane.visibility = if (index == 3) View.VISIBLE else View.GONE
        iconsPane.visibility = if (index == 4) View.VISIBLE else View.GONE
        when (index) {
            // Re-evaluate API-key state on every show — covers the case
            // where the user just added (or removed) the key in Settings
            // and is returning here to use the chat.
            0 -> chatPane.refresh()
            1 -> widgetsPane.refresh()
            2 -> wallpapersPane.refresh()
            3 -> transitionsPane.refresh()
            4 -> iconsPane.refresh()
        }
    }

    /** Public access used by [LauncherActivity.onResume] to re-check the
     *  API-key state when the activity comes back from Settings. */
    fun refreshChatPane() { chatPane.refresh() }

    /** Refresh whichever manage tab is currently visible. Called from
     *  [LauncherActivity.onResume] so a wallpaper / widget / transition
     *  added via a side-activity (Nearby / QR receive, file import,
     *  Settings round-trip) shows up immediately when the user returns
     *  to a tab that's already selected — `showPane()` only refreshes
     *  on tab *change*, so without this hook a user staying on the same
     *  tab never sees the newly imported entry. */
    fun refreshActivePane() {
        when (tabLayout.selectedTabPosition) {
            0 -> chatPane.refresh()
            1 -> widgetsPane.refresh()
            2 -> wallpapersPane.refresh()
            3 -> transitionsPane.refresh()
            4 -> iconsPane.refresh()
        }
    }

    /** Switch to the AI tab and put the chat pane into manual mode for the
     *  given artefact type. Used by [LauncherActivity.enterManualAiMode]
     *  for both standalone manual flows and AddToHomeSheet's "Generate
     *  with external AI" submenu. The optional [placement] tells a Widget
     *  flow to commit the result onto a specific home cell. */
    fun enterManualMode(
        type: ManualAiCanvas.Type,
        placement: ManualAiCanvas.PlacementTarget? = null,
        edit: ManualAiCanvas.WidgetEdit? = null,
    ) {
        if (tabLayout.selectedTabPosition != 0) tabLayout.getTabAt(0)?.select()
        chatPane.enterManualMode(type, placement, edit)
    }

    /** Wire the AI chat session to the chat pane. Called once by
     *  [LauncherActivity.createCommandPanel]. */
    fun bind(session: CommandSession) { chatPane.bind(session) }

    /** Always reset to the AI tab when the panel becomes visible again — see
     *  the design call "always AI default" in the manage-screens plan. */
    fun resetToAi() {
        if (tabLayout.selectedTabPosition != 0) tabLayout.getTabAt(0)?.select()
    }

    /** Cross-tab routing for the per-tab "Generate" cards. Switches to the AI
     *  tab and seeds the input with [prefill] (cursor at the end), so the
     *  user can finish the sentence and send. */
    fun switchToAiWithPrefill(prefill: String) {
        tabLayout.getTabAt(0)?.select()
        chatPane.setInputText(prefill)
    }

    /** Programmatically jump to a specific tab by index — used by the
     *  `EXTRA_OPEN_TAB` deep-link from Settings → Wallpapers and the
     *  IconFilterSheet's "Manage icon styles →" footer. */
    fun switchToTab(index: Int) {
        if (index in 0..4) tabLayout.getTabAt(index)?.select()
    }
}
