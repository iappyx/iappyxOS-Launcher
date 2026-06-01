/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.clippings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.R
import com.iappyx.launcher.cells.GeneratedWidgetCell
import com.iappyx.launcher.model.Clipping
import com.iappyx.launcher.widget.WidgetLibrary
import com.iappyx.launcher.widget.showThemed
import org.json.JSONObject
import java.io.File

/**
 * The rightmost page in the home pager — the Clippings inbox. Shows the
 * share-to-launcher captures (videos, music, articles, images, notes) as a
 * vertical, full-width card list with a kind-filter chip row at the top.
 *
 * Newest first. Empty state when no clippings exist. Cards are hosted by
 * [GeneratedWidgetCell] (re-using the same WidgetHost bridge wiring that
 * regular generated widgets use) so the editable Note card's
 * `iappyx.storage.saveFile` keeps working.
 */
class ClippingsPageView(
    context: Context,
    private val activity: LauncherActivity,
) : LinearLayout(context) {

    /** Kind filter chip row — null = "All", otherwise lowercase kind name. */
    private var activeFilter: String? = null

    /** A FrameLayout that owns its own tap-classification logic, fed via
     *  [dispatchTouchEvent] so it sees every event regardless of which
     *  child claims them.
     *
     *  Decision happens at ACTION_UP, NOT mid-press. That's the key
     *  difference from [GestureDetector], whose `onLongPress` fires while
     *  the finger is still down — so a user with a slow first tap (held
     *  ~500ms by accident) saw long-press fire BEFORE they could complete
     *  their second tap, even when they were trying to double-tap.
     *
     *  On UP we look at:
     *    - hold duration of THIS press
     *    - time since previous UP
     *  and pick double-tap / long-press / single-tap accordingly. */
    class GestureCardFrame(context: Context) : FrameLayout(context) {
        var onLongPress: (() -> Unit)? = null
        var onDoubleTap: (() -> Unit)? = null

        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val longPressMs = 500L
        private val doubleTapMs = 320L

        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var lastUpTime = 0L

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downTime = ev.eventTime
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    // Second finger landed mid-gesture (pinch / two-finger
                    // tap). Disqualify the rest of this stream from
                    // becoming a single-tap or long-press — neither
                    // gesture should fire on multi-touch input.
                    moved = true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!moved) {
                        val dx = kotlin.math.abs(ev.rawX - downX)
                        val dy = kotlin.math.abs(ev.rawY - downY)
                        if (dx > touchSlop || dy > touchSlop) moved = true
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val now = ev.eventTime
                    val held = now - downTime
                    if (!moved) {
                        // Decide IN ORDER: double-tap > long-press > single-tap
                        // (which we don't act on). A double-tap requires a
                        // recent prior UP within doubleTapMs — that wins even
                        // if the second tap was held briefly. A held release
                        // with NO recent prior tap is a long-press → menu.
                        val recentTap = now - lastUpTime <= doubleTapMs
                        when {
                            recentTap -> {
                                lastUpTime = 0L  // consume both taps
                                onDoubleTap?.invoke()
                            }
                            held >= longPressMs -> {
                                lastUpTime = 0L
                                onLongPress?.invoke()
                            }
                            else -> {
                                lastUpTime = now
                            }
                        }
                    } else {
                        lastUpTime = 0L
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // Don't reset lastUpTime — a CANCEL after a clean tap
                    // shouldn't lose the double-tap window for the next tap.
                    moved = false
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private val emptyView: TextView
    private val listView: RecyclerView

    /** TTL captions read System.currentTimeMillis() at bind time, so a
     *  caption like "Expires in 3h" goes stale after the user sits on
     *  the page for ~30 minutes. Tick once a minute and ask the visible
     *  holders to re-render their caption — cheap, no reflow. Cancelled
     *  in onDetachedFromWindow. */
    private val ttlTickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ttlTickRunnable = object : Runnable {
        override fun run() {
            updateVisibleTtlCaptions()
            ttlTickHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ttlTickHandler.removeCallbacks(ttlTickRunnable)
        ttlTickHandler.postDelayed(ttlTickRunnable, 60_000L)
    }

    /** Re-bind ONLY the TTL caption text + colour on every visible card.
     *  Avoids a full notifyDataSetChanged that would tear down WebViews. */
    private fun updateVisibleTtlCaptions() {
        for (i in 0 until listView.childCount) {
            val view = listView.getChildAt(i)
            val holder = listView.getChildViewHolder(view) as? ClippingsAdapter.VH ?: continue
            val pos = holder.bindingAdapterPosition
            val row = adapter.rowAt(pos) ?: continue
            holder.ttlCaption.text = formatTtlCaption(row.meta)
            holder.ttlCaption.setTextColor(ttlCaptionColor(row.meta))
        }
    }
    private val chipRow: LinearLayout
    private val adapter = ClippingsAdapter()

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val dp = resources.displayMetrics.density
        // Keep clear of the dock at the bottom (118dp = same clearance every
        // home page wrapper uses) and reserve top room for the system status
        // bar (ish — 24dp visual padding).
        setPadding(0, (24 * dp).toInt(), 0, (118 * dp).toInt())
        clipToPadding = false

        // Header
        val title = TextView(context).apply {
            text = "Clippings"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 22f
            typeface = android.graphics.Typeface.create(
                com.iappyx.launcher.widget.Palette.themeTypeface(context) ?: android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD,
            )
            setPadding((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        addView(title)

        // Filter chip row (horizontal scroll for safety on narrow screens).
        chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (12 * dp).toInt())
        }
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        addView(chipScroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        rebuildChips()

        // Empty state
        emptyView = TextView(context).apply {
            text = context.getString(com.iappyx.launcher.R.string.clippings_empty_initial)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((48 * dp).toInt(), (96 * dp).toInt(), (48 * dp).toInt(), (96 * dp).toInt())
            visibility = GONE
        }
        addView(emptyView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Card list
        listView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@ClippingsPageView.adapter
            clipToPadding = false
            // Avoid the recycler shifting cards off-screen on the first frame
            // before we set initial data — the empty state covers it.
        }
        addView(listView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            0,
        ).also { it.weight = 1f })
    }

    /** Rebuild the row of filter chips. Idempotent — call after kinds change. */
    /** Contrast text color for text drawn ON the accent — dark on a light
     *  accent, white on a dark one. */
    private fun onAccentColor(accent: Int): Int {
        val l = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) + 0.114 * Color.blue(accent)) / 255.0
        return if (l > 0.6) 0xFF11131A.toInt() else Color.WHITE
    }

    /** Re-apply the theme to this page's chrome (the filter pills' accent fill +
     *  contrast text + font). Called when the theme changes while the page is
     *  live. The card widgets follow the theme on their own (token push). */
    fun reapplyTheme() { rebuildChips() }

    private fun rebuildChips() {
        chipRow.removeAllViews()
        val kinds = listOf(
            null to "All",
            "video" to "Video",
            "music" to "Music",
            "article" to "Article",
            "image" to "Image",
            "note" to "Note",
        )
        val dp = resources.displayMetrics.density
        val accentColor = com.iappyx.launcher.widget.Palette.accent(context)
        for ((kind, label) in kinds) {
            val chip = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = com.iappyx.launcher.widget.Palette.themeTypeface(context) ?: android.graphics.Typeface.DEFAULT
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f * dp
                    if (activeFilter == kind) {
                        setColor(accentColor)
                    } else {
                        setColor(0x22FFFFFF)
                        setStroke((1f * dp).toInt(), 0x44FFFFFF)
                    }
                }
                // Readable on ANY accent (was hardcoded black → invisible on a dark accent).
                setTextColor(if (activeFilter == kind) onAccentColor(accentColor) else 0xFFE0E0E0.toInt())
                setOnClickListener {
                    activeFilter = kind
                    rebuildChips()
                    refresh()
                }
            }
            val lp = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = (8 * dp).toInt() }
            chipRow.addView(chip, lp)
        }
    }

    /** Read clippings + their meta.json, apply filter, sort newest-first, push
     *  to the adapter. Cheap — the meta.json files are tiny. */
    fun refresh() {
        val store = PlacementStore(context)
        val layout = store.load()
        val items = layout.clippings.mapNotNull { c ->
            val meta = readMeta(c.widgetId) ?: return@mapNotNull null
            ClippingRow(c.widgetId, meta)
        }
        val filtered = if (activeFilter == null) items else items.filter { it.meta.kind == activeFilter }
        val sorted = filtered.sortedByDescending { it.meta.createdAt }
        adapter.submit(sorted)
        // Empty-state visibility tracks the FILTERED list, not the raw
        // items — otherwise a kind filter that excludes everything left
        // a blank list area with no copy. Adjust the message accordingly.
        if (sorted.isEmpty()) {
            emptyView.text = if (items.isEmpty()) {
                getContext().getString(R.string.clippings_empty_initial)
            } else {
                getContext().getString(R.string.clippings_empty_filtered)
            }
            emptyView.visibility = VISIBLE
            listView.visibility = GONE
        } else {
            emptyView.visibility = GONE
            listView.visibility = VISIBLE
        }
    }

    private fun readMeta(widgetId: String): ClippingMeta? {
        val dir = File(context.filesDir, "widgets/$widgetId")
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val j = JSONObject(metaFile.readText())
            ClippingMeta(
                title = j.optString("title").ifBlank { "Clipping" },
                kind = j.optString("kind").ifBlank { "article" }.lowercase(),
                createdAt = j.optLong("createdAt", 0L),
                expiresAt = j.optLong("expiresAt", 0L),
                userLocked = j.optBoolean("locked", false),
                sourceHost = j.optString("sourceHost").ifBlank { null },
                sourceUrl = j.optString("sourceUrl").ifBlank { null },
            )
        } catch (_: Throwable) { null }
    }

    private data class ClippingMeta(
        val title: String,
        val kind: String,
        val createdAt: Long,
        val expiresAt: Long,
        val userLocked: Boolean,
        val sourceHost: String?,
        val sourceUrl: String?,
    )

    private data class ClippingRow(val widgetId: String, val meta: ClippingMeta)

    /** Pin one card per row. Card height varies by kind:
     *   - note → 220dp (text needs room),
     *   - video / image → 200dp (16:9-ish),
     *   - article / music → 160dp.
     *  All full-width with 16dp side gutters. */
    private inner class ClippingsAdapter :
        RecyclerView.Adapter<ClippingsAdapter.VH>() {

        private val rows = mutableListOf<ClippingRow>()

        fun submit(newRows: List<ClippingRow>) {
            rows.clear(); rows.addAll(newRows); notifyDataSetChanged()
        }

        fun rowAt(pos: Int): ClippingRow? = rows.getOrNull(pos)

        inner class VH(
            view: View,
            val container: GestureCardFrame,
            val ttlCaption: TextView,
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp = resources.displayMetrics.density
            // Outer wrapper holds card + TTL caption row, side gutters, and
            // a bottom margin between cards.
            val wrap = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(
                    (16 * dp).toInt(), 0,
                    (16 * dp).toInt(), (16 * dp).toInt(),
                )
            }
            // Inner card frame holds the GeneratedWidgetCell at fixed height.
            // Use [GestureCardFrame] so its dispatchTouchEvent feeds a local
            // GestureDetector that reliably sees ACTION_UP for both taps —
            // the cell's own gesture detector misses it (a child WebView
            // consumes the gesture, so the cell's onInterceptTouchEvent
            // never sees the UP, the long-press timer never cancels, and
            // both onLongPress + onDoubleTap fire on a fast double-tap).
            val card = GestureCardFrame(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (200 * dp).toInt(), // default; overridden in bind
                )
                background = GradientDrawable().apply {
                    setColor(0xFF1B1B1F.toInt())
                    cornerRadius = 20f * dp
                }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, 20f * dp)
                    }
                }
            }
            wrap.addView(card)
            // TTL caption row — small grey text below the card.
            val ttlText = TextView(parent.context).apply {
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(
                    (8 * dp).toInt(), (6 * dp).toInt(),
                    (8 * dp).toInt(), 0,
                )
                gravity = Gravity.START
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            wrap.addView(ttlText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            return VH(wrap, card, ttlText)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val dp = resources.displayMetrics.density
            val row = rows[position]
            val targetH = when (row.meta.kind) {
                "note" -> 220
                "article", "music" -> 160
                else -> 200
            }
            val lp = holder.container.layoutParams
            lp.height = (targetH * dp).toInt()
            holder.container.layoutParams = lp
            holder.container.removeAllViews()

            // Existing children of an old cell could leak — fresh cell each
            // bind is OK at clippings list scale (typically <30 items, only
            // visible ones materialised). RV recycles holders so the WebView
            // teardown is bounded.
            val cell = GeneratedWidgetCell(holder.itemView.context).apply {
                this.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            val htmlFile = File(holder.itemView.context.filesDir, "widgets/${row.widgetId}/widget.html")
            val html = try { htmlFile.readText() } catch (_: Throwable) { "" }
            cell.bind(activity, row.widgetId, html)
            holder.container.addView(cell)

            // Wire long-press → contextual menu and double-tap → zoom on
            // the [GestureCardFrame] that wraps the cell. Doing this at the
            // wrap level (which sees every event via dispatchTouchEvent)
            // avoids the dual-fire bug: GestureDetector reliably distinguishes
            // double-tap from long-press because it sees both ACTION_UPs.
            holder.container.onLongPress = { showCardMenu(row) }
            holder.container.onDoubleTap = {
                com.iappyx.launcher.widget.WidgetZoomOverlay(activity, cell).show()
            }

            // Render the TTL caption directly under the card.
            holder.ttlCaption.text = formatTtlCaption(row.meta)
            holder.ttlCaption.setTextColor(ttlCaptionColor(row.meta))
        }

        override fun onViewRecycled(holder: VH) {
            // Tear down WebViews so they don't leak across recycled holders.
            for (i in 0 until holder.container.childCount) {
                val v = holder.container.getChildAt(i)
                if (v is GeneratedWidgetCell) v.destroyWidget(permanent = false)
            }
            holder.container.removeAllViews()
            super.onViewRecycled(holder)
        }
    }

    /** Long-press contextual menu. Order picked for muscle-memory: most-
     *  used (Lock / Reset TTL) first, web-actions in the middle, Delete
     *  last. Menu items are gated by what's available — Note clippings
     *  with no source URL skip the open/share/copy rows. */
    private fun showCardMenu(row: ClippingRow) {
        val locked = row.meta.userLocked
        val hasSource = !row.meta.sourceUrl.isNullOrBlank()
        data class Item(val label: String, val onClick: () -> Unit)
        val items = mutableListOf<Item>()
        items += Item(
            if (locked) "Unlock (allow auto-expire)" else "Lock (prevent auto-expire)",
        ) {
            WidgetLibrary.setUserLocked(context, row.widgetId, !locked)
            refresh()
        }
        if (!locked) {
            items += Item("Reset TTL") {
                WidgetLibrary.resetClippingTtl(context, row.widgetId)
                refresh()
            }
        }
        if (hasSource) {
            items += Item("Open original") { openSource(row.meta.sourceUrl!!) }
            items += Item("Share…") { shareSource(row.meta) }
            items += Item("Copy link") { copyLink(row.meta.sourceUrl!!) }
        }
        items += Item("Delete") {
            deleteClipping(row.widgetId)
            refresh()
        }

        val labels = items.map { it.label }.toTypedArray()
        // Track the open dialog so onDetachedFromWindow can dismiss it
        // cleanly on rotation / activity finish — without this, Android
        // logs "WindowLeaked" and the activity holds a Dialog reference
        // past its lifecycle.
        currentMenuDialog?.dismiss()
        currentMenuDialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(row.meta.title.take(40))
            .setItems(labels) { dlg, which ->
                items[which].onClick()
                dlg.dismiss()
            }
            .setOnDismissListener { currentMenuDialog = null }
            .showThemed()
    }

    private var currentMenuDialog: android.app.Dialog? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Dismiss any open per-card menu so it doesn't leak the window.
        currentMenuDialog?.dismiss()
        currentMenuDialog = null
        // Stop the TTL caption tick (added below).
        ttlTickHandler.removeCallbacks(ttlTickRunnable)
    }

    private fun openSource(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
            android.widget.Toast.makeText(context, "Couldn't open link",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSource(meta: ClippingMeta) {
        val url = meta.sourceUrl ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
            putExtra(android.content.Intent.EXTRA_SUBJECT, meta.title)
        }
        try {
            context.startActivity(android.content.Intent.createChooser(intent, "Share")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { /* no share apps available */ }
    }

    private fun copyLink(url: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Clipping", url))
        android.widget.Toast.makeText(context, "Link copied",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /** "Expires in 3h" / "Expires tomorrow" / "Expires Friday" / "🔒 Locked".
     *  expiresAt == 0 → "Never expires" (TTL was set to never in Settings or
     *  the user locked the clipping, which sweeps treat the same way). */
    private fun formatTtlCaption(meta: ClippingMeta): String {
        if (meta.userLocked) return "🔒 Locked"
        if (meta.expiresAt <= 0L) return "Never expires"
        val now = System.currentTimeMillis()
        val remaining = meta.expiresAt - now
        if (remaining <= 0L) return "Expiring now…"
        val mins = remaining / 60_000L
        val hours = mins / 60L
        val days = hours / 24L
        return when {
            mins < 60L -> "Expires in ${mins.coerceAtLeast(1)}m"
            hours < 24L -> "Expires in ${hours}h"
            days < 2L -> "Expires tomorrow"
            days < 7L -> "Expires in ${days}d"
            else -> "Expires in ${days}d"
        }
    }

    private fun ttlCaptionColor(meta: ClippingMeta): Int {
        if (meta.userLocked) return 0xFFB199FF.toInt()  // muted purple
        if (meta.expiresAt <= 0L) return 0xFF888888.toInt()
        val remaining = meta.expiresAt - System.currentTimeMillis()
        // < 1h → warning amber. < 6h → soft amber. Else → grey.
        return when {
            remaining <= 60L * 60_000L -> 0xFFFFB870.toInt()
            remaining <= 6L * 60L * 60_000L -> 0xFFCCAB80.toInt()
            else -> 0xFF888888.toInt()
        }
    }

    private fun deleteClipping(widgetId: String) {
        // Delegate to LauncherActivity so the in-memory layout stays in
        // sync. A fresh PlacementStore.load → mutate → save here would
        // diverge from the launcher's in-memory copy and any subsequent
        // launcher-side save would resurrect the deletion.
        activity.deleteClipping(widgetId)
    }

    // Per-cell pause/resume is handled by LauncherActivity.applyWidgetVisibilityForCurrentPage
    // — its forEachGeneratedWidgetIn recursion descends through this page's
    // RecyclerView and reaches the realized GeneratedWidgetCells inside.
    // No clippings-specific pauseAll/resumeAll is needed here.
}
