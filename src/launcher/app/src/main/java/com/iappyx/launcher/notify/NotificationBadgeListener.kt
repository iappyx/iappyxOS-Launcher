/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.iappyx.launcher.LauncherPrefs

/** A single notification action (button). [remoteInput] is non-null for
 *  free-form reply actions (messaging apps) — see [NotificationBadgeListener.reply]. */
data class NotifAction(
    val title: CharSequence,
    val actionIntent: PendingIntent?,
    val remoteInput: RemoteInput?,
)

/** A user-facing notification surfaced in the long-press menu. */
data class NotifItem(
    val key: String,
    val title: CharSequence,
    val text: CharSequence,
    val time: Long,
    val icon: Drawable?,
    val contentIntent: PendingIntent?,
    val isClearable: Boolean,
    val actions: List<NotifAction>,
)

/**
 * Bound by the system after the user grants Notification access in
 * Settings → Notifications → Notification access → iappyxOS Launcher.
 * Reads active notifications and pushes per-package counts to [BadgeStore].
 *
 * Counting policy (Phase 1): one badge unit per active notification, after
 * filtering out ongoing/foreground/group-summary/transport notifications.
 * Apps that post one notification per chat (WhatsApp, Telegram) work
 * correctly; apps that post a single summary with `notification.number`
 * undercounted — tune in Phase 2 if it becomes a complaint.
 */
class NotificationBadgeListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val recountRunnable = Runnable { recountAndPublish() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        scheduleRecount()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        BadgeStore.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        scheduleRecount()
        // PLUGINS: BEGIN — forward to the plugin notifications bus so
        // plugins with the `notification:read` capability can react.
        // Cheap when no plugin has subscribed (bus short-circuits on
        // an empty subscription map).
        if (sbn != null) {
            try {
                com.iappyx.launcher.plugins.PluginNotificationsBus.dispatch(this, sbn)
            } catch (_: Throwable) { /* never block badging */ }
        }
        // PLUGINS: END
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        scheduleRecount()
    }

    /** Debounce — a burst of simultaneous posts collapses into one recount. */
    fun scheduleRecount() {
        main.removeCallbacks(recountRunnable)
        main.postDelayed(recountRunnable, 50L)
    }

    private fun recountAndPublish() {
        // Honour the user's pref — if they've turned the feature off, leave
        // the store empty regardless of what's posted.
        if (!LauncherPrefs(this).notificationBadgesEnabled) {
            BadgeStore.clear()
            return
        }
        val active = try { activeNotifications } catch (_: Exception) { return }
        if (active == null) { BadgeStore.clear(); return }
        // First pass: figure out which package+group keys have at least one
        // CHILD notification posted. We use this to keep group SUMMARIES that
        // are the only thing posted (e.g. WhatsApp / Telegram often post just
        // the summary "3 new messages"), and to skip summaries when their
        // children are already present (avoid double-count).
        val groupsWithChildren = HashSet<String>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (!isSummary) {
                val grp = n.group ?: sbn.groupKey
                if (grp != null) groupsWithChildren.add("${sbn.packageName}|$grp")
            }
        }
        val counts = HashMap<String, Int>()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            // Skip foreground services / music players / progress / transport.
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            when (n.category) {
                Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_TRANSPORT,
                Notification.CATEGORY_PROGRESS,
                -> continue
            }
            // Group summaries: skip ONLY if a child for the same package+group
            // is already counted — otherwise the summary IS the user-visible
            // unread badge (apps that don't post children separately).
            val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (isSummary) {
                val grp = n.group ?: sbn.groupKey
                val key = "${sbn.packageName}|$grp"
                if (groupsWithChildren.contains(key)) continue
            }
            val pkg = sbn.packageName ?: continue
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        BadgeStore.set(counts)
    }

    companion object {
        /** Live reference (when bound) so the launcher can kick a recount on
         *  resume — useful right after the user grants access, since the
         *  service binds asynchronously and may not have published yet by the
         *  time the activity becomes visible. */
        @Volatile private var instance: NotificationBadgeListener? = null

        /** Force the listener to recount + publish. No-op if not bound. */
        fun forceRecount() { instance?.scheduleRecount() }

        /** Current user-facing notifications for [pkg], newest first. Empty if
         *  the listener isn't bound. Mirrors the badge filter: drops
         *  ongoing/transport, and a group summary when its children are also
         *  present (so we don't show "3 messages" + the 3 messages). */
        fun notificationsFor(context: Context, pkg: String): List<NotifItem> {
            val svc = instance ?: return emptyList()
            val active = try { svc.activeNotifications } catch (_: Exception) { return emptyList() }
                ?: return emptyList()
            val groupsWithChildren = HashSet<String>()
            for (sbn in active) {
                if (sbn.packageName != pkg) continue
                val n = sbn.notification ?: continue
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) == 0) {
                    (n.group ?: sbn.groupKey)?.let { groupsWithChildren.add(it) }
                }
            }
            val out = ArrayList<NotifItem>()
            for (sbn in active) {
                if (sbn.packageName != pkg) continue
                val n = sbn.notification ?: continue
                if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
                if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
                    val grp = n.group ?: sbn.groupKey
                    if (grp != null && groupsWithChildren.contains(grp)) continue
                }
                val ex = n.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)
                    ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ""
                val text = ex.getCharSequence(Notification.EXTRA_TEXT)
                    ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT) ?: ""
                val icon = try { n.smallIcon?.loadDrawable(context) } catch (_: Throwable) { null }
                val actions = n.actions?.map { a ->
                    NotifAction(
                        a.title ?: "",
                        a.actionIntent,
                        a.remoteInputs?.firstOrNull { it.allowFreeFormInput },
                    )
                } ?: emptyList()
                out.add(
                    NotifItem(
                        sbn.key, title, text, sbn.postTime, icon,
                        n.contentIntent, sbn.isClearable, actions,
                    ),
                )
            }
            return out.sortedByDescending { it.time }
        }

        /** Dismiss the notification with [key] (the same key from [NotifItem]).
         *  No-op if not bound or not clearable. */
        fun dismiss(key: String) {
            try { instance?.cancelNotification(key) } catch (_: Throwable) {}
        }

        /** Send a free-form reply to a messaging notification's [action] using
         *  its RemoteInput. No-op if the action has no RemoteInput / intent. */
        fun reply(context: Context, action: NotifAction, message: CharSequence): Boolean {
            val ri = action.remoteInput ?: return false
            val pi = action.actionIntent ?: return false
            return try {
                val fill = Intent()
                val results = android.os.Bundle().apply { putCharSequence(ri.resultKey, message) }
                RemoteInput.addResultsToIntent(arrayOf(ri), fill, results)
                pi.send(context, 0, fill)
                true
            } catch (_: Throwable) { false }
        }

        /** Has the user granted Notification access to this listener? */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${NotificationBadgeListener::class.java.name}"
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            ) ?: return false
            return flat.split(":").any { it == expected }
        }

        /** Open the system Settings page where the user can flip the toggle. */
        fun openSystemSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
