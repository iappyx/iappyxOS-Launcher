/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.iappyx.launcher.R
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Page

/**
 * Pinch-to-overview panel. Shows all home pages as horizontal thumbnails with:
 *  - Tap → exit overview, jump pager to that page
 *  - Long-press → delete page (only if page has ≥ 1 page after it or is empty)
 *  - Drag → reorder pages within layout
 *
 * Slides in over the home pager when [show] is called and slides out via [hide].
 */
class OverviewPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val list: RecyclerView
    private val title: TextView
    private val close: ImageView
    private val addPage: TextView
    private val adapter = ThumbAdapter()

    /** Current snapshot of the layout passed in via [show]. Mutations made
     *  via reorder/delete go through callbacks below; the host activity must
     *  apply them to its real layout + persist. */
    private var snapshot: HomeLayout? = null

    /** Tap a thumbnail → request the host to jump to [pageIndex] and dismiss. */
    var onJumpTo: ((pageIndex: Int) -> Unit)? = null
    /** User reordered pages — host applies and saves. */
    var onReorder: ((from: Int, to: Int) -> Unit)? = null
    /** User asked to delete a page. Host validates + applies. */
    var onDelete: ((pageIndex: Int) -> Unit)? = null
    /** Dismiss request (back / close button). */
    var onRequestHide: (() -> Unit)? = null
    /** "+ Add page" chip — host appends a new empty Page and refreshes. */
    var onAddPage: (() -> Unit)? = null
    /** Tap the label → host opens a rename dialog. The host writes the
     *  new name onto `layout.pages[idx]` and calls [refresh] to repaint. */
    var onRename: ((pageIndex: Int) -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#E60D0D1A"))
        isClickable = true
        fitsSystemWindows = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        // Header — single row with back arrow, title, and "+ Add page" chip.
        // The back arrow is the only exit affordance; tap → host hides the
        // panel and we resume edit mode underneath.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        close = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.textPrimary(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val s = (40 * density).toInt()
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (4 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(0x22, 0xFF, 0xFF, 0xFF))
            }
            isClickable = true; isFocusable = true
            contentDescription = "Back"
            setOnClickListener { onRequestHide?.invoke() }
        }
        title = TextView(context).apply {
            text = "Pages"
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
        }
        addPage = TextView(context).apply {
            text = "+ Add page"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.textPrimary(context))
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(android.graphics.Color.argb(0x1F, 0x4F, 0xC3, 0xF7))
                setStroke((1 * density).toInt(), android.graphics.Color.argb(0x66, 0x4F, 0xC3, 0xF7))
            }
            val hp = (14 * density).toInt(); val vp = (8 * density).toInt()
            setPadding(hp, vp, hp, vp)
            isClickable = true; isFocusable = true
            setOnClickListener { onAddPage?.invoke() }
        }
        header.addView(close); header.addView(title); header.addView(addPage)
        root.addView(header)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@OverviewPanel.adapter
            clipToPadding = false
            val sp = (16 * density).toInt()
            setPadding(sp, (8 * density).toInt(), sp, (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(list)

        val hint = TextView(context).apply {
            text = "Tap a page to open · tap its label to rename · long-press to drag-reorder · tap the close badge on a thumbnail to delete"
            setTextColor(Palette.textSecondary(context))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        }
        root.addView(hint)

        addView(root)

        // Drag-to-reorder via ItemTouchHelper. Long-press on a thumbnail starts
        // the drag automatically — no manual startDrag plumbing needed.
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.START or ItemTouchHelper.END, 0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                onReorder?.invoke(from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // Lift effect while dragging.
                viewHolder?.itemView?.animate()
                    ?.scaleX(if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 1.05f else 1f)
                    ?.scaleY(if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 1.05f else 1f)
                    ?.alpha(if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.95f else 1f)
                    ?.setDuration(140L)?.start()
            }
        })
        touchHelper.attachToRecyclerView(list)
    }

    fun show(layout: HomeLayout, currentPageIndex: Int) {
        if (visibility == View.VISIBLE) return
        snapshot = layout
        adapter.bind(layout)
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 1.06f; scaleY = 1.06f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        post { (list.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(currentPageIndex, (24 * density).toInt()) }
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(180L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                scaleX = 1f; scaleY = 1f
            }.start()
    }

    /** Re-bind adapter after host applied a delete or reorder (so thumbnails
     *  reflect the latest layout). */
    fun refresh(layout: HomeLayout) {
        snapshot = layout
        adapter.bind(layout)
    }

    /** Smooth-scroll the thumbnail strip so [pageIndex] is centred. Used after
     *  the host adds a new page so the user immediately sees it. */
    fun scrollToPage(pageIndex: Int) {
        post {
            (list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pageIndex, (24 * density).toInt())
        }
    }

    private inner class ThumbAdapter : RecyclerView.Adapter<ThumbHolder>() {
        private var pages: List<Page> = emptyList()
        private var layoutRef: HomeLayout? = null

        fun bind(layout: HomeLayout) {
            layoutRef = layout
            pages = layout.pages.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            // Match the device's current screen aspect so thumbnails read
            // as a true mini-screen, not a stretched portrait card on a
            // landscape tablet.
            val dm = ctx.resources.displayMetrics
            val isLandscape = dm.widthPixels > dm.heightPixels
            val cardW = ((if (isLandscape) 240f else 160f) * dp).toInt()
            val cardH = ((if (isLandscape) 160f else 240f) * dp).toInt()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(cardW, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (8 * dp).toInt()
                    marginEnd = (8 * dp).toInt()
                }
                isClickable = true
                isFocusable = true
                isLongClickable = true // ItemTouchHelper picks this up
            }
            // Stack: thumbnail with a ✕ delete badge anchored top-end.
            val stack = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cardH)
            }
            val thumb = PageThumbnail(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            val deleteBadge = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (4 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#FF3B30"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#22FFFFFF"))
                }
                val s = (24 * dp).toInt()
                layoutParams = FrameLayout.LayoutParams(s, s).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = (10 * dp).toInt()
                    rightMargin = (10 * dp).toInt()
                }
                isClickable = true
                isFocusable = true
                contentDescription = "Delete page"
            }
            stack.addView(thumb)
            stack.addView(deleteBadge)
            val label = TextView(ctx).apply {
                setTextColor(Palette.textPrimary(ctx))
                textSize = 12f
                setPadding(0, (10 * dp).toInt(), 0, 0)
                // Subtle tap target so users discover the rename action.
                isClickable = true
                isFocusable = true
            }
            container.addView(stack)
            container.addView(label)
            return ThumbHolder(container, thumb, label, deleteBadge)
        }

        override fun onBindViewHolder(holder: ThumbHolder, position: Int) {
            val layout = layoutRef ?: return
            val page = pages[position]
            holder.thumb.bind(layout, page)
            val count = page.placements.size
            val pageName = page.name.takeIf { it.isNotBlank() } ?: "Page ${position + 1}"
            holder.label.text = "$pageName  ·  ${if (count == 0) "empty" else "$count item${if (count == 1) "" else "s"}"}"
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onJumpTo?.invoke(pos)
            }
            // Tap-the-label → rename. Stop propagation so the card's
            // outer click (jump-to-page) doesn't also fire.
            holder.label.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRename?.invoke(pos)
            }
            // ✕ button only enabled when there's more than one page (you can't
            // delete the last remaining page).
            holder.delete.alpha = if (pages.size > 1) 1f else 0.3f
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pages.size > 1) onDelete?.invoke(pos)
            }
        }

        override fun getItemCount() = pages.size
    }

    private class ThumbHolder(
        view: View,
        val thumb: PageThumbnail,
        val label: TextView,
        val delete: ImageView,
    ) : RecyclerView.ViewHolder(view)
}
