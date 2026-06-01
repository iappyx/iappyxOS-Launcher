/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.content.Context

/**
 * Persistent record of which profile geofences the user is currently
 * inside. The geofence broadcast receiver
 * ([com.iappyx.launcher.GeofenceTransitionReceiver]) updates this on
 * ENTER / EXIT; [ProfileWatcher] reads it in its evaluation loop.
 *
 * Backed by SharedPreferences so the state survives process death — a
 * geofence ENTER fired while the launcher process was killed still
 * shows up the next time the activity comes back, and the matcher can
 * activate the right profile on launch.
 */
object ProfileGeofenceState {
    private const val PREFS = "iappyx_profile_geofence_state"
    private const val KEY_INSIDE = "inside_slugs"

    /** Slugs currently inside. Plain copy — caller must not mutate. */
    fun snapshot(context: Context): Set<String> {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getStringSet(KEY_INSIDE, emptySet()).orEmpty().toSet()
    }

    fun setInside(context: Context, slug: String, inside: Boolean) {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = p.getStringSet(KEY_INSIDE, emptySet()).orEmpty().toMutableSet()
        val changed = if (inside) current.add(slug) else current.remove(slug)
        if (!changed) return
        p.edit().putStringSet(KEY_INSIDE, current).apply()
    }

    /** Drop a slug from the inside set (used when a profile is deleted
     *  or its trigger changes away from Geofence). */
    fun forget(context: Context, slug: String) = setInside(context, slug, false)
}
