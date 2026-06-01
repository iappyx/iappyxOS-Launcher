/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.RemoteViews

/**
 * Custom [AppWidgetHost] subclass that returns our [IappyxWidgetHostView]
 * instead of the default [AppWidgetHostView]. The custom host view logs
 * inflation failures and keeps its last-known good view visible instead of
 * blanking to the system error view ("Couldn't add widget" / "Kan widget niet
 * toevoegen") on transient RemoteViews apply failures.
 */
class IappyxWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView {
        return IappyxWidgetHostView(context)
    }
}

/**
 * HostView that:
 *  - Logs RemoteViews apply failures so we can see WHY the system error view
 *    fires (adapter-based RV without BIND_REMOTEVIEWS, resource references the
 *    provider package no longer exposes, etc.).
 *  - Refuses to blank to the generic error view on a transient failure —
 *    keeps the current displayed layout until a successful apply replaces it.
 */
class IappyxWidgetHostView(context: Context) : AppWidgetHostView(context) {
    companion object { private const val TAG = "iappyxLauncher/HostView" }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        try {
            super.updateAppWidget(remoteViews)
        } catch (t: Throwable) {
            Log.w(TAG, "updateAppWidget failed for appWidgetId=${appWidgetId}", t)
            // Don't rethrow — just leave the current view as-is so the user
            // keeps seeing whatever last rendered correctly.
        }
    }

    // Default system getErrorView() is kept — so failing widgets show the
    // "Couldn't add widget"/"Kan widget niet toevoegen" text rather than
    // silently blanking. Tag [TAG] in logcat carries the actual Throwable for
    // diagnosis.
}
