/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Application entry point. Installs a single ActivityLifecycleCallbacks that
 * applies the user's theme (custom accent + theme font) to every activity's
 * native view tree on resume — see [com.iappyx.launcher.widget.Palette.applyThemeToTree].
 * The walk is gated by a per-tree signature tag, so it only does work when the
 * theme (or the tree) actually changed; it's a cheap no-op otherwise.
 */
package com.iappyx.launcher

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.iappyx.launcher.widget.Palette

class IappyxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val decor = activity.window?.decorView ?: return
                // post so it runs after layout/inflation has settled.
                decor.post { Palette.applyThemeToTree(decor) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
