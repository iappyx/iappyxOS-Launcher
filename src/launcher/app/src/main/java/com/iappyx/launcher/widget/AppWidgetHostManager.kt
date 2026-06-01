/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup

/**
 * Owns the AppWidgetHost for the launcher process. Must startListening when
 * the activity is in the foreground, stopListening in background.
 *
 * HOST_ID is arbitrary but must be stable across launches so bound widget IDs
 * remain valid.
 *
 * Created [AppWidgetHostView]s are **cached per widget id** and reused across
 * page rebinds. Creating a fresh view on every rebind loses the widget's
 * RemoteViews state (the provider only pushes an update once after bind), and
 * the host would fall back to the "Cannot add widget" / "Kan widget niet
 * toevoegen" error view after the first rebind.
 */
class AppWidgetHostManager(context: Context) {
    companion object {
        const val HOST_ID = 0x69617070 // "iapp"
        const val REQ_PICK = 9001
        const val REQ_BIND = 9002
        const val REQ_CONFIGURE = 9003
    }

    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    // Use our own [AppWidgetHost] subclass so RemoteViews apply failures don't
    // blank the widget to the system "Couldn't add widget" error view.
    val host: AppWidgetHost = IappyxWidgetHost(context.applicationContext, HOST_ID)
    private val viewCache = mutableMapOf<Int, AppWidgetHostView>()
    /** Last (widthDp, heightDp) passed to [updateSize] per widget id. Guards
     *  against spamming updateAppWidgetOptions on every page rebind — each
     *  options change fires onAppWidgetOptionsChanged on the provider, which
     *  can race against other updates and surface the host error view. */
    private val lastSizeSent = mutableMapOf<Int, Pair<Int, Int>>()

    fun startListening() { host.startListening() }
    fun stopListening() { host.stopListening() }

    fun allocateId(): Int = host.allocateAppWidgetId()
    fun deleteId(id: Int) {
        viewCache.remove(id)
        lastSizeSent.remove(id)
        host.deleteAppWidgetId(id)
    }

    /** Return a host view for [appWidgetId], reusing the cached one if we have
     *  it. If the cached view is still attached to a previous parent, detach
     *  it first so it can be re-added cleanly.
     *
     *  IMPORTANT: we pass a [ContextThemeWrapper] with a plain DeviceDefault
     *  theme (not the activity's AppCompat-themed context). Without this,
     *  AppCompatDelegate's installed LayoutInflater.Factory2 upgrades every
     *  `<TextView>` in a widget's RemoteViews to AppCompatTextView, which
     *  tries to resolve AppCompat/Material attributes against OUR theme and
     *  throws InflateException on most modern widgets (e.g. Google Calendar
     *  with Material3 styles). Widget inflation must use a vanilla context. */
    fun createView(context: Context, appWidgetId: Int): AppWidgetHostView? {
        viewCache[appWidgetId]?.let { cached ->
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        val widgetCtx = plainThemeContext(context)
        val v = host.createView(widgetCtx, appWidgetId, info)
        v.setAppWidget(appWidgetId, info)
        viewCache[appWidgetId] = v
        return v
    }

    /** ContextThemeWrapper with its own plain LayoutInflater — bypasses
     *  AppCompatDelegate's view factory so widget RemoteViews inflate as
     *  real `<TextView>`/`<Button>`/`<ImageView>` etc. instead of AppCompat
     *  variants (which reject RemoteViews-whitelisted methods like
     *  `setImageResource(int)`).
     *
     *  The trick: source the inflater from [applicationContext], NOT from the
     *  activity. `LayoutInflater.from(activity)` has AppCompat's Factory2
     *  attached, and `cloneInContext` carries that factory forward. The
     *  application context's inflater has no factories installed. */
    private fun plainThemeContext(base: Context): Context {
        val app = base.applicationContext
        return object : android.view.ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault) {
            private var cachedInflater: android.view.LayoutInflater? = null
            override fun getSystemService(name: String): Any? {
                if (name == LAYOUT_INFLATER_SERVICE) {
                    return cachedInflater ?: run {
                        val fresh = android.view.LayoutInflater
                            .from(app)          // pristine: no AppCompat factory
                            .cloneInContext(this)
                        cachedInflater = fresh
                        fresh
                    }
                }
                return super.getSystemService(name)
            }
        }
    }

    /** Tell the widget provider its current on-screen size so RemoteViews can
     *  adapt. Skips the call if the size hasn't changed since the last
     *  notification — Android pushes an options-changed broadcast on every
     *  call, and providers reacting to it can race their own updates and
     *  surface the "Couldn't add widget" error view. */
    fun updateSize(appWidgetId: Int, widthDp: Int, heightDp: Int) {
        val last = lastSizeSent[appWidgetId]
        if (last != null && last.first == widthDp && last.second == heightDp) return
        lastSizeSent[appWidgetId] = widthDp to heightDp
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        try { manager.updateAppWidgetOptions(appWidgetId, opts) } catch (_: Exception) {}
    }
}
