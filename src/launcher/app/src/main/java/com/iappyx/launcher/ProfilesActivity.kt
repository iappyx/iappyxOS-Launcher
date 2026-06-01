/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.model.ProfileActions
import com.iappyx.launcher.model.ProfileTrigger
import com.iappyx.launcher.profile.ProfileApplier
import com.iappyx.launcher.profile.ProfileLibrary
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed

/**
 * Settings sub-screen for [Profile] management. The user lands here from
 * Settings → Profiles. Lists every saved profile with its trigger
 * summary and whether it's the currently-active one. Top action bar:
 *
 *   - Save current state as a new profile (trigger defaults to Manual;
 *     edit later via tap-row → Edit).
 *   - Switch to (picker over all profiles, applies immediately).
 *
 * Per-row tap opens the edit sheet (rename / set trigger / pick
 * auto-launch apps / delete).
 *
 * UI is built programmatically — keeps Settings/manifest changes tiny
 * and matches the manage-tab style the user already knows.
 */
class ProfilesActivity : AppCompatActivity() {

    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var listColumn: LinearLayout

    /** The profile whose geofence trigger we asked the picker to edit.
     *  Held across the activity-result round-trip so the result handler
     *  knows which profile to apply the picked location to. */
    private var pendingGeofenceProfile: Profile? = null

