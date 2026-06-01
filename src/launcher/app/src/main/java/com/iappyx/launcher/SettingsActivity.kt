/*
 * MIT License - Copyright (c) 2026 iappyx
 * Plan A Phase 3 — top-level Settings is now a category-list menu.
 *
 * Each row opens a focused settings activity (AI / Display / Plugins /
 * Backup / etc.). The previous 1200-line monolith has been split into:
 *   - AISettingsActivity            (Phase 4a)
 *   - DisplayAppearanceSettingsActivity (Phase 4b + 4c)
 *   - PluginsActivity               (Phase 1)
 *   - BackupSettingsActivity        (Phase 2)
 *   - ProfilesActivity              (pre-existing)
 *   - RemoteEditActivity            (pre-existing)
 *   - WidgetUsageActivity           (pre-existing)
 *   - AboutActivity                 (pre-existing)
 *
 * This file only owns the menu wiring + dynamic subtitle refreshes.
 */
package com.iappyx.launcher

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.widget.showThemed

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Top-level Settings is the menu — no back arrow (parent is the
        // launcher; system back gesture handles it). Title in the same
        // bar position as every detail screen for spatial continuity.
        SettingsScaffold.attach(this, getString(R.string.settings_title), showBack = false)

        findViewById<android.view.View>(R.id.ai_row).setOnClickListener {
            startActivity(android.content.Intent(this, AISettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.display_row).setOnClickListener {
            startActivity(android.content.Intent(this, DisplayAppearanceSettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.profiles_row).setOnClickListener {
            startActivity(android.content.Intent(this, ProfilesActivity::class.java))
        }
        // PLUGINS:
        findViewById<android.view.View>(R.id.plugins_row).setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.plugins.PluginsActivity::class.java,
            ))
        }
        findViewById<android.view.View>(R.id.backup_row).setOnClickListener {
            startActivity(android.content.Intent(this, BackupSettingsActivity::class.java))
        }
        // REMOTE EDIT FEATURE: row registration. Delete when removing.
        findViewById<android.view.View>(R.id.remoteedit_row)?.setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.remoteedit.RemoteEditActivity::class.java,
            ))
        }
        // USAGE:
        findViewById<android.view.View>(R.id.widget_usage_row).setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.usage.WidgetUsageActivity::class.java,
            ))
        }
        // APPLOCK: row click → open AppLocksSettingsActivity.
        findViewById<android.view.View>(R.id.app_locks_row).setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.applock.AppLocksSettingsActivity::class.java,
            ))
        }
        // QUICK WIDGETS: row click → open the slot manager.
        findViewById<android.view.View>(R.id.quick_widgets_row)?.setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.quickwidget.QuickWidgetManagerActivity::class.java,
            ))
        }
        // QUICK WIDGETS: END
        // Default home-app shortcut — deep-link to the system picker.
        // Tries Settings.ACTION_HOME_SETTINGS first (preferred — drops
        // straight onto the home-app list); falls back to the generic
        // Default Apps screen if the device's Settings app doesn't
        // surface that activity (rare on AOSP, common on some OEMs).
        findViewById<android.view.View>(R.id.default_launcher_row)?.setOnClickListener {
            val direct = android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(direct)
            } catch (_: Throwable) {
                try {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {
                    android.widget.Toast.makeText(
                        this,
                        "Couldn't open Default apps. Try Settings → Apps → Default apps.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        // Restart launcher — confirm, then kill the process. Android's
        // home-app routing brings the launcher back up automatically.
        findViewById<android.view.View>(R.id.restart_launcher_row)?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_restart_launcher_confirm_title)
                .setMessage(R.string.settings_restart_launcher_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_restart_launcher_label) { _, _ ->
                    // Small delay so the dialog has time to dismiss
                    // before we kill the process — otherwise the user
                    // sees a frozen dialog for a frame.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 150)
                }
                .showThemed()
        }
        findViewById<android.view.View>(R.id.about_row).setOnClickListener {
            startActivity(android.content.Intent(
                this, com.iappyx.launcher.about.AboutActivity::class.java,
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        // Dynamic subtitles — live counts so the user sees current state
        // without opening each section.
        refreshAISubtitle()
        refreshProfilesSubtitle()
        refreshPluginsSubtitle()
        refreshAppLocksSubtitle()
        refreshDefaultLauncherSubtitle()
    }

    /** Update the default-launcher row's subtitle to reflect whether
     *  iappyxOS is currently the default home app. */
    private fun refreshDefaultLauncherSubtitle() {
        val tv = findViewById<android.widget.TextView>(R.id.default_launcher_row_subtitle) ?: return
        val isDefault = isDefaultLauncher()
        tv.setText(
            if (isDefault) R.string.settings_default_launcher_subtitle_current
            else R.string.settings_default_launcher_subtitle_not_default,
        )
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved?.activityInfo?.packageName == packageName
    }

    // APPLOCK: subtitle reflects the locked-package count without
    // entering the screen.
    private fun refreshAppLocksSubtitle() {
        val count = LauncherPrefs(this).lockedPackages.size
        val text = if (count == 0) getString(R.string.settings_app_locks_subtitle_none)
        else getString(R.string.settings_app_locks_subtitle_format, count)
        findViewById<TextView>(R.id.app_locks_row_subtitle)?.text = text
    }

    private fun refreshAISubtitle() {
        val store = com.iappyx.launcher.ai.SecureStore(this)
        val hasKey = !store.anthropicKey.isNullOrBlank()
        val hasGithub = !store.githubToken.isNullOrBlank()
        val text = when {
            hasKey && hasGithub -> "API key set · GitHub token set"
            hasKey -> "API key set"
            hasGithub -> "GitHub token set (no AI key yet)"
            else -> "API keys, models, GitHub token"
        }
        findViewById<TextView>(R.id.ai_row_subtitle)?.text = text
    }

    private fun refreshProfilesSubtitle() {
        val all = com.iappyx.launcher.profile.ProfileLibrary.all(this)
        val active = LauncherPrefs(this).activeProfileSlug
        val text = when {
            all.isEmpty() -> getString(R.string.settings_profiles_default_subtitle)
            active != null -> {
                val name = all.firstOrNull { it.slug == active }?.name ?: active
                getString(R.string.settings_profiles_saved_active_format, all.size, name)
            }
            else -> getString(R.string.settings_profiles_saved_no_active_format, all.size)
        }
        findViewById<TextView>(R.id.profiles_subtitle)?.text = text
    }

    private fun refreshPluginsSubtitle() {
        val arr = com.iappyx.launcher.plugins.PluginsModule.listInstalledAsJson(this)
        val total = arr.length()
        var restricted = 0
        for (i in 0 until total) {
            val mode = arr.optJSONObject(i)?.optString("networkMode") ?: "always"
            if (mode != "always") restricted++
        }
        val text = when {
            total == 0 -> "No plugins installed yet"
            restricted == 0 -> "$total installed"
            else -> "$total installed · $restricted network-restricted"
        }
        findViewById<TextView>(R.id.plugins_row_subtitle)?.text = text
    }
}
