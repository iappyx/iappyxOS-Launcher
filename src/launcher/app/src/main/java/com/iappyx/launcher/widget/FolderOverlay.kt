/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.model.FolderItem
import com.iappyx.launcher.model.Placement

/**
 * Modal overlay that shows a folder's apps in a 3-column grid. Editable name.
 * Tap an icon to launch; long-press an icon to start a drag-out (the host
 * starts the system DnD; on a successful drop the icon is moved to the home
 * grid and removed from the folder). The home chrome behind the overlay is
 * blurred on API 31+.
 */
class FolderOverlay(
    private val activity: Activity,
    private val placement: Placement,
    private val sourceView: View? = null,
    private val onChanged: (name: String, items: List<FolderItem>) -> Unit,
    /** Host hook for the drag-out flow. Called the moment a long-press
     *  fires inside the folder grid — the host starts the system DnD on
     *  [sourceView] and tracks the result. On a successful drop the host
     *  removes the item from the folder. The folder overlay dismisses
     *  immediately so the home grid is the active drop target. */
    private val onStartDragOut: ((item: FolderItem, sourceView: View) -> Unit)? = null,
) {

    /** The attached overlay root (added to activity_root in [show], removed
     *  in [dismiss]). Tracked so the activity's back-press can dismiss it. */
    private var rootOverlay: ViewGroup? = null
    /** Captured at attach time so [dismiss] can persist the user's edits. */
    private var nameFieldRef: EditText? = null
    private var itemsRef: MutableList<FolderItem>? = null
    /** Views we applied [android.graphics.RenderEffect] to in [show] — cleared
     *  in [dismiss] so the home chrome isn't left blurred forever. */
    private val blurredViews = mutableListOf<View>()

    fun isShowing(): Boolean = rootOverlay != null

    fun show() {
        val dp = activity.resources.displayMetrics.density
        // Attach the overlay as a regular child of activity_root — same window
        // as the home grid. This is what makes drag-out of folder items work:
        // a system DnD started from a view inside this overlay reaches the
        // home grid's drop listeners (Dialog windows are isolated and don't).
        val activityRoot = activity.findViewById<FrameLayout>(com.iappyx.launcher.R.id.activity_root)
            ?: return

        // API 31+: blur the home chrome (RenderEffect on the views in our
        // hierarchy) AND the wallpaper (rendered as a separate ImageView in
        // the overlay so we can apply RenderEffect directly — the
        // window-level blur APIs don't reach the wallpaper window when
        // windowShowWallpaper composites it through ours).
        if (Build.VERSION.SDK_INT >= 31) {
            val blur = android.graphics.RenderEffect.createBlurEffect(
                28f, 28f, android.graphics.Shader.TileMode.CLAMP,
            )
            listOf(
                com.iappyx.launcher.R.id.pager,
                com.iappyx.launcher.R.id.home_chrome_strip,
                com.iappyx.launcher.R.id.dock_bar,
            ).forEach { id ->
                activity.findViewById<View>(id)?.let {
                    it.setRenderEffect(blur)
                    blurredViews.add(it)
                }
            }
        }

        val scrim = View(activity).apply {
            setBackgroundColor(Color.parseColor("#B2000000"))
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val root = FrameLayout(activity).apply {
            isClickable = true; isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(scrim)
            // Draw under the system bars — the scrim shows through as one
            // continuous dim layer up to the very edges.
            fitsSystemWindows = false
        }
        rootOverlay = root
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                // Translucent — the dim scrim behind carries the contrast,
                // while wallpaper + home icons faintly show through the card.
                setColor(Color.parseColor("#661A1A2E"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                width = (340 * dp).toInt()
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            isClickable = true
        }

        val nameField = EditText(activity).apply {
            setText(placement.folderName
                ?: activity.getString(com.iappyx.launcher.R.string.folder_default_name))
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = (12 * dp).toInt()
            layoutParams = lp
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else false
            }
        }
        card.addView(nameField)

        val list = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (440 * dp).toInt(),
            )
        }
        card.addView(list)

        val hint = TextView(activity).apply {
            setText(com.iappyx.launcher.R.string.folder_overlay_hint)
            setTextColor(Color.parseColor("#A0A0B8"))
            textSize = 11f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
        card.addView(hint)

        root.addView(card)

        val items = placement.folderItems.toMutableList()
        nameFieldRef = nameField
        itemsRef = items
        val adapter = FolderAdapter(
            activity = activity,
            items = items,
            onClick = { item ->
                activity.packageManager.getLaunchIntentForPackage(item.packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    com.iappyx.launcher.LauncherPrefs(activity).recordAppLaunch(item.packageName)
                    // APPLOCK: dismiss the folder only on successful auth /
                    // unlocked launch — a cancelled prompt leaves the
                    // folder open so the user can pick another app.
                    com.iappyx.launcher.applock.AppLockManager.launchApp(
                        activity, item.packageName, intent,
                    ) { dismiss() }
                }
            },
            onDragOut = { sourceView, item ->
                val host = onStartDragOut
                if (host != null) {
                    // The source view is in the activity's main window (the
                    // overlay is a child of activity_root), so a system DnD
                    // started here reaches the home grid's drop listeners
                    // normally. Host snapshots the source into the drag
                    // shadow, then we tear the overlay down so the home
                    // grid is the active drop target.
                    host(item, sourceView)
                    dismiss()
                }
            },
        )
        list.adapter = adapter

        activityRoot.addView(root)

        // Enter animation: card zooms out from the tapped folder cell; scrim fades in.
        card.post { animateIn(card, scrim) }

        // Tapping outside the card dismisses; tapping the card itself doesn't.
        root.setOnClickListener { dismissAnimated(scrim, card) }
        card.setOnClickListener { /* swallow */ }
    }

    /** Detach the overlay from activity_root and persist the user's edits. */
    fun dismiss() {
        val root = rootOverlay ?: return
        rootOverlay = null
        // Drop the IME if the name field still owned it — without this, the
        // keyboard sticks around even though the EditText is being torn down.
        nameFieldRef?.let { nf ->
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(nf.windowToken, 0)
            nf.clearFocus()
        }
        // Clear the blur on the home chrome views. The wallpaper-backdrop
        // ImageView lives inside the overlay and goes away with it.
        if (Build.VERSION.SDK_INT >= 31) {
            blurredViews.forEach { it.setRenderEffect(null) }
        }
        blurredViews.clear()
        val parent = root.parent as? ViewGroup
        parent?.removeView(root)
        val defaultName = activity.getString(com.iappyx.launcher.R.string.folder_default_name)
        val name = nameFieldRef?.text?.toString()?.ifBlank { defaultName } ?: defaultName
        val items = itemsRef.orEmpty()
        nameFieldRef = null; itemsRef = null
        onChanged(name, items.toList())
    }

    /** Zoom the card from the source folder cell's on-screen rect out to its
     *  final centered position. If no source view is provided, falls back to
     *  a simple scale-fade. */
    private fun animateIn(card: View, scrim: View) {
        val src = sourceView
        val target = android.graphics.Rect().also { card.getGlobalVisibleRect(it) }
        if (src != null && target.width() > 0 && target.height() > 0) {
            val from = android.graphics.Rect().also { src.getGlobalVisibleRect(it) }
            val scaleX = from.width().toFloat() / target.width()
            val scaleY = from.height().toFloat() / target.height()
            val tx = (from.centerX() - target.centerX()).toFloat()
            val ty = (from.centerY() - target.centerY()).toFloat()
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            card.translationX = tx
            card.translationY = ty
            card.scaleX = scaleX
            card.scaleY = scaleY
            card.alpha = 0.6f
        } else {
            card.scaleX = 0.85f; card.scaleY = 0.85f; card.alpha = 0f
        }
        card.animate()
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
        scrim.animate().alpha(1f).setDuration(200L).start()
    }

    /** Reverse of animateIn — zoom the card back into the source cell (if
     *  still valid) and fade the scrim, then detach the overlay. */
    private fun dismissAnimated(scrim: View, card: View? = null) {
        val c = card ?: run { dismiss(); return }
        val src = sourceView
        val target = android.graphics.Rect().also { c.getGlobalVisibleRect(it) }
        val anim = c.animate().setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
        if (src != null && target.width() > 0 && target.height() > 0) {
            val from = android.graphics.Rect().also { src.getGlobalVisibleRect(it) }
            anim.scaleX(from.width().toFloat() / target.width())
                .scaleY(from.height().toFloat() / target.height())
                .translationX((from.centerX() - target.centerX()).toFloat())
                .translationY((from.centerY() - target.centerY()).toFloat())
                .alpha(0.6f)
        } else {
            anim.scaleX(0.85f).scaleY(0.85f).alpha(0f)
        }
        anim.withEndAction { dismiss() }.start()
        scrim.animate().alpha(0f).setDuration(160L).start()
    }

    private class FolderAdapter(
        private val activity: Activity,
        private val items: MutableList<FolderItem>,
        private val onClick: (FolderItem) -> Unit,
        private val onDragOut: (View, FolderItem) -> Unit,
    ) : RecyclerView.Adapter<FolderAdapter.H>() {

        class H(val root: LinearLayout, val icon: FolderIconView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isClickable = true; isFocusable = true
            }
            val icon = FolderIconView(ctx).apply {
                // 72dp container (was 64dp) — the extra 8dp accommodates the
                // badge inset added inside FolderIconView so the visible icon
                // stays visually similar to the previous 64dp render.
                val s = (72 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            val label = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
            }
            root.addView(icon); root.addView(label)
            return H(root, icon, label)
        }

        override fun onBindViewHolder(h: H, position: Int) {
            val item = items[position]
            try {
                val pm = activity.packageManager
                val info = pm.getApplicationInfo(item.packageName, 0)
                val raw = pm.getApplicationIcon(info)
                val sizePx = (128 * activity.resources.displayMetrics.density).toInt()
                // Resolve the active icon filter spec so opened folders
                // render with the same shape + filter as the home grid /
                // dock / app drawer.
                val spec = com.iappyx.launcher.cells.IconFilterRegistry.resolve(
                    activity, com.iappyx.launcher.LauncherPrefs(activity).iconFilter,
                )
                h.icon.bind(
                    item.packageName,
                    com.iappyx.launcher.cells.IconMask.render(item.packageName, raw, sizePx, spec),
                )
                h.label.text = pm.getApplicationLabel(info)
            } catch (_: Exception) {
                h.label.text = item.packageName
            }
            // Touch flow:
            //  Tap        → launch the app
            //  Long-press → fire drag-out immediately (host starts system DnD)
            //  Pre-press move → cancel the pending long-press (just scrolling)
            val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            val downX = floatArrayOf(0f); val downY = floatArrayOf(0f)
            val launched = booleanArrayOf(false)
            val pendingLongPress = arrayOfNulls<Runnable>(1)
            h.root.setOnClickListener(null)
            h.root.setOnLongClickListener(null)
            // Resolve the CURRENT bound item via bindingAdapterPosition, not
            // the captured `item`. RecyclerView can recycle this holder
            // between ACTION_DOWN and the long-press timeout firing — the
            // posted Runnable would then drag-out the previously-bound app
            // (wrong icon launches / wrong app gets dragged). Returns null
            // when the holder is detached or position is invalid.
            fun currentItem(): FolderItem? {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return null
                return items.getOrNull(pos)
            }
            h.root.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX[0] = ev.x; downY[0] = ev.y
                        launched[0] = false
                        val r = Runnable {
                            if (!launched[0]) {
                                launched[0] = true
                                val now = currentItem() ?: return@Runnable
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                                onDragOut(v, now)
                            }
                        }
                        pendingLongPress[0] = r
                        v.postDelayed(r, longPressTimeout)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!launched[0]) {
                            val dx = ev.x - downX[0]; val dy = ev.y - downY[0]
                            if (kotlin.math.sqrt(dx * dx + dy * dy) > touchSlop) {
                                pendingLongPress[0]?.let { v.removeCallbacks(it) }
                                pendingLongPress[0] = null
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        pendingLongPress[0]?.let { v.removeCallbacks(it) }
                        pendingLongPress[0] = null
                        if (!launched[0]) {
                            val dx = ev.x - downX[0]; val dy = ev.y - downY[0]
                            if (kotlin.math.sqrt(dx * dx + dy * dy) <= touchSlop) {
                                v.performClick()
                                currentItem()?.let { onClick(it) }
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        pendingLongPress[0]?.let { v.removeCallbacks(it) }
                        pendingLongPress[0] = null
                    }
                }
                false
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
