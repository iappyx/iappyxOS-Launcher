/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * REMOTE EDIT FEATURE — SSE channel that pushes phone-side state
 * changes to the browser editor. Without this, the web app only
 * learns about layout/wallpaper/clipping changes when the user
 * manually switches tabs or hard-refreshes. With this, anything
 * that happens on the phone (drag a cell, share-target a clipping,
 * profile auto-switch fires, AI tool runs) propagates to the laptop
 * within ~200ms.
 *
 * How it works:
 *  - The launcher already broadcasts CLIPPINGS_CHANGED_ACTION,
 *    LAYOUT_CHANGED_ACTION and WALLPAPER_CHANGED_ACTION for every
 *    edit. This API registers a single BroadcastReceiver per active
 *    emitter and forwards a tiny JSON envelope ({"type":"layout"} /
 *    {"type":"wallpaper", "id":"..."}) to each subscribed browser.
 *  - Multi-subscriber: a single user could have two browser tabs
 *    open; both subscribe to /api/state/stream and each gets its
 *    own SseEmitter + receiver.
 *  - Cleanup: receivers are unregistered when the SSE socket drops
 *    (browser tab closed, network blip → reconnect).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.notify.BadgeStore
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.remoteedit.server.SseEmitter
import org.json.JSONObject

class StateStreamApi(private val context: Context) {

    fun subscribe(ex: MicroHttpServer.Exchange) {
        val emitter = SseEmitter(ex)
        emitter.start()
        // Initial hello so the browser can confirm the connection
        // is live (also doubles as a kick if it just reconnected
        // after a transient drop and wants to refetch state).
        emitter.send("hello", JSONObject().put("ok", true).toString())

        // Push notification-badge changes to the browser. BadgeStore
        // fires its observers whenever the launcher's notification
        // listener updates counts — we forward the full snapshot so
        // the editor can re-paint badge pills without a roundtrip
        // back to /api/state.
        val badgeObserver = object : BadgeStore.Observer {
            override fun onBadgesChanged() {
                val prefs = LauncherPrefs(context)
                val countsJson = JSONObject()
                if (prefs.notificationBadgesEnabled) {
                    for ((pkg, count) in BadgeStore.snapshot()) {
                        if (count > 0) countsJson.put(pkg, count)
                    }
                }
                emitter.send(
                    "state-change",
                    JSONObject()
                        .put("type", "badges")
                        .put("counts", countsJson)
                        .toString(),
                )
            }
        }
        BadgeStore.addObserver(badgeObserver)

        // Backup import progress. The BackupApi.import handler writes
        // to BackupProgressBus on each phase / per extracted entry; we
        // forward those events to every connected editor as another
        // state-change kind so the import modal can render a live
        // progress bar instead of a blocking spinner.
        val backupListener = object : BackupProgressBus.Listener {
            override fun onProgress(phase: String, done: Long, total: Long) {
                emitter.send(
                    "state-change",
                    JSONObject()
                        .put("type", "backup-progress")
                        .put("phase", phase)
                        .put("done", done)
                        .put("total", total)
                        .toString(),
                )
            }
        }
        BackupProgressBus.addListener(backupListener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val payload = JSONObject()
                when (action) {
                    LauncherPrefs.CLIPPINGS_CHANGED_ACTION -> {
                        // CLIPPINGS_CHANGED is fired for layout
                        // mutations AND clipping additions. The web
                        // app re-fetches /api/state OR /api/clippings
                        // depending on the active tab — kind="state"
                        // is a deliberate catch-all.
                        payload.put("type", "state")
                    }
                    LauncherPrefs.LAYOUT_CHANGED_ACTION -> {
                        payload.put("type", "layout")
                        // Forward the full layout snapshot JSON so the
                        // editor can push it into wallpaper preview
                        // iframes via postMessage (mirrors the on-device
                        // iappyx.onLayoutChanged event). The "json"
                        // extra is populated by WallpaperLayoutPublisher
                        // on every layout commit.
                        intent.getStringExtra("json")?.let {
                            payload.put("layoutJson", it)
                        }
                    }
                    LauncherPrefs.WALLPAPER_CHANGED_ACTION -> {
                        payload.put("type", "wallpaper")
                        intent.getStringExtra("id")?.let { payload.put("id", it) }
                    }
                    else -> return
                }
                emitter.send("state-change", payload.toString())
            }
        }
        val filter = IntentFilter().apply {
            addAction(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
            addAction(LauncherPrefs.LAYOUT_CHANGED_ACTION)
            addAction(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
        }
        // RECEIVER_NOT_EXPORTED on API 33+: we only receive local
        // broadcasts the launcher itself sends. External apps can't
        // spoof us; we don't accept anyone else's intents either.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Block on the SSE socket — when the browser closes the tab
        // or the connection drops, awaitClose returns and we tear
        // down the receiver. HTTP server runs each handler in its
        // own pool thread, so this long-poll is fine.
        try {
            emitter.awaitClose()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
            try { BadgeStore.removeObserver(badgeObserver) } catch (_: Throwable) {}
            try { BackupProgressBus.removeListener(backupListener) } catch (_: Throwable) {}
        }
    }
}
