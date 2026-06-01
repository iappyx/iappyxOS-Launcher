/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.R

/**
 * Full-height overlay panel that shows an alphabetical, searchable list of
 * installed apps. Lives inside LauncherActivity so a long-press can start a
 * native Android drag whose drop target is the home grid in the same window.
 *
 * UX:
 *  - Swipe up on home → [show] slides this in from the bottom
 *  - Tap close / press back / tap outside → [hide] slides it out
 *  - Long-press an app card → popup menu (Add to home, App info, Uninstall,
 *    Drag to home). "Drag to home" starts a drag; drop on the home grid places.
 *  - Drag alphabet rail → jump to letter; visible-letters highlight as you scroll.
 */
class AppDrawerPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        /** ClipDescription mimetype identifying a drag started from this drawer. */
        const val DRAG_MIME = "application/vnd.iappyx.launcher-app"
        const val DRAG_LABEL = "iappyxApp"
        const val CLIP_PKG = "pkg"
        const val CLIP_ACTIVITY = "activity"

        /** Minimum number of CATEGORY_UNDEFINED apps before the "Other"
         *  chip surfaces. Below this threshold the chip is suppressed —
         *  tiny "Other" buckets don't justify the chip-strip clutter. */
        private const val OTHER_CHIP_MIN_APPS = 5

        /** Ordered (categoryId, displayLabelResId) for the chip strip.
         *  The ApplicationInfo category constants for reference:
         *    GAME=0 AUDIO=1 VIDEO=2 IMAGE=3 SOCIAL=4
         *    NEWS=5 MAPS=6 PRODUCTIVITY=7 ACCESSIBILITY=8
         *  Categories not present in the user's installed apps are
         *  filtered out before the strip is built. Labels resolve via
         *  context.getString so a values-XX/strings.xml override picks
         *  up automatically. */
        private val CATEGORY_DISPLAY_ORDER = listOf(
            android.content.pm.ApplicationInfo.CATEGORY_GAME to R.string.drawer_category_games,
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL to R.string.drawer_category_social,
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY to R.string.drawer_category_productivity,
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO to R.string.drawer_category_audio,
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO to R.string.drawer_category_video,
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE to R.string.drawer_category_photos,
            android.content.pm.ApplicationInfo.CATEGORY_NEWS to R.string.drawer_category_news,
            android.content.pm.ApplicationInfo.CATEGORY_MAPS to R.string.drawer_category_maps,
            android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY to R.string.drawer_category_accessibility,
        )
    }

    data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable,
        val category: Int = android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED,
    )
    sealed class Row {
        data class Header(val letter: String) : Row()
        data class App(val entry: AppEntry) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val all = mutableListOf<AppEntry>()
    /** Currently-selected category chip. `null` = "All" (no category
     *  filter — also the default). Combines with the search query: an
     *  app is shown only when it matches both. */
    private var activeCategory: Int? = null
    private val grid: RecyclerView
    private val rail: AlphabetRail
    private val letterPreview: TextView
    private val search: EditText
    private lateinit var categoryChipScroll: HorizontalScrollView
    private lateinit var categoryChipRow: LinearLayout
    private val density = resources.displayMetrics.density
    private val layoutManager: GridLayoutManager
    private val adapter: Adapter
    /** Most recently opened context popup. Held so the drag-on-move flow can
     *  dismiss it cleanly when the user starts dragging. */
    private var currentContextPopup: PopupWindow? = null

    /** Called when the user chooses "Add to home" from the popup. The host
     *  places the icon on the current home page (first empty cell). */
    var onAddToHome: ((AppEntry) -> Unit)? = null
    /** Called when the panel should dismiss itself (close button / back press / drop). */
    var onRequestHide: (() -> Unit)? = null

    private val spanCount = 4

    init {
        // Fully opaque bg_home — must match the launcher's statusBarColor while
        // the drawer is open so the top system bar blends seamlessly.
        setBackgroundColor(Palette.bgHome(context))
        isClickable = true // swallow taps so clicks don't fall through
        // IMPORTANT: fitsSystemWindows is OFF on the panel itself so our
        // bg_home background paints all the way up to the top of the window,
        // under the status bar. The inner content receives the inset padding
        // below via an OnApplyWindowInsetsListener so nothing hides under the
        // clock / navigation bar.
        fitsSystemWindows = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // Apply status-bar + nav-bar insets as padding on the content column,
        // so the solid bg_home of the panel shows behind the system bars while
        // the title / search / list stay clear of them.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        // Grabber pill at the very top — drag it down to dismiss (option E).
        // Sits in its own tall-ish touch area so it's a comfortable target.
        val grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (24 * density).toInt(),
            )
            isClickable = true; isFocusable = true
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragStartY = ev.rawY; true }
                    MotionEvent.ACTION_MOVE -> {
                        translationY = (ev.rawY - dragStartY).coerceAtLeast(0f); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        finishDrag(ev.rawY - dragStartY); true
                    }
                    else -> false
                }
            }
        }
        val grabberPill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * density
                setColor(Palette.overlayWhiteMedium(context))
            }
            layoutParams = FrameLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER)
        }
        grabberWrap.addView(grabberPill)
        root.addView(grabberWrap)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (20 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        val title = TextView(context).apply {
            setText(R.string.drawer_title)
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.textPrimary(context))
            alpha = 0.7f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (8 * density).toInt()
            setPadding((12 * density).toInt(), pad, (12 * density).toInt(), pad)
            val s = (40 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true; isFocusable = true
            contentDescription = context.getString(R.string.drawer_close_cd)
            setOnClickListener { onRequestHide?.invoke() }
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#00000000"))
            }
        }
        header.addView(title); header.addView(close)
        root.addView(header)

        search = EditText(context).apply {
            setHint(R.string.drawer_search_hint)
            setHintTextColor(Palette.textSecondary(context))
            setTextColor(Palette.textPrimary(context))
            textSize = 14f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins((20 * density).toInt(), 0, (20 * density).toInt(), (12 * density).toInt())
            layoutParams = lp
        }
        root.addView(search)

        // Category chip strip — horizontally scrollable, sits between the
        // search bar and the list. Auto-hides when the user's apps don't
        // declare any android:appCategory (most-empty case = no chips
        // worth showing). Single-select: tap an active chip again to
        // clear the filter.
        categoryChipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins(0, 0, 0, (12 * density).toInt())
            layoutParams = lp
            visibility = View.GONE  // populated in refreshCategoryChips()
        }
        categoryChipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        categoryChipScroll.addView(categoryChipRow)
        root.addView(categoryChipScroll)

        val listArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        grid = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding((16 * density).toInt(), 0, (32 * density).toInt(), (24 * density).toInt())
            clipToPadding = false
        }
        layoutManager = GridLayoutManager(context, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (rows[position] is Row.Header) spanCount else 1
            }
        }
        grid.layoutManager = layoutManager

        rail = AlphabetRail(context).apply {
            layoutParams = LayoutParams((22 * density).toInt(), LayoutParams.MATCH_PARENT, Gravity.END).apply {
                marginEnd = (4 * density).toInt()
            }
        }

        letterPreview = TextView(context).apply {
            textSize = 36f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.textPrimary(context))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            visibility = View.GONE
            val lp = LayoutParams((72 * density).toInt(), (72 * density).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
            lp.marginEnd = (44 * density).toInt()
            layoutParams = lp
        }

        listArea.addView(grid)
        listArea.addView(rail)
        listArea.addView(letterPreview)
        root.addView(listArea)

        addView(root)

        adapter = Adapter(rows, this::onAppClick, this::onAppLongPress, this::onAppLongPressDrag)
        grid.adapter = adapter

        rail.onLetterTouch = { c: Char ->
            letterPreview.visibility = View.VISIBLE
            letterPreview.text = c.toString()
            // Tapping # jumps to the Recent section when present (or the
            // non-letter # bucket), otherwise scroll to the matching letter.
            val index = if (c == '#') {
                rows.indexOfFirst { r -> r is Row.Header && (r.letter == "Recent" || r.letter == "#") }
            } else {
                rows.indexOfFirst { r -> r is Row.Header && r.letter.first() == c }
            }
            if (index >= 0) layoutManager.scrollToPositionWithOffset(index, 0)
            letterPreview.postDelayed({ letterPreview.visibility = View.GONE }, 600)
        }

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateVisibleLetters()
            }
        })

        // Swipe-down anywhere on the panel closes it. Routed through dispatchTouchEvent
        // so the child RecyclerView / rail still receive normal events.
        // (See [dispatchTouchEvent] override below.)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilters()
            }
        })
    }

    /** Apply the current search query AND active category to [all] and
     *  rebuild the visible rows. Called by both the text watcher and the
     *  chip click handlers. */
    private fun applyFilters() {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val cat = activeCategory
        val filtered = all.asSequence()
            .filter { q.isEmpty() || it.label.lowercase().contains(q) }
            .filter { cat == null || it.category == cat }
            .toList()
        rows.clear()
        rows.addAll(toRows(filtered))
        adapter.notifyDataSetChanged()
        rail.activeLetters = rows.filterIsInstance<Row.Header>()
            .map { if (it.letter == "Recent") '#' else it.letter.first() }.toSet()
        post { updateVisibleLetters() }
    }

    /** Rebuild the category chip strip. Only categories that at least one
     *  installed app declares are shown — keeps the strip relevant per
     *  device. The strip auto-hides entirely when no app declares a
     *  category, so users without manifest-declared apps don't see an
     *  empty header. */
    private fun refreshCategoryChips() {
        val undefinedConst = android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
        val present = all.asSequence()
            .map { it.category }
            .filter { it != undefinedConst }
            .toSet()
        // Count apps without a manifest-declared category — surface them
        // as an "Other" chip when the bucket is large enough to be worth
        // a tap (≤4 uncategorized apps wouldn't be useful as a separate
        // chip and would just clutter the strip).
        val uncategorizedCount = all.count { it.category == undefinedConst }
        val showOther = uncategorizedCount >= OTHER_CHIP_MIN_APPS
        if (present.isEmpty() && !showOther) {
            categoryChipScroll.visibility = View.GONE
            categoryChipRow.removeAllViews()
            activeCategory = null
            return
        }
        categoryChipScroll.visibility = View.VISIBLE
        categoryChipRow.removeAllViews()
        // "All" chip first, then each present category in display order,
        // then "Other" trailing — uncategorized apps are the long tail,
        // not the headline.
        categoryChipRow.addView(makeCategoryChip(null, context.getString(R.string.drawer_category_all)))
        for ((catId, labelResId) in CATEGORY_DISPLAY_ORDER) {
            if (catId in present) {
                categoryChipRow.addView(makeCategoryChip(catId, context.getString(labelResId)))
            }
        }
        if (showOther) {
            categoryChipRow.addView(makeCategoryChip(undefinedConst,
                context.getString(R.string.drawer_category_other)))
        }
        // If the previously-active category no longer has any apps (e.g.
        // user uninstalled the only app in that category), drop it.
        // The "Other" bucket is valid as long as showOther is true.
        val stillValid = activeCategory == null ||
            activeCategory in present ||
            (activeCategory == undefinedConst && showOther)
        if (!stillValid) {
            activeCategory = null
        }
    }

    private fun makeCategoryChip(catId: Int?, label: String): TextView {
        val isActive = activeCategory == catId
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((14 * density).toInt(), (8 * density).toInt(),
                (14 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (isActive) {
                    setColor(Palette.accent(context))
                } else {
                    setColor(Palette.bgCell(context))
                    setStroke((1 * density).toInt(), Palette.separator(context))
                }
            }
            setTextColor(
                if (isActive) Color.parseColor("#0D0D1A")
                else Palette.textPrimary(context),
            )
            isClickable = true; isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = (8 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                // Toggle: tapping the active chip clears the filter.
                activeCategory = if (isActive) null else catId
                refreshCategoryChips()
                applyFilters()
            }
        }
    }


    // ── Dismiss gestures (option E: grabber + pull-at-top) ────────────
    //
    // Two independent drag paths, both follow the finger in real time:
    //   1. Grabber pill at the top: drag anywhere in its 24dp strip
    //   2. List drag-down: only when the RecyclerView is scrolled to position 0
    //      AND the pull exceeds touchSlop — mirrors iOS sheet behaviour.
    //
    // On release past 25% of panel height OR a fast downward fling, the panel
    // is dismissed. Otherwise it springs back to translationY = 0.

    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    private var dragStartY = 0f
    private var listDragActive = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                listDragActive = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Steal the event ONLY if: list can't scroll up (already at top)
                // AND the user has pulled down past touchSlop. This leaves all
                // normal scrolling unaffected.
                val dy = ev.rawY - dragStartY
                if (!listDragActive && dy > touchSlop && !grid.canScrollVertically(-1)) {
                    listDragActive = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!listDragActive) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationY = (ev.rawY - dragStartY).coerceAtLeast(0f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listDragActive = false
                finishDrag(ev.rawY - dragStartY)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** Commit the drag — dismiss if past 25% of height, else spring back. */
    private fun finishDrag(deltaY: Float) {
        val threshold = height * 0.25f
        if (deltaY > threshold) {
            onRequestHide?.invoke()
        } else {
            animate().translationY(0f).setDuration(180L)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    /** Call on show to load / reload the installed-app list. */
    fun refreshApps() {
        all.clear()
        all.addAll(loadApps())
        refreshCategoryChips()
        applyFilters()
    }

    private val spring by lazy {
        androidx.dynamicanimation.animation.SpringAnimation(this,
            androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y)
            .setSpring(
                androidx.dynamicanimation.animation.SpringForce()
                    .setStiffness(androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW)
                    .setDampingRatio(0.78f)
            )
    }

    fun show() {
        if (visibility == View.VISIBLE) return
        refreshApps()
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        translationY = h
        visibility = View.VISIBLE
        spring.cancel()
        spring.animateToFinalPosition(0f)
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        val dest = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        spring.cancel()
        spring.addEndListener(object : androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(anim: androidx.dynamicanimation.animation.DynamicAnimation<*>, canceled: Boolean, value: Float, velocity: Float) {
                if (!canceled && value >= dest - 1f) visibility = View.GONE
                spring.removeEndListener(this)
            }
        })
        spring.animateToFinalPosition(dest)
        // If the search field had focus, the IME would otherwise hang around
        // covering the home screen during the slide-down animation.
        if (search.hasFocus() || search.text.isNotEmpty()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
        }
        search.setText("")
    }

    private fun loadApps(): List<AppEntry> {
        // [AppRegistry] caches the heavy queryIntentActivities + per-app
        // loadIcon work once at activity startup, on a background thread.
        // First open of the drawer used to block ~500ms–2s on this; now
        // it's typically a no-op cache hit by the time the swipe finishes.
        val prefs = com.iappyx.launcher.LauncherPrefs(context)
        return AppRegistry.apps(context).map { app ->
            AppEntry(
                // Honour a per-app custom label — flows into display, A–Z
                // sort/rail, and search filtering since they all key off it.
                label = prefs.appLabel(app.packageName, app.label).toString(),
                packageName = app.packageName,
                activityName = app.activityName ?: "",
                icon = app.icon,
                category = app.category,
            )
        }
    }

    /** Exclusive end index of the "Recent" section in [rows]. Items whose
     *  position is < this belong to Recent and should map to '#' on the rail. */
    private var recentSectionEnd: Int = 0

    private fun toRows(apps: List<AppEntry>): List<Row> {
        val out = mutableListOf<Row>()

        // "Recent" section at the top (2 rows of 4 = 8 entries at spanCount=4).
        // Only shown when not actively searching (search replaces the full list).
        val query = try { search.text?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
        if (query.isEmpty()) {
            val recentPkgs = com.iappyx.launcher.LauncherPrefs(context).recentApps()
            val byPkg = apps.associateBy { it.packageName }
            val recentEntries = recentPkgs.mapNotNull { byPkg[it] }.take(8)
            if (recentEntries.isNotEmpty()) {
                out.add(Row.Header("Recent"))
                recentEntries.forEach { out.add(Row.App(it)) }
            }
        }
        recentSectionEnd = out.size

        var current = ""
        for (e in apps) {
            val c = firstLetter(e.label)
            if (c != current) { out.add(Row.Header(c)); current = c }
            out.add(Row.App(e))
        }
        return out
    }

    private fun firstLetter(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    private fun updateVisibleLetters() {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        val seen = mutableSetOf<Char>()
        for (pos in first..last) {
            val r = rows.getOrNull(pos) ?: continue
            // Items within the Recent section map to '#' on the rail — their
            // first letters would otherwise light up A–Z arbitrarily based on
            // whatever the user has recently launched.
            if (pos < recentSectionEnd) { seen.add('#'); continue }
            val letter = when (r) {
                is Row.Header -> r.letter.first()
                is Row.App -> firstLetter(r.entry.label).first()
            }
            seen.add(letter)
        }
        rail.visibleLetters = seen
    }

    private fun onAppClick(entry: AppEntry) {
        context.packageManager.getLaunchIntentForPackage(entry.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            com.iappyx.launcher.LauncherPrefs(context).recordAppLaunch(entry.packageName)
            // APPLOCK: gate locked packages behind biometric / device
            // credential. onRequestHide only fires on a successful
            // launch — cancelled auth leaves the drawer open so the
            // user can try a different app.
            val act = context as? android.app.Activity
            if (act != null) {
                com.iappyx.launcher.applock.AppLockManager.launchApp(
                    act, entry.packageName, intent,
                ) { onRequestHide?.invoke() }
            } else {
                context.startActivity(intent)
                onRequestHide?.invoke()
            }
        }
    }

    private fun onAppLongPress(cardView: View, entry: AppEntry) {
        cardView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showContextPopup(cardView, entry)
    }

    /** Called when the user moves their finger after the long-press popup
     *  has appeared. Dismiss the popup, hide the drawer, and start a system
     *  drag carrying the app's package + activity so the home grid's drop
     *  listener can place it. */
    private fun onAppLongPressDrag(cardView: View, entry: AppEntry) {
        currentContextPopup?.dismiss()
        currentContextPopup = null
        startDragFor(cardView, entry)
        onRequestHide?.invoke()
    }

    private fun showContextPopup(anchor: View, entry: AppEntry) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            elevation = 12 * density
        }

        val popup = PopupWindow(
            container,
            (220 * density).toInt(),
            LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * density
            setOnDismissListener {
                if (currentContextPopup === this) currentContextPopup = null
            }
        }
        currentContextPopup = popup

        fun row(
            label: String,
            subtitle: String? = null,
            iconDrawable: android.graphics.drawable.Drawable? = null,
            onClick: () -> Unit,
        ) {
            // Outer row is HORIZONTAL when there's a leading icon (shortcut
            // entries), VERTICAL otherwise (standard actions stay text-only).
            val outer = LinearLayout(context).apply {
                orientation = if (iconDrawable != null) LinearLayout.HORIZONTAL
                              else LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
                background = android.content.res.ColorStateList.valueOf(Palette.separator(context)).let {
                    android.graphics.drawable.RippleDrawable(it, null, null)
                }
                setOnClickListener { popup.dismiss(); onClick() }
            }
            if (iconDrawable != null) {
                outer.addView(android.widget.ImageView(context).apply {
                    setImageDrawable(iconDrawable)
                    val s = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginEnd = (10 * density).toInt()
                    }
                })
            }
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    if (iconDrawable != null) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (iconDrawable != null) 1f else 0f,
                )
            }
            textCol.addView(TextView(context).apply {
                text = label
                setTextColor(Palette.textPrimary(context))
                textSize = 14f
            })
            if (subtitle != null) textCol.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Palette.textSecondary(context))
                textSize = 11f
            })
            outer.addView(textCol)
            container.addView(outer)
        }

        // App Shortcuts (static + dynamic). Only available when this
        // launcher is the active default home — LauncherApps.getShortcuts
        // throws SecurityException otherwise. We try, swallow on failure,
        // and fall through to the standard rows.
        val shortcuts = fetchAppShortcuts(entry.packageName)
        val launcherApps = if (shortcuts.isNotEmpty()) {
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? android.content.pm.LauncherApps
        } else null
        val iconDpi = resources.displayMetrics.densityDpi
        for (sc in shortcuts) {
            val label = (sc.shortLabel ?: sc.longLabel
                ?: context.getString(R.string.drawer_shortcut_fallback_label)).toString()
            val icon = try {
                launcherApps?.getShortcutIconDrawable(sc, iconDpi)
            } catch (_: Throwable) { null }
            row(label, iconDrawable = icon) { startAppShortcut(sc, anchor) }
        }
        if (shortcuts.isNotEmpty()) {
            // Hairline divider between shortcuts and the standard actions.
            container.addView(View(context).apply {
                setBackgroundColor(Palette.separator(context))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt(),
                )
                lp.topMargin = (4 * density).toInt()
                lp.bottomMargin = (4 * density).toInt()
                layoutParams = lp
            })
        }

        // "Drag to home screen" is now implicit — keep holding after the
        // popup appears and start moving; see Adapter's touch listener.
        row(context.getString(R.string.drawer_action_add_to_home)) {
            onAddToHome?.invoke(entry); onRequestHide?.invoke()
        }
        row(context.getString(R.string.drawer_action_app_info)) { openAppInfo(entry.packageName) }
        row(context.getString(R.string.drawer_action_uninstall)) { uninstallApp(entry.packageName) }

        popup.showAsDropDown(anchor, 0, -(anchor.height * 0.2f).toInt())
        popup.themeContent()

        // Pop-in animation: scale + fade, anchored to the top (where the anchor sits).
        container.alpha = 0f
        container.scaleX = 0.88f
        container.scaleY = 0.88f
        container.pivotX = container.width / 2f
        container.pivotY = 0f
        container.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
    }

    private fun startDragFor(anchor: View, entry: AppEntry) {
        // If the user was searching, the soft keyboard is still up — it would
        // cover the lower half of the home screen during the drag. Drop focus
        // and hide the IME before starting the drag.
        if (search.hasFocus() || search.text.isNotEmpty()) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
        }
        // Start a single-window drag: ClipData carries the package/activity so the
        // OnDragListener on home grids can place the icon wherever the user drops.
        val clip = ClipData(
            ClipDescription(DRAG_LABEL, arrayOf(DRAG_MIME)),
            ClipData.Item(entry.packageName),
        ).apply {
            addItem(ClipData.Item(entry.activityName))
        }
        val shadow = View.DragShadowBuilder(anchor)
        if (Build.VERSION.SDK_INT >= 24) {
            anchor.startDragAndDrop(clip, shadow, entry, 0)
        } else {
            @Suppress("DEPRECATION")
            anchor.startDrag(clip, shadow, entry, 0)
        }
    }

    private fun openAppInfo(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Fetch static + dynamic app shortcuts for [packageName]. Returns an
     *  empty list when:
     *    - the launcher isn't the user's active home (LauncherApps refuses)
     *    - the device is below API 25
     *    - the target app declares no shortcuts
     *    - any unexpected error (best-effort: don't break the long-press
     *      menu just because shortcuts couldn't be resolved). */
    private fun fetchAppShortcuts(
        packageName: String,
    ): List<android.content.pm.ShortcutInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? android.content.pm.LauncherApps ?: return emptyList()
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = android.content.pm.LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(
                    android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            val list = launcherApps.getShortcuts(query, android.os.Process.myUserHandle())
                ?: return emptyList()
            // Show the most prominent ones first; cap so the popup doesn't
            // overflow the screen.
            list.sortedBy { it.rank }.take(5)
        } catch (_: Throwable) { emptyList() }
    }

    /** Launch one app shortcut. [anchor] is the popup row that triggered it
     *  — the system uses its location as the source bounds for the launch
     *  animation. Failures (uncommon) get a toast. */
    private fun startAppShortcut(
        shortcut: android.content.pm.ShortcutInfo, anchor: View,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? android.content.pm.LauncherApps ?: return
            val rect = android.graphics.Rect()
            anchor.getGlobalVisibleRect(rect)
            launcherApps.startShortcut(shortcut, rect, null)
            onRequestHide?.invoke()
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                context, R.string.drawer_shortcut_launch_failed_toast,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun uninstallApp(pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            // System apps and bundled OEM packages refuse uninstall — fall
            // back to App Info where the user can disable / force-stop.
            android.widget.Toast.makeText(
                context, R.string.drawer_uninstall_blocked_toast,
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** The RecyclerView adapter. Rows are either Header (full-width letter) or
     *  App (single grid cell).
     *
     *  Touch flow on an App cell (the "haptic-touch / iOS" pattern):
     *   - Tap → onClick (launch the app).
     *   - Long-press (500ms held still) → onLong (host shows the context popup).
     *   - **Continued press + finger movement** after the popup appears →
     *     onLongPressDrag (host dismisses popup, hides drawer, starts a system
     *     DnD so the user drags the icon onto the home screen in one fluid
     *     gesture).
     *   - Release without moving → popup remains visible for menu use.
     *   - Movement BEFORE long-press fires → cancels the long-press and lets
     *     the RecyclerView scroll normally.
     */
    private class Adapter(
        private val items: List<Row>,
        private val onClick: (AppEntry) -> Unit,
        private val onLong: (View, AppEntry) -> Unit,
        private val onLongPressDrag: (View, AppEntry) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Header -> TYPE_HEADER; is Row.App -> TYPE_APP
        }
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            return if (viewType == TYPE_HEADER) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                }
                val letter = TextView(ctx).apply {
                    setTextColor(Palette.accent(ctx))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding((4 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                }
                val line = View(ctx).apply {
                    setBackgroundColor(Palette.separator(context))
                    layoutParams = LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f)
                }
                row.addView(letter); row.addView(line)
                HeaderHolder(row, letter)
            } else {
                val root = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    val p = (8 * dp).toInt()
                    setPadding((6 * dp).toInt(), p, (6 * dp).toInt(), p)
                    isClickable = true; isFocusable = true
                }
                val icon = ImageView(ctx).apply {
                    val size = (56 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val label = TextView(ctx).apply {
                    textSize = 11f
                    setTextColor(Palette.textPrimary(ctx))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(0, (6 * dp).toInt(), 0, 0)
                }
                root.addView(icon); root.addView(label)
                AppHolder(root, icon, label)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is Row.Header -> {
                    // Internal sentinel "Recent" → localized label.
                    // A–Z / # letters render as-is.
                    val h = holder as HeaderHolder
                    h.letter.text =
                        if (r.letter == "Recent") h.itemView.context.getString(R.string.drawer_recent_section)
                        else r.letter
                }
                is Row.App -> (holder as AppHolder).apply {
                    // Route the system drawable through IconMask so the
                    // drawer matches the home grid's icon shape + active
                    // filter (greyscale / sepia / mono accent / …). The
                    // mask cache is keyed on (pkg, sizePx, filterSlug) so
                    // re-binds are O(1).
                    val bindCtx = root.context
                    val sizePx = (96 * bindCtx.resources.displayMetrics.density).toInt()
                    val spec = com.iappyx.launcher.cells.IconFilterRegistry.resolve(
                        bindCtx, com.iappyx.launcher.LauncherPrefs(bindCtx).iconFilter,
                    )
                    icon.setImageBitmap(
                        com.iappyx.launcher.cells.IconMask.render(
                            r.entry.packageName, r.entry.icon, sizePx, spec,
                        ),
                    )
                    label.text = r.entry.label
                    val entry = r.entry
                    val ctx = root.context
                    val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
                    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
                    // Per-binding state. Wrapped in arrays so the lambda can mutate.
                    val downX = floatArrayOf(0f); val downY = floatArrayOf(0f)
                    val longPressFired = booleanArrayOf(false)
                    val dragStarted = booleanArrayOf(false)
                    val pendingLongPress = arrayOfNulls<Runnable>(1)
                    // Clear any prior listener bindings (RecyclerView reuses views).
                    root.setOnClickListener(null)
                    root.setOnLongClickListener(null)
                    root.setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX[0] = ev.x; downY[0] = ev.y
                                longPressFired[0] = false
                                dragStarted[0] = false
                                val r2 = Runnable {
                                    if (!dragStarted[0]) {
                                        longPressFired[0] = true
                                        // Now that the popup is up, the gesture
                                        // belongs to us — block the RecyclerView
                                        // (and any other ancestor) from
                                        // intercepting further moves as a scroll.
                                        v.parent?.requestDisallowInterceptTouchEvent(true)
                                        onLong(v, entry)
                                    }
                                }
                                pendingLongPress[0] = r2
                                v.postDelayed(r2, longPressTimeout)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = ev.x - downX[0]
                                val dy = ev.y - downY[0]
                                val moved = kotlin.math.sqrt(dx * dx + dy * dy) > touchSlop
                                if (longPressFired[0]) {
                                    // Popup is up — finger moved → start drag.
                                    if (moved && !dragStarted[0]) {
                                        dragStarted[0] = true
                                        onLongPressDrag(v, entry)
                                    }
                                    // Always consume after long-press fires so
                                    // the RecyclerView doesn't hijack the
                                    // gesture as a vertical scroll.
                                    return@setOnTouchListener true
                                } else if (moved) {
                                    // Pre-long-press scroll — cancel the timer
                                    // and let the parent RecyclerView scroll.
                                    pendingLongPress[0]?.let { v.removeCallbacks(it) }
                                    pendingLongPress[0] = null
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                pendingLongPress[0]?.let { v.removeCallbacks(it) }
                                pendingLongPress[0] = null
                                if (!longPressFired[0] && !dragStarted[0]) {
                                    val dx = ev.x - downX[0]
                                    val dy = ev.y - downY[0]
                                    if (kotlin.math.sqrt(dx * dx + dy * dy) <= touchSlop) {
                                        v.performClick()  // for accessibility
                                        onClick(entry)
                                    }
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                pendingLongPress[0]?.let { v.removeCallbacks(it) }
                                pendingLongPress[0] = null
                            }
                        }
                        // Don't consume — return false so the RecyclerView can
                        // continue scrolling for non-touched events. We've
                        // already done what we need.
                        false
                    }
                }
            }
        }

        class HeaderHolder(val root: View, val letter: TextView) : RecyclerView.ViewHolder(root)
        class AppHolder(val root: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)
    }
}
