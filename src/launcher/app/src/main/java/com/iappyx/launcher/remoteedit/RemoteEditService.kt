/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * REMOTE EDIT FEATURE — foreground service that keeps the editor
 * sharing alive while the user does other things on the phone (locks
 * the screen, opens another app, etc.).
 *
 * The service does NOT own the EditServer — RemoteEditActivity still
 * does. The service's job is to keep the process at foreground
 * priority + hold a partial wake lock + display a persistent
 * notification with a Stop action. As long as this service is alive,
 * Android keeps the launcher process running and its EditServer
 * threads continue serving the laptop. Stopping the service (via
 * notification or explicit user action) broadcasts STOP, which the
 * activity catches and finish()es itself — shutting down the server.
 *
 * To remove: delete this file + the `<service>` block in the
 * manifest's REMOTE EDIT FEATURE region + the START/STOP calls in
 * RemoteEditActivity.
 */
package com.iappyx.launcher.remoteedit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.iappyx.launcher.R

class RemoteEditService : Service() {

    companion object {
        const val CHANNEL_ID = "iappyx_remoteedit"
        const val NOTIF_ID = 4711
        /** Service intent action to stop the service from outside
         *  (notification tap, programmatic). When fired, [onStartCommand]
         *  calls stopSelf which triggers onDestroy → STOP_BROADCAST. */
        const val ACTION_STOP = "com.iappyx.launcher.remoteedit.STOP"
        /** Refresh the notification's body text (e.g. after the
         *  activity learned a pairing code or got a new client). */
        const val ACTION_REFRESH = "com.iappyx.launcher.remoteedit.REFRESH"
        /** Show the floating right-edge dot. Requires the user has
         *  granted SYSTEM_ALERT_WINDOW; service silently no-ops if
         *  not. Activity sends this after the user opts in. */
        const val ACTION_SHOW_OVERLAY = "com.iappyx.launcher.remoteedit.SHOW_OVERLAY"
        /** Hide the floating dot but keep the share running. */
        const val ACTION_HIDE_OVERLAY = "com.iappyx.launcher.remoteedit.HIDE_OVERLAY"
        /** Broadcast fired on service destruction so the activity
         *  can finish() itself in turn, tearing down the EditServer. */
        const val STOP_BROADCAST = "com.iappyx.launcher.remoteedit.STOP_BROADCAST"

        /** Volatile so [buildNotification] picks up the latest text
         *  every time it's rebuilt. The activity writes to this from
         *  its onClientStateChanged callback + initial startServer. */
        @Volatile var statusText: String = ""

        /** The currently-running EditServer, parked here so it
         *  survives [RemoteEditActivity]'s destruction. The
         *  activity used to own this in its own field, but
         *  pressing HOME from a launcher's child activity tears
         *  the activity down, which would have torn the share
         *  down too. Now the service is the lifecycle owner —
         *  EditServer dies only when the service dies, which
         *  only happens via explicit Stop. */
        @Volatile var activeServer: com.iappyx.launcher.remoteedit.server.EditServer? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    /** Optional floating-status overlay. Null when the user hasn't
     *  opted in OR hasn't granted SYSTEM_ALERT_WINDOW. Shown via
     *  ACTION_SHOW_OVERLAY, hidden via ACTION_HIDE_OVERLAY. */
    private var overlay: RemoteEditOverlay? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, buildNotification())
            }
            ACTION_SHOW_OVERLAY -> {
                if (overlay == null) overlay = RemoteEditOverlay(this)
                overlay?.show()
            }
            ACTION_HIDE_OVERLAY -> {
                overlay?.hide()
            }
        }
        // STICKY so a transient OOM kill restarts us; activity will
        // re-stamp statusText on next bind.
        return START_STICKY
    }

    override fun onDestroy() {
        try { overlay?.hide() } catch (_: Throwable) {}
        overlay = null
        // The service is the EditServer's lifecycle owner now: when
        // we go away, the share goes away too. Stop the server here
        // so the OS reclaims its port/sockets cleanly.
        try { activeServer?.stop() } catch (_: Throwable) {}
        activeServer = null
        releaseWakeLock()
        // Tell the activity (if still alive) that sharing is stopping
        // so it can finish() itself and not show stale URLs.
        try {
            sendBroadcast(Intent(STOP_BROADCAST).setPackage(packageName))
        } catch (_: Throwable) { /* best-effort */ }
        statusText = ""
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remoteedit_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.remoteedit_notif_channel_desc)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        // Tap notification → open the activity in single-top mode so
        // the existing instance gets focus rather than spawning a new
        // one (which would double-start the server).
        val openIntent = Intent(this, RemoteEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Stop action → re-enters this service with ACTION_STOP →
        // stopSelf → onDestroy → broadcast → activity finishes.
        val stopIntent = Intent(this, RemoteEditService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = statusText.ifBlank { getString(R.string.remoteedit_notif_default_text) }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.remoteedit_notif_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.remoteedit_notif_stop), stopPi,
            )
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        // PARTIAL_WAKE_LOCK keeps the CPU running while the screen
        // can sleep. 8h cap is a defensive upper bound — the user
        // explicitly stopping the share releases earlier; OOM kill
        // releases via PowerManager auto-release.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iappyx:remoteedit").apply {
            setReferenceCounted(false)
            acquire(8L * 60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }
}
