/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.iappyx.launcher.command.CommandSession
import com.iappyx.launcher.command.LauncherCommandRunner
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.widget.CommandPanelHost

/**
 * Owns the AI command-panel surface that lives at pager position 0:
 * the [CommandSession] (chat conversation state) and the
 * [CommandPanelHost] view reference. Survives pager recycle / re-bind
 * because the session is a controller-scoped singleton.
 *
 * Also registers a process-local [LauncherActivity.ACTION_CHAT_HISTORY_CLEARED]
 * receiver that lets Settings → Clear chat history wipe the running
 * session without instantiating one for a launcher that's not in
 * foreground.
 *
 * @param activity host activity (for Context, lifecycle, runOnUiThread)
 * @param store layout store passed through to the runner
 * @param listener forwards runner-side callbacks (layout get/apply,
 *        current home page index) back to the activity. The activity
 *        provides this so the controller doesn't reach for activity
 *        state directly.
 */
class CommandPanelController(
    private val activity: LauncherActivity,
    private val store: PlacementStore,
    private val listener: Listener,
) {

    /** Forwarded from the [LauncherCommandRunner.Listener] — the
     *  activity does the actual layout writes. */
    interface Listener {
        fun getLayout(): HomeLayout
        fun applyLayout(newLayout: HomeLayout)
        fun currentHomePageIndex(): Int
    }

    /** Conversation state lives here so it survives pager recycle / re-bind
     *  (HomePagerAdapter creates a fresh [CommandPanelHost] view on every
     *  bind, but feeds it this same session). */
    private val sessionDelegate: Lazy<CommandSession> = lazy {
        val runner = LauncherCommandRunner(
            activity = activity,
            store = store,
            listener = object : LauncherCommandRunner.Listener {
                override fun getLayout() = listener.getLayout()
                override fun applyLayout(newLayout: HomeLayout) {
                    activity.runOnUiThread { listener.applyLayout(newLayout) }
                }
                override fun currentHomePageIndex() = listener.currentHomePageIndex()
            },
        )
        CommandSession(activity, runner)
    }

    /** Force-realizes the lazy session. Use via [withSession] for code
     *  paths that genuinely need the session (e.g. voice-transcript send). */
    val session: CommandSession get() = sessionDelegate.value

    private var hostRef: CommandPanelHost? = null

    /** Currently-bound CommandPanelHost view, if any. Useful for tab
     *  switching, refresh nudges, manual-mode entry. Null before the
     *  pager has bound position 0 OR after the host was recycled. */
    val host: CommandPanelHost? get() = hostRef

    /** Build a fresh [CommandPanelHost] bound to this controller's
     *  session. Called by [HomePagerAdapter] on bind of pager position 0. */
    fun createPanel(): CommandPanelHost {
        val h = CommandPanelHost(activity)
        h.bind(session)
        hostRef = h
        return h
    }

    /** Drop in-memory chat state IF the lazy session has been realized.
     *  Avoids touching the property accessor (which would force lazy
     *  init for nothing — Settings already cleared the DB before this
     *  fires, so a fresh session would just load zero rows on construct). */
    fun clearHistoryIfInitialized() {
        if (sessionDelegate.isInitialized()) {
            session.clearHistory()
        }
    }

    private var chatHistoryClearedReceiver: BroadcastReceiver? = null

    fun start() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                clearHistoryIfInitialized()
            }
        }
        val filter = IntentFilter(LauncherActivity.ACTION_CHAT_HISTORY_CLEARED)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(rx, filter)
        }
        chatHistoryClearedReceiver = rx
    }

    fun stop() {
        chatHistoryClearedReceiver?.let {
            try { activity.unregisterReceiver(it) } catch (_: Throwable) { /* not registered */ }
        }
        chatHistoryClearedReceiver = null
    }
}
