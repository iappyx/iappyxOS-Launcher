/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.iappyx.launcher.widget.showThemed

/**
 * One-time "enjoying the launcher? consider supporting" nudge.
 *
 * Shows at most twice, and only for an established user:
 *  - First eligible once the app has been installed for [MIN_INSTALL_DAYS]
 *    (read from `firstInstallTime` — no install-date bookkeeping needed).
 *  - First showing offers Buy-a-coffee / Maybe-later / No-thanks. "Maybe
 *    later" snoozes the prompt for [SNOOZE_DAYS] exactly once.
 *  - After the snooze elapses it shows a second (final) time without the
 *    "Maybe later" option.
 *  - "Buy me a coffee" or "No thanks" (or a cancel on the second showing)
 *    dismisses it permanently.
 */
object SupportPrompt {

    private const val MIN_INSTALL_DAYS = 3L
    private const val SNOOZE_DAYS = 14L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** Small delay so the dialog doesn't slam up the instant home resumes. */
    private const val SETTLE_DELAY_MS = 1000L

    fun maybeShow(activity: LauncherActivity) {
        val prefs = LauncherPrefs(activity)
        if (prefs.supportPromptDismissed) return
        if (prefs.firstRunPending) return // let the first-run hero finish first

        val now = System.currentTimeMillis()
        val snoozeUntil = prefs.supportPromptSnoozeUntil
        if (snoozeUntil > 0L && now < snoozeUntil) return

        val installed = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
        } catch (_: Throwable) {
            return
        }
        if ((now - installed) / DAY_MS < MIN_INSTALL_DAYS) return

        // A non-zero snooze value means the single "Maybe later" reprieve has
        // already been spent — this is the final showing.
        val finalShowing = snoozeUntil > 0L

        activity.window.decorView.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            show(activity, prefs, finalShowing)
        }, SETTLE_DELAY_MS)
    }

    private fun show(activity: LauncherActivity, prefs: LauncherPrefs, finalShowing: Boolean) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.support_prompt_title)
            .setMessage(R.string.support_prompt_body)
            .setPositiveButton(R.string.support_prompt_positive) { _, _ ->
                prefs.supportPromptDismissed = true
                openSupport(activity)
            }
            .setNegativeButton(R.string.support_prompt_no) { _, _ ->
                prefs.supportPromptDismissed = true
            }
        if (finalShowing) {
            // No more reprieves — a cancel (back / tap-outside) ends it too.
            builder.setOnCancelListener { prefs.supportPromptDismissed = true }
        } else {
            builder.setNeutralButton(R.string.support_prompt_later) { _, _ ->
                prefs.supportPromptSnoozeUntil = System.currentTimeMillis() + SNOOZE_DAYS * DAY_MS
            }
            // A cancel without choosing acts as the soft "maybe later".
            builder.setOnCancelListener {
                prefs.supportPromptSnoozeUntil = System.currentTimeMillis() + SNOOZE_DAYS * DAY_MS
            }
        }
        builder.showThemed()
    }

    private fun openSupport(activity: LauncherActivity) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(R.string.about_support_url)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
            // No browser — silently skip; the About screen still has the link.
        }
    }
}
