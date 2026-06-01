/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.intent.IntentRunner
import com.iappyx.launcher.model.IntentAction
import com.iappyx.launcher.model.IntentExtra
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed

/**
 * Configure a single [IntentAction] attached to a profile. Save returns
 * the JSON via ActivityResult; caller [ProfilesActivity] splices it
 * into the profile.
 *
 * Programmatic UI — no XML — to keep the editor self-contained. The
 * launcher already uses programmatic-only UIs in several places
 * (CommandPanel, IconEditor pieces) so this fits the codebase style.
 */
class IntentActionEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_SLUG = "profile_slug"
        const val EXTRA_REPLACE_INDEX = "replace_index"
        const val EXTRA_EXISTING_JSON = "existing_json"
        const val EXTRA_RESULT_ACTION_JSON = "result_action_json"
        private const val STATE_DRAFT_JSON = "draft_json"

        /** Build the launch [Intent] for adding (replaceIndex = -1) or
         *  editing (replaceIndex >= 0) an action on the given profile. */
        fun intent(
            ctx: Context,
            profileSlug: String,
            replaceIndex: Int,
            existing: IntentAction?,
        ): Intent = Intent(ctx, IntentActionEditorActivity::class.java).apply {
            putExtra(EXTRA_PROFILE_SLUG, profileSlug)
            putExtra(EXTRA_REPLACE_INDEX, replaceIndex)
            if (existing != null) putExtra(EXTRA_EXISTING_JSON, existing.toJson().toString())
        }
    }

    // ── UI references ─────────────────────────────────────────────────
    private lateinit var labelField: EditText
    private lateinit var verbSpinner: Spinner
    private lateinit var packageField: AutoCompleteTextView
    private lateinit var classField: AutoCompleteTextView
    private lateinit var actionField: AutoCompleteTextView
    private lateinit var dataUriField: EditText
    private lateinit var mimeTypeField: EditText
    private lateinit var categoriesField: EditText
    private lateinit var flagsContainer: LinearLayout
    private lateinit var warmupCheckbox: CheckBox
    private lateinit var extrasContainer: LinearLayout
    private lateinit var addExtraBtn: Button
    private lateinit var testResultLabel: TextView

    private val flagOptions = listOf(
        "FLAG_ACTIVITY_NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
        "FLAG_ACTIVITY_CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
        "FLAG_ACTIVITY_CLEAR_TASK" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
        "FLAG_RECEIVER_FOREGROUND" to Intent.FLAG_RECEIVER_FOREGROUND,
        "FLAG_INCLUDE_STOPPED_PACKAGES" to Intent.FLAG_INCLUDE_STOPPED_PACKAGES,
    )
    private val flagCheckBoxes = mutableListOf<CheckBox>()

    private val extras = mutableListOf<IntentExtra>()

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.intent_editor_title)
        // Use the activity-window background so dialog/list usage feels
        // native to the launcher's existing dark settings UIs.
        window.decorView.setBackgroundColor(Palette.bgHome(this))

        // Restore in-progress edits across config changes (rotation, font
        // size, dark-mode toggle). Falls back to EXTRA_EXISTING_JSON when
        // no saved-state bundle exists (fresh entry).
        val draftJson = savedInstanceState?.getString(STATE_DRAFT_JSON)
        val existing = (draftJson ?: intent.getStringExtra(EXTRA_EXISTING_JSON))?.let {
            try { IntentAction.fromJson(org.json.JSONObject(it)) } catch (_: Throwable) { null }
        }

        setContentView(buildUi())
        if (existing != null) hydrate(existing)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Snapshot the form so rotation / font-size / dark-mode change
        // doesn't wipe the user's in-progress draft. We serialise via the
        // same JSON shape we save with so onCreate can re-hydrate via
        // the existing path.
        try {
            val draft = buildDraftFromForm() ?: return
            outState.putString(STATE_DRAFT_JSON, draft.toJson().toString())
        } catch (_: Throwable) { /* drop — better lost-draft than crash */ }
    }

    // ── UI construction ───────────────────────────────────────────────

    private fun buildUi(): View {
        // Outer page = vertical column with system-bar inset padding so
        // the header doesn't slide under the status bar / nav bar on
        // edge-to-edge displays. Same shape ProfilesActivity uses.
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bgHome(this@IntentActionEditorActivity))
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // Header — back chip + title — matches ProfilesActivity styling.
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(12), p(8), p(20), p(8))
            // Back chip
            addView(TextView(this@IntentActionEditorActivity).apply {
                setText(R.string.action_back)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.accent(this@IntentActionEditorActivity))
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.accent(this@IntentActionEditorActivity), 0x1F))
                    setStroke((1 * dp).toInt(),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(
                            Palette.accent(this@IntentActionEditorActivity), 0x66))
                }
                setPadding(p(14), p(8), p(14), p(8))
                isClickable = true; isFocusable = true
                setOnClickListener { finish() }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.rightMargin = p(12)
                layoutParams = lp
            })
            addView(TextView(this@IntentActionEditorActivity).apply {
                setText(R.string.intent_editor_title)
                setTextColor(Palette.textPrimary(this@IntentActionEditorActivity))
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        // Body = scrollable form content.
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(20), p(8), p(20), p(40))
        }

        // Top-most form item: preset picker. Users who know the spec they
        // want can ignore this; users who don't get a curated start point.
        val presetBtn = Button(this).apply {
            text = getString(R.string.intent_editor_preset_btn)
            setOnClickListener { showPresetPicker() }
        }
        root.addView(presetBtn)

        root.addView(sectionLabel(R.string.intent_editor_label_label))
        labelField = EditText(this).apply {
            hint = getString(R.string.intent_editor_label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(labelField)

        root.addView(sectionLabel(R.string.intent_editor_verb_label))
        verbSpinner = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@IntentActionEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                IntentAction.Verb.values().map { it.name },
            )
        }
        root.addView(verbSpinner)
        verbSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long) {
                refreshClassAutocomplete()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        root.addView(sectionLabel(R.string.intent_editor_package_label))
        packageField = AutoCompleteTextView(this).apply {
            hint = getString(R.string.intent_editor_package_hint)
            threshold = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(packageField)
        packageField.setAdapter(installedPackagesAdapter())
        packageField.setOnItemClickListener { _, _, pos, _ ->
            val pkg = (packageField.adapter.getItem(pos) as? PackageRow)?.pkg
            if (pkg != null) {
                packageField.setText(pkg, false)
                refreshClassAutocomplete()
            }
        }
        // Re-fill class autocomplete when the package field loses focus
        // even without an explicit dropdown click (user typed it manually).
        packageField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refreshClassAutocomplete() }

        root.addView(sectionLabel(R.string.intent_editor_class_label))
        classField = AutoCompleteTextView(this).apply {
            hint = getString(R.string.intent_editor_class_hint)
            threshold = 0
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(classField)

        root.addView(sectionLabel(R.string.intent_editor_action_label))
        actionField = AutoCompleteTextView(this).apply {
            hint = getString(R.string.intent_editor_action_hint)
            threshold = 0
            inputType = InputType.TYPE_CLASS_TEXT
            setAdapter(android.widget.ArrayAdapter(
                this@IntentActionEditorActivity,
                android.R.layout.simple_dropdown_item_1line,
                listOf(
                    Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_MAIN,
                    Intent.ACTION_DIAL, Intent.ACTION_CALL,
                    "com.wireguard.android.action.SET_TUNNEL_UP",
                    "com.wireguard.android.action.SET_TUNNEL_DOWN",
                    "net.dinglisch.android.tasker.ACTION_TASK",
                ),
            ))
        }
        root.addView(actionField)

        root.addView(sectionLabel(R.string.intent_editor_data_label))
        dataUriField = EditText(this).apply {
            hint = getString(R.string.intent_editor_data_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(dataUriField)

        root.addView(sectionLabel(R.string.intent_editor_mime_label))
        mimeTypeField = EditText(this).apply {
            hint = getString(R.string.intent_editor_mime_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(mimeTypeField)

        root.addView(sectionLabel(R.string.intent_editor_categories_label))
        categoriesField = EditText(this).apply {
            hint = getString(R.string.intent_editor_categories_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(categoriesField)

        root.addView(sectionLabel(R.string.intent_editor_flags_label))
        flagsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((name, _) in flagOptions) {
            val cb = CheckBox(this).apply { text = name }
            flagCheckBoxes += cb
            flagsContainer.addView(cb)
        }
        root.addView(flagsContainer)

        // Warm-up opt-in. Off by default; flip on per-action when the
        // target is prone to the cached-app freeze problem.
        root.addView(sectionLabel(R.string.intent_editor_warmup_label))
        warmupCheckbox = CheckBox(this).apply {
            text = getString(R.string.intent_editor_warmup_checkbox_text)
        }
        root.addView(warmupCheckbox)
        // Inline hint paragraph so the tradeoff isn't invisible.
        root.addView(TextView(this).apply {
            setText(R.string.intent_editor_warmup_hint)
            textSize = 12f
            setPadding(0, p(2), 0, 0)
            setTextColor(Palette.textSecondary(this@IntentActionEditorActivity))
        })

        root.addView(sectionLabel(R.string.intent_editor_extras_label))
        extrasContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(extrasContainer)
        addExtraBtn = Button(this).apply {
            text = getString(R.string.intent_editor_extras_add)
            setOnClickListener {
                extras += IntentExtra("", IntentExtra.ExtraType.STRING, "")
                renderExtras()
            }
        }
        root.addView(addExtraBtn)

        // Test + Save action row at the bottom.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, p(20), 0, 0)
        }
        val testBtn = Button(this).apply {
            text = getString(R.string.intent_editor_test_btn)
            setOnClickListener { runTest() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveBtn = Button(this).apply {
            text = getString(R.string.intent_editor_save_btn)
            setOnClickListener { onSave() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(testBtn)
        actionRow.addView(saveBtn)
        root.addView(actionRow)

        testResultLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, p(8), 0, 0)
            setTextColor(Color.parseColor("#80A0A0B8"))
        }
        root.addView(testResultLabel)

        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // Body fills remaining space below the header.
        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0,
        ).also { it.weight = 1f })
        return page
    }

    private fun sectionLabel(@androidx.annotation.StringRes id: Int): TextView = TextView(this).apply {
        setText(id)
        setTypeface(typeface, Typeface.BOLD)
        textSize = 13f
        setPadding(0, p(14), 0, p(4))
        setTextColor(Palette.textSecondary(this@IntentActionEditorActivity))
    }

    // ── Hydration / save ──────────────────────────────────────────────

    /** Repopulate the form fields from [a]. Two callers:
     *  1. Restoring a saved IntentAction (edit existing) — full restore.
     *  2. Applying a preset template — [fromPreset]=true. In that case
     *     we preserve user-local choices that the preset template would
     *     otherwise silently overwrite back to default (warm-up). */
    private fun hydrate(a: IntentAction, fromPreset: Boolean = false) {
        labelField.setText(a.label)
        val verbIdx = IntentAction.Verb.values().indexOf(a.verb).coerceAtLeast(0)
        verbSpinner.setSelection(verbIdx)
        packageField.setText(a.packageName.orEmpty(), false)
        classField.setText(a.className.orEmpty(), false)
        actionField.setText(a.action.orEmpty(), false)
        dataUriField.setText(a.dataUri.orEmpty())
        mimeTypeField.setText(a.mimeType.orEmpty())
        categoriesField.setText(a.categories.joinToString(", "))
        for ((i, pair) in flagOptions.withIndex()) {
            flagCheckBoxes[i].isChecked = (a.flags and pair.second) != 0
        }
        // Preset templates always carry warmupTargetFirst=false. If the
        // user already enabled warm-up for this action, keep their choice
        // when re-applying a preset (so re-applying to "reset some other
        // field" doesn't quietly disable warm-up). On a fresh edit, the
        // saved value is faithfully restored.
        warmupCheckbox.isChecked = if (fromPreset) {
            warmupCheckbox.isChecked || a.warmupTargetFirst
        } else {
            a.warmupTargetFirst
        }
        extras.clear()
        extras += a.extras
        renderExtras()
        refreshClassAutocomplete()
    }

    private fun onSave() {
        val label = labelField.text.toString().trim()
        if (label.isEmpty()) {
            Toast.makeText(this, R.string.intent_editor_label_required, Toast.LENGTH_SHORT).show()
            return
        }
        val verb = runCatching {
            IntentAction.Verb.valueOf(verbSpinner.selectedItem.toString())
        }.getOrDefault(IntentAction.Verb.BROADCAST)
        val pkg = packageField.text.toString().trim().ifEmpty { null }
        val cls = classField.text.toString().trim().ifEmpty { null }
        val act = actionField.text.toString().trim().ifEmpty { null }
        val data = dataUriField.text.toString().trim().ifEmpty { null }
        // At least one targeting field is required — without any of these
        // the resulting Intent matches nothing and we'd silently report
        // "Action sent" while delivering to no receiver.
        if (act == null && pkg == null && data == null) {
            Toast.makeText(
                this, R.string.intent_editor_targeting_required, Toast.LENGTH_LONG,
            ).show()
            return
        }
        // Drop extras with blank keys — round-trip would lose them silently
        // (IntentExtra.fromJson skips blank-key entries), so warn the user
        // and strip them here. Saves with the user's value-only rows
        // disappearing on next load otherwise.
        val droppedBlankKeys = extras.count { it.key.isBlank() }
        val cleanExtras = extras.filter { it.key.isNotBlank() }
        if (droppedBlankKeys > 0) {
            Toast.makeText(
                this,
                getString(R.string.intent_editor_blank_extras_dropped, droppedBlankKeys),
                Toast.LENGTH_LONG,
            ).show()
        }
        // Categories from comma-separated input
        val cats = categoriesField.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotBlank() }
        var flags = 0
        for ((i, pair) in flagOptions.withIndex()) {
            if (flagCheckBoxes[i].isChecked) flags = flags or pair.second
        }
        val action = IntentAction(
            label = label,
            verb = verb,
            packageName = pkg,
            className = cls,
            action = act,
            dataUri = data,
            mimeType = mimeTypeField.text.toString().trim().ifEmpty { null },
            categories = cats,
            flags = flags,
            extras = cleanExtras,
            warmupTargetFirst = warmupCheckbox.isChecked,
        )
        val result = Intent().apply {
            putExtra(EXTRA_PROFILE_SLUG, intent.getStringExtra(EXTRA_PROFILE_SLUG))
            putExtra(EXTRA_REPLACE_INDEX,
                intent.getIntExtra(EXTRA_REPLACE_INDEX, -1))
            putExtra(EXTRA_RESULT_ACTION_JSON, action.toJson().toString())
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun runTest() {
        val draft = buildDraftFromForm() ?: return

        // Pre-flight: if the target needs a permission we declared in
        // our manifest but the user hasn't runtime-granted yet, ask
        // first. requestPermissions is async; we set [pendingTestAfterGrant]
        // so onRequestPermissionsResult can re-fire runTest() the moment
        // the user accepts (no second manual tap needed).
        val needed = neededDeclaredPermissionFor(draft)
        if (needed != null) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, needed) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                testResultLabel.text = getString(
                    R.string.intent_editor_test_perm_pending_format, needed,
                )
                pendingTestAfterGrant = true
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(needed), REQ_CODE_TEST_PERM,
                )
                return
            }
        }

        val r = IntentRunner.fire(this, draft)
        testResultLabel.text = when (r) {
            is IntentRunner.Result.Ok -> getString(R.string.profiles_custom_actions_test_ok)
            is IntentRunner.Result.NoMatchingComponent ->
                getString(R.string.profiles_custom_actions_test_nomatch_format, r.message)
            is IntentRunner.Result.PermissionDenied ->
                getString(R.string.profiles_custom_actions_test_perm_format, r.message)
            is IntentRunner.Result.Failed ->
                getString(R.string.profiles_custom_actions_test_failed_format,
                    r.throwable.message ?: "error")
        }
    }

    /** Map of target packages we know are receiver-permission-gated to the
     *  permission they require. Same minimal list as in AndroidManifest;
     *  keep them in sync. Returns the permission only when this draft
     *  targets one of those packages — null otherwise. */
    private fun neededDeclaredPermissionFor(draft: IntentAction): String? = when (draft.packageName) {
        "com.wireguard.android" -> "com.wireguard.android.permission.CONTROL_TUNNELS"
        else -> null
    }

    /** Set when [runTest] short-circuited to request a runtime permission.
     *  [onRequestPermissionsResult] consumes it: if the user granted, we
     *  re-fire runTest() automatically so they don't have to tap again. */
    private var pendingTestAfterGrant: Boolean = false
    private val REQ_CODE_TEST_PERM = 0xC051

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CODE_TEST_PERM) return
        val pending = pendingTestAfterGrant
        pendingTestAfterGrant = false
        if (!pending) return
        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            runTest()
        } else {
            testResultLabel.text = getString(
                R.string.intent_editor_test_perm_denied,
            )
        }
    }

    /** Two-step preset picker. The picker is a custom programmatic view
     *  with category section headers and per-preset rows (bold title,
     *  small dim summary) so it looks like the rest of the launcher's
     *  settings UI rather than a default-styled AlertDialog list. Tap a
     *  row → confirm dialog with toggle requirements and fill hints.
     *  Confirm → form is rehydrated with the preset template. */
    private fun showPresetPicker() {
        val presets = com.iappyx.launcher.intent.IntentPresetLibrary.all(this)
        if (presets.isEmpty()) {
            android.widget.Toast.makeText(
                this, "No presets available", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, p(8), 0, p(8))
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.intent_editor_preset_picker_title)
            .setView(scroll)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Group preset rows by category, with a small section header above
        // each group. Same visual hierarchy a Material settings list uses.
        val byCat = presets.groupBy { it.category }
        for ((cat, items) in byCat) {
            list.addView(TextView(this).apply {
                text = cat.uppercase()
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.08f
                setTextColor(Palette.accentLight(this@IntentActionEditorActivity))
                setPadding(p(20), p(14), p(20), p(6))
            })
            for (preset in items) {
                list.addView(buildPresetRow(preset) {
                    dialog.dismiss()
                    showPresetConfirm(preset)
                })
            }
        }
        dialog.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(dialog) }
        dialog.show()
    }

    /** One row in the preset picker — title (bold) + summary (dim, small),
     *  with selectableItemBackground ripple. */
    private fun buildPresetRow(
        preset: com.iappyx.launcher.intent.IntentPresetLibrary.Preset,
        onTap: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(20), p(10), p(20), p(10))
            isClickable = true
            isFocusable = true
            // Use the platform pressed-state ripple so the picker feels
            // like a native settings list, not a custom one-off panel.
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true,
            )
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onTap() }
        }
        row.addView(TextView(this).apply {
            text = preset.label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.textPrimary(this@IntentActionEditorActivity))
        })
        row.addView(TextView(this).apply {
            text = preset.summary
            textSize = 12f
            setTextColor(Palette.textSecondary(this@IntentActionEditorActivity))
            setPadding(0, p(2), 0, 0)
        })
        return row
    }

    private fun showPresetConfirm(preset: com.iappyx.launcher.intent.IntentPresetLibrary.Preset) {
        val msg = buildString {
            append(preset.summary).append("\n\n")
            if (preset.needsToggle.isNotBlank()) {
                append("Setup needed:\n").append(preset.needsToggle).append("\n\n")
            }
            if (preset.fillHints.isNotBlank()) {
                append("After Apply:\n").append(preset.fillHints)
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(preset.label)
            .setMessage(msg)
            .setPositiveButton(R.string.intent_editor_preset_apply) { _, _ ->
                hydrate(preset.template, fromPreset = true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    private fun buildDraftFromForm(): IntentAction? {
        val verb = runCatching {
            IntentAction.Verb.valueOf(verbSpinner.selectedItem.toString())
        }.getOrDefault(IntentAction.Verb.BROADCAST)
        var flags = 0
        for ((i, pair) in flagOptions.withIndex()) {
            if (flagCheckBoxes[i].isChecked) flags = flags or pair.second
        }
        return IntentAction(
            label = labelField.text.toString().trim().ifEmpty { "(test)" },
            verb = verb,
            packageName = packageField.text.toString().trim().ifEmpty { null },
            className = classField.text.toString().trim().ifEmpty { null },
            action = actionField.text.toString().trim().ifEmpty { null },
            dataUri = dataUriField.text.toString().trim().ifEmpty { null },
            mimeType = mimeTypeField.text.toString().trim().ifEmpty { null },
            categories = categoriesField.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotBlank() },
            flags = flags,
            extras = extras.toList(),
            warmupTargetFirst = warmupCheckbox.isChecked,
        )
    }

    // ── Extras editor rows ────────────────────────────────────────────

    private fun renderExtras() {
        extrasContainer.removeAllViews()
        for ((index, _) in extras.withIndex()) {
            extrasContainer.addView(buildExtraRow(index))
        }
    }

    private fun buildExtraRow(index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, p(4), 0, p(4))
        }
        val keyEdit = EditText(this).apply {
            hint = getString(R.string.intent_editor_extras_key_hint)
            setText(extras[index].key)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        keyEdit.addTextChangedListener(simpleWatcher {
            extras[index] = extras[index].copy(key = keyEdit.text.toString())
        })

        val typeSpinner = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@IntentActionEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                IntentExtra.ExtraType.values().map { it.name },
            )
            setSelection(IntentExtra.ExtraType.values().indexOf(extras[index].type))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long) {
                extras[index] = extras[index].copy(type = IntentExtra.ExtraType.values()[i])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val valueEdit = EditText(this).apply {
            hint = getString(R.string.intent_editor_extras_value_hint)
            setText(extras[index].value)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        valueEdit.addTextChangedListener(simpleWatcher {
            extras[index] = extras[index].copy(value = valueEdit.text.toString())
        })

        val removeBtn = Button(this).apply {
            text = "✕"
            setOnClickListener {
                extras.removeAt(index)
                renderExtras()
            }
        }

        row.addView(keyEdit)
        row.addView(typeSpinner)
        row.addView(valueEdit)
        row.addView(removeBtn)
        return row
    }

    private inline fun simpleWatcher(crossinline onChange: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { onChange() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

    // ── Autocomplete data sources ─────────────────────────────────────

    private data class PackageRow(val pkg: String, val label: String) {
        override fun toString(): String = "$label ($pkg)"
    }

    private fun installedPackagesAdapter(): android.widget.ArrayAdapter<PackageRow> {
        val pm = packageManager
        val all = try { pm.getInstalledApplications(0) } catch (_: Throwable) { emptyList() }
        val rows = all.map {
            val label = try { pm.getApplicationLabel(it).toString() } catch (_: Throwable) { it.packageName }
            PackageRow(it.packageName, label)
        }.sortedBy { it.label.lowercase() }
        return android.widget.ArrayAdapter(this,
            android.R.layout.simple_dropdown_item_1line, rows)
    }

    /** Pull receiver / activity / service classes for the typed package and
     *  hand them to the class field's autocomplete. Marks non-exported
     *  ones with a "(may need setup)" suffix so the user knows they
     *  might need to enable a remote-control toggle in the target app. */
    private fun refreshClassAutocomplete() {
        val pkg = packageField.text.toString().trim()
        if (pkg.isEmpty()) {
            classField.setAdapter(android.widget.ArrayAdapter(this,
                android.R.layout.simple_dropdown_item_1line, emptyList<String>()))
            return
        }
        val verb = runCatching {
            IntentAction.Verb.valueOf(verbSpinner.selectedItem.toString())
        }.getOrDefault(IntentAction.Verb.BROADCAST)
        val flag = when (verb) {
            IntentAction.Verb.BROADCAST -> PackageManager.GET_RECEIVERS
            IntentAction.Verb.ACTIVITY -> PackageManager.GET_ACTIVITIES
            IntentAction.Verb.SERVICE,
            IntentAction.Verb.FOREGROUND_SERVICE -> PackageManager.GET_SERVICES
        }
        val classes = try {
            val info = packageManager.getPackageInfo(pkg, flag)
            val rs = when (verb) {
                IntentAction.Verb.BROADCAST -> info.receivers?.map { it.name to it.exported }
                IntentAction.Verb.ACTIVITY -> info.activities?.map { it.name to it.exported }
                IntentAction.Verb.SERVICE,
                IntentAction.Verb.FOREGROUND_SERVICE -> info.services?.map { it.name to it.exported }
            } ?: emptyList()
            rs.map { (name, exported) ->
                if (exported) name else getString(R.string.intent_editor_class_not_exported_format, name)
            }
        } catch (_: Throwable) { emptyList() }
        classField.setAdapter(android.widget.ArrayAdapter(this,
            android.R.layout.simple_dropdown_item_1line, classes))
    }

    private fun p(dpVal: Int): Int = (dpVal * dp).toInt()
}
