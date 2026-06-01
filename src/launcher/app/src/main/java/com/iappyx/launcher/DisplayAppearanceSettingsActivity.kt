/*
 * MIT License - Copyright (c) 2026 iappyx
 * Plan A Phase 4b/c — Display & appearance settings extracted from
 * SettingsActivity. Owns the grid pickers, all display toggles
 * (dock labels / long-press menu / rotation / notification badges /
 * language / orientation), transitions, icon filter, live wallpaper,
 * wallpaper-toolkit permissions, and clippings TTL.
 *
 * This is intentionally a single screen — its sections are cohesive
 * ("how the home screen looks/behaves") and breaking them further would
 * just make the user tap into 4 places for what's logically one
 * configuration surface.
 */
package com.iappyx.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Placement
import com.iappyx.launcher.widget.showThemed

class DisplayAppearanceSettingsActivity : AppCompatActivity() {

    private val colsRange = 3..7
    private val rowsRange = 4..10
    private val dockRange = 3..7

    private val REQ_WALLPAPER_PERM_LOCATION = 8101
    private val REQ_WALLPAPER_PERM_CALENDAR = 8102
    private val REQ_WALLPAPER_PERM_MEDIA = 8103

    private var cols = 5
    private var rows = 6
    private var dock = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_appearance)
        SettingsScaffold.attach(this, getString(R.string.settings_display_section))
        setupGridPickers()
        setupToggles()
    }

    override fun onResume() {
        super.onResume()
        val prefs = LauncherPrefs(this)
        val granted = com.iappyx.launcher.notify.NotificationBadgeListener.isEnabled(this)
        // Detach the OnCheckedChangeListener before the programmatic
        // setChecked: if access was revoked externally the switch value
        // flips from true→false, and the listener (installed by
        // setupToggles) would silently overwrite the user's saved
        // intent with false + clear the badge store. The fix is to
        // change the visible state without triggering the listener.
        val badgesSw = findViewById<Switch>(R.id.toggle_notification_badges)
        badgesSw.setOnCheckedChangeListener(null)
        badgesSw.isChecked = prefs.notificationBadgesEnabled && granted
        badgesSw.setOnCheckedChangeListener { _, checked ->
            prefs.notificationBadgesEnabled = checked
            if (checked && !com.iappyx.launcher.notify.NotificationBadgeListener.isEnabled(this)) {
                com.iappyx.launcher.notify.NotificationBadgeListener.openSystemSettings(this)
            } else if (!checked) {
                com.iappyx.launcher.notify.BadgeStore.clear()
            }
        }
        findViewById<TextView>(R.id.notification_badges_grant_btn).visibility =
            if (prefs.notificationBadgesEnabled && !granted) android.view.View.VISIBLE
            else android.view.View.GONE

        val wm = android.app.WallpaperManager.getInstance(this)
        val info = wm.wallpaperInfo
        val active = info?.packageName == packageName &&
            info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"
        val title = com.iappyx.launcher.wallpaper.WallpaperLibrary.all(this)
            .firstOrNull { it.id == prefs.activeWallpaperId }?.title
            ?: getString(R.string.settings_wallpaper_fallback_clock)
        findViewById<TextView>(R.id.live_wallpaper_value).text =
            if (active) title else getString(R.string.settings_live_wallpaper_set_prompt)

        fun granted(p: String) =
            checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.wallpaper_perm_location_value).setText(
            if (granted(android.Manifest.permission.ACCESS_FINE_LOCATION))
                R.string.state_granted
            else R.string.settings_wallpaper_perm_grant_prompt,
        )
        findViewById<TextView>(R.id.wallpaper_perm_calendar_value).setText(
            if (granted(android.Manifest.permission.READ_CALENDAR))
                R.string.state_granted
            else R.string.settings_wallpaper_perm_grant_prompt,
        )
        val mediaPerm = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        findViewById<TextView>(R.id.wallpaper_perm_media_value).setText(
            if (granted(mediaPerm))
                R.string.state_granted
            else R.string.settings_wallpaper_perm_grant_prompt,
        )
    }

    private fun setupToggles() {
        val prefs = LauncherPrefs(this)
        val sw = findViewById<Switch>(R.id.toggle_dock_labels)
        sw.isChecked = prefs.showDockLabels
        sw.setOnCheckedChangeListener { _, checked -> prefs.showDockLabels = checked }

        findViewById<android.view.View>(R.id.widget_theme_row).setOnClickListener {
            startActivity(android.content.Intent(this, com.iappyx.launcher.theme.ThemeEditorActivity::class.java))
        }

        val appDrawerRow = findViewById<android.view.View>(R.id.app_drawer_row)
        val appDrawerValue = findViewById<TextView>(R.id.app_drawer_value)
        fun refreshAppDrawer() {
            appDrawerValue.setText(
                if (prefs.appDrawerStyle == "field") R.string.settings_app_drawer_field
                else R.string.settings_app_drawer_standard,
            )
        }
        refreshAppDrawer()
        appDrawerRow.setOnClickListener {
            val items = arrayOf(
                getString(R.string.settings_app_drawer_standard),
                getString(R.string.settings_app_drawer_field),
            )
            val current = if (prefs.appDrawerStyle == "field") 1 else 0
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_app_drawer_label)
                .setSingleChoiceItems(items, current) { dlg, which ->
                    prefs.appDrawerStyle = if (which == 1) "field" else "standard"
                    refreshAppDrawer()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showThemed()
        }

        val longPressSw = findViewById<Switch>(R.id.toggle_long_press_menu)
        longPressSw.isChecked = prefs.useLongPressMenu
        longPressSw.setOnCheckedChangeListener { _, checked ->
            prefs.useLongPressMenu = checked
        }

        val rotateSw = findViewById<Switch>(R.id.toggle_allow_rotation)
        rotateSw.isChecked = prefs.allowRotation
        rotateSw.setOnCheckedChangeListener { _, checked -> prefs.allowRotation = checked }

        val dominantRow = findViewById<android.view.View>(R.id.dominant_orientation_row)
        val dominantValue = findViewById<TextView>(R.id.dominant_orientation_value)
        fun refreshDominant() {
            dominantValue.setText(
                if (prefs.dominantOrientation == "landscape") R.string.settings_orientation_landscape
                else R.string.settings_orientation_portrait,
            )
        }
        refreshDominant()
        dominantRow.setOnClickListener {
            val items = arrayOf(
                getString(R.string.settings_orientation_portrait),
                getString(R.string.settings_orientation_landscape),
            )
            val current = if (prefs.dominantOrientation == "landscape") 1 else 0
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_dominant_orientation_label)
                .setSingleChoiceItems(items, current) { dlg, which ->
                    prefs.dominantOrientation = if (which == 1) "landscape" else "portrait"
                    refreshDominant()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showThemed()
        }

        val languageRow = findViewById<android.view.View>(R.id.language_row)
        val languageValue = findViewById<TextView>(R.id.language_value)
        val languageCodes = arrayOf("", "en", "nl")
        fun languageLabel(code: String): String = when (code) {
            "en" -> getString(R.string.settings_language_english)
            "nl" -> getString(R.string.settings_language_nederlands)
            else -> getString(R.string.settings_language_system)
        }
        fun currentLanguageCode(): String {
            val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            return if (locales.isEmpty) "" else locales.toLanguageTags().substringBefore('-')
        }
        languageValue.text = languageLabel(currentLanguageCode())
        languageRow.setOnClickListener {
            val current = languageCodes.indexOf(currentLanguageCode()).coerceAtLeast(0)
            val labels = languageCodes.map { languageLabel(it) }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_language_dialog_title)
                .setSingleChoiceItems(labels, current) { dlg, which ->
                    val picked = languageCodes[which]
                    val locales = if (picked.isEmpty())
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    else androidx.core.os.LocaleListCompat.forLanguageTags(picked)
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                    languageValue.text = languageLabel(picked)
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showThemed()
        }

        val badgesSw = findViewById<Switch>(R.id.toggle_notification_badges)
        val grantBtn = findViewById<TextView>(R.id.notification_badges_grant_btn)
        fun refreshBadgesUi() {
            val granted = com.iappyx.launcher.notify.NotificationBadgeListener.isEnabled(this)
            badgesSw.isChecked = prefs.notificationBadgesEnabled && granted
            grantBtn.visibility =
                if (prefs.notificationBadgesEnabled && !granted) android.view.View.VISIBLE
                else android.view.View.GONE
        }
        refreshBadgesUi()
        badgesSw.setOnCheckedChangeListener { _, checked ->
            prefs.notificationBadgesEnabled = checked
            if (checked && !com.iappyx.launcher.notify.NotificationBadgeListener.isEnabled(this)) {
                com.iappyx.launcher.notify.NotificationBadgeListener.openSystemSettings(this)
            } else if (!checked) {
                com.iappyx.launcher.notify.BadgeStore.clear()
            }
            refreshBadgesUi()
        }
        grantBtn.setOnClickListener {
            com.iappyx.launcher.notify.NotificationBadgeListener.openSystemSettings(this)
        }

        val transitionRow = findViewById<android.view.View>(R.id.page_transition_row)
        val transitionValue = findViewById<TextView>(R.id.page_transition_value)
        fun refreshTransitionLabel() {
            val all = com.iappyx.launcher.transitions.TransitionLibrary.all(this)
            val id = prefs.pageTransitionStyle
            val match = all.firstOrNull { it.id == id }
            transitionValue.text = if (match != null) match.title
            else {
                prefs.pageTransitionStyle = "horizontal"
                all.firstOrNull { it.id == "horizontal" }?.title
                    ?: getString(R.string.settings_page_transition_default)
            }
        }
        refreshTransitionLabel()
        transitionRow.setOnClickListener {
            com.iappyx.launcher.widget.PageTransitionSheet(
                activity = this,
                current = prefs.pageTransitionStyle,
                onPick = { picked ->
                    prefs.pageTransitionStyle = picked
                    refreshTransitionLabel()
                },
            ).show()
        }

        val liveWallpaperRow = findViewById<android.view.View>(R.id.live_wallpaper_row)
        val liveWallpaperValue = findViewById<TextView>(R.id.live_wallpaper_value)
        fun refreshLiveWallpaperLabel() {
            val wm = android.app.WallpaperManager.getInstance(this)
            val info = wm.wallpaperInfo
            val active = info?.packageName == packageName &&
                info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"
            val all = com.iappyx.launcher.wallpaper.WallpaperLibrary.all(this)
            val id = prefs.activeWallpaperId
            val match = all.firstOrNull { it.id == id }
            val title = if (match != null) match.title
            else {
                val default = "rotating_radial_gradient"
                prefs.activeWallpaperId = default
                all.firstOrNull { it.id == default }?.title
                    ?: getString(R.string.settings_wallpaper_fallback_rotating_radial)
            }
            liveWallpaperValue.text =
                if (active) title else getString(R.string.settings_live_wallpaper_set_prompt)
        }
        refreshLiveWallpaperLabel()
        liveWallpaperRow.setOnClickListener {
            com.iappyx.launcher.widget.WallpaperSheet(
                activity = this,
                current = prefs.activeWallpaperId,
                onPick = { id ->
                    prefs.activeWallpaperId = id
                    refreshLiveWallpaperLabel()
                    val intent = Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
                        .setPackage(packageName)
                        .putExtra("id", id)
                    sendBroadcast(intent)
                },
            ).show()
        }

        fun isGranted(perm: String): Boolean =
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val locValue = findViewById<TextView>(R.id.wallpaper_perm_location_value)
        val calValue = findViewById<TextView>(R.id.wallpaper_perm_calendar_value)
        val mediaValue = findViewById<TextView>(R.id.wallpaper_perm_media_value)
        val mediaPerm = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        fun refreshPermLabels() {
            locValue.setText(
                if (isGranted(android.Manifest.permission.ACCESS_FINE_LOCATION))
                    R.string.state_granted
                else R.string.settings_wallpaper_perm_grant_prompt,
            )
            calValue.setText(
                if (isGranted(android.Manifest.permission.READ_CALENDAR))
                    R.string.state_granted
                else R.string.settings_wallpaper_perm_grant_prompt,
            )
            mediaValue.setText(
                if (isGranted(mediaPerm))
                    R.string.state_granted
                else R.string.settings_wallpaper_perm_grant_prompt,
            )
        }
        refreshPermLabels()
        findViewById<android.view.View>(R.id.wallpaper_perm_location).setOnClickListener {
            if (!isGranted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                    REQ_WALLPAPER_PERM_LOCATION,
                )
            } else {
                Toast.makeText(this, R.string.state_already_granted, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.view.View>(R.id.wallpaper_perm_calendar).setOnClickListener {
            if (!isGranted(android.Manifest.permission.READ_CALENDAR)) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.READ_CALENDAR),
                    REQ_WALLPAPER_PERM_CALENDAR,
                )
            } else {
                Toast.makeText(this, R.string.state_already_granted, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.view.View>(R.id.wallpaper_perm_media).setOnClickListener {
            if (!isGranted(mediaPerm)) {
                requestPermissions(arrayOf(mediaPerm), REQ_WALLPAPER_PERM_MEDIA)
            } else {
                Toast.makeText(this, R.string.state_already_granted, Toast.LENGTH_SHORT).show()
            }
        }

        val iconFilterRow = findViewById<android.view.View>(R.id.icon_filter_row)
        val iconFilterValue = findViewById<TextView>(R.id.icon_filter_value)
        fun refreshIconFilterLabel() {
            val slug = prefs.iconFilter
            val spec = com.iappyx.launcher.cells.IconFilterRegistry.resolve(this, slug)
            if (spec.slug == "none" && slug != "none") {
                prefs.iconFilter = "none"
            }
            iconFilterValue.text = spec.name
        }
        refreshIconFilterLabel()
        iconFilterRow.setOnClickListener {
            com.iappyx.launcher.widget.IconFilterSheet(
                activity = this,
                current = prefs.iconFilter,
                onPick = { picked ->
                    prefs.iconFilter = picked
                    refreshIconFilterLabel()
                    com.iappyx.launcher.cells.IconMask.clearCache()
                },
            ).show()
        }

        val iconPackRow = findViewById<android.view.View>(R.id.icon_pack_row)
        val iconPackValue = findViewById<TextView>(R.id.icon_pack_value)
        fun refreshIconPackLabel() {
            val pkg = prefs.iconPack
            if (pkg.isBlank()) {
                iconPackValue.setText(R.string.settings_icon_pack_none)
                return
            }
            iconPackValue.text = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0),
                ).toString()
            } catch (_: Throwable) {
                // Pack uninstalled since it was selected — reset to none.
                prefs.iconPack = ""
                com.iappyx.launcher.cells.IconPack.setActive(this, null)
                getString(R.string.settings_icon_pack_none)
            }
        }
        refreshIconPackLabel()
        iconPackRow.setOnClickListener {
            com.iappyx.launcher.widget.IconPackSheet(
                activity = this,
                current = prefs.iconPack,
                onPick = { picked ->
                    prefs.iconPack = picked
                    com.iappyx.launcher.cells.IconPack.setActive(this, picked.ifBlank { null })
                    refreshIconPackLabel()
                },
            ).show()
        }

        val maskUnthemedSw = findViewById<Switch>(R.id.toggle_mask_unthemed)
        maskUnthemedSw.isChecked = prefs.maskUnthemed
        maskUnthemedSw.setOnCheckedChangeListener { _, checked ->
            prefs.maskUnthemed = checked
            com.iappyx.launcher.cells.IconMask.clearCache()
        }

        setupClippingsTtlRow(prefs)
    }

    private fun setupClippingsTtlRow(prefs: LauncherPrefs) {
        val row = findViewById<android.view.View>(R.id.clippings_ttl_row) ?: return
        val subtitle = findViewById<TextView>(R.id.clippings_ttl_value)
        val kinds = listOf("video", "music", "article", "image", "note")
        val kindLabels = mapOf(
            "video" to "Video", "music" to "Music",
            "article" to "Article", "image" to "Image", "note" to "Note",
        )
        val options = listOf(
            1L * 60 * 60_000 to "1 hour",
            6L * 60 * 60_000 to "6 hours",
            24L * 60 * 60_000 to "1 day",
            3L * 24 * 60 * 60_000 to "3 days",
            7L * 24 * 60 * 60_000 to "7 days",
            14L * 24 * 60 * 60_000 to "14 days",
            30L * 24 * 60 * 60_000 to "30 days",
            0L to "Never",
        )
        fun ttlLabel(ms: Long): String = options.firstOrNull { it.first == ms }?.second
            ?: when {
                ms <= 0L -> "Never"
                ms < 60L * 60_000 -> "${ms / 60_000}m"
                ms < 24L * 60 * 60_000 -> "${ms / (60L * 60_000)}h"
                else -> "${ms / (24L * 60 * 60_000)}d"
            }
        fun refreshSubtitle() {
            subtitle.text = kinds.joinToString(" · ") { k ->
                "${kindLabels[k]?.first()}: ${ttlLabel(prefs.clippingTtlMs(k))}"
            }
        }
        refreshSubtitle()

        row.setOnClickListener {
            val labels = kinds.map { k ->
                "${kindLabels[k]} — ${ttlLabel(prefs.clippingTtlMs(k))}"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Clippings auto-expire")
                .setItems(labels) { dlg, idx ->
                    dlg.dismiss()
                    val kind = kinds[idx]
                    val current = prefs.clippingTtlMs(kind)
                    val checked = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
                    AlertDialog.Builder(this)
                        .setTitle("${kindLabels[kind]} TTL")
                        .setSingleChoiceItems(
                            options.map { it.second }.toTypedArray(),
                            checked,
                        ) { sub, picked ->
                            prefs.setClippingTtlMs(kind, options[picked].first)
                            refreshSubtitle()
                            sub.dismiss()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .showThemed()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showThemed()
        }
    }

    private fun setupGridPickers() {
        val placements = PlacementStore(this)
        val current = placements.load()
        cols = current.cols
        rows = current.rows
        dock = current.dockSlots

        val colsValue = findViewById<TextView>(R.id.cols_value)
        val rowsValue = findViewById<TextView>(R.id.rows_value)
        val dockValue = findViewById<TextView>(R.id.dock_value)
        val preview = findViewById<TextView>(R.id.grid_preview)
        val previewView = findViewById<com.iappyx.launcher.widget.GridPreviewView>(R.id.grid_preview_view)

        val colsMinus = findViewById<Button>(R.id.cols_minus)
        val colsPlus = findViewById<Button>(R.id.cols_plus)
        val rowsMinus = findViewById<Button>(R.id.rows_minus)
        val rowsPlus = findViewById<Button>(R.id.rows_plus)
        val dockMinus = findViewById<Button>(R.id.dock_minus)
        val dockPlus = findViewById<Button>(R.id.dock_plus)

        fun dim(b: Button, enabled: Boolean) {
            b.isEnabled = enabled
            b.alpha = if (enabled) 1f else 0.35f
        }

        fun refresh() {
            colsValue.text = cols.toString()
            rowsValue.text = rows.toString()
            dockValue.text = dock.toString()
            preview.text = getString(R.string.settings_grid_preview_format, cols, rows, dock)
            previewView.cols = cols
            previewView.rows = rows
            previewView.dockSlots = dock
            dim(colsMinus, cols > colsRange.first); dim(colsPlus, cols < colsRange.last)
            dim(rowsMinus, rows > rowsRange.first); dim(rowsPlus, rows < rowsRange.last)
            dim(dockMinus, dock > dockRange.first); dim(dockPlus, dock < dockRange.last)
        }
        refresh()

        colsMinus.setOnClickListener { if (cols > colsRange.first) { cols--; refresh() } }
        colsPlus.setOnClickListener { if (cols < colsRange.last) { cols++; refresh() } }
        rowsMinus.setOnClickListener { if (rows > rowsRange.first) { rows--; refresh() } }
        rowsPlus.setOnClickListener { if (rows < rowsRange.last) { rows++; refresh() } }
        dockMinus.setOnClickListener { if (dock > dockRange.first) { dock--; refresh() } }
        dockPlus.setOnClickListener { if (dock < dockRange.last) { dock++; refresh() } }

        findViewById<Button>(R.id.apply_grid_btn).setOnClickListener {
            applyGrid(placements, current)
        }
    }

    private fun applyGrid(store: PlacementStore, current: HomeLayout) {
        if (cols == current.cols && rows == current.rows && dock == current.dockSlots) {
            Toast.makeText(this, R.string.settings_grid_no_changes_toast, Toast.LENGTH_SHORT).show()
            return
        }

        val homeLost = countHomeOverflow(current)
        val dockLost = countDockOverflow(current)
        val totalLost = homeLost + dockLost

        if (totalLost > 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_grid_shrinking_title)
                .setMessage(buildString {
                    append(getString(R.string.settings_grid_shrinking_intro_format, totalLost))
                    if (homeLost > 0) append(getString(R.string.settings_grid_shrinking_home_format, homeLost))
                    if (dockLost > 0) append(getString(R.string.settings_grid_shrinking_dock_format, dockLost))
                    append(getString(R.string.settings_grid_shrinking_continue_q))
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply) { _, _ -> commitGrid(store, current) }
                .showThemed()
        } else {
            commitGrid(store, current)
        }
    }

    private fun commitGrid(store: PlacementStore, current: HomeLayout) {
        val newPages = current.pages.map { page ->
            com.iappyx.launcher.model.Page(
                page.placements
                    .filter { fits(it, cols, rows) }
                    .toMutableList()
            )
        }.toMutableList()
        if (newPages.isEmpty()) newPages.add(com.iappyx.launcher.model.Page())

        val newDockPages = current.dockPages.map { dp ->
            dp.filter { it.col in 0 until dock }.toMutableList()
        }.toMutableList()
        if (newDockPages.isEmpty()) newDockPages.add(mutableListOf())

        val newLayout = HomeLayout(
            cols = cols, rows = rows, dockSlots = dock,
            pages = newPages, dockPages = newDockPages,
            clippings = current.clippings.toMutableList(),
        )
        store.save(newLayout)

        Toast.makeText(this, R.string.settings_grid_updated_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fits(p: Placement, newCols: Int, newRows: Int): Boolean =
        p.col >= 0 && p.row >= 0 &&
        p.col + p.wSpan <= newCols && p.row + p.hSpan <= newRows

    private fun countHomeOverflow(layout: HomeLayout): Int =
        layout.pages.sumOf { page -> page.placements.count { !fits(it, cols, rows) } }

    private fun countDockOverflow(layout: HomeLayout): Int =
        layout.dockPages.sumOf { dp -> dp.count { it.col !in 0 until dock } }
}
