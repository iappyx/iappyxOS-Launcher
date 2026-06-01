/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * REMOTE EDIT FEATURE — entry point. Owns the HTTP server's lifetime.
 *
 * Pairs with [RemoteEditService]:
 *  - Activity starts the EditServer + asks the service to run a
 *    foreground notification + hold a partial wake lock.
 *  - As long as the service is alive, the launcher process stays at
 *    foreground priority, so the activity's EditServer threads stay
 *    alive even when the user locks the screen or opens another app.
 *  - Server is stopped on activity onDestroy OR when the service
 *    broadcasts STOP_BROADCAST (notification "Stop" tap).
 *
 * To remove the feature, delete the entire `remoteedit/` package +
 * the manifest entry + the Settings row.
 */
package com.iappyx.launcher.remoteedit

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.iappyx.launcher.R
import com.iappyx.launcher.remoteedit.server.EditServer
import com.iappyx.launcher.widget.showThemed
import com.iappyx.launcher.remoteedit.server.NetworkInfoProbe

class RemoteEditActivity : AppCompatActivity() {

    /** Local reference for convenience; the source of truth for
     *  "is the server alive" is [RemoteEditService.activeServer]
     *  (a static volatile). Activity does NOT own the server
     *  lifecycle — only the service does. */
    private val server: EditServer? get() = RemoteEditService.activeServer
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var urlView: TextView
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var disconnectBtn: Button
    private lateinit var stopSharingBtn: Button
    private lateinit var overlayToggleBtn: Button
    private lateinit var idlePane: View
    private lateinit var activePane: View
    private lateinit var startSharingBtn: Button

