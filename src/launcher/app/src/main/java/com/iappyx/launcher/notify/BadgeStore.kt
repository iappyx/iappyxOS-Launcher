/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.notify

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory map of `package → unread count`. Populated by
 * [NotificationBadgeListener] from active StatusBarNotifications, consumed by
 * IconCell / FolderCell which register as observers and redraw their badge
 * when the map changes.
 */
object BadgeStore {

    interface Observer { fun onBadgesChanged() }

    private val counts = ConcurrentHashMap<String, Int>()
    private val observers = ArrayList<Observer>()
    private val main = Handler(Looper.getMainLooper())

    fun get(packageName: String): Int = counts[packageName] ?: 0

    /** Snapshot of all currently-tracked (package → count) pairs.
     *  Used by the remote-edit web interface to render badges on
     *  the editor's icon cells. Returns a fresh map so callers can
     *  iterate / send it without worrying about concurrent
     *  modification. */
    fun snapshot(): Map<String, Int> = HashMap(counts)

    /** Replace all counts atomically. Zero-and-negative entries are stripped
     *  so `get` returns 0 for cleared apps without us tracking dead keys. */
    fun set(map: Map<String, Int>) {
        counts.clear()
        for ((k, v) in map) if (v > 0) counts[k] = v
        notifyObservers()
    }

    fun clear() {
        if (counts.isEmpty()) return
        counts.clear()
        notifyObservers()
    }

    fun addObserver(o: Observer) {
        synchronized(observers) { if (!observers.contains(o)) observers.add(o) }
    }

    fun removeObserver(o: Observer) {
        synchronized(observers) { observers.remove(o) }
    }

    private fun notifyObservers() {
        val snapshot = synchronized(observers) { observers.toList() }
        // Always dispatch on main — listener service callbacks can come from
        // a binder thread.
        main.post { snapshot.forEach { it.onBadgesChanged() } }
    }
}
