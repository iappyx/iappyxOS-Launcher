/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.content.Context
import android.content.Intent
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.model.ProfileSnapshot

/**
 * Capture / apply for [com.iappyx.launcher.model.Profile]. Bridges the
 * profile data model with the launcher's existing state stores
 * ([PlacementStore], [LauncherPrefs]).
 */
object ProfileApplier {

    /** Read the launcher's current live state into a fresh snapshot. */
    fun captureCurrent(context: Context): ProfileSnapshot {
        val prefs = LauncherPrefs(context)
        val layout = PlacementStore(context).load()
        return ProfileSnapshot(
            layout = layout,
            wallpaperId = prefs.activeWallpaperId,
            iconFilter = prefs.iconFilter,
            pageTransition = prefs.pageTransitionStyle,
        )
    }

    /** Atomic swap to [profile] — writes its snapshot into the launcher's
     *  live state and runs side effects ([Profile.onActivate]).
     *
     *  Caller is expected to refresh the visible UI afterwards. From
     *  inside [com.iappyx.launcher.LauncherActivity], call this and then
     *  the activity's [com.iappyx.launcher.LauncherActivity.applyProfileSwap]
     *  helper (which does pagerAdapter.setLayout + notifyIconFiltersChanged
     *  + wallpaper broadcast) on the UI thread. */
    fun apply(context: Context, profile: Profile) {
        val snapshot = profile.snapshot
        val prefs = LauncherPrefs(context)

        // 1) Active profile FIRST — every layout / pref setter we touch
        //    below triggers an auto-sync into the active profile, so we
        //    must already be pointing at the destination profile or those
        //    syncs would write back to the wrong (previous) one.
        prefs.activeProfileSlug = profile.slug

        // 2) Layout — atomic via PlacementStore's tmp-file swap.
        PlacementStore(context).save(snapshot.layout)

        // 3) Prefs.
        prefs.iconFilter = snapshot.iconFilter
        prefs.pageTransitionStyle = snapshot.pageTransition
        prefs.activeWallpaperId = snapshot.wallpaperId

        // 3) Cache invalidation so the next icon render uses the new filter.
        IconMask.clearCache()
        IconFilterRegistry.invalidateAll()

        // 4) Wallpaper broadcast — the :wallpaper process picks this up
        //    and hot-reloads its WebView without a re-bind.
        try {
            val intent = Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
                .setPackage(context.packageName)
                .putExtra("id", snapshot.wallpaperId)
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}

        // 5a) Custom intents (configured per-profile) — VPN connects,
        //     presence broadcasts, automation hooks. Fire BEFORE
        //     launchPackages so any setup the user wants (VPN up, DND
        //     on, etc.) lands before user-visible apps appear.
        //     Fire-and-forget; tracks failure count so we can show one
        //     summarising hint toast at the end.
        var customFailures = 0
        for (action in profile.onActivate.customActions) {
            val r = com.iappyx.launcher.intent.IntentRunner.fire(context, action)
            if (r !is com.iappyx.launcher.intent.IntentRunner.Result.Ok) customFailures++
        }
        if (customFailures > 0) {
            try {
                android.widget.Toast.makeText(
                    context,
                    "Some profile actions didn't run — open Profiles → " +
                        profile.name + " → Custom actions to test.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Throwable) { /* not all contexts can show a toast */ }
        }

        // 5b) Auto-launch packages. Fire-and-forget — missing apps just
        //    silently skip.
        for (pkg in profile.onActivate.launchPackages) {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: continue
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                LauncherPrefs(context).recordAppLaunch(pkg)
            } catch (_: Throwable) { /* skip */ }
        }
    }

    /** Update the active profile's snapshot to mirror the current live
     *  state. Called on every commit to PlacementStore (and on
     *  wallpaper / filter / transition changes) so edits made while a
     *  profile is active stay attached to that profile. No-op when no
     *  profile is active. */
    fun captureIntoActive(context: Context) {
        val slug = LauncherPrefs(context).activeProfileSlug ?: return
        val current = ProfileLibrary.get(context, slug) ?: return
        val updated = current.copy(snapshot = captureCurrent(context))
        ProfileLibrary.save(context, updated)
    }
}
