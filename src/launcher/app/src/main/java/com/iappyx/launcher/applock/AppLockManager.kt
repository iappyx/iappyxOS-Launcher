/*
 * MIT License - Copyright (c) 2026 iappyx
 * APPLOCK: BEGIN — Removable. Delete this file + the fenced `APPLOCK`
 * blocks in LauncherPrefs.kt, IconCell.kt, AppDrawerPanel.kt,
 * FolderOverlay.kt, SearchPanel.kt, SettingsActivity.kt, and the
 * activity_settings.xml row, and the launcher reverts to launching every
 * app immediately on tap.
 *
 * What this does: when the user taps a locked app's icon (from the home
 * grid, dock, drawer, folder, or search), we open a `BiometricPrompt`
 * with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` allowed authenticators
 * before firing the launch intent. Phones without biometric hardware
 * fall back to PIN / pattern / password. Phones with no device lock at
 * all see no prompt — the app launches normally (Android can't enforce
 * what doesn't exist).
 *
 * Storage: [LauncherPrefs.lockedPackages] (Set<String>). No PIN cached
 * on our side — Android's KeyguardManager owns that.
 *
 * Session model: every launch re-authenticates ("always require"). No
 * remember-for-N-minutes in v1. A subsequent design pass could add a
 * timeout if we want — `lastUnlockedAt` map keyed by package, suppress
 * prompt if elapsed < threshold. Not now.
 *
 * Limitation: only the LAUNCH from the launcher is gated. Once
 * authenticated, the target app is in the recents stack like any other,
 * its notification can show on the lock screen, and switching back via
 * the recents carousel does NOT re-prompt. This is a fundamental
 * launcher-level limitation (only work-profile / device-admin can do
 * better). UI surfaces this in the App locks settings screen.
 */
package com.iappyx.launcher.applock

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.iappyx.launcher.LauncherPrefs

object AppLockManager {

    /** True when [packageName] is currently locked. Cheap — backed by
     *  SharedPreferences read which is memory-resident after first load. */
    fun isLocked(context: Context, packageName: String): Boolean =
        LauncherPrefs(context).lockedPackages.contains(packageName)

    /** Launch [intent] (typically `pm.getLaunchIntentForPackage(packageName)`)
     *  for [packageName]. If the package is in the locked set AND the host
     *  Activity is a FragmentActivity (BiometricPrompt requires one), prompt
     *  first; otherwise launch immediately. Returns nothing — the actual
     *  launch happens asynchronously after auth success.
     *
     *  Callers can pass any callable as [onLaunched] to run extra work
     *  on the successful launch path (e.g. `onRequestHide` for the
     *  drawer / search panel that owned the icon). Skipped on auth fail
     *  or cancel — the panel stays open. */
    @JvmOverloads
    fun launchApp(
        activity: Activity,
        packageName: String,
        intent: Intent,
        onLaunched: (() -> Unit)? = null,
    ) {
        if (!isLocked(activity, packageName)) {
            doLaunch(activity, intent, onLaunched)
            return
        }
        // BiometricPrompt is a Fragment API — requires FragmentActivity.
        // LauncherActivity already extends AppCompatActivity (FragmentActivity
        // subclass), so this is satisfied in the common case. Defensive
        // fallback: if some caller hands us a non-FragmentActivity (custom
        // dialog activity, etc.), launch unguarded — better to skip the
        // lock than to crash on a class cast.
        val fa = activity as? FragmentActivity
        if (fa == null) {
            doLaunch(activity, intent, onLaunched)
            return
        }
        authenticate(fa,
            title = activity.getString(
                com.iappyx.launcher.R.string.app_lock_prompt_title,
                appLabel(activity, packageName)),
            subtitle = activity.getString(com.iappyx.launcher.R.string.app_lock_prompt_subtitle),
            onSuccess = { doLaunch(activity, intent, onLaunched) },
            onFail = { /* user cancelled or kept failing — drop silently */ },
        )
    }

    /** Open a BiometricPrompt. Used by [launchApp]; exposed so other UI
     *  (e.g. the App locks settings screen guarding the toggle list)
     *  can reuse the exact same auth flow with consistent strings. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFail: (() -> Unit)? = null,
    ) {
        val canAuth = BiometricManager.from(activity).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // No device lock configured at all — Android can't enforce
            // anything we wouldn't allow. Launch unguarded; the App locks
            // settings screen warns about this on toggle.
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFail?.invoke()
                }
                // onAuthenticationFailed (single bad fingerprint) is a
                // transient state — the prompt stays open and the user
                // can retry. We only care about onAuthenticationError
                // (cancel / lockout / hardware unavailable).
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            // setNegativeButtonText must be omitted when DEVICE_CREDENTIAL
            // is allowed — the prompt provides "Use PIN" automatically.
            .build()
        try { prompt.authenticate(info) }
        catch (_: Throwable) {
            // Defensive — if the prompt fails to fire for any reason,
            // don't strand the user. Drop the lock for this launch.
            onSuccess()
        }
    }

    /** Returns the app's human-readable label, or the package name if
     *  the label can't be resolved (uninstalled, restricted, etc.). */
    private fun appLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) { packageName }
    }

    private fun doLaunch(activity: Activity, intent: Intent, onLaunched: (() -> Unit)?) {
        try {
            activity.startActivity(intent)
            onLaunched?.invoke()
        } catch (_: Throwable) {
            // Same swallow-on-fail pattern the launcher uses everywhere
            // else (apps can be uninstalled mid-tap). Worst case the user
            // taps again and gets the system "no longer installed" toast.
        }
    }
}
// APPLOCK: END
