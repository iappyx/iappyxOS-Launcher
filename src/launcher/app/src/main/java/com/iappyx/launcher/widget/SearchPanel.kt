/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.search.SearchResult
import com.iappyx.launcher.search.SearchSources

/**
 * Full-screen universal search overlay. Slides in from the top when the user
 * swipes down on the home screen. Shows recent queries + frequent apps; when a
 * query is typed, replaces them with categorized results (Apps, Contacts,
 * Settings).
 */
class SearchPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val search: EditText
    private val recentsRow: LinearLayout
    private val frequentGrid: RecyclerView
    private val resultsList: RecyclerView
    private val beforeSearchBlock: LinearLayout
    private val grabberWrap: FrameLayout

    // Both delegate to [AppRegistry] so the heavy package-query + icon-load
    // happens once at activity startup on a background thread, and the
    // first open of search reuses that cache instead of stalling the main
    // thread while the user waits for results.
    private val appsCache get() = AppRegistry.apps(context)
    private val settingsCache get() = AppRegistry.settings(context)

    private val prefs = LauncherPrefs(context)
    private val handler = Handler(Looper.getMainLooper())

    private val frequentAdapter = FrequentAppsAdapter(emptyList()) { entry ->
        launchApp(entry.packageName)
    }
    private val resultsAdapter = ResultsAdapter()

    /** Every plugin-result WebView ever spawned this session. They're held by
     *  pooled RecyclerView holders and were never destroyed — a native leak +
     *  retained renderer per plugin search (H3-1). Destroyed wholesale when the
     *  panel closes (see [destroyPluginWebViews]); we can't destroy on recycle
     *  because the holders are pooled and reused within a session. */
    private val pluginWebViews = mutableListOf<android.webkit.WebView>()

    /** Called when the panel wants to dismiss itself. */
    var onRequestHide: (() -> Unit)? = null

    init {
        setBackgroundColor(Palette.bgHome(context))
        isClickable = true
        fitsSystemWindows = false

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Include the IME's height in the bottom padding so the
            // results list isn't covered by the soft keyboard when the
            // user is typing. max(navBar, ime) — only one can be
            // visible at a time in practice, but max() handles the
            // edge where the IME is shorter than the nav bar.
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // Header: title + close
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (20 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        val title = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.search_title)
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = ImageView(context).apply {
            setImageResource(com.iappyx.launcher.R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.textPrimary(context))
            // Full opacity + 48 dp tap target — close is now the only
            // dismiss path while a search query is active, so it needs
            // to read as primary action, not a faded secondary.
            alpha = 1f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            setOnClickListener { onRequestHide?.invoke() }
            val s = (48 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            contentDescription = context.getString(com.iappyx.launcher.R.string.search_close_cd)
        }
        header.addView(title); header.addView(close)
        root.addView(header)

        // Search input
        search = EditText(context).apply {
            setHint(com.iappyx.launcher.R.string.search_hint)
            setHintTextColor(Palette.textSecondary(context))
            setTextColor(Palette.textPrimary(context))
            textSize = 16f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            imeOptions = EditorInfo.IME_ACTION_GO
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins((20 * density).toInt(), 0, (20 * density).toInt(), (12 * density).toInt())
            layoutParams = lp
        }
        root.addView(search)

        // Before-search block (recents + frequent apps).
        beforeSearchBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        // Recent searches row
        val recentsWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        }
        val recentsLabel = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.search_recent_section)
            setTextColor(Palette.accent(context))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val p = (20 * density).toInt()
            setPadding(p, (8 * density).toInt(), p, (6 * density).toInt())
        }
        val recentsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (12 * density).toInt()
            layoutParams = lp
        }
        recentsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val sidePad = (20 * density).toInt()
            setPadding(sidePad, 0, sidePad, 0)
        }
        recentsScroll.addView(recentsRow)
        recentsWrap.addView(recentsLabel)
        recentsWrap.addView(recentsScroll)
        beforeSearchBlock.addView(recentsWrap)

        // Frequent apps grid
        val frequentLabel = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.search_frequent_apps_section)
            setTextColor(Palette.accent(context))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val p = (20 * density).toInt()
            setPadding(p, (8 * density).toInt(), p, (6 * density).toInt())
        }
        beforeSearchBlock.addView(frequentLabel)
        frequentGrid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = frequentAdapter
            val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            val p = (12 * density).toInt()
            setPadding(p, 0, p, (16 * density).toInt())
            clipToPadding = false
            layoutParams = lp
        }
        beforeSearchBlock.addView(frequentGrid)
        root.addView(beforeSearchBlock)

        // Results list (hidden until query typed)
        resultsList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = resultsAdapter
            val sidePad = (20 * density).toInt()
            setPadding(sidePad, 0, sidePad, (24 * density).toInt())
            clipToPadding = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(resultsList)

        // Grabber at the BOTTOM — panel emerges from the top, drag down-handle up to collapse.
        grabberWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (24 * density).toInt())
            isClickable = true; isFocusable = true
        }
        val grabberPill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2 * density
                setColor(Color.parseColor("#55FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams((40 * density).toInt(), (4 * density).toInt(), Gravity.CENTER)
        }
        grabberWrap.addView(grabberPill)
        root.addView(grabberWrap)

        addView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                handler.removeCallbacksAndMessages(searchToken)
                handler.postAtTime({ runSearch(s?.toString().orEmpty()) }, searchToken,
                    android.os.SystemClock.uptimeMillis() + 80)
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isGo || isEnter) {
                val q = search.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) {
                    prefs.recordSearch(q)
                    launchTopResult()
                }
                true
            } else false
        }

        wireDragToClose()
    }

    // ── Entry points from LauncherActivity ───────────────────

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
        refreshBeforeSearch()
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        translationY = -h
        visibility = View.VISIBLE
        spring.cancel()
        spring.animateToFinalPosition(0f)
        search.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Destroy every plugin-result WebView and discard the holders that
     *  referenced them, so a future search builds fresh ones instead of
     *  reusing a destroyed WebView. Called when the panel finishes closing. */
    private fun destroyPluginWebViews() {
        if (pluginWebViews.isEmpty()) return
        for (wv in pluginWebViews) {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                try { wv.removeJavascriptInterface("iappyxResultHost") } catch (_: Throwable) {}
                try { wv.removeJavascriptInterface("iappyxPlugins") } catch (_: Throwable) {}
                wv.destroy()
            } catch (_: Throwable) {}
        }
        pluginWebViews.clear()
        webViewToHit.clear()
        // Drop all pooled/cached holders (they hold the now-dead WebViews).
        resultsList.adapter = null
        resultsList.adapter = resultsAdapter
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        // Drop any in-flight debounced search — without this, an 80ms-delayed
        // runSearch() can fire after we've already started the dismiss animation
        // and dispatch results into a panel that's GONE.
        handler.removeCallbacksAndMessages(searchToken)
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(search.windowToken, 0)
        val dest = -h
        spring.cancel()
        spring.addEndListener(object : androidx.dynamicanimation.animation.DynamicAnimation.OnAnimationEndListener {
            override fun onAnimationEnd(anim: androidx.dynamicanimation.animation.DynamicAnimation<*>, canceled: Boolean, value: Float, velocity: Float) {
                if (!canceled && value <= dest + 1f) {
                    visibility = View.GONE
                    // Search is done — free the plugin-result WebViews so they
                    // don't accumulate across sessions.
                    destroyPluginWebViews()
                }
                spring.removeEndListener(this)
            }
        })
        spring.animateToFinalPosition(dest)
        search.setText("")
    }

    // ── Before-search content (recents + frequent apps) ─────

    private fun refreshBeforeSearch() {
        rebuildRecents()
        val freqPkgs = prefs.frequentApps(8)
        val all = appsCache
        val byPkg = all.associateBy { it.packageName }
        val frequent = freqPkgs.mapNotNull { byPkg[it] }
        frequentAdapter.setItems(frequent)
    }

    private fun rebuildRecents() {
        recentsRow.removeAllViews()
        val items = prefs.recentSearches()
        if (items.isEmpty()) {
            recentsRow.addView(TextView(context).apply {
                setText(com.iappyx.launcher.R.string.search_no_recent_yet)
                setTextColor(Palette.textSecondary(context))
                textSize = 12f
            })
            return
        }
        for (q in items) recentsRow.addView(makeSearchChip(q))
    }

    private fun makeSearchChip(query: String): View {
        val dp = density
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Palette.bgCell(context))
                setStroke((1 * dp).toInt(), Palette.separator(context))
            }
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.rightMargin = (8 * dp).toInt()
            layoutParams = lp
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { search.setText(query); search.setSelection(query.length) }
        }
        val chipText = TextView(context).apply {
            this.text = query
            setTextColor(Palette.textPrimary(context))
            textSize = 13f
        }
        val removeBtn = ImageView(context).apply {
            setImageResource(com.iappyx.launcher.R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Palette.textSecondary(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val s = (16 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = (8 * dp).toInt()
                marginEnd = (4 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true; isFocusable = true
            contentDescription = context.getString(
                com.iappyx.launcher.R.string.search_remove_recent_cd_format, query,
            )
            setOnClickListener {
                prefs.removeSearch(query)
                rebuildRecents()
            }
        }
        chip.addView(chipText); chip.addView(removeBtn)
        return chip
    }

    // ── Live search ──────────────────────────────────────────

    private val searchToken = Any()

    /** PLUGINS: current plugin-search query token. Cancelled when the
     *  user types another character so late replies don't pollute the
     *  new query's results. */
    private var pluginQueryToken: com.iappyx.launcher.plugins.PluginSearchAggregator.Cancellable? = null
    /** PLUGINS: the synchronous base rows (apps + contacts + settings +
     *  math + web). Kept so the async plugin handler can re-merge
     *  without re-running the sync sources. */
    private var baseRows: List<ResultRow> = emptyList()
    /** PLUGINS: latest plugin hits to merge. Sorted by aggregator. */
    private var pluginHits: List<com.iappyx.launcher.plugins.PluginSearchAggregator.Hit> = emptyList()

    /** Called from LauncherActivity after READ_CONTACTS grant so results refresh. */
    fun rerunCurrentSearch() { runSearch(search.text?.toString().orEmpty()) }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            // Empty query → results list is about to go away. Drop
            // any plugin-result WebView ↔ Hit cross-links so the map
            // doesn't retain WebViews + Hit payloads across the
            // panel's lifetime.
            webViewToHit.clear()
            resultsList.visibility = View.GONE
            beforeSearchBlock.visibility = View.VISIBLE
            return
        }
        beforeSearchBlock.visibility = View.GONE
        resultsList.visibility = View.VISIBLE

        val results = mutableListOf<ResultRow>()
        // Math first — surfaces "= 42" right at the top when the query is an
        // expression. Skipped silently when it doesn't parse OR when the
        // input doesn't smell like math (so plain text/numeric searches
        // don't get a noisy math row).
        com.iappyx.launcher.search.MathEvaluator.evaluate(q)?.let {
            results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_calculator)))
            results.add(ResultRow.MathResult(q, it.display))
        }
        val apps = SearchSources.searchApps(appsCache, q)
        if (apps.isNotEmpty()) {
            results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_apps)))
            apps.forEach { results.add(ResultRow.App(it)) }
        }
        val hasContacts = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasContacts) {
            val contacts = SearchSources.searchContacts(context, q)
            if (contacts.isNotEmpty()) {
                results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_contacts)))
                contacts.forEach { results.add(ResultRow.Contact(it)) }
            }
        } else {
            // Show a single CTA row so the user knows how to enable contacts.
            results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_contacts)))
            results.add(ResultRow.GrantContactsPerm)
        }
        val settings = SearchSources.searchSettings(settingsCache, q)
        if (settings.isNotEmpty()) {
            results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_settings)))
            settings.forEach { results.add(ResultRow.Setting(it)) }
        }
        // Always-shown bottom fallback. Lets users escape to web search even
        // when there ARE local matches — handy when the local match isn't
        // the one they wanted (e.g. searching for an article, recipe, etc.).
        results.add(ResultRow.Header(context.getString(com.iappyx.launcher.R.string.search_section_web)))
        results.add(ResultRow.WebSearch(q))
        // PLUGINS: snapshot the synchronous results so the async plugin
        // handler can merge into the same list without re-running these
        // sources. Plugin hits are inserted as their own section ABOVE
        // the web-search fallback once they arrive.
        baseRows = results.toList()
        // Cancel any in-flight plugin query — typing another character
        // invalidates old results.
        pluginQueryToken?.cancel()
        pluginHits = emptyList()
        // Debounce the plugin fan-out by 250ms so type-ahead doesn't
        // hammer plugins. Sync results render immediately; plugin rows
        // appear ~250-750ms after a settled query.
        handler.removeCallbacksAndMessages(searchToken)
        handler.postAtTime(
            { firePluginSearch(q) },
            searchToken,
            android.os.SystemClock.uptimeMillis() + 250L,
        )
        resultsAdapter.setItems(results)
    }

    /** PLUGINS: fan-out + incremental merge. Called from the debounced
     *  runSearch. */
    private fun firePluginSearch(query: String) {
        pluginQueryToken = com.iappyx.launcher.plugins.PluginSearchAggregator.query(
            context, query,
            onUpdate = { hits ->
                pluginHits = hits
                mergeAndRender()
            },
            onDone = { /* final state already pushed via last onUpdate */ },
        )
    }

    /** PLUGINS: combine the synchronous base rows + the latest plugin
     *  hits into one ResultRow list. Plugin section is inserted just
     *  BEFORE the web-search fallback so the last row is always the
     *  "go to web" escape hatch. Plugins that returned 0 visible hits
     *  simply don't appear — keep the panel uncluttered. */
    private fun mergeAndRender() {
        if (pluginHits.isEmpty()) {
            resultsAdapter.setItems(baseRows)
            return
        }
        val out = mutableListOf<ResultRow>()
        val webTail = baseRows.takeLast(2)
        val baseHead = baseRows.dropLast(2)
        out.addAll(baseHead)
        // Group hits by plugin so each section header appears at most
        // once, even when recent-acted hits from different plugins get
        // interleaved at the top of the sorted list. pluginHits is
        // already sorted by (recentRank, pluginId, title); groupBy keeps
        // first-encounter order, so each group's position reflects its
        // best recentRank, and within-group order preserves recents-first
        // then alphabetical.
        val grouped = pluginHits.groupBy { it.pluginId }
        for ((_, hits) in grouped) {
            out.add(ResultRow.Header(hits.first().pluginName))
            hits.forEach { out.add(ResultRow.PluginEntity(it)) }
        }
        out.addAll(webTail)
        resultsAdapter.setItems(out)
    }

    private fun launchTopResult() {
        val first = (resultsAdapter).firstLaunchable() ?: return
        when (first) {
            is SearchResult.App -> launchApp(first.packageName)
            is SearchResult.Contact -> openContact(first)
            is SearchResult.Setting -> launchSettings(first)
        }
    }

    private fun launchApp(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        LauncherPrefs(context).recordAppLaunch(pkg)
        // APPLOCK: only hide the search panel after a successful auth /
        // unlocked launch. Cancelled prompts leave the search visible.
        val act = context as? android.app.Activity
        if (act != null) {
            com.iappyx.launcher.applock.AppLockManager.launchApp(
                act, pkg, intent,
            ) { onRequestHide?.invoke() }
        } else {
            context.startActivity(intent)
            onRequestHide?.invoke()
        }
    }

    private fun openContact(c: SearchResult.Contact) {
        val uri = if (c.lookupKey != null)
            android.net.Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "${c.lookupKey}/${c.contactId}",
            )
        else android.net.Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_URI, c.contactId.toString(),
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent); onRequestHide?.invoke() } catch (_: Exception) {}
    }

    private fun launchSettings(s: SearchResult.Setting) {
        // Action-based launches first (the data class's toIntent prefers them
        // when available); component-based as fallback. Action launches are
        // guaranteed by Android; component launches fail on Android 12+ for
        // internal Settings activities — surface that to the user instead of
        // failing silently the way the old try/catch did.
        try {
            context.startActivity(s.toIntent())
            onRequestHide?.invoke()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                context.getString(
                    com.iappyx.launcher.R.string.search_settings_blocked_toast_format, s.label,
                ),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ── Drag-to-close ────────────────────────────────────────
    //
    // Gesture tracked at [dispatchTouchEvent] so it works from anywhere in the
    // panel — including over the RecyclerView, the search field, the grabber,
    // the empty space between. Children receive their normal touch stream
    // until we decide the user is clearly trying to close the panel (upward
    // drag past 2×touchSlop, and — if results are showing — only when the list
    // is scrolled to the bottom so we don't steal from normal list scrolling).

    private val touchSlop by lazy { android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private var dragStartY = 0f
    private var dragging = false

    private fun wireDragToClose() { /* no-op; see dispatchTouchEvent */ }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val upward = dragStartY - ev.rawY
                    // Drag-to-dismiss is ONLY active when the user hasn't
                    // started typing (results list is hidden). Once the
                    // list is visible, the only dismiss path is the
                    // close button — this avoids the "scroll to bottom,
                    // try to scroll again, panel disappears" trap caused
                    // by the previous canScrollVertically-based gate.
                    // Per feedback_touch_arbitration: don't invent more
                    // heuristics; delete the buggy ones.
                    val canDismissDrag = resultsList.visibility != View.VISIBLE
                    if (upward > touchSlop * 2 && canDismissDrag) {
                        dragging = true
                        // Cancel the child's in-progress touch so it doesn't fire clicks.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (dragging) {
                    translationY = (ev.rawY - dragStartY).coerceAtMost(0f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    finishDrag(dragStartY - ev.rawY)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun finishDrag(upwardDistance: Float) {
        // 15% of panel height — keeps the existing scroll-vs-dismiss
        // disambiguation logic untouched, just lowers the commit point
        // so the drag feels more responsive. ~120 dp on a typical
        // phone, down from ~200 dp.
        val threshold = height * 0.15f
        if (upwardDistance > threshold) onRequestHide?.invoke()
        else animate().translationY(0f).setDuration(180L)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    // ── Adapters ─────────────────────────────────────────────

    private sealed class ResultRow {
        data class Header(val title: String) : ResultRow()
        data class App(val data: SearchResult.App) : ResultRow()
        data class Contact(val data: SearchResult.Contact) : ResultRow()
        data class Setting(val data: SearchResult.Setting) : ResultRow()
        /** Placeholder CTA shown in the Contacts section when READ_CONTACTS isn't granted. */
        object GrantContactsPerm : ResultRow()
        /** Inline calculator result. Tap to copy. */
        data class MathResult(val expression: String, val display: String) : ResultRow()
        /** Always-shown bottom row: tap to ACTION_WEB_SEARCH the current query. */
        data class WebSearch(val query: String) : ResultRow()
        /** PLUGINS: interactive search result rendered by a plugin.
         *  Inflates a tiny WebView with the plugin's bridge proxy
         *  attached, so toggles / sliders / play-buttons inside the
         *  result actually work without leaving search. */
        data class PluginEntity(val hit: com.iappyx.launcher.plugins.PluginSearchAggregator.Hit) : ResultRow()
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<ResultRow>()
        fun setItems(list: List<ResultRow>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun firstLaunchable(): SearchResult? {
            for (r in items) when (r) {
                is ResultRow.App -> return r.data
                is ResultRow.Contact -> return r.data
                is ResultRow.Setting -> return r.data
                else -> {}
            }
            return null
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is ResultRow.Header -> 0
            is ResultRow.App -> 1
            is ResultRow.Contact -> 2
            is ResultRow.Setting -> 3
            ResultRow.GrantContactsPerm -> 4
            is ResultRow.MathResult -> 5
            is ResultRow.WebSearch -> 6
            is ResultRow.PluginEntity -> 7
        }
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context; val dp = ctx.resources.displayMetrics.density
            return when (viewType) {
                0 -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (16 * dp).toInt(), 0, (6 * dp).toInt())
                        layoutParams = RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    val label = TextView(ctx).apply {
                        setTextColor(Palette.accent(ctx)); textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, 0, (8 * dp).toInt(), 0)
                    }
                    val line = View(ctx).apply {
                        setBackgroundColor(Palette.separator(context))
                        layoutParams = LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f)
                    }
                    row.addView(label); row.addView(line)
                    HeaderHolder(row, label)
                }
                7 -> PluginEntityHolder(buildPluginEntityCard(ctx))
                else -> GenericHolder(buildRowCard(ctx))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is ResultRow.Header -> (holder as HeaderHolder).label.text = r.title
                is ResultRow.App -> {
                    // Match the home grid / dock / drawer / folder icon
                    // treatment — same shape + active filter via IconMask.
                    // bindCard is shared with non-app rows (settings,
                    // contacts) so we mask here and hand it a wrapped
                    // BitmapDrawable, keeping bindCard surface-agnostic.
                    val ctx = holder.itemView.context
                    val sizePx = (96 * ctx.resources.displayMetrics.density).toInt()
                    val spec = com.iappyx.launcher.cells.IconFilterRegistry.resolve(
                        ctx, com.iappyx.launcher.LauncherPrefs(ctx).iconFilter,
                    )
                    val masked = com.iappyx.launcher.cells.IconMask.render(
                        r.data.packageName, r.data.icon, sizePx, spec,
                    )
                    val maskedDrawable = android.graphics.drawable.BitmapDrawable(
                        ctx.resources, masked,
                    )
                    bindCard(holder as GenericHolder, maskedDrawable, r.data.label, null) {
                        launchApp(r.data.packageName)
                    }
                }
                is ResultRow.Contact -> {
                    val ctx = holder.itemView.context
                    bindCard(holder as GenericHolder, null, r.data.label,
                        r.data.phone ?: ctx.getString(com.iappyx.launcher.R.string.search_contact_subtitle_fallback)) {
                        openContact(r.data)
                    }
                }
                is ResultRow.Setting -> bindCard(holder as GenericHolder,
                    null, r.data.label, r.data.subtitle) {
                    launchSettings(r.data)
                }
                ResultRow.GrantContactsPerm -> {
                    val ctx = holder.itemView.context
                    bindCard(holder as GenericHolder, null,
                        ctx.getString(com.iappyx.launcher.R.string.search_contacts_grant_label),
                        ctx.getString(com.iappyx.launcher.R.string.search_contacts_grant_hint)) {
                        requestContactsPermission()
                    }
                }
                is ResultRow.MathResult -> {
                    val ctx = holder.itemView.context
                    bindCard(holder as GenericHolder, null,
                        ctx.getString(com.iappyx.launcher.R.string.search_math_result_format, r.display),
                        ctx.getString(com.iappyx.launcher.R.string.search_math_tap_to_copy)) {
                        copyToClipboard(r.display)
                    }
                }
                is ResultRow.WebSearch -> {
                    val ctx = holder.itemView.context
                    bindCard(holder as GenericHolder, null,
                        ctx.getString(com.iappyx.launcher.R.string.search_web_format, r.query),
                        ctx.getString(com.iappyx.launcher.R.string.search_web_hint)) {
                        fireWebSearch(r.query)
                    }
                }
                is ResultRow.PluginEntity -> bindPluginEntity(holder as PluginEntityHolder, r.hit)
            }
        }
    }

    /** Holds the inline plugin-search result. Each row owns its own
     *  WebView; we DON'T pool them across the adapter because the
     *  contents are HTML-distinct per hit, and a freshly-loaded WebView
     *  is cheap relative to the rest of search (a single OkHttp call
     *  is more expensive than spawning a WebView). RecyclerView's view-
     *  type caching keeps the holder alive across rebinds within the
     *  same query, so each WebView only loads once per query. */
    private class PluginEntityHolder(val root: View) : RecyclerView.ViewHolder(root) {
        val titleView: TextView = root.findViewById(R.id_plugin_title)
        val subtitleView: TextView = root.findViewById(R.id_plugin_subtitle)
        val webView: android.webkit.WebView = root.findViewById(R.id_plugin_webview)
        var currentHitId: String? = null
    }
    /** Programmatic-id sentinels — RecyclerView is happy with View.generateViewId
     *  but we use stable constants so the holder's findViewById can map back. */
    private object R {
        val id_plugin_title = View.generateViewId()
        val id_plugin_subtitle = View.generateViewId()
        val id_plugin_webview = View.generateViewId()
    }

    private fun buildPluginEntityCard(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14f * dp
                setColor(Palette.bgCell(ctx))
                setStroke((1 * dp).toInt(), Palette.separator(ctx))
            }
            val lp = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (4 * dp).toInt()
            lp.bottomMargin = (4 * dp).toInt()
            layoutParams = lp
            setPadding((12 * dp).toInt(), (10 * dp).toInt(),
                       (12 * dp).toInt(), (10 * dp).toInt())
        }
        // Title row — plugin name + title from the result.
        val title = TextView(ctx).apply {
            id = R.id_plugin_title
            setTextColor(Palette.textPrimary(ctx))
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(ctx).apply {
            id = R.id_plugin_subtitle
            setTextColor(Palette.textSecondary(ctx))
            textSize = 11f
        }
        // Tiny WebView body. Sandbox is the same as a widget cell — the
        // plugin author already controls this code, no new trust surface.
        @android.annotation.SuppressLint("SetJavaScriptEnabled")
        val wv = android.webkit.WebView(ctx).apply {
            id = R.id_plugin_webview
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            isLongClickable = false
            isHapticFeedbackEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (80 * dp).toInt(),
            )
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(wv)
        pluginWebViews.add(wv)
        return root
    }

    /** PLUGINS: bumped whenever the user invokes a plugin call from a
     *  search result. Drives [LauncherPrefs.bumpSearchRecent] so the
     *  next search promotes recently-acted-on rows to the top. Used to
     *  cross-link `PluginEntityHolder` ↔ its `Hit` at invoke time
     *  (because `SearchResultPluginsBridge` knows only the WebView). */
    private val webViewToHit = java.util.IdentityHashMap<android.webkit.WebView,
        com.iappyx.launcher.plugins.PluginSearchAggregator.Hit>()

    private fun bindPluginEntity(
        holder: PluginEntityHolder,
        hit: com.iappyx.launcher.plugins.PluginSearchAggregator.Hit,
    ) {
        holder.titleView.text = hit.title
        holder.subtitleView.text = if (hit.subtitle.isEmpty()) hit.pluginName
        else "${hit.pluginName} · ${hit.subtitle}"
        val dp = density
        // Resize to the plugin's requested height. Clamp 40-220 dp so a
        // misbehaving plugin can't push the rest of the results off-screen.
        val h = hit.heightDp.coerceIn(40, 220)
        holder.webView.layoutParams = holder.webView.layoutParams.apply {
            height = (h * dp).toInt()
        }
        // PLUGINS: cross-link this WebView → hit so the plugins bridge
        // can bump recents on invoke. Always set (cheap) even on rebind
        // since RecyclerView can reuse holders across hits.
        webViewToHit[holder.webView] = hit
        // PLUGINS: skeleton hits (async pending placeholders) get a
        // muted style + no WebView load. Re-binding when the resolved
        // version arrives goes through the html-load path below.
        if (hit.isPending) {
            holder.subtitleView.alpha = 0.5f
            holder.titleView.alpha = 0.6f
            holder.webView.loadDataWithBaseURL(
                "https://search.local/",
                """<!doctype html><html><body style="margin:0;background:transparent;
                color:#666;font:13px -apple-system,sans-serif;
                display:flex;align-items:center;justify-content:center;height:100%;">Loading…</body></html>""",
                "text/html", "utf-8", null,
            )
            holder.currentHitId = hit.id + "::pending"
            return
        }
        holder.subtitleView.alpha = 1f
        holder.titleView.alpha = 1f
        // Re-load only when the hit id changes — when the user types
        // another character and the same hit re-appears, leave the
        // existing WebView alone (preserves user interaction state like
        // slider position). The "::pending" suffix forces a reload when
        // a skeleton resolves to its real content.
        if (holder.currentHitId == hit.id) return
        holder.currentHitId = hit.id
        holder.webView.removeJavascriptInterface("iappyxResultHost")
        holder.webView.removeJavascriptInterface("iappyxPlugins")
        // Caller-side plugin proxy that does NOT depend on a WidgetHost
        // (we don't have one in the search panel context). Routes the
        // JS-side `iappyx.plugin('id').method(args)` straight to
        // PluginHost.invoke + delivers the reply back into THIS WebView's
        // window._iappyxCb[cbId].
        holder.webView.addJavascriptInterface(
            SearchResultPluginsBridge(holder.webView), "iappyxPlugins",
        )
        holder.webView.addJavascriptInterface(
            SearchResultHostBridge(holder, hit), "iappyxResultHost",
        )
        val page = buildSearchResultPage(hit)
        holder.webView.loadDataWithBaseURL(
            "https://search.local/", page, "text/html", "utf-8", null,
        )
    }

    /** Plugin proxy bridge for the search-result WebView. Same JS-side
     *  shape as `iappyxPlugins` on widgets — exposes `invoke(pluginId,
     *  method, argsJson, cbId)` + `list(cbId)`. Replies are delivered
     *  by directly evaluating `window._iappyxCb[cbId](json)` on the
     *  search-result WebView. */
    private inner class SearchResultPluginsBridge(private val webView: android.webkit.WebView) {
        @android.webkit.JavascriptInterface
        fun invoke(pluginId: String?, method: String?, argsJson: String?, cbId: String?) {
            if (cbId == null) return
            if (pluginId.isNullOrEmpty() || method.isNullOrEmpty()) {
                deliver(cbId, "{\"ok\":false,\"error\":\"pluginId and method are required\"}")
                return
            }
            // PLUGINS: bump search recents — any plugin invoke from a
            // result WebView is the strongest "user cares about this
            // row" signal we have. Bumping the linked hit promotes it
            // on the next search.
            webViewToHit[webView]?.let {
                LauncherPrefs(context).bumpSearchRecent(it.pluginId, it.id)
            }
            val safeArgs = if (argsJson.isNullOrEmpty()) "{}" else argsJson
            com.iappyx.launcher.plugins.PluginHost.invoke(
                context.applicationContext, pluginId, method, safeArgs,
            ) { json -> deliver(cbId, json) }
        }
        @android.webkit.JavascriptInterface
        fun list(cbId: String?) {
            if (cbId == null) return
            val sb = StringBuilder("[")
            var first = true
            for (e in com.iappyx.launcher.plugins.PluginRegistry.all(context)) {
                if (!e.enabled) continue
                if (!first) sb.append(',')
                first = false
                sb.append('{')
                  .append("\"id\":\"").append(escape(e.manifest.id)).append("\",")
                  .append("\"name\":\"").append(escape(e.manifest.name)).append("\",")
                  .append("\"version\":\"").append(escape(e.manifest.version)).append("\"")
                  .append('}')
            }
            sb.append(']')
            deliver(cbId, "{\"ok\":true,\"plugins\":$sb}")
        }
        private fun deliver(cbId: String, json: String) {
            val cbLit = org.json.JSONObject.quote(cbId)
            val script = "try { var fn = (window._iappyxCb || {})[$cbLit];" +
                " if (fn) { delete window._iappyxCb[$cbLit]; fn($json); } }" +
                " catch (e) { try { console.error('cb error', e); } catch(_){} }"
            handler.post {
                try { webView.evaluateJavascript(script, null) }
                catch (_: Throwable) { /* webview destroyed */ }
            }
        }
        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    /** Bridge object exposed to the result WebView. Wraps the parts of
     *  the search-panel host that the inline result might need to
     *  control: resize itself, dismiss the panel after a launch-like
     *  action, log to logcat. */
    private inner class SearchResultHostBridge(
        private val holder: PluginEntityHolder,
        private val hit: com.iappyx.launcher.plugins.PluginSearchAggregator.Hit,
    ) {
        @android.webkit.JavascriptInterface
        fun log(msg: String?) {
            android.util.Log.d("iappyx-search-result", "[${hit.pluginId}/${hit.id}] $msg")
        }
        @android.webkit.JavascriptInterface
        fun resize(heightDp: String?) {
            val h = heightDp?.toIntOrNull()?.coerceIn(40, 220) ?: return
            handler.post {
                holder.webView.layoutParams = holder.webView.layoutParams.apply {
                    height = (h * density).toInt()
                }
                holder.webView.requestLayout()
            }
        }
        @android.webkit.JavascriptInterface
        fun dismissSearch() {
            handler.post { onRequestHide?.invoke() }
        }
        /** Fire ACTION_VIEW for a URL — used by search results that
         *  want to open a document, photo, or web page in the user's
         *  default browser. Auto-dismisses the search panel after the
         *  intent fires; the user is leaving search anyway. */
        @android.webkit.JavascriptInterface
        fun openUrl(url: String?) {
            if (url.isNullOrBlank()) return
            handler.post {
                try {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url))
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    onRequestHide?.invoke()
                } catch (_: Throwable) { /* malformed url / no handler */ }
            }
        }
    }

    /** Compose the final HTML loaded into the result WebView. Same
     *  pattern as the widget shim — inject our shim script first, then
     *  the plugin's HTML. The base URL `https://search.local/` is a
     *  static-string sentinel; we never resolve it, but it gives the
     *  page a stable origin so localStorage/fetch don't get weirdly
     *  cross-origin'd. */
    private fun buildSearchResultPage(
        hit: com.iappyx.launcher.plugins.PluginSearchAggregator.Hit,
    ): String {
        val pluginIdLit = org.json.JSONObject.quote(hit.pluginId)
        val shim = readAssetText("plugins-system/search-result-shim.js")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  *{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
  html,body{width:100%;height:100%;font:13px/1.4 -apple-system,Roboto,system-ui,sans-serif;
    color:#ECECEC;background:transparent;overflow:hidden}
  button,input{font:inherit;color:inherit}
</style>
<script>$shim
window.__iappyxResultPluginId = $pluginIdLit;</script>
</head><body>${hit.html}</body></html>"""
    }

    private fun readAssetText(path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Throwable) { "" }
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("math result", text))
        // Android 13+ shows its own clipboard confirmation overlay, so a toast
        // would double up. Below 13 the user gets nothing visible without it.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.iappyx.launcher.R.string.search_copied_toast_format, text),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun fireWebSearch(query: String) {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            LauncherPrefs(context).recordSearch(query)
            onRequestHide?.invoke()
        } catch (_: Throwable) {
            android.widget.Toast.makeText(
                context, com.iappyx.launcher.R.string.search_no_web_app_toast,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Ask the hosting Activity for READ_CONTACTS. The result comes back into
     *  LauncherActivity.onRequestPermissionsResult — we re-run the current
     *  search in onResume / on panel show. */
    private fun requestContactsPermission() {
        val activity = (context as? android.app.Activity) ?: return
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.READ_CONTACTS),
            REQ_READ_CONTACTS,
        )
    }

    companion object { const val REQ_READ_CONTACTS = 31337 }

    private fun buildRowCard(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Palette.bgCell(ctx))
            }
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * dp).toInt() }
            isClickable = true; isFocusable = true
        }
        val icon = ImageView(ctx).apply {
            val s = (40 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (12 * dp).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val label = TextView(ctx).apply {
            setTextColor(Palette.textPrimary(ctx)); textSize = 15f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            setTextColor(Palette.textSecondary(ctx)); textSize = 11f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textCol.addView(label); textCol.addView(sub)
        row.addView(icon); row.addView(textCol)
        return row
    }

    private fun bindCard(h: GenericHolder, iconDrawable: android.graphics.drawable.Drawable?, label: String, subtitle: String?, onClick: () -> Unit) {
        val row = h.root as LinearLayout
        val icon = row.getChildAt(0) as ImageView
        val textCol = row.getChildAt(1) as LinearLayout
        val labelView = textCol.getChildAt(0) as TextView
        val subView = textCol.getChildAt(1) as TextView
        if (iconDrawable != null) {
            icon.setImageDrawable(iconDrawable); icon.visibility = View.VISIBLE
        } else {
            icon.setImageDrawable(null); icon.visibility = View.GONE
        }
        labelView.text = label
        if (subtitle.isNullOrBlank()) { subView.visibility = View.GONE }
        else { subView.text = subtitle; subView.visibility = View.VISIBLE }
        row.setOnClickListener { onClick() }
    }

    private class HeaderHolder(val root: View, val label: TextView) : RecyclerView.ViewHolder(root)
    private class GenericHolder(val root: View) : RecyclerView.ViewHolder(root)

    private class FrequentAppsAdapter(
        private var items: List<SearchResult.App>,
        private val onLaunch: (SearchResult.App) -> Unit,
    ) : RecyclerView.Adapter<FrequentAppsAdapter.H>() {

        fun setItems(list: List<SearchResult.App>) { items = list; notifyDataSetChanged() }
        override fun getItemCount(): Int = items.size

        class H(val root: LinearLayout, val icon: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val ctx = parent.context; val dp = ctx.resources.displayMetrics.density
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isClickable = true; isFocusable = true
            }
            val icon = ImageView(ctx).apply {
                val s = (48 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
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
            return H(root, icon, label)
        }

        override fun onBindViewHolder(h: H, position: Int) {
            val entry = items[position]
            // Same masked render path as drawer / folder / app-search row.
            val ctx = h.root.context
            val sizePx = (96 * ctx.resources.displayMetrics.density).toInt()
            val spec = com.iappyx.launcher.cells.IconFilterRegistry.resolve(
                ctx, com.iappyx.launcher.LauncherPrefs(ctx).iconFilter,
            )
            h.icon.setImageBitmap(
                com.iappyx.launcher.cells.IconMask.render(
                    entry.packageName, entry.icon, sizePx, spec,
                ),
            )
            h.label.text = entry.label
            h.root.setOnClickListener { onLaunch(entry) }
        }
    }
}
