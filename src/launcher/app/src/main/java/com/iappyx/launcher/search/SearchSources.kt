/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import android.provider.Settings

/**
 * Unified search over apps, contacts and system settings. Each source filters
 * its data set with a simple case-insensitive contains + starts-with ranking.
 */
sealed class SearchResult {
    abstract val label: String

    data class App(
        override val label: String,
        val packageName: String,
        val activityName: String?,
        val icon: Drawable,
        /** [android.content.pm.ApplicationInfo.category] declared by the
         *  app's `android:appCategory` manifest attribute.
         *  [ApplicationInfo.CATEGORY_UNDEFINED] (-1) for the long tail of
         *  apps that don't bother declaring one. Used by the app drawer's
         *  category chip strip. */
        val category: Int = android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED,
    ) : SearchResult()

    data class Contact(
        override val label: String,
        val contactId: Long,
        val lookupKey: String?,
        val phone: String?,
        val photoUri: String?,
    ) : SearchResult()

    data class Setting(
        override val label: String,
        val subtitle: String?,
        /** Documented Settings action (e.g. [Settings.ACTION_WIFI_SETTINGS]).
         *  Preferred over [component] because action-based launches are
         *  guaranteed-supported public APIs; component launches fail on
         *  Android 12+ for activities that aren't proper top-level entries. */
        val action: String? = null,
        /** Fallback when no action exists — direct ComponentName launch.
         *  Used for OEM-specific or unlisted Settings screens. */
        val component: ComponentName? = null,
    ) : SearchResult() {
        fun toIntent(): android.content.Intent {
            if (action != null) {
                return android.content.Intent(action).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            return android.content.Intent().apply {
                component = this@Setting.component
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

object SearchSources {

    // ── Apps ─────────────────────────────────────────────────

    /** Full list of installed launchable apps (cached by the caller). */
    fun allApps(context: Context): List<SearchResult.App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { ri: ResolveInfo ->
                // Read android:appCategory once per app. Falls back to
                // CATEGORY_UNDEFINED for apps that don't declare one
                // (most of the long tail).
                val cat = try {
                    pm.getApplicationInfo(ri.activityInfo.packageName, 0).category
                } catch (_: Throwable) {
                    android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
                }
                SearchResult.App(
                    label = com.iappyx.launcher.LauncherPrefs(context)
                        .appLabel(ri.activityInfo.packageName, ri.loadLabel(pm)).toString(),
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    icon = ri.loadIcon(pm),
                    category = cat,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun searchApps(all: List<SearchResult.App>, query: String, limit: Int = 5): List<SearchResult.App> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return all.asSequence()
            .filter { it.label.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<SearchResult.App> { it.label.lowercase().startsWith(q) }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(limit).toList()
    }

    // ── Contacts (requires READ_CONTACTS) ───────────────────

    /**
     * Query the contacts provider by display name. Returns empty list if the
     * permission is missing — the caller shows a "Grant access" link.
     */
    fun searchContacts(context: Context, query: String, limit: Int = 5): List<SearchResult.Contact> {
        if (query.isBlank()) return emptyList()
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.PHOTO_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
        val args = arrayOf("%$query%")
        val out = mutableListOf<SearchResult.Contact>()
        try {
            context.contentResolver.query(uri, projection, selection, args,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC LIMIT $limit"
            )?.use { c ->
                val iName = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val iId = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val iLookup = c.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val iPhoto = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
                val iHas = c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val phone = if (c.getInt(iHas) > 0) lookupPhone(context, id) else null
                    out.add(SearchResult.Contact(
                        label = c.getString(iName) ?: "(no name)",
                        contactId = id,
                        lookupKey = c.getString(iLookup),
                        photoUri = c.getString(iPhoto),
                        phone = phone,
                    ))
                }
            }
        } catch (_: Exception) { /* surface nothing on query failure */ }
        return out
    }

    private fun lookupPhone(context: Context, contactId: Long): String? {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
    }

    // ── System settings (dynamic enumeration) ───────────────

    /** Curated list of Android Settings deep-link actions that are guaranteed
     *  by the platform (every Settings.ACTION_* constant has a stable contract,
     *  unlike component-name launches which Android 12+ blocks for many
     *  internal Settings activities). Each is paired with a human-readable
     *  label since [PackageManager.resolveActivity] doesn't always return
     *  one for action-only intents.
     *
     *  Order is roughly: connectivity → device → apps → system. Labels are
     *  English-only — could be localised later via res strings if needed. */
    private val KNOWN_ACTIONS: List<Pair<String, String>> = listOf(
        Settings.ACTION_SETTINGS to "Settings",
        Settings.ACTION_WIFI_SETTINGS to "Wi-Fi",
        Settings.ACTION_WIRELESS_SETTINGS to "Wireless & networks",
        Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth",
        Settings.ACTION_NFC_SETTINGS to "NFC",
        Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane mode",
        Settings.ACTION_DATA_USAGE_SETTINGS to "Data usage",
        Settings.ACTION_DATA_ROAMING_SETTINGS to "Mobile networks",
        Settings.ACTION_VPN_SETTINGS to "VPN",
        Settings.ACTION_NETWORK_OPERATOR_SETTINGS to "Mobile operators",
        Settings.ACTION_DISPLAY_SETTINGS to "Display",
        Settings.ACTION_SOUND_SETTINGS to "Sound",
        Settings.ACTION_DREAM_SETTINGS to "Screen saver",
        Settings.ACTION_HARD_KEYBOARD_SETTINGS to "Physical keyboard",
        Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery saver",
        Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location",
        Settings.ACTION_INPUT_METHOD_SETTINGS to "Languages & input",
        Settings.ACTION_LOCALE_SETTINGS to "Languages",
        Settings.ACTION_USER_DICTIONARY_SETTINGS to "Personal dictionary",
        Settings.ACTION_DATE_SETTINGS to "Date & time",
        Settings.ACTION_PRIVACY_SETTINGS to "Privacy",
        Settings.ACTION_SECURITY_SETTINGS to "Security",
        Settings.ACTION_ACCESSIBILITY_SETTINGS to "Accessibility",
        Settings.ACTION_USAGE_ACCESS_SETTINGS to "Usage access",
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "Notification access",
        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS to "Do Not Disturb access",
        Settings.ACTION_HOME_SETTINGS to "Default home app",
        Settings.ACTION_DEVICE_INFO_SETTINGS to "About phone",
        Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "Storage",
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "Developer options",
        Settings.ACTION_APPLICATION_SETTINGS to "Apps",
        Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS to "Manage apps",
        Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS to "All apps",
        Settings.ACTION_SYNC_SETTINGS to "Accounts & sync",
        Settings.ACTION_BIOMETRIC_ENROLL to "Fingerprint & biometrics",
        Settings.ACTION_QUICK_LAUNCH_SETTINGS to "Quick launch",
        Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS to "Input method subtypes",
        Settings.ACTION_VOICE_INPUT_SETTINGS to "Voice input",
        Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "Do Not Disturb",
        Settings.ACTION_CAPTIONING_SETTINGS to "Captions",
        Settings.ACTION_PRINT_SETTINGS to "Printing",
        Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery",
    )

    /**
     * Dynamically enumerate launchable Settings entries. Two passes:
     *
     *   1. Documented [Settings] actions (the curated [KNOWN_ACTIONS] list).
     *      Each is filtered through [PackageManager.resolveActivity] so we
     *      only surface ones the device actually supports — that lets
     *      OEM-stripped builds (no NFC, no VPN, etc.) drop the missing
     *      entries cleanly. These are guaranteed-launchable.
     *
     *   2. Component-name fallback — walks the Settings app's own activity
     *      list for OEM-specific screens that don't have a documented
     *      action. Skipped if a same-label entry already came from pass 1.
     *      Component launches fail on Android 12+ for many internal
     *      activities (the bug the user hit), so this is best-effort.
     */
    fun allSettings(context: Context): List<SearchResult.Setting> {
        val pm = context.packageManager
        val out = mutableListOf<SearchResult.Setting>()
        val seenLabels = mutableSetOf<String>()
        val seenActions = mutableSetOf<String>()

        // Pass 1 — documented actions.
        for ((action, label) in KNOWN_ACTIONS) {
            if (action in seenActions) continue
            seenActions.add(action)
            val intent = Intent(action)
            val resolve = pm.resolveActivity(intent, 0)
            if (resolve == null) continue // not supported on this device
            val key = label.lowercase()
            if (!seenLabels.add(key)) continue
            out.add(SearchResult.Setting(
                label = label,
                subtitle = "Settings",
                action = action,
            ))
        }

        // Pass 2 — Settings package activities, deduped against labels we
        // already covered in pass 1.
        val root = Intent(Settings.ACTION_SETTINGS).resolveActivity(pm)
        if (root != null) {
            val pkg = root.packageName
            try {
                val info = pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS,
                )
                val activities = info.activities ?: emptyArray()
                for (a in activities) {
                    if (!a.exported) continue
                    val label = try { a.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
                    if (label.isEmpty() || label.equals(pkg, ignoreCase = true)) continue
                    val key = label.lowercase()
                    if (!seenLabels.add(key)) continue
                    out.add(SearchResult.Setting(
                        label = label,
                        subtitle = "Settings",
                        component = ComponentName(a.packageName, a.name),
                    ))
                }
            } catch (_: Exception) { /* swallow — pass 1 already gave us a base list */ }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun searchSettings(all: List<SearchResult.Setting>, query: String, limit: Int = 5): List<SearchResult.Setting> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return all.asSequence()
            .filter { it.label.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<SearchResult.Setting> { it.label.lowercase().startsWith(q) }
                    .thenBy { it.label.length }
                    .thenBy { it.label.lowercase() }
            )
            .take(limit).toList()
    }
}
