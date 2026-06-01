/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.ReceiveSheet
import com.iappyx.launcher.sharing.ShareSheet

/**
 * Shared base for the four "manage" tabs (Widgets / Wallpapers / Transitions /
 * Icon styles). Owns:
 *  - the top-bar layout (title + ⊕ popup menu + ⚙ Settings gear);
 *  - the popup menu wiring (Generate via AI / Receive from device);
 *  - shared dialog helpers (Refine, Rename, Delete confirm, single-field
 *    text input, progress with cancellable cleanup);
 *  - share-sheet + receive-sheet entry points (subclasses supply the kind and
 *    file-import handler).
 *
 * Subclasses keep their own carousel + adapter + per-card action lists. The
 * Refine + Share buttons that USED TO live in the header are folded into the
 * per-card actions array, so the header shrinks from 6 controls to 3 (title
 * isn't a control). See [Step A.4 in the manage-tab refactor plan].
 */
abstract class ManageTabBase(
    protected val activity: LauncherActivity,
    protected val host: CommandPanelHost,
) : LinearLayout(activity) {

    protected val dp = resources.displayMetrics.density

    // ── Subclass configuration ──────────────────────────────────

    /** Tab title shown in the new top bar (e.g. "Widgets", "Wallpapers"). */
    @get:StringRes protected abstract val titleRes: Int

    /** Prefilled description routed to the AI Command Bar when the user
     *  picks "Generate with AI" from the ⊕ menu. */
    @get:StringRes protected abstract val generatePrefillRes: Int

    /** Short label for the [ReceiveSheet] subtitle ("widget" / "wallpaper"
     *  / "transition" / "icon style"). */
    protected abstract val kindLabel: String

    /** Called when the user picks "Receive from device → From file" and
     *  the SAF picker returns a Uri. Subclasses parse / install. */
    protected abstract fun onReceiveFromFile(uri: android.net.Uri)

    /** Refresh the data. Called externally (e.g. from
     *  [CommandPanelHost.refreshAfterChange]) and from base helpers when a
     *  rename / delete / iterate completes. Subclasses re-read their
     *  library and rebind the carousel. */
    abstract fun refresh()

    // ── Top bar ────────────────────────────────────────────────

    /** Build the new single-row top bar: title left, contextual action row
     *  in the middle, ⚙ Settings gear far right. Subclasses populate the
     *  contextual action row in their init block via [actionRow] +
     *  [makeIconButton] — typically 7 icons (AI / Share / Edit / Files /
     *  Place-or-Use / Import / Delete) for Widgets, slightly less for the
     *  other tabs (no Files, "Apply" instead of "Place"). */
    protected fun makeHeaderBar(): View {
        val row = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * dp).toInt(), (8 * dp).toInt(),
                (4 * dp).toInt(), (8 * dp).toInt(),
            )
        }
        // Title — large, bold, ellipsizing so a long localized string never
        // pushes the action icons off the right edge on narrow screens.
        row.addView(TextView(activity).apply {
            setText(titleRes)
            setTextColor(Palette.textPrimary(context))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * dp).toInt()
            }
        })
        // Subclass-populated horizontal strip for contextual icons. Wrapped
        // in a LinearLayout so subclasses can simply call addView on it.
        actionRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(actionRow)
        row.addView(makeGearButton())
        return row
    }

    /** Container for the per-tab contextual icon row. Populated by subclass
     *  init blocks via [makeIconButton]. The base just supplies the slot
     *  between the title and the gear so the layout stays consistent across
     *  tabs. Initialised lazily by [makeHeaderBar]. */
    protected lateinit var actionRow: LinearLayout
        private set

    /** Build an icon button with an optional label below the glyph.
     *  Used to populate [actionRow] from subclass init blocks. When
     *  [labelRes] is non-zero, returns a vertical LinearLayout with the
     *  icon stacked above a small text label (the chip-set "label-under-
     *  icon" pattern). When 0, returns a plain ImageView. The wrapper
     *  takes the full ripple + click; subclasses can pass either form
     *  to [setIconEnabled] without caring about which shape it got.
     *
     *  [destructive] flips the icon tint to a soft red — used by the
     *  Delete icon so users notice it before tapping. */
    protected fun makeIconButton(
        @androidx.annotation.DrawableRes iconRes: Int,
        @StringRes contentDescRes: Int,
        @StringRes labelRes: Int = 0,
        destructive: Boolean = false,
        onClick: (View) -> Unit,
    ): View {
        val iconTint = ColorStateList.valueOf(
            if (destructive) android.graphics.Color.parseColor("#FF6B6B")
            else Palette.overlayWhiteStrong(context),
        )
        // The image inside is sized 28dp so the visual weight stays similar
        // to the previous 36dp-with-padding form, but we drop the background
        // ripple from the ImageView itself — the wrapper carries it so the
        // ripple covers icon + label as one tap target.
        val image = ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = iconTint
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        if (labelRes == 0) {
            // No label — return the bare ImageView with the click + ripple
            // attached directly. Backwards-compatible shape for callers
            // that don't want a label (no current callers, kept for future).
            image.background = ColorStateList.valueOf(Palette.separator(context))
                .let { RippleDrawable(it, null, null) }
            image.contentDescription = context.getString(contentDescRes)
            image.isClickable = true
            image.isFocusable = true
            val pad = (8 * dp).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.layoutParams = LinearLayout.LayoutParams(
                (36 * dp).toInt(), (36 * dp).toInt(),
            )
            image.setOnClickListener { onClick(image) }
            return image
        }
        // Labelled form — vertical stack inside a clickable wrapper. The
        // wrapper holds the ripple so a tap anywhere on icon + label feels
        // like one element.
        val labelTint = if (destructive)
            android.graphics.Color.parseColor("#FF6B6B")
        else Palette.textSecondary(context)
        val label = TextView(activity).apply {
            setText(labelRes)
            textSize = 10f
            setTextColor(labelTint)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = (1 * dp).toInt()
            layoutParams = lp
        }
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = ColorStateList.valueOf(Palette.separator(context))
                .let { RippleDrawable(it, null, null) }
            val padH = (4 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(contentDescRes)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (2 * dp).toInt() }
            addView(image)
            addView(label)
            setOnClickListener { onClick(this) }
        }
    }

    /** Toggle enabled+alpha for an [actionRow] icon based on whether the
     *  carousel currently has an active card the action can target. The
     *  icon stays mounted (so the layout doesn't reflow when the user
     *  swipes between bundled and user cards), it just dims and stops
     *  receiving clicks. */
    protected fun setIconEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    /** Settings gear — same look as the previous header version (white-on-
     *  surface, ripple feedback). */
    private fun makeGearButton(): View = ImageView(activity).apply {
        setImageResource(R.drawable.ic_settings)
        imageTintList = ColorStateList.valueOf(Palette.overlayWhiteStrong(context))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val sz = (40 * dp).toInt()
        val pad = (10 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(sz, sz)
        isClickable = true
        isFocusable = true
        background = ColorStateList.valueOf(Palette.separator(context))
            .let { RippleDrawable(it, null, null) }
        contentDescription = context.getString(R.string.home_settings_cd)
        setOnClickListener {
            activity.startActivity(android.content.Intent(
                activity, com.iappyx.launcher.SettingsActivity::class.java,
            ))
        }
    }

    /** Routes the user to the AI Command Bar with the tab-specific
     *  prefill prompt. Used by the top-bar "New" icon — the per-card
     *  "Refine" chip handles iteration of the visible card separately. */
    protected fun startGenerateNew() {
        host.switchToAiWithPrefill(activity.getString(generatePrefillRes))
    }

    // ── Receive flow ──────────────────────────────────────────

    /** Opens the existing [ReceiveSheet] with the subclass's kindLabel and
     *  routes its "From file" branch to [onReceiveFromFile] via the
     *  activity's SAF launcher. Centralised here so all four tabs use the
     *  same wording / behaviour. */
    protected fun openReceiveSheet() {
        ReceiveSheet(
            activity,
            kindLabel = kindLabel,
            onFromFile = {
                activity.launchImport("*/*") { uri -> onReceiveFromFile(uri) }
            },
        ).show()
    }

    // ── Share flow ────────────────────────────────────────────

    /** Open the [ShareSheet] for a specific entry. Subclasses call this from
     *  per-card "Share" actions. */
    protected fun openShareSheet(kind: ArtefactBundle.Kind, id: String) {
        ShareSheet(activity, kind, id).show()
    }

    // ── Refine flow ───────────────────────────────────────────

    /** Show the "describe what to change" dialog. [onSubmit] receives the
     *  trimmed instruction text; empty submissions are filtered. Used by
     *  per-tab refineWithAi calls; the actual iteration runs on a
     *  background thread inside the subclass. */
    protected fun showRefineDialog(
        @StringRes titleRes: Int = R.string.action_refine_with_ai,
        hint: String,
        onSubmit: (String) -> Unit,
    ) {
        showSingleFieldDialog(
            title = activity.getString(titleRes),
            initial = "",
            multiLine = true,
            hint = hint,
            positiveLabel = activity.getString(R.string.action_refine),
            onSave = { instruction ->
                val trimmed = instruction.trim()
                if (trimmed.isNotEmpty()) onSubmit(trimmed)
            },
        )
    }

    /** Modal progress dialog used during long iterate runs. Returns the
     *  AlertDialog so the caller can dismiss when work completes. */
    protected fun showProgressDialog(
        @StringRes titleRes: Int,
        message: String,
    ): AlertDialog {
        val d = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setView(ProgressBar(activity).apply {
                val pad = (20 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            })
            .setCancelable(false)
            .create()
        d.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(d) }
        d.show()
        return d
    }

    // ── Rename / Edit-meta flow ───────────────────────────────

    /** Two-field rename + description editor. Both fields are pre-populated;
     *  on save the callback gets the new title + new description (each
     *  trimmed). Either may be unchanged from the input — subclasses decide
     *  what to act on. Empty title is silently kept (no rename) — the
     *  callback still fires so subclasses can persist a description-only
     *  change. */
    protected fun showRenameDialog(
        currentTitle: String,
        currentDescription: String,
        @StringRes titleRes: Int = R.string.action_edit_name_desc,
        onSave: (newTitle: String, newDescription: String) -> Unit,
    ) {
        val pad = (20 * dp).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val titleField = EditText(activity).apply {
            setText(currentTitle); setSelection(currentTitle.length)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.hint_name)
        }
        val descField = EditText(activity).apply {
            setText(currentDescription)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            setHint(R.string.hint_description)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        container.addView(titleField)
        container.addView(descField)
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                onSave(
                    titleField.text.toString().trim(),
                    descField.text.toString().trim(),
                )
            }.showThemed()
    }

    // ── Single-field text dialog ──────────────────────────────

    /** Generic single-field text dialog (used by refineDialog and one-off
     *  rename-only flows). Centralised to keep dialog padding / input-type
     *  defaults consistent across tabs. */
    protected fun showSingleFieldDialog(
        title: String,
        initial: String = "",
        multiLine: Boolean = false,
        hint: String? = null,
        positiveLabel: String = activity.getString(R.string.action_save),
        onSave: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(initial); setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            maxLines = if (multiLine) 6 else 2
            if (hint != null) this.hint = hint
        }
        val pad = (20 * dp).toInt()
        val frame = FrameLayout(activity).apply {
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(frame)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(positiveLabel) { _, _ -> onSave(input.text.toString()) }
            .showThemed()
    }

    // ── Delete confirm ────────────────────────────────────────

    /** Shared "this can't be undone" delete confirmation. Subclasses pass the
     *  user-visible artefact name + a confirmation callback. */
    protected fun showDeleteConfirm(
        artefactTitle: String,
        @StringRes titleRes: Int,
        onConfirm: () -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(activity.getString(R.string.manage_delete_confirm_format, artefactTitle))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> onConfirm() }
            .showThemed()
    }

    // ── Common toast helpers ──────────────────────────────────

    protected fun toast(@StringRes msgRes: Int, length: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(activity, msgRes, length).show()
    }

    protected fun toast(msg: String, length: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(activity, msg, length).show()
    }

}
