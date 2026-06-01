/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
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
import com.iappyx.launcher.wallpaper.WallpaperLibrary

/**
 * "Wallpapers" tab — swipeable carousel of live wallpaper previews.
 *
 * Shape mirrors [ManageWidgetsTab]:
 *  - Top bar (provided by [ManageTabBase]): Title · New · Share · Import · ⚙
 *  - Optional "iappyxOS Live isn't your wallpaper yet" banner
 *  - ViewPager2 carousel of [LivePreviewCard]s (PHONE aspect)
 *  - Page indicator dots
 *
 * Per-card chip strip (under the preview): Use · Refine · Edit · Delete.
 * Bundled wallpapers show only Use; the rest don't apply to read-only
 * bundled assets (same convention as bundled widgets / transitions).
 */
class ManageWallpapersTab(
    activity: LauncherActivity,
    host: CommandPanelHost,
) : ManageTabBase(activity, host) {

    override val titleRes: Int = R.string.manage_tab_title_wallpapers
    override val generatePrefillRes: Int = R.string.manage_wallpapers_prefill_prompt
    override val kindLabel: String = "wallpaper"

    private lateinit var pager: ViewPager2
    private lateinit var emptyState: TextView
    private lateinit var indicator: LinearLayout
    private lateinit var statusBannerHolder: FrameLayout
    private val adapter = WallpaperCarouselAdapter()

    // Top-bar contextual icon row buttons. Refs kept so we can flip enabled
    // state when the user swipes the carousel.
    private lateinit var newBtn: View
    private lateinit var shareBtn: View
    private lateinit var importBtn: View

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.bgHome(activity))

        addView(makeHeaderBar(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Populate the contextual icon row provided by the base class.
        // Order: New · Share · Import. Refine / Edit / Delete live in the
        // per-card chip strip.
        newBtn = makeIconButton(
            iconRes = R.drawable.ic_auto_awesome,
            contentDescRes = R.string.manage_icon_cd_new,
            labelRes = R.string.manage_icon_label_new,
        ) { startGenerateNew() }
        shareBtn = makeIconButton(
            iconRes = R.drawable.ic_file_upload,
            contentDescRes = R.string.manage_icon_cd_share,
            labelRes = R.string.manage_icon_label_share,
        ) { currentEntry()?.let { openShareSheet(ArtefactBundle.Kind.WALLPAPER, it.id) } }
        importBtn = makeIconButton(
            iconRes = R.drawable.ic_file_download,
            contentDescRes = R.string.manage_icon_cd_import,
            labelRes = R.string.manage_icon_label_import,
        ) { openReceiveSheet() }
        actionRow.addView(newBtn)
        actionRow.addView(shareBtn)
        actionRow.addView(importBtn)

        statusBannerHolder = FrameLayout(activity)
        addView(statusBannerHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
            setText(R.string.manage_wallpapers_empty)
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
        rebuildStatusBanner()
        val all = WallpaperLibrary.all(activity)
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

    private fun currentEntry(): WallpaperLibrary.Entry? =
        adapter.itemAt(pager.currentItem)

    private fun refreshActionRow() {
        val entry = currentEntry()
        val isUserOwned = entry?.isUserGenerated == true
        // New is always available. Share is per-card and only for user-owned.
        // Import is global.
        setIconEnabled(newBtn, true)
        setIconEnabled(shareBtn, isUserOwned)
        setIconEnabled(importBtn, true)
    }

    private fun rebuildStatusBanner() {
        statusBannerHolder.removeAllViews()
        val wm = WallpaperManager.getInstance(activity)
        val info = wm.wallpaperInfo
        val active = info?.packageName == activity.packageName &&
            info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"
        if (active) return // No banner when iappyxOS Live is set — saves space.
        val banner = LinearLayout(activity).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(Palette.separatorSubtle(context))
                setStroke((1 * dp).toInt(), Palette.separatorStrong(context))
            }
            setPadding(
                (16 * dp).toInt(), (10 * dp).toInt(),
                (16 * dp).toInt(), (10 * dp).toInt(),
            )
            isClickable = true; isFocusable = true
            setOnClickListener { openSystemPicker() }
        }
        banner.addView(TextView(activity).apply {
            text = "iappyxOS Live isn't your wallpaper yet"
            setTextColor(Palette.textPrimary(context)); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        banner.addView(TextView(activity).apply {
            setText(R.string.manage_wallpapers_set_picker_hint)
            setTextColor(Palette.textSecondary(context)); textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = (16 * dp).toInt(); rightMargin = (16 * dp).toInt()
            topMargin = (8 * dp).toInt(); bottomMargin = (4 * dp).toInt()
        }
        statusBannerHolder.addView(banner, lp)
    }

    /** Bounded variant of the nested-swipe claim — same as widgets tab.
     *  Touches outside the preview surface fall through to the outer pager. */
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

    private inner class WallpaperCarouselAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<WallpaperLibrary.Entry>()

        fun replace(entries: List<WallpaperLibrary.Entry>) {
            items.clear(); items.addAll(entries)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): WallpaperLibrary.Entry? = items.getOrNull(position)

        override fun getItemCount(): Int = items.size + 1
        override fun getItemViewType(position: Int): Int =
            if (position < items.size) TYPE_PREVIEW else TYPE_CTA

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_CTA) {
                val card = EndOfListCard(activity, kindLabel = "wallpapers").apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    onGenerate = { startGenerateNew() }
                    onBrowse = {
                        activity.startActivity(android.content.Intent(
                            activity, com.iappyx.launcher.ShowcaseBrowserActivity::class.java,
                        ).putExtra(
                            com.iappyx.launcher.ShowcaseBrowserActivity.EXTRA_KIND, "wallpaper",
                        ))
                    }
                }
                CtaViewHolder(card)
            } else {
                val card = LivePreviewCard(
                    activity = activity,
                    type = LivePreviewCard.PreviewType.WALLPAPER,
                    aspect = LivePreviewCard.Aspect.PHONE,
                )
                card.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                LivePreviewViewHolder(card)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is LivePreviewViewHolder) return
            val entry = items[position]
            val activeId = LauncherPrefs(activity).activeWallpaperId
            val isActive = entry.id == activeId
            val locked = !entry.isUserGenerated
            val tags = mutableListOf<LivePreviewCard.Tag>().apply {
                if (isActive) add(LivePreviewCard.Tag("active"))
                if (locked) add(LivePreviewCard.Tag("bundled", accent = "#FFB0B0FF"))
            }
            // Per-card chip strip — icon + label form. Use is the primary
            // card-specific action; bundled wallpapers show only Use, the
            // rest of the chips are user-generated only.
            val actions = mutableListOf<LivePreviewCard.Action>().apply {
                add(LivePreviewCard.Action(
                    label = if (isActive) activity.getString(R.string.action_active_pill)
                    else activity.getString(R.string.manage_card_label_use),
                    enabled = !isActive,
                    iconRes = R.drawable.ic_check,
                ) { setActive(entry) })
                if (!locked) {
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_refine),
                        iconRes = R.drawable.ic_auto_awesome,
                    ) { refineWithAi(entry, pager.currentItem) })
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_edit),
                        iconRes = R.drawable.ic_edit,
                    ) { editMetaDialog(entry) })
                    add(LivePreviewCard.Action(
                        label = activity.getString(R.string.manage_card_label_delete),
                        enabled = !isActive,
                        iconRes = R.drawable.ic_delete,
                        destructive = true,
                    ) { confirmDelete(entry) })
                }
            }
            holder.card.bind(
                title = entry.title,
                subtitle = entry.subtitle,
                url = entry.url,
                tags = tags,
                actions = actions,
            )
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

    private fun setActive(entry: WallpaperLibrary.Entry) {
        val prefs = LauncherPrefs(activity)
        prefs.activeWallpaperId = entry.id
        val intent = Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
            .setPackage(activity.packageName)
            .putExtra("id", entry.id)
        activity.sendBroadcast(intent)
        val wm = WallpaperManager.getInstance(activity)
        val info = wm.wallpaperInfo
        val ourActive = info?.packageName == activity.packageName &&
            info.serviceName == "com.iappyx.launcher.wallpaper.IappyxWallpaperService"
        if (!ourActive) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.manage_wallpapers_set_dialog_title)
                .setMessage(R.string.manage_wallpapers_set_dialog_message)
                .setNegativeButton(R.string.action_later, null)
                .setPositiveButton(R.string.action_open_picker) { _, _ -> openSystemPicker() }
                .showThemed()
        } else {
            toast(activity.getString(R.string.manage_wallpapers_switched_toast_format, entry.title))
        }
        refresh()
    }

    private fun openSystemPicker() {
        val component = ComponentName(
            activity, "com.iappyx.launcher.wallpaper.IappyxWallpaperService",
        )
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { activity.startActivity(intent) } catch (_: Throwable) {
            try {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                toast(R.string.manage_wallpapers_no_picker_toast)
            }
        }
    }

    private fun editMetaDialog(entry: WallpaperLibrary.Entry) {
        val initialDesc = if (entry.subtitle == "AI-generated") "" else entry.subtitle
        showRenameDialog(
            currentTitle = entry.title,
            currentDescription = initialDesc,
        ) { newTitle, newDesc ->
            var changed = false
            if (newTitle.isNotEmpty() && newTitle != entry.title) {
                if (WallpaperLibrary.renameUser(activity, entry.id, newTitle)) changed = true
            }
            if (newDesc != initialDesc) {
                if (WallpaperLibrary.updatePrompt(activity, entry.id, newDesc)) changed = true
            }
            if (changed) refresh()
        }
    }

    private fun refineWithAi(entry: WallpaperLibrary.Entry, position: Int) {
        showRefineDialog(
            hint = "e.g. slow it down, swap to ocean colours, …",
        ) { instruction -> runIteration(entry, position, instruction) }
    }

    private fun runIteration(entry: WallpaperLibrary.Entry, position: Int, instruction: String) {
        val progress = showProgressDialog(
            R.string.dialog_ai_rewriting_title,
            activity.getString(R.string.refine_in_progress_message_format, instruction.take(60)),
        )
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                com.iappyx.launcher.wallpaper.WallpaperGenerator.iterate(
                    activity, entry.id, instruction,
                )
                main.post {
                    progress.dismiss()
                    toast(R.string.refine_done_toast)
                    val activeId = LauncherPrefs(activity).activeWallpaperId
                    if (entry.id == activeId) {
                        activity.sendBroadcast(
                            Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
                                .setPackage(activity.packageName)
                                .putExtra("id", entry.id),
                        )
                    }
                    val rv = pager.getChildAt(0) as? RecyclerView
                    rv?.adapter?.notifyItemChanged(position)
                }
            } catch (e: com.iappyx.launcher.wallpaper.WallpaperGenerator.GenerationException) {
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

    private fun confirmDelete(entry: WallpaperLibrary.Entry) {
        showDeleteConfirm(
            artefactTitle = entry.title,
            titleRes = R.string.manage_transitions_delete_dialog_title,
        ) {
            if (WallpaperLibrary.deleteUser(activity, entry.id)) {
                toast(R.string.manage_transitions_deleted_toast)
                refresh()
            } else {
                toast(R.string.manage_transitions_delete_failed_toast)
            }
        }
    }

    // ── Receive (called by base when ⊕ → Receive → From file fires) ─

    /** Accepts either a `.iappyx-wallpaper` bundle (zip with bundle.json
     *  + wallpaper.html + meta.json) OR a raw `.html` file. */
    override fun onReceiveFromFile(uri: android.net.Uri) {
        val bytes: ByteArray = try {
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

        // Try bundle first (zip files start with PK\x03\x04).
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            try {
                val bundle = ArtefactBundle.readBundle(bytes)
                if (bundle.kind != ArtefactBundle.Kind.WALLPAPER) {
                    toast("That bundle is a ${bundle.kind.label}, not a wallpaper",
                        android.widget.Toast.LENGTH_LONG)
                    return
                }
                ArtefactBundle.install(activity, bundle)
                toast(R.string.manage_imported_toast)
                refresh()
                return
            } catch (_: Throwable) { /* fall through to HTML path */ }
        }

        // Plain HTML fallback.
        val html = bytes.toString(Charsets.UTF_8)
        if (!html.trimStart().startsWith("<")) {
            toast(R.string.manage_not_a_wallpaper, android.widget.Toast.LENGTH_LONG)
            return
        }
        val newId = java.util.UUID.randomUUID().toString()
        val dir = WallpaperLibrary.userDir(activity)
        try {
            java.io.File(dir, "$newId.html").writeText(html, Charsets.UTF_8)
            val title = com.iappyx.launcher.wallpaper.WallpaperGenerator.extractHtmlTitle(html)
                ?: com.iappyx.launcher.wallpaper.WallpaperGenerator.smartTitle("Imported wallpaper")
            val meta = org.json.JSONObject().apply {
                put("title", title)
                put("prompt", "Imported from file")
                put("createdAt", System.currentTimeMillis())
            }
            java.io.File(dir, "$newId.json").writeText(meta.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            java.io.File(dir, "$newId.html").delete()
            java.io.File(dir, "$newId.json").delete()
            toast(activity.getString(R.string.manage_save_failed_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG)
            return
        }
        toast(R.string.manage_imported_toast)
        refresh()
    }
}
