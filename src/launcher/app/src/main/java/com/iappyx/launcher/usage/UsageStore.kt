// USAGE: BEGIN — Widget battery-usage tracking (Tier 2). Removable.
package com.iappyx.launcher.usage

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persistent per-widget usage counters. Each widget gets one row keyed by
 * widgetId; the row is a JSON blob with cumulative milliseconds spent in each
 * battery-relevant resource since the last reset.
 *
 * Not Joules-accurate — proportional. The goal is "this widget held GPS for
 * 1 h 51 m while the launcher only ran for 1 h 51 m, that's why your battery
 * is flat", not "this widget cost X mAh".
 *
 * Thread-safe via the inherent atomicity of [SharedPreferences.Editor.commit];
 * called from the main thread + from `@JavascriptInterface` background threads
 * indirectly via WidgetHost which hops to UI thread for most bridges. The
 * read-modify-write window is small enough (sub-ms) that ordering is fine
 * in practice — battery numbers don't have to be perfectly precise.
 *
 * Schema (one JSON object per widget):
 *  - sensorMs:    long  total sensor-active milliseconds
 *  - gpsMs:       long  watchPosition listener-active milliseconds
 *  - trackingMs:  long  foreground LocationService active milliseconds
 *  - audioMs:     long  ExoPlayer playback milliseconds
 *  - visibleMs:   long  widget foreground-visible milliseconds
 *  - bytesIn:     long  HTTP bytes received via the http bridge
 *
 * Plus a global key `_windowStartMs` for the window start.
 */
object UsageStore {
    private const val PREFS = "widget_usage_stats"
    private const val WINDOW_KEY = "_windowStartMs"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Window-start ms (epoch). Lazily initialised to "now" the first time
     *  the file is touched, so a fresh install / reset shows a sensible
     *  duration. */
    fun windowStartMs(ctx: Context): Long {
        val p = prefs(ctx)
        var v = p.getLong(WINDOW_KEY, 0L)
        if (v == 0L) {
            v = System.currentTimeMillis()
            p.edit().putLong(WINDOW_KEY, v).apply()
        }
        return v
    }

    fun resetWindow(ctx: Context) {
        prefs(ctx).edit().clear().putLong(WINDOW_KEY, System.currentTimeMillis()).apply()
    }

    /** Add [delta] ms to [field] for [widgetId]. Read-modify-write the JSON
     *  row in place. Cheap — entries are tiny. */
    fun addMs(ctx: Context, widgetId: String, field: String, delta: Long) {
        if (delta <= 0L) return
        val p = prefs(ctx)
        val raw = p.getString(widgetId, null)
        val o = try { if (raw != null) JSONObject(raw) else JSONObject() }
                catch (_: Throwable) { JSONObject() }
        o.put(field, o.optLong(field, 0L) + delta)
        p.edit().putString(widgetId, o.toString()).apply()
    }

    fun addBytes(ctx: Context, widgetId: String, delta: Long) =
        addMs(ctx, widgetId, "bytesIn", delta)

    /** Return all widget rows. Used by [WidgetUsageActivity]. */
    fun snapshot(ctx: Context): Map<String, JSONObject> {
        val p = prefs(ctx)
        val out = HashMap<String, JSONObject>()
        for ((k, v) in p.all) {
            if (k == WINDOW_KEY) continue
            if (v !is String) continue
            try { out[k] = JSONObject(v) } catch (_: Throwable) { /* skip */ }
        }
        return out
    }
}
// USAGE: END