    private val geofencePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingGeofenceProfile
        pendingGeofenceProfile = null
        if (result.resultCode != RESULT_OK || profile == null) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val lat = data.getDoubleExtra(GeofencePickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(GeofencePickerActivity.EXTRA_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        val radius = data.getFloatExtra(
            GeofencePickerActivity.EXTRA_RADIUS, GeofencePickerActivity.DEFAULT_RADIUS_M,
        )
        val label = data.getStringExtra(GeofencePickerActivity.EXTRA_LABEL)
            ?.takeIf { it.isNotBlank() }
            ?: profile.name
        applyTrigger(profile, ProfileTrigger.Geofence(label, lat, lng, radius))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        SettingsScaffold.attach(this, getString(R.string.settings_profiles_label))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bgHome(this@ProfilesActivity))
            // Apply system-bar insets via OnApplyWindowInsetsListener so the
            // header doesn't slide under the status bar on edge-to-edge
            // displays. fitsSystemWindows alone is legacy and was producing
            // a 0-inset on some Android versions.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        // Shared settings toolbar — single back arrow + title position
        // used by every settings screen. Replaces the older custom
        // "back chip + title" header that drifted visually from the rest.
        val toolbar = android.view.LayoutInflater.from(this)
            .inflate(R.layout.settings_toolbar, root, false)
        root.addView(toolbar)
        root.addView(TextView(this).apply {
            setText(R.string.profiles_intro)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 13f
            setPadding(p(20), 0, p(20), p(12))
        })

        // Pause-auto-switch toggle. Sits above the header buttons so it's
        // the first thing the user sees — being right next to the back chip
        // makes the workflow obvious: open profiles → flip pause on → edit
        // freely → flip pause off → leave.
        root.addView(buildPauseAutoSwitchRow())

        // Header buttons.
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(16), p(8), p(16), p(12))
        }
        headerRow.addView(makeHeaderButton(
            getString(R.string.profiles_save_current), accent = true,
        ) { promptCreateFromCurrent() })
        headerRow.addView(makeHeaderButton(getString(R.string.profiles_switch_to)) { promptSwitch() })
        root.addView(headerRow)

        // List of profiles in a scroll.
        listColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(12), 0, p(12), p(20))
        }
        val scroll = ScrollView(this).apply { addView(listColumn) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return root
    }

    private fun refresh() {
        listColumn.removeAllViews()
        val profiles = ProfileLibrary.all(this)
        val active = LauncherPrefs(this).activeProfileSlug
        if (profiles.isEmpty()) {
            listColumn.addView(TextView(this).apply {
                setText(R.string.profiles_empty_hint)
                setTextColor(Palette.textDisabled(this@ProfilesActivity))
                textSize = 13f; gravity = Gravity.CENTER
                setPadding(p(20), p(40), p(20), p(40))
            })
            return
        }
        for (p in profiles) listColumn.addView(makeProfileRow(p, isActive = p.slug == active))
    }

    private fun makeProfileRow(profile: Profile, isActive: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(
                    ((if (isActive) 2 else 1) * dp).toInt(),
                    if (isActive) Palette.accentChipStroke(this@ProfilesActivity)
                    else Palette.separator(this@ProfilesActivity),
                )
            }
            val pad = p(14)
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            setOnClickListener { showEditSheet(profile) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = p(10)
            layoutParams = lp
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = profile.name
            setTextColor(Palette.textPrimary(this@ProfilesActivity)); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (isActive) titleRow.addView(TextView(this).apply {
            setText(R.string.profiles_active_pill)
            setTextColor(Palette.accentLight(this@ProfilesActivity))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Palette.accentChipBg(this@ProfilesActivity))
            }
            setPadding(p(10), p(4), p(10), p(4))
        })
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            text = describeTrigger(profile.trigger)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 12f
            setPadding(0, p(4), 0, 0)
        })
        if (profile.onActivate.launchPackages.isNotEmpty()) card.addView(TextView(this).apply {
            text = getString(R.string.profiles_auto_launches_format,
                describeLaunchPackages(profile.onActivate.launchPackages))
            setTextColor(Color.parseColor("#80A0A0B8"))
            textSize = 11f
            setPadding(0, p(2), 0, 0)
        })
        if (profile.onActivate.customActions.isNotEmpty()) card.addView(TextView(this).apply {
            text = getString(R.string.profiles_custom_actions_summary_format,
                profile.onActivate.customActions.size,
                profile.onActivate.customActions.joinToString(", ") { it.label }
                    .let { if (it.length > 60) it.take(57) + "…" else it })
            setTextColor(Color.parseColor("#80A0A0B8"))
            textSize = 11f
            setPadding(0, p(2), 0, 0)
        })
        return card
    }

    // ── Actions ────────────────────────────────────────────────────

    private fun promptCreateFromCurrent() {
        val input = EditText(this).apply {
            setHint(R.string.profiles_create_name_hint)
            setTextColor(Palette.textPrimary(this@ProfilesActivity))
            setHintTextColor(Palette.textDisabled(this@ProfilesActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_create_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                createProfile(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun createProfile(name: String) {
        val slug = ProfileLibrary.freshSlugFor(this, name)
        val profile = Profile(
            slug = slug,
            name = name,
            snapshot = ProfileApplier.captureCurrent(this),
            trigger = ProfileTrigger.Manual,
            onActivate = ProfileActions(),
            createdAt = System.currentTimeMillis(),
        )
        if (ProfileLibrary.save(this, profile)) {
            // First-saved profile becomes active automatically — no point in
            // having a profile-driven launcher with no active profile.
            if (LauncherPrefs(this).activeProfileSlug.isNullOrBlank()) {
                LauncherPrefs(this).activeProfileSlug = slug
            }
            Toast.makeText(this,
                getString(R.string.profiles_saved_toast_format, name),
                Toast.LENGTH_SHORT).show()
            refresh()
        } else {
            Toast.makeText(this, R.string.profiles_couldnt_save_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun promptSwitch() {
        val profiles = ProfileLibrary.all(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, R.string.profiles_no_profiles_yet_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = profiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_switch_dialog_title)
            .setItems(labels) { _, which ->
                applyAndFinish(profiles[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun applyAndFinish(profile: Profile) {
        ProfileApplier.apply(this, profile)
        Toast.makeText(this,
            getString(R.string.profiles_switched_toast_format, profile.name),
            Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun showEditSheet(profile: Profile) {
        val opts = arrayOf(
            getString(R.string.profiles_action_rename),
            getString(R.string.profiles_action_set_trigger),
            getString(R.string.profiles_action_set_auto_launch),
            getString(R.string.profiles_action_set_custom_actions),
            getString(R.string.profiles_action_switch_to_this),
            getString(R.string.profiles_action_duplicate),
            getString(R.string.profiles_action_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> promptRename(profile)
                    1 -> promptSetTrigger(profile)
                    2 -> promptAutoLaunch(profile)
                    3 -> showCustomActionsList(profile)
                    4 -> applyAndFinish(profile)
                    5 -> promptDuplicate(profile)
                    6 -> promptDelete(profile)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptDuplicate(profile: Profile) {
        val input = EditText(this).apply {
            setText(getString(R.string.profiles_duplicate_default_name_format, profile.name))
            setSelection(0, text.length)
            setTextColor(Palette.textPrimary(this@ProfilesActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_duplicate_dialog_title_format, profile.name))
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val newSlug = ProfileLibrary.freshSlugFor(this, newName)
                // Clone the source profile's snapshot + onActivate verbatim;
                // reset trigger to Manual so the duplicate doesn't immediately
                // collide with the original's auto-trigger.
                val copy = profile.copy(
                    slug = newSlug,
                    name = newName,
                    trigger = ProfileTrigger.Manual,
                    createdAt = System.currentTimeMillis(),
                )
                if (ProfileLibrary.save(this, copy)) {
                    Toast.makeText(this,
                        getString(R.string.profiles_duplicated_toast_format, newName),
                        Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(this, R.string.profiles_couldnt_duplicate_toast,
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptRename(profile: Profile) {
        val input = EditText(this).apply {
            setText(profile.name)
            setSelection(0, profile.name.length)
            setTextColor(Palette.textPrimary(this@ProfilesActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_rename_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() &&
                    ProfileLibrary.rename(this, profile.slug, newName)
                ) refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptDelete(profile: Profile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_delete_dialog_title_format, profile.name))
            .setMessage(R.string.profiles_delete_dialog_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (ProfileLibrary.delete(this, profile.slug)) {
                    val prefs = LauncherPrefs(this)
                    if (prefs.activeProfileSlug == profile.slug) prefs.activeProfileSlug = null
                    if (profile.trigger is ProfileTrigger.Geofence) {
                        com.iappyx.launcher.profile.ProfileGeofenceManager
                            .unregister(this, profile.slug)
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptSetTrigger(profile: Profile) {
        val kinds = arrayOf(
            getString(R.string.profiles_trigger_kind_manual),
            getString(R.string.profiles_trigger_kind_android_auto),
            getString(R.string.profiles_trigger_kind_wifi_connected),
            getString(R.string.profiles_trigger_kind_wifi_disconnected),
            getString(R.string.profiles_trigger_kind_geofence),
            getString(R.string.profiles_trigger_kind_bt_device),
            getString(R.string.profiles_trigger_kind_time_of_day),
            getString(R.string.profiles_trigger_kind_charger),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.profiles_trigger_dialog_title_format, profile.name))
            .setItems(kinds) { _, which ->
                when (which) {
                    0 -> applyTrigger(profile, ProfileTrigger.Manual)
                    1 -> applyTrigger(profile, ProfileTrigger.AndroidAuto)
                    2 -> promptWifiSsid(profile)
                    3 -> applyTrigger(profile, ProfileTrigger.WifiDisconnected)
                    4 -> promptGeofence(profile)
                    5 -> promptBluetoothDevice(profile)
                    6 -> promptTimeOfDay(profile)
                    7 -> promptCharger(profile)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptBluetoothDevice(profile: Profile) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null) {
            Toast.makeText(this, R.string.profiles_bt_unavailable_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = try {
            adapter.bondedDevices.toList()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.profiles_bt_permission_needed_toast, Toast.LENGTH_LONG).show()
            return
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this, R.string.profiles_bt_no_paired_devices_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = bonded.map { d ->
            val name = try { d.name } catch (_: SecurityException) { null } ?: d.address
            name to d.address
        }
        val labels = rows.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_bt_picker_title)
            .setItems(labels) { _, idx ->
                val (label, address) = rows[idx]
                applyTrigger(profile, ProfileTrigger.BluetoothDeviceConnected(
                    deviceAddress = address, label = label,
                ))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptCharger(profile: Profile) {
        val opts = arrayOf(
            getString(R.string.profiles_charger_any),
            getString(R.string.profiles_charger_wired),
            getString(R.string.profiles_charger_wireless),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_charger_picker_title)
            .setItems(opts) { _, idx ->
                val kind = when (idx) {
                    1 -> ProfileTrigger.ChargerConnected.ChargerKind.WIRED
                    2 -> ProfileTrigger.ChargerConnected.ChargerKind.WIRELESS
                    else -> ProfileTrigger.ChargerConnected.ChargerKind.ANY
                }
                applyTrigger(profile, ProfileTrigger.ChargerConnected(kind))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    /** Rich Time-of-Day picker — start/end TimePickers + day-of-week chip
     *  row + common-presets shortcut. Built programmatically so it
     *  matches the rest of the launcher's settings UI. */
    private fun promptTimeOfDay(profile: Profile) {
        val existing = profile.trigger as? ProfileTrigger.TimeOfDay
        var startMin = existing?.startMinuteOfDay ?: (9 * 60)   // default 09:00
        var endMin = existing?.endMinuteOfDay ?: (17 * 60)      // default 17:00
        var dayMask = existing?.daysOfWeek ?: 0x1F              // default Mon-Fri

        val dp = resources.displayMetrics.density.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24 * dp, 16 * dp, 24 * dp, 8 * dp)
        }
        // Section: Start
        val startLabel = TextView(this).apply {
            text = getString(R.string.profiles_time_start_label)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(startLabel)
        val startPicker = android.widget.TimePicker(this).apply {
            setIs24HourView(android.text.format.DateFormat.is24HourFormat(this@ProfilesActivity))
            hour = startMin / 60
            minute = startMin % 60
            setOnTimeChangedListener { _, h, m -> startMin = h * 60 + m }
        }
        root.addView(startPicker)
        // Section: End
        val endLabel = TextView(this).apply {
            text = getString(R.string.profiles_time_end_label)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 12 * dp, 0, 0)
        }
        root.addView(endLabel)
        val endPicker = android.widget.TimePicker(this).apply {
            setIs24HourView(android.text.format.DateFormat.is24HourFormat(this@ProfilesActivity))
            hour = endMin / 60
            minute = endMin % 60
            setOnTimeChangedListener { _, h, m -> endMin = h * 60 + m }
        }
        root.addView(endPicker)
        // Section: Days
        val daysLabel = TextView(this).apply {
            text = getString(R.string.profiles_time_days_label)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16 * dp, 0, 8 * dp)
        }
        root.addView(daysLabel)
        val dayShortNames = resources.getStringArray(R.array.profiles_time_day_short)
        val dayChips = mutableListOf<TextView>()
        val daysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun renderDayChip(idx: Int): TextView = TextView(this).apply {
            text = dayShortNames[idx]
            textSize = 13f
            setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp)
            setTextColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener {
                dayMask = dayMask xor (1 shl idx)
                refreshDayChipBgs(dayChips, dayMask)
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = 4 * dp
            layoutParams = lp
        }
        for (i in 0..6) {
            val chip = renderDayChip(i)
            dayChips += chip
            daysRow.addView(chip)
        }
        refreshDayChipBgs(dayChips, dayMask)
        root.addView(daysRow)

        // Preset row
        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12 * dp, 0, 0)
        }
        fun makePresetBtn(label: String, mask: Int) = TextView(this).apply {
            text = label
            textSize = 12f
            setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp)
            setTextColor(Palette.accent(this@ProfilesActivity))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    Palette.accent(this@ProfilesActivity), 0x1F))
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                dayMask = mask
                refreshDayChipBgs(dayChips, dayMask)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = 6 * dp
            layoutParams = lp
        }
        presets.addView(makePresetBtn(getString(R.string.profiles_time_preset_weekdays), 0x1F))
        presets.addView(makePresetBtn(getString(R.string.profiles_time_preset_weekend), 0x60))
        presets.addView(makePresetBtn(getString(R.string.profiles_time_preset_every_day), 0x7F))
        root.addView(presets)

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_time_picker_title)
            .setView(android.widget.ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.action_save) { _, _ ->
                if (dayMask == 0) {
                    Toast.makeText(this,
                        R.string.profiles_time_no_days_toast,
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyTrigger(profile, ProfileTrigger.TimeOfDay(
                    startMinuteOfDay = startMin,
                    endMinuteOfDay = endMin,
                    daysOfWeek = dayMask,
                ))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    /** Repaint the day chip backgrounds from the current bitmask. */
    private fun refreshDayChipBgs(chips: List<TextView>, mask: Int) {
        for ((i, chip) in chips.withIndex()) {
            val on = (mask and (1 shl i)) != 0
            chip.background = GradientDrawable().apply {
                cornerRadius = 999f
                if (on) {
                    setColor(Palette.accentChipBg(this@ProfilesActivity))
                    setStroke((1 * dp).toInt(),
                        Palette.accentChipBgStrong(this@ProfilesActivity))
                } else {
                    setColor(Palette.separatorSubtle(this@ProfilesActivity))
                    setStroke((1 * dp).toInt(), Palette.separator(this@ProfilesActivity))
                }
            }
            chip.setTextColor(if (on) Color.WHITE else Palette.textSecondary(this@ProfilesActivity))
        }
    }

    private fun promptWifiSsid(profile: Profile) {
        val input = EditText(this).apply {
            setHint(R.string.profiles_wifi_ssid_hint)
            setTextColor(Palette.textPrimary(this@ProfilesActivity))
            setHintTextColor(Palette.textDisabled(this@ProfilesActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_wifi_ssid_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val ssid = input.text.toString().trim()
                if (ssid.isNotEmpty()) applyTrigger(profile, ProfileTrigger.WifiSsid(ssid))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun promptGeofence(profile: Profile) {
        // Pre-seed the picker with the profile's current geofence (if any)
        // so editing an existing trigger lands on the same pin instead of
        // a blank map. Defaults otherwise come from the picker itself.
        pendingGeofenceProfile = profile
        val intent = Intent(this, GeofencePickerActivity::class.java)
        val existing = profile.trigger as? ProfileTrigger.Geofence
        if (existing != null) {
            intent.putExtra(GeofencePickerActivity.EXTRA_LAT, existing.latitude)
            intent.putExtra(GeofencePickerActivity.EXTRA_LNG, existing.longitude)
            intent.putExtra(GeofencePickerActivity.EXTRA_RADIUS, existing.radiusM)
            intent.putExtra(GeofencePickerActivity.EXTRA_LABEL, existing.label)
        } else {
            intent.putExtra(GeofencePickerActivity.EXTRA_LABEL, profile.name)
        }
        geofencePickerLauncher.launch(intent)
    }

    private fun applyTrigger(profile: Profile, trigger: ProfileTrigger) {
        val updated = profile.copy(trigger = trigger)
        if (ProfileLibrary.save(this, updated)) {
            // Re-register / unregister the profile geofence as needed.
            // Switching from Geofence to anything else: drop the fence;
            // switching to Geofence: register fresh; same Geofence with
            // different lat/long: register replaces the existing fence.
            val wasGeofence = profile.trigger is ProfileTrigger.Geofence
            val nowGeofence = trigger is ProfileTrigger.Geofence
            if (wasGeofence && !nowGeofence) {
                com.iappyx.launcher.profile.ProfileGeofenceManager
                    .unregister(this, profile.slug)
            }
            if (nowGeofence) {
                val needsBg = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsBg) {
                    // Geofence auto-switch can't fire in the background without
                    // "Allow all the time". Guide the user instead of silently
                    // registering a fence that will never trigger. Geofences
                    // re-register from LauncherActivity.onCreate once granted.
                    promptBackgroundLocation()
                } else {
                    com.iappyx.launcher.profile.ProfileGeofenceManager
                        .register(this, updated) { ok, err ->
                            if (!ok) runOnUiThread {
                                Toast.makeText(
                                    this,
                                    getString(R.string.profiles_geofence_register_failed, err ?: ""),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
            // Time-of-day triggers wake at boundaries via AlarmManager.
            // The watcher schedules / cancels for the next-soonest
            // boundary across all profiles; ask it to recompute when
            // we add or remove a TimeOfDay trigger.
            val touchesTime = profile.trigger is ProfileTrigger.TimeOfDay ||
                trigger is ProfileTrigger.TimeOfDay
            if (touchesTime) {
                sendBroadcast(android.content.Intent(ACTION_PROFILE_TIME_RESCHEDULE)
                    .setPackage(packageName))
            }
            Toast.makeText(this, R.string.profiles_trigger_saved_toast, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    /** Geofence profiles are useless without "Allow all the time" location —
     *  the OS won't deliver transitions in the background otherwise. Explain
     *  it and send the user to the app's location settings (a runtime dialog
     *  can't grant background location directly on API 30+). The fence
     *  re-registers automatically from LauncherActivity.onCreate once granted. */
    private fun promptBackgroundLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_bg_location_title)
            .setMessage(R.string.profiles_bg_location_msg)
            .setPositiveButton(R.string.profiles_bg_location_open) { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: Throwable) { /* no Settings app — nothing we can do */ }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showThemed()
    }

    /** ActivityResult contract: open the editor, get back a JSON string of
     *  the saved [com.iappyx.launcher.model.IntentAction]. We avoid passing
     *  the bigger Profile through extras — the activity only knows about
     *  one action at a time and we splice it back into the profile here. */
    private val customActionEditorLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        val slug = data.getStringExtra(IntentActionEditorActivity.EXTRA_PROFILE_SLUG) ?: return@registerForActivityResult
        val replaceIndex = data.getIntExtra(IntentActionEditorActivity.EXTRA_REPLACE_INDEX, -1)
        val json = data.getStringExtra(IntentActionEditorActivity.EXTRA_RESULT_ACTION_JSON)
            ?: return@registerForActivityResult
        val action = try {
            com.iappyx.launcher.model.IntentAction.fromJson(org.json.JSONObject(json))
        } catch (_: Throwable) { return@registerForActivityResult }
        val profile = ProfileLibrary.get(this, slug) ?: return@registerForActivityResult
        val newList = profile.onActivate.customActions.toMutableList()
        if (replaceIndex in newList.indices) newList[replaceIndex] = action
        else newList.add(action)
        val updated = profile.copy(onActivate = profile.onActivate.copy(customActions = newList))
        if (ProfileLibrary.save(this, updated)) {
            refresh()
            // Re-open the list so the user can keep editing.
            showCustomActionsList(updated)
        }
    }

    /** Top-level list of [IntentAction]s for a profile. Each row shows
     *  label + verb summary; tap → edit, long-press / swipe equivalent
     *  via a small "remove?" confirm. "+ Add custom action" appended. */
    private fun showCustomActionsList(profile: Profile) {
        val actions = profile.onActivate.customActions
        val labels = actions.mapIndexed { i, a ->
            val sub = describeIntentAction(a)
            "${a.label}\n$sub"
        }.toMutableList()
        labels.add(getString(R.string.profiles_custom_actions_add))
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_custom_actions_dialog_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.size - 1) {
                    // Add new
                    customActionEditorLauncher.launch(
                        IntentActionEditorActivity.intent(this, profile.slug, replaceIndex = -1, existing = null),
                    )
                } else {
                    // Existing action — show edit / remove sub-menu
                    showCustomActionRowMenu(profile, which)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun showCustomActionRowMenu(profile: Profile, index: Int) {
        val action = profile.onActivate.customActions.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(action.label)
            .setItems(arrayOf(
                getString(R.string.profiles_custom_actions_edit),
                getString(R.string.profiles_custom_actions_test),
                getString(R.string.profiles_custom_actions_remove),
            )) { _, which ->
                when (which) {
                    0 -> customActionEditorLauncher.launch(
                        IntentActionEditorActivity.intent(this, profile.slug, replaceIndex = index, existing = action),
                    )
                    1 -> {
                        val r = com.iappyx.launcher.intent.IntentRunner.fire(this, action)
                        Toast.makeText(this, describeRunResult(r), Toast.LENGTH_LONG).show()
                    }
                    2 -> {
                        val newList = profile.onActivate.customActions.toMutableList()
                        newList.removeAt(index)
                        val updated = profile.copy(
                            onActivate = profile.onActivate.copy(customActions = newList),
                        )
                        if (ProfileLibrary.save(this, updated)) {
                            refresh()
                            showCustomActionsList(updated)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    /** "Broadcast → com.wireguard.android  •  SET_TUNNEL_UP" — one-line. */
    private fun describeIntentAction(a: com.iappyx.launcher.model.IntentAction): String {
        val verb = a.verb.name.lowercase().replace('_', ' ')
        val target = listOfNotNull(a.packageName, a.action).joinToString("  •  ").ifBlank { "—" }
        return "$verb → $target"
    }

    private fun describeRunResult(r: com.iappyx.launcher.intent.IntentRunner.Result): String = when (r) {
        is com.iappyx.launcher.intent.IntentRunner.Result.Ok -> getString(R.string.profiles_custom_actions_test_ok)
        is com.iappyx.launcher.intent.IntentRunner.Result.NoMatchingComponent ->
            getString(R.string.profiles_custom_actions_test_nomatch_format, r.message)
        is com.iappyx.launcher.intent.IntentRunner.Result.PermissionDenied ->
            getString(R.string.profiles_custom_actions_test_perm_format, r.message)
        is com.iappyx.launcher.intent.IntentRunner.Result.Failed ->
            getString(R.string.profiles_custom_actions_test_failed_format, r.throwable.message ?: "error")
    }

    private fun promptAutoLaunch(profile: Profile) {
        val pm = packageManager
        // Build a list of installed launchable apps, sorted by label.
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val labels = resolves.map { it.second }.toTypedArray()
        val pkgs = resolves.map { it.first }
        val checked = BooleanArray(pkgs.size) { pkgs[it] in profile.onActivate.launchPackages }
        AlertDialog.Builder(this)
            .setTitle(R.string.profiles_auto_launch_dialog_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.action_save) { _, _ ->
                val picked = pkgs.filterIndexed { i, _ -> checked[i] }
                val updated = profile.copy(onActivate = ProfileActions(launchPackages = picked))
                if (ProfileLibrary.save(this, updated)) refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    // ── Helpers ────────────────────────────────────────────────────

    /** Human-readable list of app labels for the row's "Auto-launches"
     *  line. Resolves package names to their installed-app labels and
     *  caps at 3 with a "+N more" suffix so the tile stays one line.
     *  Falls back to the package name when the app isn't installed. */
    private fun describeLaunchPackages(packages: List<String>): String {
        val pm = packageManager
        val labels = packages.map { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Throwable) { pkg }
        }
        if (labels.size <= 3) return labels.joinToString(", ")
        val first = labels.take(3).joinToString(", ")
        return getString(R.string.profiles_auto_launches_more_format, first, labels.size - 3)
    }

    private fun describeTrigger(t: ProfileTrigger): String = when (t) {
        is ProfileTrigger.Geofence ->
            getString(R.string.profiles_trigger_desc_geofence_format, t.label)
        ProfileTrigger.AndroidAuto -> getString(R.string.profiles_trigger_desc_android_auto)
        is ProfileTrigger.WifiSsid ->
            getString(R.string.profiles_trigger_desc_wifi_ssid_format, t.ssid)
        ProfileTrigger.WifiDisconnected -> getString(R.string.profiles_trigger_desc_wifi_disconnected)
        is ProfileTrigger.BluetoothDeviceConnected ->
            getString(R.string.profiles_trigger_desc_bt_format, t.label)
        is ProfileTrigger.TimeOfDay -> describeTimeOfDay(t)
        is ProfileTrigger.ChargerConnected -> when (t.kind) {
            ProfileTrigger.ChargerConnected.ChargerKind.ANY ->
                getString(R.string.profiles_trigger_desc_charger_any)
            ProfileTrigger.ChargerConnected.ChargerKind.WIRED ->
                getString(R.string.profiles_trigger_desc_charger_wired)
            ProfileTrigger.ChargerConnected.ChargerKind.WIRELESS ->
                getString(R.string.profiles_trigger_desc_charger_wireless)
        }
        ProfileTrigger.Manual -> getString(R.string.profiles_trigger_desc_manual)
    }

    /** "Mon-Fri 09:00-17:00" — collapse contiguous day ranges, otherwise
     *  comma-separate. Format times via the user's locale. */
    private fun describeTimeOfDay(t: ProfileTrigger.TimeOfDay): String {
        val daysLabel = formatDaysOfWeek(t.daysOfWeek)
        val fmt: (Int) -> String = { mins ->
            String.format(java.util.Locale.getDefault(), "%02d:%02d", mins / 60, mins % 60)
        }
        return getString(
            R.string.profiles_trigger_desc_time_format,
            daysLabel, fmt(t.startMinuteOfDay), fmt(t.endMinuteOfDay),
        )
    }

    private fun formatDaysOfWeek(mask: Int): String {
        if (mask == 0x7F) return getString(R.string.profiles_time_preset_every_day)
        if (mask == 0x1F) return getString(R.string.profiles_time_preset_weekdays)
        if (mask == 0x60) return getString(R.string.profiles_time_preset_weekend)
        val names = resources.getStringArray(R.array.profiles_time_day_short)
        val active = (0..6).filter { (mask and (1 shl it)) != 0 }
        return active.joinToString("·") { names[it] }
    }

    private fun makeHeaderButton(
        label: String, accent: Boolean = false, onClick: () -> Unit,
    ): View = TextView(this).apply {
        text = label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(if (accent) Palette.accentChipBg(this@ProfilesActivity)
            else Palette.separatorSubtle(this@ProfilesActivity))
            setStroke((1 * dp).toInt(),
                if (accent) Palette.accentChipBgStrong(this@ProfilesActivity)
                else Palette.separatorStrong(this@ProfilesActivity))
        }
        setPadding(p(14), p(8), p(14), p(8))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.rightMargin = p(8)
        layoutParams = lp
    }

    private fun p(value: Int): Int = (value * dp).toInt()

    /** Toggle row that flips [LauncherPrefs.profileAutoSwitchPaused].
     *
     *  Why a manual toggle rather than auto-pause-on-foreground: the user
     *  often wants to LEAVE the profiles screen running while they walk
     *  out of WiFi range / start their car / etc. to verify a trigger
     *  fires. Auto-pause-on-foreground would silently break that
     *  verification flow. A deliberate toggle is more honest. */
    private fun buildPauseAutoSwitchRow(): View {
        val prefs = LauncherPrefs(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(16), 0, p(16), p(8))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke((1 * dp).toInt(), Color.parseColor("#28FFFFFF"))
            }
            setPadding(p(16), p(12), p(12), p(12))
        }
        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        labelCol.addView(TextView(this).apply {
            setText(R.string.profiles_pause_autoswitch)
            setTextColor(Palette.textPrimary(this@ProfilesActivity))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        labelCol.addView(TextView(this).apply {
            setText(R.string.profiles_pause_autoswitch_hint)
            setTextColor(Palette.textSecondary(this@ProfilesActivity))
            textSize = 11f
            setPadding(0, p(2), 0, 0)
        })
        row.addView(labelCol)
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = prefs.profileAutoSwitchPaused
            setOnCheckedChangeListener { _, on ->
                prefs.profileAutoSwitchPaused = on
                // When unpausing, send a broadcast so LauncherActivity (which
                // owns the live ProfileWatcher) can re-evaluate immediately
                // and snap to whichever profile the current triggers select —
                // otherwise the user has to wait for the next WiFi/Auto/
                // geofence event before the matcher kicks in.
                if (!on) {
                    sendBroadcast(android.content.Intent(
                        ACTION_PROFILE_AUTOSWITCH_RESUMED,
                    ).setPackage(packageName))
                }
            }
        }
        row.addView(sw)
        container.addView(row)
        return container
    }

    companion object {
        /** Local broadcast action: profile auto-switch was just un-paused.
         *  Listened to in LauncherActivity to trigger an immediate
         *  ProfileWatcher.reevaluate(). */
        const val ACTION_PROFILE_AUTOSWITCH_RESUMED =
            "com.iappyx.launcher.PROFILE_AUTOSWITCH_RESUMED"

        /** Local broadcast action: a TimeOfDay-triggered profile was added,
         *  edited, or removed. LauncherActivity listens and asks the
         *  watcher to recompute the next AlarmManager boundary. */
        const val ACTION_PROFILE_TIME_RESCHEDULE =
            "com.iappyx.launcher.PROFILE_TIME_RESCHEDULE"
    }
}
