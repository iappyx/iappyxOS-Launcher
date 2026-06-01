/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.R
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconFilterRunner
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.sharing.ArtefactBundle

/**
 * Manage tab for icon filters. Mirrors the structure of the other manage
 * tabs but uses a vertical [RecyclerView] (not a carousel) because each
 * filter preview is a slim row of 4 sample icons.
 *
 * Top bar (provided by [ManageTabBase]): Title · New · Import · ⚙
 * (No Share in the top bar — there's no "currently visible" card here
 * the way there is in the carousel tabs; sharing happens per-row.)
 *
 * Per-card chip strip (under the preview row): Use · Refine · Share · Edit · Delete.
 * Bundled filters show only Use; the rest don't apply to read-only
 * bundled assets.
 */
class ManageIconFiltersTab(
    activity: LauncherActivity,
    host: CommandPanelHost,
) : ManageTabBase(activity, host) {

    override val titleRes: Int = R.string.manage_tab_title_icon_styles
    override val generatePrefillRes: Int = R.string.manage_icons_prefill_prompt
    override val kindLabel: String = "icon style"

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private val adapter = FiltersAdapter()
    private var entries: List<IconFilterRegistry.Entry> = emptyList()

    private lateinit var newBtn: View
    private lateinit var importBtn: View

    /** Cached package list of "popular" apps used as preview-icon sources.
     *  Lazily resolved on first refresh — reading PackageManager isn't
     *  free, and the set doesn't change while the tab is alive. */
    private var previewPackages: List<String> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.bgHome(activity))

        addView(makeHeaderBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Top-bar contextual icon row: New · Import. No Share — sharing
        // applies to a specific filter, so it lives per-card.
        newBtn = makeIconButton(
            iconRes = R.drawable.ic_auto_awesome,
            contentDescRes = R.string.manage_icon_cd_new,
            labelRes = R.string.manage_icon_label_new,
        ) { startGenerateNew() }
        importBtn = makeIconButton(
            iconRes = R.drawable.ic_file_download,
            contentDescRes = R.string.manage_icon_cd_import,
            labelRes = R.string.manage_icon_label_import,
        ) { openReceiveSheet() }
        actionRow.addView(newBtn)
        actionRow.addView(importBtn)

        recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ManageIconFiltersTab.adapter
            clipToPadding = false
            setPadding(
                (12 * dp).toInt(), (8 * dp).toInt(),
                (12 * dp).toInt(), (16 * dp).toInt(),
            )
        }
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyState = TextView(activity).apply {
            setText(R.string.manage_icons_empty)
            setTextColor(Palette.textDisabled(context))
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(
                (32 * dp).toInt(), (32 * dp).toInt(),
                (32 * dp).toInt(), (32 * dp).toInt(),
            )
            visibility = View.GONE
        }
        addView(emptyState, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun refresh() {
        if (previewPackages.isEmpty()) previewPackages = pickPreviewPackages()
        entries = IconFilterRegistry.all(activity)
        adapter.notifyDataSetChanged()
        // The list is never empty (10 bundled entries always present), so the
        // empty state only shows when bundles fail to load. Defensive.
        val empty = entries.isEmpty()
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ── Active filter management ────────────────────────────

    private fun setActive(slug: String) {
        val prefs = LauncherPrefs(activity)
        if (prefs.iconFilter == slug) return
        prefs.iconFilter = slug
        IconMask.clearCache()
        IconFilterRegistry.invalidateAll()
        // Repaint the home grid + dock now that the active filter changed.
        // Activity's onResume fallback also catches this, but doing it here
        // keeps the manage-tab feedback instant.
        activity.notifyIconFiltersChanged()
        adapter.notifyDataSetChanged()
    }

    private fun refineWithAi(entry: IconFilterRegistry.Entry) {
        showRefineDialog(
            titleRes = R.string.manage_icons_refine_dialog_format_id,
            hint = activity.getString(R.string.manage_icons_refine_hint),
        ) { instruction ->
            toast(R.string.manage_icons_refining_toast)
            Thread {
                try {
                    com.iappyx.launcher.cells.IconFilterGenerator
                        .iterate(activity, entry.slug, instruction)
                    activity.runOnUiThread {
                        IconMask.clearCache()
                        activity.notifyIconFiltersChanged()
                        refresh()
                    }
                } catch (e: Throwable) {
                    activity.runOnUiThread {
                        val unknownTxt = activity.getString(R.string.unknown_error_short)
                        toast(activity.getString(
                            R.string.manage_icons_refine_failed_toast_format,
                            e.message ?: unknownTxt,
                        ), Toast.LENGTH_LONG)
                    }
                }
            }.start()
        }
    }

    private fun renameDialog(entry: IconFilterRegistry.Entry) {
        showSingleFieldDialog(
            title = activity.getString(R.string.manage_icons_rename_dialog_title),
            initial = entry.title,
        ) { newName ->
            if (IconFilterRegistry.renameUser(activity, entry.slug, newName)) refresh()
        }
    }

    private fun confirmDelete(entry: IconFilterRegistry.Entry) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.manage_icons_delete_dialog_title_format, entry.title))
            .setMessage(R.string.manage_icons_delete_dialog_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wasActive = LauncherPrefs(activity).iconFilter == entry.slug
                if (IconFilterRegistry.delete(activity, entry.slug)) {
                    if (wasActive) {
                        // Fall back to "none" so we never leave a dangling
                        // pointer in prefs.
                        setActive("none")
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    // ── Receive (called by base when ⊕ → Receive → From file fires) ─

    override fun onReceiveFromFile(uri: android.net.Uri) {
        try {
            val bytes = activity.contentResolver.openInputStream(uri)?.readBytes()
                ?: throw java.io.IOException(activity.getString(R.string.manage_icons_read_failed))
            val bundle = ArtefactBundle.readBundle(bytes)
            if (bundle.kind != ArtefactBundle.Kind.ICON_FILTER) {
                toast(activity.getString(R.string.manage_icons_wrong_kind_format, bundle.kind.label),
                    Toast.LENGTH_LONG)
                return
            }
            val slug = ArtefactBundle.install(activity, bundle)
            toast(activity.getString(R.string.manage_icons_imported_toast_format, bundle.title))
            // Stamp meta from the bundle if it didn't already have one.
            if ("meta.json" !in bundle.files) {
                IconFilterRegistry.writeMeta(
                    activity, slug, prompt = bundle.prompt, title = bundle.title,
                )
            }
            refresh()
        } catch (e: Throwable) {
            val unknownTxt = activity.getString(R.string.unknown_error_short)
            toast(activity.getString(R.string.manage_icons_import_failed_toast_format, e.message ?: unknownTxt),
                Toast.LENGTH_LONG)
        }
    }

    // ── Preview-icon resolution ─────────────────────────────

    /** Pick four installed packages whose icons read clearly at 48dp.
     *  Pulls from the user's most-launched first (so the preview reflects
     *  what they actually use), padding with the device's default browser /
     *  camera / gallery as a last resort. Falls back to `null` slots in the
     *  preview row when fewer than four resolvable packages exist. */
    private fun pickPreviewPackages(): List<String> {
        val launches = LauncherPrefs(activity).launchCounts()
            .entries.sortedByDescending { it.value }.map { it.key }
        val candidates = (launches + listOf(
            "com.android.chrome", "com.google.android.apps.maps",
            "com.android.calendar", "com.android.gallery3d",
            "com.android.settings", "com.android.deskclock",
        )).distinct()
        val pm = activity.packageManager
        val resolved = mutableListOf<String>()
        for (pkg in candidates) {
            if (resolved.size >= 4) break
            try { pm.getApplicationInfo(pkg, 0); resolved.add(pkg) }
            catch (_: Throwable) { /* not installed */ }
        }
        return resolved
    }

    // ── Adapter ─────────────────────────────────────────────

    private inner class FiltersAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 14 * dp
                    setColor(Palette.separator(context))
                    setStroke((1 * dp).toInt(), Palette.separator(context))
                }
                val p = (14 * dp).toInt()
                setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (10 * dp).toInt() }
            }
            // Row header: title + subtitle on the left.
            val titleRow = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleCol = LinearLayout(activity).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(activity).apply {
                setTextColor(Palette.textPrimary(context)); textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val subtitle = TextView(activity).apply {
                setTextColor(Palette.textSecondary(context)); textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }
            titleCol.addView(title); titleCol.addView(subtitle)
            titleRow.addView(titleCol)
            card.addView(titleRow)

            // Preview row: 4 slot ImageViews showing representative icons under
            // the entry's filter. Filled in onBindViewHolder.
            val preview = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            val slot = (48 * dp).toInt()
            repeat(4) {
                preview.addView(ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(slot, slot).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
            card.addView(preview)

            // Per-card chip strip — icon + label form. Wired in
            // onBindViewHolder so chips reflect the row's entry state
            // (active / locked / etc).
            val chipRow = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            card.addView(chipRow)

            return Holder(card, title, subtitle, preview, chipRow)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as Holder
            val entry = entries[position]
            val isActive = entry.slug == LauncherPrefs(activity).iconFilter
            val locked = !entry.isUserGenerated
            h.title.text = entry.title
            h.subtitle.text = entry.subtitle
            h.subtitle.visibility = if (entry.subtitle.isBlank()) View.GONE else View.VISIBLE
            // Make the active card stand out a little extra.
            (h.itemView.background as? GradientDrawable)?.let { bg ->
                bg.setStroke(
                    ((if (isActive) 2 else 1) * dp).toInt(),
                    if (isActive) Palette.accentChipStroke(context)
                    else Palette.separator(context),
                )
            }

            // Preview icons under this entry's filter.
            val spec = IconFilterRegistry.resolve(activity, entry.slug)
            val pm = activity.packageManager
            for (i in 0 until 4) {
                val iv = h.preview.getChildAt(i) as ImageView
                val pkg = previewPackages.getOrNull(i)
                if (pkg == null) {
                    iv.setImageDrawable(null); iv.visibility = View.INVISIBLE; continue
                }
                iv.visibility = View.VISIBLE
                try {
                    val raw = pm.getApplicationIcon(pkg)
                    val sizePx = (48 * dp).toInt()
                    iv.setImageBitmap(IconMask.render(pkg, raw, sizePx, spec))
                    val tint = IconFilterRunner.tintFor(activity, spec, gridPos = null)
                    if (tint != null) {
                        iv.setColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY)
                    } else {
                        iv.clearColorFilter()
                    }
                } catch (_: Throwable) {
                    iv.setImageDrawable(null); iv.visibility = View.INVISIBLE
                }
            }

            // Rebuild the chip row each bind. Bundled entries get only Use;
            // user-generated get Use / Refine / Share / Edit / Delete.
            h.chipRow.removeAllViews()
            h.chipRow.addView(buildChip(
                labelRes = if (isActive) R.string.action_active_pill
                else R.string.manage_card_label_use,
                iconRes = R.drawable.ic_check,
                enabled = !isActive,
            ) { setActive(entry.slug) })
            if (!locked) {
                h.chipRow.addView(buildChip(
                    labelRes = R.string.manage_card_label_refine,
                    iconRes = R.drawable.ic_auto_awesome,
                ) { refineWithAi(entry) })
                h.chipRow.addView(buildChip(
                    labelRes = R.string.manage_card_label_share_short,
                    iconRes = R.drawable.ic_file_upload,
                ) { openShareSheet(ArtefactBundle.Kind.ICON_FILTER, entry.slug) })
                h.chipRow.addView(buildChip(
                    labelRes = R.string.manage_card_label_edit,
                    iconRes = R.drawable.ic_edit,
                ) { renameDialog(entry) })
                h.chipRow.addView(buildChip(
                    labelRes = R.string.manage_card_label_delete,
                    iconRes = R.drawable.ic_delete,
                    destructive = true,
                ) { confirmDelete(entry) })
            }

            h.itemView.setOnClickListener { setActive(entry.slug) }
        }
    }

    /** Build a vertical icon + label chip matching the carousel-tab style.
     *  Lives here (in the tab) rather than on [LivePreviewCard] /
     *  [TransitionPreviewCard] because the icon-filter rows are plain
     *  LinearLayouts, not those Card classes — but the visual recipe is
     *  identical. */
    private fun buildChip(
        labelRes: Int,
        iconRes: Int,
        enabled: Boolean = true,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val activeTint = if (destructive) Color.parseColor("#FF6B6B") else Color.WHITE
        val tint = if (enabled) activeTint else Color.parseColor("#66FFFFFF")
        val image = ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val label = TextView(activity).apply {
            setText(labelRes)
            textSize = 10f
            setTextColor(tint)
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
            background = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#22FFFFFF"),
            ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
            val padH = (8 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.4f
            if (enabled) setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (4 * dp).toInt(); lp.rightMargin = (4 * dp).toInt()
            layoutParams = lp
            addView(image); addView(label)
        }
    }

    private class Holder(
        view: View,
        val title: TextView,
        val subtitle: TextView,
        val preview: LinearLayout,
        val chipRow: LinearLayout,
    ) : RecyclerView.ViewHolder(view)
}