    /** Service broadcasts STOP_BROADCAST when it dies (notification
     *  "Stop" tap or system kill). Activity catches it, shuts down
     *  the EditServer, and finish()es itself so the UI doesn't show
     *  stale URLs/codes that won't actually work. */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RemoteEditService.STOP_BROADCAST) {
                // Service stopped the EditServer in its own onDestroy.
                // Stay on this screen and flip the UI back to the
                // idle pane so the user can immediately Start again
                // (or just see "sharing is off"). The previous
                // behaviour was to finish() the activity, which felt
                // surprising — the screen would vanish as soon as
                // Stop was tapped.
                if (!isFinishing) setSharingActive(false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_edit)
        com.iappyx.launcher.SettingsScaffold.attach(
            this, getString(com.iappyx.launcher.R.string.settings_remoteedit_label),
        )
        // KEEP_SCREEN_ON is applied conditionally in [setSharingActive]:
        // only while sharing is on does the URL/code matter and the
        // user benefit from no dimming. Idle pane → screen dims
        // normally.

        urlView = findViewById(R.id.remoteedit_url)
        codeView = findViewById(R.id.remoteedit_code)
        statusView = findViewById(R.id.remoteedit_status)
        disconnectBtn = findViewById(R.id.remoteedit_disconnect)
        stopSharingBtn = findViewById(R.id.remoteedit_stop_sharing)
        overlayToggleBtn = findViewById(R.id.remoteedit_overlay_toggle)
        idlePane = findViewById(R.id.remoteedit_idle_pane)
        activePane = findViewById(R.id.remoteedit_active_pane)
        startSharingBtn = findViewById(R.id.remoteedit_start_sharing)
        startSharingBtn.setOnClickListener {
            // Explicit Start: kicks the server + service, flips to the
            // active pane via populateUiFromServer.
            startServer()
        }
        disconnectBtn.setOnClickListener {
            server?.dropClient()
            statusView.setText(R.string.remoteedit_status_waiting)
            disconnectBtn.visibility = View.GONE
        }
        stopSharingBtn.setOnClickListener {
            // User explicitly stopping sharing: tell the service to
            // shut down. The STOP_BROADCAST receiver will flip the
            // UI back to the idle pane; we also do it optimistically
            // here so the user sees instant feedback even if the
            // broadcast takes a frame.
            startService(
                Intent(this, RemoteEditService::class.java)
                    .setAction(RemoteEditService.ACTION_STOP),
            )
            setSharingActive(false)
        }
        overlayToggleBtn.setOnClickListener { onOverlayTogglePressed() }

        // Subscribe BEFORE startService so we don't miss an early
        // STOP_BROADCAST (defensive — onStop sequence on this path
        // is essentially synchronous).
        val filter = IntentFilter(RemoteEditService.STOP_BROADCAST)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back navigates AWAY from the screen but KEEPS the
                // share alive (foreground service keeps running). This
                // is the whole point of the v2 design — user can do
                // anything on the phone while the laptop session
                // continues. To actually stop sharing, the user taps
                // the explicit Stop sharing button (or the notification
                // action).
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Do NOT auto-start sharing on entry — the user opts in via the
        // explicit Start sharing button on the idle pane. If the service
        // is already running (e.g. user re-entered via the notification
        // or the floating overlay dot), pick up that existing session.
        val existing = RemoteEditService.activeServer
        if (existing != null) {
            attachCallbacks(existing)
            populateUiFromServer(existing)
            refreshServiceNotification()
            setSharingActive(true)
        } else {
            setSharingActive(false)
        }
    }

    /** Single source of truth for which pane is visible. Also gates
     *  the KEEP_SCREEN_ON flag — only worth holding while a URL/code
     *  is on-screen to be read. Called from onResume, the Start
     *  button (via startServer's success path), the Stop button's
     *  STOP_BROADCAST callback, and a service-side stop. */
    private fun setSharingActive(active: Boolean) {
        if (active) {
            idlePane.visibility = View.GONE
            activePane.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activePane.visibility = View.GONE
            idlePane.visibility = View.VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Reset transient UI bits so the next Start shows a clean
            // status line rather than the last-seen connection state.
            disconnectBtn.visibility = View.GONE
            urlView.text = "—"
            codeView.text = "—"
            statusView.setText(R.string.remoteedit_status_starting)
            overlayToggleBtn.setText(R.string.remoteedit_overlay_show)
            overlayShown = false
        }
    }

    /** No-op now: server is NOT torn down on pause anymore. The
     *  foreground service is what keeps the process priority high
     *  enough for the EditServer's threads to keep serving while
     *  this activity is paused / stopped. */
    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(stopReceiver) } catch (_: Throwable) {}
        // Do NOT stop the service or the EditServer on activity
        // destroy. Pressing HOME from a launcher's child activity
        // finishes it — but the user still wants the share to
        // keep running. The server is owned by the service now;
        // it dies only when the user explicitly stops sharing (via
        // the in-activity button or the notification's Stop action).
        super.onDestroy()
    }

    private fun startServer() {
        val existing = RemoteEditService.activeServer
        if (existing != null) {
            // Server is already running (e.g. user came back from
            // HOME via the notification). Just re-bind callbacks
            // for live status updates + refill the UI from current
            // state.
            attachCallbacks(existing)
            populateUiFromServer(existing)
            refreshServiceNotification()
            setSharingActive(true)
            return
        }
        try {
            val ip = NetworkInfoProbe.lanAddress(this) ?: run {
                setSharingActive(true)
                statusView.setText(R.string.remoteedit_status_failed)
                return
            }
            // EditServer is parked in the service's static field so
            // it survives activity recreation. Use applicationContext
            // so a destroyed activity can be GC'd cleanly (the
            // server only needs Context for the launcher's public
            // APIs; the Activity-using chat code path still works
            // for as long as THIS activity is alive, which is the
            // expected case during chatting).
            val s = EditServer(this)
            attachCallbacks(s)
            s.start()
            RemoteEditService.activeServer = s
            urlView.text = "http://$ip:${s.port}/"
            codeView.text = s.pairingCode.chunked(2).joinToString(" ")
            statusView.setText(R.string.remoteedit_status_waiting)
            // Seed the initial notification body with the URL + code
            // so the user can see them in the shade even without
            // coming back to this screen.
            RemoteEditService.statusText = "http://$ip:${s.port}/  ·  " +
                s.pairingCode.chunked(2).joinToString(" ")
            // Start the foreground service. While alive it keeps
            // the process running so the EditServer's threads
            // survive lock + app-switch.
            ContextCompat.startForegroundService(
                this, Intent(this, RemoteEditService::class.java),
            )
            setSharingActive(true)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "remote-edit server start failed: ${t.message}", t)
            setSharingActive(true)
            statusView.setText(R.string.remoteedit_status_failed)
        }
    }

    private fun attachCallbacks(s: EditServer) {
        s.onClientStateChanged = { hasClient, label ->
            mainHandler.post {
                if (hasClient) {
                    statusView.text = getString(
                        R.string.remoteedit_status_connected_format, label,
                    )
                    disconnectBtn.visibility = View.VISIBLE
                    RemoteEditService.statusText =
                        getString(R.string.remoteedit_status_connected_format, label)
                } else {
                    statusView.setText(R.string.remoteedit_status_waiting)
                    disconnectBtn.visibility = View.GONE
                    RemoteEditService.statusText =
                        getString(R.string.remoteedit_status_waiting)
                }
                refreshServiceNotification()
            }
        }
    }

    private fun populateUiFromServer(s: EditServer) {
        val ip = NetworkInfoProbe.lanAddress(this)
        if (ip != null) {
            urlView.text = "http://$ip:${s.port}/"
        }
        codeView.text = s.pairingCode.chunked(2).joinToString(" ")
        if (s.hasClient()) {
            disconnectBtn.visibility = View.VISIBLE
        }
    }

    /** Track our local belief about overlay state. Service is the
     *  source of truth; we just toggle on user taps. Resets to false
     *  on activity recreation (acceptable — user can re-tap). */
    private var overlayShown = false

    private fun onOverlayTogglePressed() {
        if (!RemoteEditOverlay.canShow(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.remoteedit_overlay_perm_title)
                .setMessage(R.string.remoteedit_overlay_perm_message)
                .setPositiveButton(R.string.remoteedit_overlay_perm_grant) { _, _ ->
                    // Deep-link to the system's "Display over other
                    // apps" page. User grants → returns here → next
                    // tap hits the can-show branch.
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    } catch (_: Throwable) { /* settings unavailable */ }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showThemed()
            return
        }
        if (overlayShown) {
            startService(
                Intent(this, RemoteEditService::class.java)
                    .setAction(RemoteEditService.ACTION_HIDE_OVERLAY),
            )
            overlayShown = false
            overlayToggleBtn.setText(R.string.remoteedit_overlay_show)
        } else {
            startService(
                Intent(this, RemoteEditService::class.java)
                    .setAction(RemoteEditService.ACTION_SHOW_OVERLAY),
            )
            overlayShown = true
            overlayToggleBtn.setText(R.string.remoteedit_overlay_hide)
        }
    }

    private fun refreshServiceNotification() {
        try {
            startService(
                Intent(this, RemoteEditService::class.java)
                    .setAction(RemoteEditService.ACTION_REFRESH),
            )
        } catch (_: Throwable) { /* service may not be up yet */ }
    }

    companion object {
        private const val TAG = "iappyxRemoteEdit"
    }
}
