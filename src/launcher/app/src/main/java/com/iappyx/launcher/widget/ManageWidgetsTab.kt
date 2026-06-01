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
import com.iappyx.launcher.R
import com.iappyx.launcher.sharing.ArtefactBundle

/**
 * "Widgets" tab — a swipeable carousel of live widget previews. Each page
 * runs the widget's HTML in its own WebView with the full bridge surface,
 * so what you see in the carousel matches what you'll see on the home grid.
 *
 * Layout, top to bottom:
 *  - Top bar: Title · ⊕ (Generate / Receive) · ⚙ Settings  (provided by [ManageTabBase])
 *  - ViewPager2 carousel of [LivePreviewCard]s
 *  - Page indicator dots
 *
 * Per-card actions (rendered inside each [LivePreviewCard.Action] row):
 *  Place on home · Refine · Share · Edit name & description · Bundle files · Delete
 * The first three sit at the top of the action list because they're the
 * most commonly-tapped; rename / files / delete follow.
 */
class ManageWidgetsTab(
    activity: LauncherActivity,
    host: CommandPanelHost,
) : ManageTabBase(activity, host) {

    override val titleRes: Int = R.string.manage_tab_title_widgets
    override val generatePrefillRes: Int = R.string.manage_widgets_prefill_prompt
    override val kindLabel: String = "widget"

    // ViewPager + supporting views are lateinit because the contextual
    // icon row in the top bar (built earlier in init) captures `pager`
    // via lambda closures. Kotlin's strict definite-assignment check
    // refuses to read `pager.currentItem` from those closures unless the
    // declaration site allows it. Lateinit lets the closures compile;
    // their lambdas run after init completes anyway.
    private lateinit var pager: ViewPager2
    private lateinit var emptyState: TextView
    private lateinit var indicator: LinearLayout
    private val adapter = WidgetCarouselAdapter()

    // Top-bar contextual icon row buttons. Refs kept so we can flip enabled
    // state when the user swipes the carousel.
    //
    // The top bar holds CROSS-CARD actions only (AI menu, Share, Import).
    // Strictly per-card actions (Edit, Files, Place, Delete) live in the
    // chip strip BELOW the preview where their tie to the visible card is
    // unambiguous.
    private lateinit var aiBtn: View
    private lateinit var shareBtn: View
    private lateinit var importBtn: View

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.bgHome(activity))

        addView(makeHeaderBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Populate the top-bar contextual icon row. Order: New · Share · Import.
        // Each icon has a small text label underneath so the affordance is
        // unmissable. Refine / Edit / Files / Place / Delete are intentionally
        // NOT here — they live in the per-card chip strip below the preview
        // because their target is unambiguously "this card".
        aiBtn = makeIconButton(
            iconRes = R.drawable.ic_auto_awesome,
            contentDescRes = R.string.manage_icon_cd_new,
            labelRes = R.string.manage_icon_label_new,
        ) { startGenerateNew() }
        shareBtn = makeIconButton(
            iconRes = R.drawable.ic_file_upload,
            contentDescRes = R.string.manage_icon_cd_share,
            labelRes = R.string.manage_icon_label_share,
        ) { currentEntry()?.let { openShareSheet(ArtefactBundle.Kind.WIDGET, it.id) } }
        importBtn = makeIconButton(
            iconRes = R.drawable.ic_file_download,
            contentDescRes = R.string.manage_icon_cd_import,
            labelRes = R.string.manage_icon_label_import,
        ) { openReceiveSheet() }
        actionRow.addView(aiBtn)
        actionRow.addView(shareBtn)
        actionRow.addView(importBtn)

        pager = ViewPager2(activity).apply {
            offscreenPageLimit = 1
            clipToPadding = false
            setPageTransformer { page, position ->
                // Subtle scale on adjacent pages so the active one pops.
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
        // Wrap the pager in a frame so we can overlay chevron buttons. The
        // frame fills the available space in the LinearLayout; the pager
        // fills the frame; the chevrons sit on top, anchored start / end.
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
            setText(R.string.manage_widgets_empty)
            setTextColor(Palette.textDisabled(context))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                (32 * dp).toInt(), (32 * dp).toInt(),
                (32 * dp).toInt(), (32 * dp).toInt(),
            )
            visibility = View.GONE
        }
        addView(emptyState, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun refresh() {
        val entries = WidgetLibrary.all(activity)
        adapter.replace(entries)
        if (entries.isEmpty()) {
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

    /** Currently-visible widget entry, or null if the carousel is empty or
     *  parked on the trailing CTA card. Called by every action handler in
     *  the icon row to know what to operate on. */
    private fun currentEntry(): WidgetLibrary.Entry? =
        adapter.itemAt(pager.currentItem)

    /** Refresh the top-bar icon row's enabled/disabled states based on
     *  the active card. Called from [refresh] and the carousel's
     *  onPageSelected callback so dimming tracks the user's swipes. The
     *  per-card actions (Edit / Files / Place / Delete) update through
     *  the adapter's bind() instead — they live in the chip strip below
     *  the preview, not here. */
    private fun refreshActionRow() {
        val entry = currentEntry()
        val isUserOwned = entry?.isUserGenerated == true
        // AI button stays enabled even when the carousel is empty — Generate
        // doesn't need an active card. Refine inside the menu greys out via
        // showAiMenu's refineEnabled param.
        setIconEnabled(aiBtn, true)
        // Share: only for user-owned entries (bundled is read-only).
        setIconEnabled(shareBtn, isUserOwned)
        // Import: always enabled — it's a global action.
        setIconEnabled(importBtn, true)
    }

    /** Bounded variant of the nested-swipe claim: only claim horizontal
     *  gestures that start INSIDE the visible card's preview surface. Touches
     *  outside the preview (header, action chips, page dots, status banner)
     *  fall through to the outer launcher pager — that's how the user gets
     *  back to the home grid. The chevron buttons handle navigation when the
     *  user wants to stay inside the carousel. */
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
        // Trailing CTA card: claim the entire card so the user can swipe
        // back to the previous entry from inside it. Without this, the
        // outer launcher pager intercepts horizontal swipes and the CTA
        // becomes a one-way trip.
        if (child is EndOfListCard) return true
        val card = child as? LivePreviewCard ?: return false
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

    /** Walk the inner RecyclerView's children, calling onPageVisible for the
     *  selected one and onPageHidden for the rest. Lives on the outer class
     *  to dodge a forward-reference issue with the inner adapter class. */
    private fun deliverPageVisibility(position: Int) {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.findContainingViewHolder(child) as? LivePreviewViewHolder ?: continue
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

    private inner class WidgetCarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<WidgetLibrary.Entry>()

        fun replace(entries: List<WidgetLibrary.Entry>) {
            items.clear(); items.addAll(entries)
            notifyDataSetChanged()
        }

        /** Lookup helper — returns null for the trailing CTA card slot
         *  (position == items.size) and any out-of-bounds query. The
         *  contextual icon row uses this to determine "is there an active
         *  widget at this carousel position?". */
        fun itemAt(position: Int): WidgetLibrary.Entry? = items.getOrNull(position)

        // +1 trailing card with Generate / Browse Showcase CTAs — the
        // user lands here by simply swiping past the last widget.
        override fun getItemCount(): Int = items.size + 1
        override fun getItemViewType(position: Int): Int =
            if (position < items.size) TYPE_PREVIEW else TYPE_CTA

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_CTA) {
                val card = EndOfListCard(activity, kindLabel = "widgets").apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    onGenerate = { host.switchToAiWithPrefill(context.getString(generatePrefillRes)) }
                    onBrowse = {
                        activity.startActivity(android.content.Intent(
                            activity, com.iappyx.launcher.ShowcaseBrowserActivity::class.java,
                        ).putExtra(
                            com.iappyx.launcher.ShowcaseBrowserActivity.EXTRA_KIND, "widget",
                        ))
                    }
                }
                CtaViewHolder(card)
            } else {
                val card = LivePreviewCard(
                    activity = activity,
                    type = LivePreviewCard.PreviewType.WIDGET,
                    aspect = LivePreviewCard.Aspect.SQUARE,
                )
                card.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                LivePreviewViewHolder(card)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is LivePreviewViewHolder) return // CTA: nothing to bind
            val entry = items[position]
            val locked = !entry.isUserGenerated
            val tags = mutableListOf<LivePreviewCard.Tag>().apply {
                if (entry.isInUse) add(LivePreviewCard.Tag("in use"))
                if (locked) add(LivePreviewCard.Tag("bundled", accent = "#FFB0B0FF"))
                if (entry.userLocked) add(LivePreviewCard.Tag("locked", accent = "#FFFFB199"))
            }
            // Per-card chip strip below the preview — icon + label form.
            // Holds every action whose target is unambiguously THIS card:
            // Place / Refine / Edit / Files / Delete. The cross-card ones
            // (New / Share / Import) live in the top bar. Bundled widgets
            // show only Place — the rest don't apply to read-only bundled
            // assets (same convention as bundled wallpapers / transitions).
            val actions = mutableListOf<LivePreviewCard.Action>().apply {
                add(LivePreviewCard.Action(
                    label = activity.getString(R.string.manage_card_label_place),
                    enabled = !entry.isInUse,
                    iconRes = R.drawable.ic_grid_view,
                ) { placeOnHome(entry) })
                if (!locked) {
                    // Refine: greyed out when the user has locked the widget —
                    // a one-line hint that the lock is real, plus the dedicated
                    // lock toggle below makes flipping it obvious.
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_refine),
                        enabled = !entry.userLocked,
                        iconRes = R.drawable.ic_auto_awesome,
                    ) { refineWithAi(entry, pager.currentItem) })
                    add(LivePreviewCard.Action(
                        label = if (entry.userLocked) "Unlock" else "Lock",
                        iconRes = if (entry.userLocked) R.drawable.ic_lock else R.drawable.ic_lock_open,
                    ) { toggleUserLock(entry) })
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_edit),
                        iconRes = R.drawable.ic_edit,
                    ) { editMetaDialog(entry) })
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_files),
                        iconRes = R.drawable.ic_folder,
                    ) { BundleFilesSheet.show(activity, entry.id, entry.title) })
                    add(LivePreviewCard.Action(
                        label = if (entry.isInUse) activity.getString(R.string.action_in_use)
                        else activity.getString(R.string.manage_card_label_delete),
                        enabled = !entry.isInUse,
                        iconRes = R.drawable.ic_delete,
                        destructive = true,
                    ) { confirmDelete(entry) })
                }
            }
            // Bundled widgets render from `assets/widgets/{slug}.html`; user
            // widgets from their per-id sandbox. Pick the right URL.
            val url = if (locked && entry.assetPath != null) {
                "file:///android_asset/${entry.assetPath}"
            } else {
                "file://${java.io.File(activity.filesDir, "widgets/${entry.id}/widget.html").absolutePath}"
            }
            holder.card.bind(
                title = entry.title,
                subtitle = entry.subtitle,
                url = url,
                tags = tags,
                actions = actions,
            )
            // Pause if not the currently visible page.
            if (position == pager.currentItem) holder.card.onPageVisible()
            else holder.card.onPageHidden()
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is LivePreviewViewHolder) holder.card.unbind()
        }
    }

    private class CtaViewHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TYPE_PREVIEW = 0
        private const val TYPE_CTA = 1
    }

    // ── Per-card actions ────────────────────────────────────────

    /** Flip the user lock on a widget — refuses for bundled. After flipping
     *  we [refresh] so the card's tag + the Refine button's enabled state
     *  re-render. */
    private fun toggleUserLock(entry: WidgetLibrary.Entry) {
        val newState = !entry.userLocked
        if (WidgetLibrary.setUserLocked(activity, entry.id, newState)) {
            refresh()
            toast(if (newState) "Locked — AI won't change this widget."
                  else "Unlocked — AI can refine this widget again.")
        }
    }

    private fun placeOnHome(entry: WidgetLibrary.Entry) {
        val ok = activity.placeExistingWidget(entry.id, wSpan = 2, hSpan = 2)
        if (ok) {
            toast(R.string.manage_widgets_placed_toast)
            refresh()
        } else {
            toast(activity.getString(R.string.manage_widgets_no_space_toast),
                android.widget.Toast.LENGTH_LONG)
        }
    }

    /** Combined Rename + Edit-description — replaces the two individual
     *  per-card actions with a single dialog containing both fields, since
     *  users almost always tweak both at once after a generation. */
    private fun editMetaDialog(entry: WidgetLibrary.Entry) {
        val initialDesc = entry.subtitle.takeIf { it != "AI-generated" } ?: ""
        showRenameDialog(
            currentTitle = entry.title,
            currentDescription = initialDesc,
        ) { newTitle, newDesc ->
            var changed = false
            if (newTitle.isNotEmpty() && newTitle != entry.title) {
                if (WidgetLibrary.rename(activity, entry.id, newTitle)) changed = true
            }
            if (newDesc != initialDesc) {
                if (WidgetLibrary.updatePrompt(activity, entry.id, newDesc)) changed = true
            }
            if (changed) refresh()
        }
    }

    private fun refineWithAi(entry: WidgetLibrary.Entry, position: Int) {
        showRefineDialog(
            hint = "e.g. add a unit toggle, make the font bigger, …",
        ) { instruction -> runIteration(entry, position, instruction) }
    }

    private fun runIteration(entry: WidgetLibrary.Entry, position: Int, instruction: String) {
        val progress = showProgressDialog(
            R.string.dialog_ai_rewriting_title,
            activity.getString(R.string.refine_in_progress_message_format, instruction.take(60)),
        )
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                WidgetGenerator.iterate(activity, entry.id, instruction)
                main.post {
                    progress.dismiss()
                    toast(R.string.refine_done_toast)
                    // notifyItemChanged forces the adapter to rebind the
                    // visible card, which unbind+bind the WebView and pulls
                    // in the new HTML.
                    val rv = pager.getChildAt(0) as? RecyclerView
                    rv?.adapter?.notifyItemChanged(position)
                }
            } catch (e: WidgetGenerator.GenerationException) {
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

    private fun confirmDelete(entry: WidgetLibrary.Entry) {
        showDeleteConfirm(
            artefactTitle = entry.title,
            titleRes = R.string.manage_widgets_delete_dialog_title,
        ) {
            if (WidgetLibrary.delete(activity, entry.id)) {
                toast(R.string.manage_transitions_deleted_toast)
                refresh()
            } else {
                toast(R.string.manage_transitions_delete_failed_toast)
            }
        }
    }

    // ── Receive (called by base when ⊕ → Receive → From file fires) ─

    /** Accepts either a `.iappyx-widget` bundle (zip with bundle.json) OR
     *  a legacy zip with just widget.html + meta.json — bundles take
     *  precedence, the legacy path stays for backwards compat with files
     *  exported by older versions. */
    override fun onReceiveFromFile(uri: android.net.Uri) {
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run {
                    toast(R.string.manage_open_failed_toast, android.widget.Toast.LENGTH_LONG)
                    return
                }
        } catch (e: Throwable) {
            toast(activity.getString(R.string.manage_read_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG)
            return
        }

        // Bundle path — uses the canonical kind discriminator.
        try {
            val bundle = ArtefactBundle.readBundle(bytes)
            if (bundle.kind != ArtefactBundle.Kind.WIDGET) {
                toast(activity.getString(R.string.manage_icons_wrong_kind_format, bundle.kind.label),
                    android.widget.Toast.LENGTH_LONG)
                return
            }
            ArtefactBundle.install(activity, bundle)
            toast(R.string.manage_imported_toast)
            refresh()
            return
        } catch (_: Throwable) { /* fall through to legacy zip path */ }

        // Legacy: widget.html + meta.json with no bundle.json discriminator.
        val newId = java.util.UUID.randomUUID().toString()
        val dir = java.io.File(activity.filesDir, "widgets/$newId").also { it.mkdirs() }
        var html: ByteArray? = null
        var meta: ByteArray? = null
        try {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name.substringAfterLast('/')) {
                        "widget.html" -> html = zip.readBytes()
                        "meta.json" -> meta = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            toast(activity.getString(R.string.manage_read_zip_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG)
            return
        }
        val htmlBytes = html
        if (htmlBytes == null) {
            dir.deleteRecursively()
            toast(R.string.manage_zip_no_widget_html, android.widget.Toast.LENGTH_LONG)
            return
        }
        java.io.File(dir, "widget.html").writeBytes(htmlBytes)
        val htmlString = String(htmlBytes, Charsets.UTF_8)
        val originalPrompt = meta?.let { metaBytes ->
            runCatching {
                org.json.JSONObject(String(metaBytes, Charsets.UTF_8)).optString("prompt")
            }.getOrNull()
        } ?: ""
        WidgetLibrary.writeMeta(activity, newId, prompt = originalPrompt, html = htmlString)
        toast(R.string.manage_imported_toast)
        refresh()
    }
}
