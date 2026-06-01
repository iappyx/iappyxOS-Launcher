/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import com.iappyx.launcher.search.SearchResult
import com.iappyx.launcher.search.SearchSources

/**
 * Process-wide cache for the installed-apps + Settings-activities list.
 * Both [AppDrawerPanel] and [SearchPanel] used to load this synchronously
 * the first time they were opened — `pm.queryIntentActivities` plus a
 * `loadIcon` per app routinely cost 500ms–2s on a busy device, all on
 * the main thread, which is why the first swipe-up / swipe-down felt
 * janky.
 *
 * Now [LauncherActivity.onCreate] fires [prewarm] on a background thread.
 * By the time the user actually opens either panel, the cache is usually
 * already populated; if a race lands the user on a cold cache, [apps] /
 * [settings] fall back to a synchronous load (slow path is preserved
 * for correctness, just no longer the common path).
 *
 * Cache lifecycle:
 *  - Filled on prewarm OR first sync access.
 *  - [invalidate] called from the activity's package-broadcast receiver
 *    (ADD / REMOVE / REPLACE) so a freshly-installed app shows up in
 *    the drawer + search without a launcher restart. The activity also
 *    re-prewarms so the next open is fast.
 *
 * Visibility: package-private to widget.* + LauncherActivity, since the
 * activity drives prewarm + invalidation lifecycle.
 */
object AppRegistry {

    @Volatile private var cachedApps: List<SearchResult.App>? = null
    @Volatile private var cachedSettings: List<SearchResult.Setting>? = null
    private val lock = Any()

    /** Kick off a background load of both lists. Cheap to call multiple
     *  times — only one load runs at a time, and a finished cache is not
     *  re-loaded. */
    fun prewarm(context: Context) {
        if (cachedApps != null && cachedSettings != null) return
        val app = context.applicationContext
        Thread {
            val apps = if (cachedApps == null) SearchSources.allApps(app) else null
            val settings = if (cachedSettings == null) SearchSources.allSettings(app) else null
            synchronized(lock) {
                if (apps != null && cachedApps == null) cachedApps = apps
                if (settings != null && cachedSettings == null) cachedSettings = settings
            }
        }.apply { isDaemon = true; name = "AppRegistry-prewarm"; start() }
    }

    /** Get the cached apps list, loading synchronously on a cold cache.
     *
     *  Returns the just-loaded `list` directly rather than reading
     *  `cachedApps!!` after the synchronized block — `invalidate()` from
     *  another thread between the assign and the read would otherwise
     *  null the field and NPE the caller. The local is always non-null. */
    fun apps(context: Context): List<SearchResult.App> {
        cachedApps?.let { return it }
        val list = SearchSources.allApps(context.applicationContext)
        synchronized(lock) { if (cachedApps == null) cachedApps = list }
        return list
    }

    fun settings(context: Context): List<SearchResult.Setting> {
        cachedSettings?.let { return it }
        val list = SearchSources.allSettings(context.applicationContext)
        synchronized(lock) { if (cachedSettings == null) cachedSettings = list }
        return list
    }

    /** Drop both caches — call when packages have been added / removed /
     *  changed so the next access (or prewarm) picks up the new state. */
    fun invalidate() {
        synchronized(lock) {
            cachedApps = null
            cachedSettings = null
        }
    }
}
