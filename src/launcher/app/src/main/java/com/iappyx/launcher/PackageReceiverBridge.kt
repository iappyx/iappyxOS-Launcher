/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.widget.AppRegistry

/**
 * Listens for package install/remove/replace/change broadcasts and keeps
 * the launcher's AppRegistry + icon mask cache fresh. The activity is
 * notified via callbacks for the parts that touch its layout/adapters.
 *
 * **Removed** (and not part of an update — `EXTRA_REPLACING=true` is
 * ignored, the matching ADDED right after handles the refresh):
 *  - AppRegistry.invalidate + prewarm
 *  - [onPackageRemoved] callback so the activity can sweep placements
 *    that pointed at the removed package out of the home + dock layout.
 *
 * **Added / Replaced / Changed:**
 *  - IconMask.clearCache (icons may have new densities / labels)
 *  - AppRegistry.invalidate + prewarm
 *  - [onPackageAdded] callback so the activity can notifyDataSetChanged
 *    on its adapters.
 *
 * Self-package broadcasts are ignored — installer churn during the
 * launcher's own self-update would otherwise trash the layout.
 *
 * Lifecycle: pair [start]/[stop] with `onStart`/`onStop`.
 */
class PackageReceiverBridge(
    private val context: Context,
    private val onPackageRemoved: (pkg: String) -> Unit,
    private val onPackageAdded: () -> Unit,
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            // Don't react to our own package — installer churn during the
            // launcher's own self-update would otherwise trash the layout.
            if (pkg == c.packageName) return
            when (intent.action) {
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (replacing) return // app is being updated, not uninstalled
                    AppRegistry.invalidate()
                    AppRegistry.prewarm(c)
                    onPackageRemoved(pkg)
                }
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED -> {
                    IconMask.clearCache()
                    AppRegistry.invalidate()
                    AppRegistry.prewarm(c)
                    onPackageAdded()
                }
            }
        }
    }

    private var registered: Boolean = false

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { /* not registered */ }
        registered = false
    }
}
