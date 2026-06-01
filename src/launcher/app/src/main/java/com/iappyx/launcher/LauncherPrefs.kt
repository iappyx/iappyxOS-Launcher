/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.Context
import android.content.SharedPreferences

/** Plain SharedPreferences wrapper for launcher UI toggles (not keys/secrets). */
class LauncherPrefs(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("iappyx_launcher_prefs", Context.MODE_PRIVATE)

    var showDockLabels: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DOCK_LABELS, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_DOCK_LABELS, value).apply() }

    /** Which app drawer opens on swipe-up / drawer icon:
     *  "standard" (default) or "field" (the native Field organism). */
    var appDrawerStyle: String
        get() = prefs.getString(KEY_APP_DRAWER_STYLE, "standard") ?: "standard"
        set(value) { prefs.edit().putString(KEY_APP_DRAWER_STYLE, value).apply() }

    /** True until the user sees (and dismisses) the first-run welcome overlay. */
    var firstRunPending: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_PENDING, true)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_RUN_PENDING, value).apply() }

    /** True once the one-time support prompt has been resolved permanently
     *  (the user tapped "Buy me a coffee" or "No thanks"). Never shown again. */
    var supportPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_SUPPORT_PROMPT_DISMISSED, false)
        set(value) { prefs.edit().putBoolean(KEY_SUPPORT_PROMPT_DISMISSED, value).apply() }

    /** Epoch-ms before which the support prompt stays hidden. `0` = never
     *  snoozed; set to now+14d when the user taps "Maybe later" (one-shot —
     *  a non-zero value also signals the single reprieve has been used). */
    var supportPromptSnoozeUntil: Long
        get() = prefs.getLong(KEY_SUPPORT_PROMPT_SNOOZE_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_SUPPORT_PROMPT_SNOOZE_UNTIL, value).apply() }

    /** Page transition style. One of:
     *   - "horizontal" — cells sweep left/right, staggered by column (default).
     *   - "vertical"   — cells fall up/down, staggered by row.
     *   - "cube"       — pages rotate around their meeting edge like a 3D cube.
     *   - "depth"      — outgoing page recedes (scale+fade) under incoming.
     *   - "zoom"       — outgoing zooms out, incoming zooms in from above.
     *   - "scatter"    — cells fly in from per-cell random off-screen points.
     *   - "fade"       — pure alpha crossfade, no motion.
     *   - "tilt"       — horizontal sweep + per-cell rotateY tilt for depth.
     */
    var pageTransitionStyle: String
        get() = prefs.getString(KEY_PAGE_TRANSITION_STYLE, "horizontal") ?: "horizontal"
        set(value) {
            prefs.edit().putString(KEY_PAGE_TRANSITION_STYLE, value).apply()
            syncToActiveProfile()
        }

    /** When true, the NotificationListener publishes per-package counts to
     *  BadgeStore and IconCell paints the red bubble. Off by default — the
     *  listener service still binds (system controls that), but it short-
     *  circuits and the store stays empty. Toggling on without granting
     *  Notification access does nothing visible. */
    var notificationBadgesEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_BADGES, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATION_BADGES, value).apply() }

    // PLUGINS — per-plugin universal-search exposure. Default-include
    // semantics (every plugin that exposes `search` participates unless
    // its id is in this set). Users opt out per plugin via the toggle
    // in PluginDetailActivity. Empty set = everyone participating, the
    // most common state.
    var searchExcludedPlugins: Set<String>
        get() = prefs.getStringSet(KEY_SEARCH_EXCLUDED_PLUGINS, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_SEARCH_EXCLUDED_PLUGINS, value).apply() }

    /** Convenience: set [pluginId]'s search-exposure on or off. */
    fun setSearchExposed(pluginId: String, exposed: Boolean) {
        val cur = searchExcludedPlugins.toMutableSet()
        if (exposed) cur.remove(pluginId) else cur.add(pluginId)
        searchExcludedPlugins = cur
    }

    fun isSearchExposed(pluginId: String): Boolean =
        !searchExcludedPlugins.contains(pluginId)

    // ── Search recents ─────────────────────────────────────────
    // A ring of "things you've recently acted on in search results",
    // keyed by `<pluginId>:<resultId>`. The aggregator promotes recent
    // ids to the TOP of the next search rendering, so the controls
    // you toggle most often surface first. ~50 entries max, oldest
    // expired by the lazy compaction in [bumpSearchRecent].
    fun searchRecentKeys(): List<String> {
        val raw = prefs.getString(KEY_SEARCH_RECENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        // Format: "key1|ts1,key2|ts2,..." — flat string is cheaper to
        // parse than a JSON array and the data is opaque to other code.
        return raw.split(",").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) parts[0] else null
        }
    }

    fun bumpSearchRecent(pluginId: String, resultId: String) {
        val key = "$pluginId:$resultId"
        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_SEARCH_RECENTS, "") ?: ""
        val existing = if (raw.isBlank()) emptyList()
        else raw.split(",").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
        }
        // Filter the old entry for this key + cap to 50 newest + sort
        // newest-first.
        val updated = (existing.filter { it.first != key } + (key to now))
            .sortedByDescending { it.second }
            .take(SEARCH_RECENTS_CAP)
        val out = updated.joinToString(",") { "${it.first}|${it.second}" }
        prefs.edit().putString(KEY_SEARCH_RECENTS, out).apply()
    }

    fun clearSearchRecents() { prefs.edit().remove(KEY_SEARCH_RECENTS).apply() }

    // APPLOCK: BEGIN — per-package biometric / device-credential gate.
    // Stored as a SharedPreferences string-set keyed by KEY_LOCKED_PACKAGES.
    // Profile-scoped sync deliberately skipped — if the user wants WhatsApp
    // locked, they want it locked regardless of which launcher profile is
    // active. Reading/writing is sync (memory-resident after first prefs
    // load) so [AppLockManager.isLocked] can be called from the click
    // listener without blocking.
    var lockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED_PACKAGES, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_LOCKED_PACKAGES, value).apply() }

    /** Toggle one package's locked state. Cheaper than reading the full set,
     *  mutating it, and writing it back from caller code. Returns the new
     *  state ([true] = now locked). */
    fun setLocked(packageName: String, locked: Boolean): Boolean {
        val current = lockedPackages.toMutableSet()
        if (locked) current.add(packageName) else current.remove(packageName)
        lockedPackages = current
        return locked
    }
    // APPLOCK: END

    /** Visual treatment applied to every app icon on the home grid + dock.
     *  See [com.iappyx.launcher.cells.IconFilter] for the list of supported
     *  values (`"none"` is the default — no transform). */
    var iconFilter: String
        get() = prefs.getString(KEY_ICON_FILTER, "none") ?: "none"
        set(value) {
            prefs.edit().putString(KEY_ICON_FILTER, value).apply()
            syncToActiveProfile()
        }

    /** Package name of the active third-party icon pack, or "" for none.
     *  Themed apps draw the pack's icon; unthemed apps fall back to the
     *  built-in [com.iappyx.launcher.cells.IconMask] treatment. When a pack is
     *  active it also takes precedence over [iconFilter] (the colour filters
     *  are skipped so they don't clash with the pack's curated look). */
    var iconPack: String
        get() = prefs.getString(KEY_ICON_PACK, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ICON_PACK, value).apply() }

    /** When an icon pack is active, also apply the pack's iconback / iconmask /
     *  iconupon to apps the pack doesn't explicitly theme, so the grid reads as
     *  one cohesive set instead of half-themed. Only has an effect when the
     *  active pack actually ships those directives. Default true. */
    var maskUnthemed: Boolean
        get() = prefs.getBoolean(KEY_MASK_UNTHEMED, true)
        set(value) { prefs.edit().putBoolean(KEY_MASK_UNTHEMED, value).apply() }

    /** Per-app manual icon overrides: app package → drawable resource name in
     *  the active icon pack. Lets the user fix a wrong/missing auto-match.
     *  Serialised as `pkg=drawable` tab-separated entries. */
    var iconOverrides: Map<String, String>
        get() {
            val raw = prefs.getString(KEY_ICON_OVERRIDES, "") ?: ""
            if (raw.isBlank()) return emptyMap()
            return raw.split('\t').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
        }
        set(value) {
            prefs.edit().putString(
                KEY_ICON_OVERRIDES,
                value.entries.joinToString("\t") { "${it.key}=${it.value}" },
            ).apply()
        }

    fun setIconOverride(pkg: String, drawableName: String?) {
        val current = iconOverrides.toMutableMap()
        if (drawableName.isNullOrBlank()) current.remove(pkg) else current[pkg] = drawableName
        iconOverrides = current
    }

    /** Per-app custom labels: app package → user-chosen display name. Applied
     *  wherever the system app label is shown (home grid, dock, drawer, search).
     *  Serialised as tab-separated `pkg=label` entries. */
    var appLabelOverrides: Map<String, String>
        get() {
            val raw = prefs.getString(KEY_APP_LABELS, "") ?: ""
            if (raw.isBlank()) return emptyMap()
            return raw.split('\t').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
        }
        set(value) {
            prefs.edit().putString(
                KEY_APP_LABELS,
                value.entries.joinToString("\t") { "${it.key}=${it.value}" },
            ).apply()
        }

    /** Set or clear a per-app label. Whitespace is collapsed and capped so the
     *  serialisation (tab/`=` delimited) and the UI stay sane. Empty clears. */
    fun setAppLabel(pkg: String, label: String?) {
        val clean = label?.replace(Regex("\\s+"), " ")?.trim()?.take(40)
        val current = appLabelOverrides.toMutableMap()
        if (clean.isNullOrEmpty()) current.remove(pkg) else current[pkg] = clean
        appLabelOverrides = current
    }

    /** Display label for [pkg]: the user override if set, else [fallback]
     *  (the system app label the caller already resolved). */
    fun appLabel(pkg: String?, fallback: CharSequence): CharSequence {
        if (pkg == null) return fallback
        return appLabelOverrides[pkg]?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** Asset id of the HTML payload the live wallpaper renders. Resolved by
     *  [com.iappyx.launcher.wallpaper.WallpaperLibrary]. Read by the
     *  `:wallpaper` process; the launcher process broadcasts
     *  [WALLPAPER_CHANGED_ACTION] after writing so the running engine picks
     *  the change up without waiting for a rebind. */
    var activeWallpaperId: String
        get() = prefs.getString(KEY_ACTIVE_WALLPAPER, "rotating_radial_gradient")
            ?: "rotating_radial_gradient"
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_WALLPAPER, value).apply()
            syncToActiveProfile()
        }

    /** Wall-clock of the most recent successful backup export. Surfaced as
     *  "Last backup: 2 minutes ago" in the Backup & Restore Settings row.
     *  0 = no backup ever taken. Excluded from backups themselves so an
     *  imported device doesn't pretend it has a recent local backup. */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply() }

    /** Allow the launcher activity to follow device rotation. Off by default —
     *  the home grid is designed for one orientation ([dominantOrientation])
     *  and looks squished in the other until we ship a layout-rotation
     *  transform. Tablet users typically want this on with a Landscape
     *  dominant; phone users typically want it off with a Portrait dominant. */
    var allowRotation: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_ROTATION, false)
        set(value) { prefs.edit().putBoolean(KEY_ALLOW_ROTATION, value).apply() }

    /** When true, long-pressing a home-grid cell shows the same context
     *  menu the app drawer uses (App info / Uninstall / app shortcuts /
     *  Refine etc.) instead of dropping straight into edit mode. Edit
     *  mode stays reachable via a "Customize home" entry inside the
     *  popup, and via empty-cell long-press / pinch-to-zoom.
     *
     *  On by default — matches the muscle memory people bring from
     *  every other Android launcher. Toggleable in Settings so users
     *  who prefer the old "long-press = edit mode" behaviour can flip
     *  it off. */
    var useLongPressMenu: Boolean
        get() = prefs.getBoolean(KEY_USE_LONG_PRESS_MENU, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_LONG_PRESS_MENU, value).apply() }

    /** The orientation the user designs their grid in. Drives both the
     *  locked orientation (when [allowRotation] is off) and the canonical
     *  storage orientation (when on). First-run default is autodetected
     *  from `smallestScreenWidthDp` — phones get Portrait, tablets get
     *  Landscape. Persisted as the string `"portrait"` or `"landscape"`. */
    var dominantOrientation: String
        get() {
            prefs.getString(KEY_DOMINANT_ORIENTATION, null)?.let { return it }
            // First-run autodetect, persisted so we don't keep re-deciding.
            val swDp = appContext.resources.configuration.smallestScreenWidthDp
            val detected = if (swDp >= 600) "landscape" else "portrait"
            prefs.edit().putString(KEY_DOMINANT_ORIENTATION, detected).apply()
            return detected
        }
        set(value) { prefs.edit().putString(KEY_DOMINANT_ORIENTATION, value).apply() }

    /** Push the live state into the active profile's snapshot, if any.
     *  Called from setters of pref fields a profile snapshots
     *  (wallpaper, icon filter, page transition) so edits made while a
     *  profile is auto-active stay attached to that profile. PlacementStore
     *  calls the same hook for layout commits. Best-effort — any exception
     *  is swallowed so a sync failure can't cascade into a broken save. */
    private fun syncToActiveProfile() {
        try { com.iappyx.launcher.profile.ProfileApplier.captureIntoActive(appContext) }
        catch (_: Throwable) { /* best-effort */ }
    }

    /** Slug of the currently-active [com.iappyx.launcher.model.Profile],
     *  or null when no profile owns the live state. The profile system
     *  uses this to decide where edits-while-active should be stored
     *  back, and to skip re-applying when the matcher picks the same
     *  profile we're already on. */
    var activeProfileSlug: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().run {
                if (value.isNullOrBlank()) remove(KEY_ACTIVE_PROFILE)
                else putString(KEY_ACTIVE_PROFILE, value)
                apply()
            }
        }

    /** When true, [com.iappyx.launcher.profile.ProfileWatcher] still tracks
     *  WiFi / Auto / geofence state but does NOT fire profile swaps. Use
     *  this while editing profiles — otherwise being in range of a trigger
     *  (e.g. "home WiFi → home profile") snaps the active profile back to
     *  the trigger's target every time you arrive on the profiles screen,
     *  blocking edits to other profiles. The user toggles this from the
     *  profiles screen. Default false. */
    var profileAutoSwitchPaused: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_AUTOSWITCH_PAUSED, false)
        set(value) { prefs.edit().putBoolean(KEY_PROFILE_AUTOSWITCH_PAUSED, value).apply() }

    /** Record that the user just launched [pkg] — bumps the recents list AND
     *  increments the total launch count used for "frequent apps". */
    fun recordAppLaunch(pkg: String) {
        val current = recentApps().toMutableList()
        current.remove(pkg)
        current.add(0, pkg)
        while (current.size > RECENT_CAP) current.removeAt(current.size - 1)
        val counts = launchCounts().toMutableMap()
        counts[pkg] = (counts[pkg] ?: 0) + 1
        prefs.edit()
            .putString(KEY_RECENT_APPS, current.joinToString("\t"))
            .putString(KEY_APP_COUNTS, counts.entries.joinToString("\t") { "${it.key}=${it.value}" })
            .apply()
    }

    /** Most recently launched packages, newest first. */
    fun recentApps(): List<String> =
        prefs.getString(KEY_RECENT_APPS, "")
            ?.split("\t")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Map of pkg → total launch count, used to rank "frequent apps". */
    fun launchCounts(): Map<String, Int> =
        prefs.getString(KEY_APP_COUNTS, "")
            ?.split("\t")
            ?.filter { it.isNotBlank() }
            ?.mapNotNull {
                val eq = it.indexOf('='); if (eq < 0) null
                else it.substring(0, eq) to (it.substring(eq + 1).toIntOrNull() ?: 0)
            }
            ?.toMap()
            .orEmpty()

    /** Packages ranked by launch count, highest first. */
    fun frequentApps(limit: Int = 8): List<String> =
        launchCounts().entries.sortedByDescending { it.value }.take(limit).map { it.key }

    // ── Universal search: recent queries ───────────────────────

    /** Persist a search query — bumps it to the front of the list (deduped). */
    fun recordSearch(query: String) {
        val q = query.trim(); if (q.isEmpty()) return
        val current = recentSearches().toMutableList()
        current.remove(q)
        current.add(0, q)
        while (current.size > SEARCH_CAP) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_RECENT_SEARCHES, current.joinToString("\t")).apply()
    }

    fun recentSearches(): List<String> =
        prefs.getString(KEY_RECENT_SEARCHES, "")
            ?.split("\t")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun removeSearch(query: String) {
        val current = recentSearches().toMutableList()
        if (current.remove(query)) {
            prefs.edit().putString(KEY_RECENT_SEARCHES, current.joinToString("\t")).apply()
        }
    }

    fun clearSearches() { prefs.edit().remove(KEY_RECENT_SEARCHES).apply() }

    /** Per-kind default time-to-live (ms) applied to every newly-shared
     *  clipping and to "Reset TTL" actions. 0L means "never expire" — the
     *  clipping is treated as locked-by-default and the auto-sweep skips it.
     *  Each kind has its own slot so heavier-to-collect kinds (notes, music)
     *  default to a longer life than glanceable ones (videos, articles). */
    fun clippingTtlMs(kind: String): Long {
        val key = clippingTtlKey(kind) ?: return defaultClippingTtlMs(kind)
        return prefs.getLong(key, defaultClippingTtlMs(kind))
    }

    fun setClippingTtlMs(kind: String, ttlMs: Long) {
        val key = clippingTtlKey(kind) ?: return
        prefs.edit().putLong(key, ttlMs).apply()
    }

    private fun clippingTtlKey(kind: String): String? = when (kind.lowercase()) {
        "video" -> KEY_TTL_VIDEO
        "article" -> KEY_TTL_ARTICLE
        "music" -> KEY_TTL_MUSIC
        "image" -> KEY_TTL_IMAGE
        "note" -> KEY_TTL_NOTE
        else -> null
    }

    /** First-launch defaults — also used as the fallback when the user picks
     *  "Reset to defaults" in Settings. Match the constants ShareReceiver
     *  used to embed before settings existed. */
    private fun defaultClippingTtlMs(kind: String): Long = when (kind.lowercase()) {
        "video", "article" -> 24L * 60 * 60 * 1000      // 1d
        "music", "image", "note" -> 7L * 24 * 60 * 60 * 1000 // 7d
        else -> 24L * 60 * 60 * 1000
    }

    companion object {
        private const val KEY_SHOW_DOCK_LABELS = "show_dock_labels"
        private const val KEY_APP_DRAWER_STYLE = "app_drawer_style"
        private const val KEY_RECENT_APPS = "recent_apps"
        private const val KEY_APP_COUNTS = "app_launch_counts"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_FIRST_RUN_PENDING = "first_run_pending"
        private const val KEY_SUPPORT_PROMPT_DISMISSED = "support_prompt_dismissed"
        private const val KEY_SUPPORT_PROMPT_SNOOZE_UNTIL = "support_prompt_snooze_until"
        private const val KEY_PAGE_TRANSITION_STYLE = "page_transition_style"
        private const val KEY_NOTIFICATION_BADGES = "notification_badges_enabled"
        private const val KEY_ICON_FILTER = "icon_filter"
        private const val KEY_ICON_PACK = "icon_pack"
        private const val KEY_MASK_UNTHEMED = "icon_pack_mask_unthemed"
        private const val KEY_ICON_OVERRIDES = "icon_pack_overrides"
        private const val KEY_APP_LABELS = "app_label_overrides"
        private const val KEY_ACTIVE_WALLPAPER = "active_wallpaper_id"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_ALLOW_ROTATION = "allow_rotation"
        private const val KEY_DOMINANT_ORIENTATION = "dominant_orientation"
        private const val KEY_USE_LONG_PRESS_MENU = "use_long_press_menu"
        private const val KEY_ACTIVE_PROFILE = "active_profile_slug"
        private const val KEY_PROFILE_AUTOSWITCH_PAUSED = "profile_autoswitch_paused"
        private const val KEY_TTL_VIDEO = "clipping_ttl_video_ms"
        private const val KEY_TTL_ARTICLE = "clipping_ttl_article_ms"
        private const val KEY_TTL_MUSIC = "clipping_ttl_music_ms"
        private const val KEY_TTL_IMAGE = "clipping_ttl_image_ms"
        private const val KEY_TTL_NOTE = "clipping_ttl_note_ms"
        // APPLOCK: per-package biometric / device-credential gate.
        private const val KEY_LOCKED_PACKAGES = "locked_packages"
        // PLUGINS: per-plugin universal-search exposure (default-include).
        private const val KEY_SEARCH_EXCLUDED_PLUGINS = "search_excluded_plugins"
        // PLUGINS: search recents — "pluginId:resultId" keys + last-acted ts.
        private const val KEY_SEARCH_RECENTS = "search_recents"
        private const val SEARCH_RECENTS_CAP = 50
        const val RECENT_CAP = 8
        const val SEARCH_CAP = 10
        /** Broadcast action fired by the launcher process after the active
         *  wallpaper id changes — picked up by the live wallpaper engine in
         *  the `:wallpaper` process so it can hot-reload its WebView. */
        const val WALLPAPER_CHANGED_ACTION = "com.iappyx.launcher.WALLPAPER_CHANGED"

        /** Broadcast action fired by the launcher process after the home
         *  grid + dock layout commits (drag-drop release, page add/remove,
         *  app install, edit-mode exit). The intent's `"json"` String extra
         *  carries the full layout snapshot — bounding boxes only, no app
         *  identity — so the live wallpaper can do collision-aware
         *  animations (orbit-around, fill-the-gaps). See [LayoutSerializer]
         *  for the JSON shape. */
        const val LAYOUT_CHANGED_ACTION = "com.iappyx.launcher.LAYOUT_CHANGED"

        /** Fired by ShareReceiverActivity when a new clipping is dropped into
         *  the launcher from outside (Android share-sheet target). The
         *  running launcher activity refreshes its pager so the new card
         *  shows up without waiting for an onResume cycle. Distinct from
         *  [LAYOUT_CHANGED_ACTION] (which is wallpaper-bound and fires per
         *  page settle) — sharing the action would force a full pager
         *  recycle on every swipe and produce a visible flicker. */
        const val CLIPPINGS_CHANGED_ACTION = "com.iappyx.launcher.CLIPPINGS_CHANGED"

        /** Filename of the cached layout snapshot in `filesDir`. The launcher
         *  writes it whenever it broadcasts; the wallpaper service reads it
         *  on engine create so `iappyxLayout.get()` returns valid data
         *  before the first broadcast arrives (covers the case where the
         *  wallpaper engine boots before the launcher activity does). */
        const val LAYOUT_SNAPSHOT_FILE = "wallpaper_layout_snapshot.json"
    }
}
