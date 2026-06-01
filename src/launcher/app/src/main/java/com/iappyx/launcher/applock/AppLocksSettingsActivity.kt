/*
 * MIT License - Copyright (c) 2026 iappyx
 * APPLOCK: Settings → App locks screen.
 *
 * Programmatic Kotlin UI (no XML layout) to match the WidgetUsageActivity
 * pattern. Scrollable list of installed launchable apps with a checkbox
 * per row; tap-to-toggle persists immediately to [LauncherPrefs.lockedPackages].
 *
 * v1 deliberate scope:
 *   - No search field — short app lists scroll fine on a phone; the long-
 *     press menu on individual icons is the fast path for "lock this one".
 *   - Locked apps sort to the top so they're easy to find again.
 *   - The launcher itself is excluded — locking yourself out of your own
 *     launcher would be funny but unhelpful.
 *   - One-line hint at the top + a one-line note about the limitation
 *     (recents preview, notifications) so users don't expect more than
 *     this provides.
 */
package com.iappyx.launcher.applock

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.R
import com.iappyx.launcher.SettingsScaffold
import com.iappyx.launcher.widget.Palette

class AppLocksSettingsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var prefs: LauncherPrefs

    /** Whether the user has authenticated for THIS visit to the screen.
     *  Reset on every pause — switching away to another app and coming
     *  back re-prompts. Matches the system Settings → Security pattern. */
    private var authed = false
    /** Reentry guard so backgrounding the prompt itself (user pulled the
     *  notification shade, etc.) doesn't fire a second prompt on top. */
    private var promptInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide from the recents carousel — the list of locked packages
        // is itself sensitive info we don't want someone glancing at.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        prefs = LauncherPrefs(this)
        setContentView(build())
        SettingsScaffold.attach(this, getString(R.string.app_lock_screen_title))
    }

    override fun onResume() {
        super.onResume()
        if (authed) {
            // Already authed for this visit (e.g. config change). Just
            // refresh the data in case the locked set changed elsewhere.
            rebuildList()
            return
        }
        // First-time / bootstrap case: nothing locked yet → no point
        // gating, the user can't bypass a lock that doesn't exist.
        if (prefs.lockedPackages.isEmpty()) {
            authed = true
            rebuildList()
            return
        }
        if (promptInFlight) return
        promptInFlight = true
        // Show a small placeholder while the system biometric prompt
        // covers the activity — better than a flash of the list.
        listContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        listContainer.addView(TextView(this).apply {
            text = getString(R.string.app_lock_settings_gate_placeholder)
            setTextColor(Palette.textSecondary(this@AppLocksSettingsActivity))
            textSize = 13f
            setPadding(0, (24 * dp).toInt(), 0, 0)
        })
        AppLockManager.authenticate(
            activity = this,
            title = getString(R.string.app_lock_settings_auth_title),
            subtitle = getString(R.string.app_lock_settings_auth_subtitle),
            onSuccess = {
                promptInFlight = false
                authed = true
                rebuildList()
            },
            onFail = {
                // Cancelled / locked-out / etc. — close the screen so
                // the lock can't be bypassed by force-quitting the
                // prompt and falling through to the list.
                promptInFlight = false
                finish()
            },
        )
    }

    override fun onPause() {
        super.onPause()
        // Backgrounding clears the auth — coming back triggers another
        // prompt. Mirrors the system Settings → Security behavior.
        authed = false
    }

    private fun build(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bgHome(this@AppLocksSettingsActivity))
            fitsSystemWindows = true
        }
        // Shared toolbar pattern — back arrow + title.
        val toolbar = layoutInflater.inflate(R.layout.settings_toolbar, root, false)
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).apply { weight = 1f }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content)
        root.addView(scroll)

        // Hint header.
        content.addView(TextView(this).apply {
            text = getString(R.string.app_lock_screen_hint)
            setTextColor(Palette.textSecondary(this@AppLocksSettingsActivity))
            textSize = 13f
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        // Limitation note (amber-ish via textSecondary; we're a dark-theme
        // launcher, no need to load a separate warning palette).
        content.addView(TextView(this).apply {
            text = getString(R.string.app_lock_limitation_note)
            setTextColor(Palette.textSecondary(this@AppLocksSettingsActivity))
            textSize = 12f
            alpha = 0.85f
            setPadding(0, 0, 0, (16 * dp).toInt())
            setTypeface(typeface, Typeface.ITALIC)
        })

        // The actual app list container — children added by rebuildList.
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        return root
    }

    private data class AppRow(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val locked: Boolean,
    )

    private fun rebuildList() {
        listContainer.removeAllViews()
        val pm = packageManager
        // Use the same package-query the dock / drawer uses: anything with
        // a LAUNCHER intent. That's the user-meaningful "app" set.
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val ownPkg = packageName
        val lockedSet = prefs.lockedPackages

        val rows = resolved.asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            // Skip the launcher itself — locking yourself out is funny
            // but unhelpful, and the lock prompt would race with the
            // launcher's own onResume.
            .filter { it != ownPkg }
            .mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppRow(
                        packageName = pkg,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = pm.getApplicationIcon(info),
                        locked = lockedSet.contains(pkg),
                    )
                } catch (_: PackageManager.NameNotFoundException) { null }
            }
            // Locked first (so the user sees what's protected), then A-Z.
            .sortedWith(compareByDescending<AppRow> { it.locked }
                .thenBy { it.label.lowercase() })
            .toList()

        for (row in rows) listContainer.addView(buildRow(row))
    }

    private fun buildRow(row: AppRow): View {
        val dp = resources.displayMetrics.density
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            isClickable = true
            isFocusable = true
            // Subtle ripple on tap.
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        val iconPx = (40 * dp).toInt()
        val iconView = ImageView(this).apply {
            setImageDrawable(row.icon)
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
        }
        rowView.addView(iconView)

        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f; leftMargin = (12 * dp).toInt(); rightMargin = (12 * dp).toInt()
            }
        }
        labelCol.addView(TextView(this).apply {
            text = row.label
            setTextColor(Palette.textPrimary(this@AppLocksSettingsActivity))
            textSize = 15f
        })
        labelCol.addView(TextView(this).apply {
            text = row.packageName
            setTextColor(Palette.textSecondary(this@AppLocksSettingsActivity))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        rowView.addView(labelCol)

        val check = CheckBox(this).apply {
            isChecked = row.locked
            // No text — the row label IS the label. Avoid double-rendering.
        }
        rowView.addView(check)

        // Tap anywhere on the row toggles the checkbox. The checkbox's
        // own onChange does the persistence so we don't duplicate.
        rowView.setOnClickListener { check.isChecked = !check.isChecked }
        check.setOnCheckedChangeListener { _, isChecked ->
            prefs.setLocked(row.packageName, isChecked)
            // Don't rebuildList() on every tap — that would re-sort the
            // tapped row away from under the user's finger. Re-sort
            // happens naturally on next onResume.
        }
        return rowView
    }
}
