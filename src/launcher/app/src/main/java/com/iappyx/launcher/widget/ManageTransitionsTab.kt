/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.R
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.transitions.TransitionGenerator
import com.iappyx.launcher.transitions.TransitionLibrary

/**
 * "Transitions" manage tab — same carousel shape as widgets / wallpapers
 * but populated with transition specs and a fake-grid live preview.
 *
 * Layout, top to bottom:
 *  - Top bar (provided by [ManageTabBase]): Title · New · Share · Import · ⚙
 *  - Carousel of [TransitionPreviewCard] running the spec on a RAF loop
 *  - Page indicator dots
 *
 * Per-card chip strip: Use · Refine · Edit · Delete.
 * Bundled transitions show only Use; the rest don't apply to read-only
 * bundled assets.
 */
class ManageTransitionsTab(
    activity: LauncherActivity,
    host: CommandPanelHost,
) : ManageTabBase(activity, host) {

    override val titleRes: Int = R.string.manage_tab_title_transitions
    override val generatePrefillRes: Int = R.string.manage_transitions_prefill_prompt
    override val kindLabel: String = "transition"

    private lateinit var pager: ViewPager2
    private lateinit var emptyState: TextView
    private lateinit var indicator: LinearLayout
    private val adapter = TransitionCarouselAdapter()

    private lateinit var newBtn: View
    private lateinit var shareBtn: View
    private lateinit var importBtn: View

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.bgHome(activity))

        addView(makeHeaderBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        newBtn = makeIconButton(
            iconRes = R.drawable.ic_auto_awesome,
            contentDescRes = R.string.manage_icon_cd_new,
            labelRes = R.string.manage_icon_label_new,
        ) { startGenerateNew() }
        shareBtn = makeIconButton(
            iconRes = R.drawable.ic_file_upload,
            contentDescRes = R.string.manage_icon_cd_share,
            labelRes = R.string.manage_icon_label_share,
        ) { currentEntry()?.let { openShareSheet(ArtefactBundle.Kind.TRANSITION, it.id) } }
        importBtn = makeIconButton(
            iconRes = R.drawable.ic_file_download,
            contentDescRes = R.string.manage_icon_cd_import,
            labelRes = R.string.manage_icon_label_import,
        ) { openReceiveSheet() }
        actionRow.addView(newBtn)
        actionRow.addView(shareBtn)
        actionRow.addView(importBtn)

        pager = ViewPager2(activity).apply {
            offscreenPageLimit = 1
            clipToPadding = false
            setPageTransformer { page, position ->
                val scale = 1f - kotlin.math.min(1f, kotlin.math.abs(position)) * 0.04f
                page.scaleX = scale; page.scaleY = scale
            }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    refreshIndicator(position)
                    deliverPageVisibility(position)
                    refreshActionRow()
                }
            })
        }
        pager.adapter = adapter

        val carouselFrame = FrameLayout(activity).apply {
            addView(pager, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(makeChevron(forward = false), chevronLp(start = true))
            addView(makeChevron(forward = true), chevronLp(start = false))
        }
        addView(carouselFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        lockNestedSwipe(pager)

        indicator = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        emptyState = TextView(activity).apply {
            setText(R.string.manage_transitions_empty)
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
        val all = TransitionLibrary.all(activity)
        adapter.replace(all)
        if (all.isEmpty()) {
            pager.visibility = View.GONE
            indicator.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            pager.visibility = View.VISIBLE
            indicator.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            refreshIndicator(pager.currentItem)
        }
        refreshActionRow()
    }

    private fun currentEntry(): TransitionLibrary.Entry? =
        adapter.itemAt(pager.currentItem)

    private fun refreshActionRow() {
        val entry = currentEntry()
        val isUserOwned = entry?.isUserGenerated == true
        setIconEnabled(newBtn, true)
        setIconEnabled(shareBtn, isUserOwned)
        setIconEnabled(importBtn, true)
    }

    private fun lockNestedSwipe(inner: ViewPager2) {
        val rv = inner.getChildAt(0) as? RecyclerView ?: return
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(r: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    if (touchOnVisiblePreview(r, e.x, e.y)) {
                        r.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return false
            }
            override fun onTouchEvent(r: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }

    private fun touchOnVisiblePreview(rv: RecyclerView, x: Float, y: Float): Boolean {
        val child = rv.findChildViewUnder(x, y) ?: return false
        if (child is EndOfListCard) return true
        val card = child as? TransitionPreviewCard ?: return false
        val rvLoc = IntArray(2); rv.getLocationOnScreen(rvLoc)
        val psLoc = IntArray(2); card.previewSurface.getLocationOnScreen(psLoc)
        val left = (psLoc[0] - rvLoc[0]).toFloat()
        val top = (psLoc[1] - rvLoc[1]).toFloat()
        val right = left + card.previewSurface.width
        val bottom = top + card.previewSurface.height
        return x in left..right && y in top..bottom
    }

    private fun makeChevron(forward: Boolean): View = android.widget.ImageView(activity).apply {
        setImageResource(if (forward) R.drawable.ic_chevron_right else R.drawable.ic_chevron_left)
        imageTintList = android.content.res.ColorStateList.valueOf(
            Palette.overlayWhiteStrong(context),
        )
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val pad = (10 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true; isFocusable = true
        background = android.content.res.ColorStateList.valueOf(
            Palette.separator(context),
        ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
        setOnClickListener {
            val cur = pager.currentItem
            val next = if (forward) cur + 1 else cur - 1
            if (next in 0 until adapter.itemCount) {
                pager.setCurrentItem(next, true)
            }
        }
    }

    private fun chevronLp(start: Boolean): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or
                if (start) Gravity.START else Gravity.END
            leftMargin = if (start) (4 * dp).toInt() else 0
            rightMargin = if (start) 0 else (4 * dp).toInt()
        }
    }

    private fun deliverPageVisibility(position: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.findContainingViewHolder(child) as? TransitionPreviewViewHolder ?: continue
            if (holder.bindingAdapterPosition == position) holder.card.onPageVisible()
            else holder.card.onPageHidden()
        }
    }

    private fun refreshIndicator(currentPosition: Int) {
        indicator.removeAllViews()
        val count = adapter.itemCount
        if (count <= 1) return
        for (i in 0 until count) {
            indicator.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == currentPosition) Palette.textPrimary(context)
                    else Palette.separatorStrong(context))
                }
                val lp = LinearLayout.LayoutParams((6 * dp).toInt(), (6 * dp).toInt())
                lp.leftMargin = (3 * dp).toInt(); lp.rightMargin = (3 * dp).toInt()
                layoutParams = lp
            })
        }
    }

    // ── Carousel adapter ────────────────────────────────────────

    private inner class TransitionCarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<TransitionLibrary.Entry>()

        fun replace(entries: List<TransitionLibrary.Entry>) {
            items.clear(); items.addAll(entries)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): TransitionLibrary.Entry? = items.getOrNull(position)

        override fun getItemCount(): Int = items.size + 1
        override fun getItemViewType(position: Int): Int =
            if (position < items.size) TYPE_PREVIEW else TYPE_CTA

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_CTA) {
                val card = EndOfListCard(activity, kindLabel = "transitions").apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    onGenerate = { startGenerateNew() }
                    onBrowse = {
                        activity.startActivity(android.content.Intent(
                            activity, com.iappyx.launcher.ShowcaseBrowserActivity::class.java,
                        ).putExtra(
                            com.iappyx.launcher.ShowcaseBrowserActivity.EXTRA_KIND, "transition",
                        ))
                    }
                }
                CtaViewHolder(card)
            } else {
                val card = TransitionPreviewCard(activity)
                card.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                TransitionPreviewViewHolder(card)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is TransitionPreviewViewHolder) return
            val entry = items[position]
            val activeId = LauncherPrefs(activity).pageTransitionStyle
            val isActive = entry.id == activeId
            val locked = !entry.isUserGenerated
            val tags = mutableListOf<TransitionPreviewCard.Tag>().apply {
                if (isActive) add(TransitionPreviewCard.Tag("active"))
                if (locked) add(TransitionPreviewCard.Tag("bundled", accent = "#FFB0B0FF"))
            }
            // Per-card chip strip — icon + label form. Use is the primary
            // card-specific action; bundled transitions show only Use.
            val actions = mutableListOf<TransitionPreviewCard.Action>().apply {
                add(TransitionPreviewCard.Action(
                    label = if (isActive) activity.getString(R.string.action_active_pill)
                    else activity.getString(R.string.manage_card_label_use),
                    enabled = !isActive,
                    iconRes = R.drawable.ic_check,
                ) { setActive(entry) })
                if (!locked) {
                    add(TransitionPreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_refine),
                        iconRes = R.drawable.ic_auto_awesome,
                    ) { refineWithAi(entry, pager.currentItem) })
                    add(TransitionPreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_edit),
                        iconRes = R.drawable.ic_edit,
                    ) { editMetaDialog(entry) })
                    add(TransitionPreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_delete),
                        enabled = !isActive,
                        iconRes = R.drawable.ic_delete,
                        destructive = true,
                    ) { confirmDelete(entry) })
                }
            }
            val spec = TransitionLibrary.specFor(activity, entry.id)
            holder.card.bind(
                title = entry.title,
                subtitle = entry.subtitle,
                spec = spec,
                tags = tags,
                actions = actions,
            )
            if (position == pager.currentItem) holder.card.onPageVisible()
            else holder.card.onPageHidden()
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is TransitionPreviewViewHolder) holder.card.unbind()
        }
    }

    private class CtaViewHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TYPE_PREVIEW = 0
        private const val TYPE_CTA = 1
    }

    // ── Per-card actions ────────────────────────────────────────

    private fun setActive(entry: TransitionLibrary.Entry) {
        LauncherPrefs(activity).pageTransitionStyle = entry.id
        toast(activity.getString(R.string.manage_transitions_switched_toast_format, entry.title))
        refresh()
    }

    private fun editMetaDialog(entry: TransitionLibrary.Entry) {
        val initialDesc = if (entry.subtitle == "AI-generated") "" else entry.subtitle
        showRenameDialog(
            currentTitle = entry.title,
            currentDescription = initialDesc,
        ) { newTitle, newDesc ->
            var changed = false
            if (newTitle.isNotEmpty() && newTitle != entry.title) {
                if (TransitionLibrary.renameUser(activity, entry.id, newTitle)) changed = true
            }
            if (newDesc != initialDesc) {
                if (TransitionLibrary.updatePrompt(activity, entry.id, newDesc)) changed = true
            }
            if (changed) refresh()
        }
    }

    private fun refineWithAi(entry: TransitionLibrary.Entry, position: Int) {
        showRefineDialog(
            hint = "e.g. make it slower, only animate odd columns, …",
        ) { instruction -> runIteration(entry, position, instruction) }
    }

    private fun runIteration(entry: TransitionLibrary.Entry, position: Int, instruction: String) {
        val progress = showProgressDialog(
            R.string.dialog_ai_rewriting_title,
            activity.getString(R.string.refine_in_progress_message_format, instruction.take(60)),
        )
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                TransitionGenerator.iterate(activity, entry.id, instruction)
                TransitionLibrary.invalidate(entry.id)
                main.post {
                    progress.dismiss()
                    toast(R.string.refine_done_toast)
                    refresh()
                }
            } catch (e: TransitionGenerator.GenerationException) {
                main.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.refine_failed_dialog_title)
                        .setMessage(e.message)
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            } catch (e: Throwable) {
                main.post {
                    progress.dismiss()
                    toast(activity.getString(R.string.unexpected_error_toast_format, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    private fun confirmDelete(entry: TransitionLibrary.Entry) {
        showDeleteConfirm(
            artefactTitle = entry.title,
            titleRes = R.string.manage_transitions_delete_dialog_title,
        ) {
            if (TransitionLibrary.deleteUser(activity, entry.id)) {
                TransitionLibrary.invalidate(entry.id)
                toast(R.string.manage_transitions_deleted_toast)
                refresh()
            } else {
                toast(R.string.manage_transitions_delete_failed_toast)
            }
        }
    }

    // ── Receive ─────────────────────────────────────────────

    /** Accepts either a `.iappyx-transition` bundle (zip with bundle.json
     *  + spec.json + meta.json) OR a raw transition spec `.json` file. */
    override fun onReceiveFromFile(uri: android.net.Uri) {
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run {
                    toast(R.string.manage_transitions_open_failed_toast, android.widget.Toast.LENGTH_LONG)
                    return
                }
        } catch (e: Throwable) {
            toast(activity.getString(R.string.manage_read_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG)
            return
        }

        // Bundle path — zip starts with PK\x03\x04.
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            try {
                val bundle = ArtefactBundle.readBundle(bytes)
                if (bundle.kind != ArtefactBundle.Kind.TRANSITION) {
                    toast("That bundle is a ${bundle.kind.label}, not a transition",
                        android.widget.Toast.LENGTH_LONG)
                    return
                }
                ArtefactBundle.install(activity, bundle)
                toast(R.string.manage_imported_toast)
                refresh()
                return
            } catch (_: Throwable) { /* fall through to spec-json path */ }
        }

        // Raw transition spec JSON.
        val raw = bytes.toString(Charsets.UTF_8)
        val spec = com.iappyx.launcher.transitions.TransitionSpec.parse(raw)
        if (spec == null) {
            toast(R.string.manage_not_a_transition, android.widget.Toast.LENGTH_LONG)
            return
        }
        val newId = java.util.UUID.randomUUID().toString()
        val dir = TransitionLibrary.userDir(activity)
        try {
            java.io.File(dir, "$newId.json").writeText(raw, Charsets.UTF_8)
            TransitionLibrary.writeMeta(activity, newId, "Imported from file")
        } catch (e: Throwable) {
            java.io.File(dir, "$newId.json").delete()
            java.io.File(dir, "$newId.meta.json").delete()
            toast(activity.getString(R.string.manage_save_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG)
            return
        }
        toast(R.string.manage_imported_toast)
        refresh()
    }
}
