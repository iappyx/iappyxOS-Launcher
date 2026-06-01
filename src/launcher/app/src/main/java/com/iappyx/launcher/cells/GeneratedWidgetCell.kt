/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.iappyx.launcher.WidgetHost
import com.iappyx.launcher.widget.MetaParser
import com.iappyx.launcher.widget.WidgetPolicy
import com.iappyx.launcher.widget.WidgetSandbox

/**
 * A home-screen grid cell that renders a generated widget (HTML/JS) inside a
 * sandboxed WebView. Owns:
 *  - the WebView
 *  - a per-widget [WidgetHost] (the full iappyxOS ShellActivity surface adapted
 *    as a ContextWrapper, sandboxed by widget id)
 *  - a long-press detector (WebView would otherwise swallow it for text selection)
 *
 * The bridge surface matches iappyxOS's — 34 @JavascriptInterface bridges,
 * exposed through the one-liner `window.iappyx = {...}` shim injected on
 * page finish, same as in iappyxOS ShellActivity.
 */
@SuppressLint("SetJavaScriptEnabled")
class GeneratedWidgetCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Live WebView owned by this cell. Public so `WidgetZoomOverlay` can
     *  reparent it for the expand-to-card animation. */
    var webView: WebView? = null
        private set
    private lateinit var widgetHost: WidgetHost
    private var policy: WidgetPolicy = WidgetPolicy.DEFAULT
    var widgetId: String = ""
        private set

    private var onLongPressCallback: (() -> Unit)? = null
    private var onDoubleTapCallback: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) { onLongPressCallback?.invoke() }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapCallback?.invoke()
            return true
        }
    })

    // ── Touch arbitration state (NestedScrollableHost pattern) ───
    // The cell hosts a WebView whose orientation collides with our parent
    // ViewPager2 (the home pager scrolls horizontally; widgets scroll
    // vertically and may also scroll horizontally). We follow the canonical
    // AOSP NestedScrollableHost touch-dispatch pattern in
    // [onInterceptTouchEvent]. Slop is read once from ViewConfiguration; we
    // do NOT cache axis decisions across MOVEs — every event re-evaluates so
    // a gesture that flips axis mid-stream behaves correctly.
    private var initialX = 0f
    private var initialY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** Device-pixel rectangles inside the WebView where the widget declared
     *  `touch-action: pan-x` (horizontally-pannable region — slider, chip
     *  row, drag dial). Reported by [BridgeShims.H_CLAIM_PROBE] via the
     *  [HClaimBridge] interface. Volatile because the JS bridge thread
     *  writes; touch dispatch on the UI thread reads. */
    @Volatile private var hClaimRects: List<android.graphics.RectF> = emptyList()
    /** Device-pixel rectangles where the widget declared `touch-action:
     *  none` or `pan-x pan-y` — full ownership of all gestures inside
     *  (map pan/zoom, drawing canvas, custom 2D drag UI). Both vertical
     *  and horizontal drags are claimed; drawer/search/page-swipe are
     *  suppressed inside these regions. */
    @Volatile private var fullClaimRects: List<android.graphics.RectF> = emptyList()
    /** Set on each [MotionEvent.ACTION_DOWN] from the pan-x hit-test. */
    private var inHClaimRegion = false
    /** Set on each [MotionEvent.ACTION_DOWN] from the full-claim hit-test. */
    private var inFullClaimRegion = false

    /** Imperative gesture lock controlled by the widget JS via
     *  [iappyx.setSwipeLock(bool)]. While `true`, the cell unconditionally
     *  claims the current gesture (full disallow on parent intercept) —
     *  the widget is handling the touch itself (drag-and-drop, drawing,
     *  custom carousels) and the launcher must stay out of the way.
     *
     *  Reset to `false` on every [MotionEvent.ACTION_DOWN] so a new
     *  gesture starts clean; widget code sets it again on its own
     *  pointerdown handler if it wants this gesture too. Also reset on
     *  ACTION_UP/CANCEL as a safety net for widgets that forget to call
     *  `setSwipeLock(false)`.
     *
     *  Volatile because the JS bridge thread writes; touch dispatch on
     *  the UI thread reads. */
    @Volatile private var widgetGestureLock: Boolean = false

    private inner class GestureLockBridge {
        @android.webkit.JavascriptInterface
        fun setLock(locked: Boolean) {
            widgetGestureLock = locked
        }
    }

    private inner class HClaimBridge {
        @android.webkit.JavascriptInterface
        fun set(json: String) {
            val hList = mutableListOf<android.graphics.RectF>()
            val fullList = mutableListOf<android.graphics.RectF>()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val x = o.getDouble("x").toFloat()
                    val y = o.getDouble("y").toFloat()
                    val w = o.getDouble("w").toFloat()
                    val h = o.getDouble("h").toFloat()
                    val rect = android.graphics.RectF(x, y, x + w, y + h)
                    when (o.optString("axis", "h")) {
                        "all" -> fullList.add(rect)
                        else -> hList.add(rect)
                    }
                }
            } catch (_: Throwable) { /* keep previous rects on parse failure */ return }
            hClaimRects = hList
            fullClaimRects = fullList
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // Inset the WebView from the cell edge so AI-generated widgets line up
        // visually with the launcher's icons (6dp internal pad) and stock
        // AppWidgets (Android adds its own surrounding margins). Without this
        // the widget content runs to the cell edge and looks crammed up
        // against neighbouring cells.
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        // Re-push system-bar safe insets to the widget whenever the cell's
        // window insets or its on-screen position change. Cells that visually
        // overlap the status bar / nav bar get top/bottom padding so widget
        // content (and its touch targets) sit below the gesture-inset zone
        // the OS reserves for shade pull-down / back gesture. See the
        // `--iappyx-safe-top` / `--iappyx-safe-bottom` declarations in the
        // injected CSS guard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyInsetsToWidget()
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyInsetsToWidget() }
    }

    /** Radius the launcher uses to round AI-generated widget corners. Tries the
     *  Android 12+ system attr first (matches stock widgets pixel-for-pixel),
     *  falls back to a Material-3-ish 24dp on older versions. */
    private fun widgetCornerRadiusPx(): Float {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                return resources.getDimension(android.R.dimen.system_app_widget_background_radius)
            } catch (_: Throwable) {}
        }
        return 24f * resources.displayMetrics.density
    }

    fun setOnCellLongPress(callback: () -> Unit) { onLongPressCallback = callback }
    fun setOnCellDoubleTap(callback: () -> Unit) { onDoubleTapCallback = callback }

    /** Last time we dispatched a CSS resize event into the WebView. Used by
     *  [nudgeResize] for in-class debouncing — the page transformer can call
     *  nudge on every transformer-settle frame; we cap the actual dispatch
     *  to once per ~250 ms. Field-level rather than method-static so it's
     *  per-cell (each WebView has its own debounce window). */
    private var lastResizeNudgeAt: Long = 0L

    /** Force the widget's WebView to re-measure and re-layout, so Chromium
     *  re-allocates its compositor tiles at the cell's current size. This
     *  is the recovery path for the "tile memory limits exceeded" Chromium
     *  warning that fires during cell-transforming transitions: when the
     *  WebView's tile pool has been pruned mid-swipe, CSS content can be
     *  rendered against a partially-collapsed compositor area and stay
     *  that way until something forces a fresh layout pass.
     *
     *  Three-step recovery:
     *    1. `requestLayout()` — schedules an Android layout pass for the
     *       WebView. Chromium's `onSizeChanged` fires; tiles re-allocate
     *       against the correct measured viewport.
     *    2. `invalidate()` — schedules a redraw so the new tiles paint.
     *    3. JS `resize` event — CSS recomputes vh/vw/vmin/vmax in case
     *       widget code listens for resize and adjusts canvas / DOM size.
     *
     *  Internally debounced to 250 ms so transformer settle-spam doesn't
     *  trigger this every frame. */
    fun nudgeResize() {
        val wv = webView ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastResizeNudgeAt < 250L) return
        lastResizeNudgeAt = now
        try {
            wv.requestLayout()
            wv.invalidate()
            wv.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        } catch (_: Throwable) { /* WebView torn down — ignore */ }
    }

    /** Pause or resume the widget's WebView based on whether its home page
     *  is currently visible (or whether the launcher itself is in the
     *  foreground). Respects the meta-tag policy: widgets that declare
     *  `<meta name="iappyx-widget" content="keepAlive">` stay running
     *  regardless — they're the music players / live trackers that NEED
     *  to keep ticking off-screen. Default policy (`pause`, no keepAlive)
     *  pauses RAF, JS timers, and most JS work in the WebView, which
     *  cuts the dominant background drain when 2-3 pages of widgets are
     *  realized at once. */
    fun setLifecycleVisible(visible: Boolean) {
        val wv = webView ?: return
        // Sensors / watchPosition pause UNCONDITIONALLY — even for
        // keepAlive widgets. A keepAlive widget off-screen still drains
        // the battery if it keeps the rotation-vector or GPS listener
        // alive, and an off-screen widget can't render a response to
        // those readings anyway. WebView onPause is what `keepAlive`
        // actually gates (music players, live trackers).
        if (visible) {
            if (!policy.keepAlive) wv.onResume()
            if (::widgetHost.isInitialized) {
                widgetHost.resumeBridges()
                widgetHost.usage().onVisible()  // USAGE
            }
        } else {
            if (!policy.keepAlive) wv.onPause()
            if (::widgetHost.isInitialized) {
                widgetHost.pauseBridges()
                widgetHost.usage().onHidden()  // USAGE
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Feed our own gestureDetector (long-press hook is wired but unused
        // today; double-tap is). Always returns false — actual arbitration
        // happens in the NestedScrollableHost-style logic below.
        gestureDetector.onTouchEvent(ev)
        handleNestedTouch(ev)
        return false
    }

    /**
     * Canonical [AOSP NestedScrollableHost](https://github.com/android/views-widgets-samples/blob/main/ViewPager2/app/src/main/java/androidx/viewpager2/integration/testapp/NestedScrollableHost.kt)
     * touch arbitration. Our parent ViewPager2 is horizontal; the WebView
     * inside this cell may scroll vertically, horizontally, both, or neither.
     *
     * Decision tree on each MOVE past slop:
     *  - Gesture perpendicular to pager (= vertical) → release disallow.
     *    LauncherActivity's drawer/search & VP2 see the gesture.
     *  - Gesture parallel to pager (= horizontal) AND the child can scroll
     *    that direction → keep disallow. WebView scrolls horizontally.
     *  - Gesture parallel AND child cannot scroll that direction → release
     *    disallow. VP2 takes over and changes pages.
     *
     * Pinch / multi-touch is handled by setting disallow on POINTER_DOWN: a
     * second finger means the user is interacting with widget contents, not
     * with the launcher.
     *
     * `markClaimedToActivity()` mirrors every disallow call so
     * [LauncherActivity] can suppress drawer/search firing on UP for any
     * gesture this cell took ownership of.
     */
    private fun handleNestedTouch(ev: MotionEvent) {
        // Imperative gesture lock from the widget's JS (set via
        // iappyx.setSwipeLock). Claim every event for the duration of the
        // lock — no axis arbitration, no can-scroll checks. Widget JS owns
        // the gesture end-to-end. Used by drag-and-drop UIs, drawing
        // canvases, custom 2D-drag widgets that don't fit the static
        // touch-action declarative model.
        if (widgetGestureLock) {
            parent?.requestDisallowInterceptTouchEvent(true)
            markClaimedToActivity()
            return
        }
        // Multi-touch (pinch, two-finger pan, etc.) is always widget
        // interaction — claim BEFORE the canScroll early-return. Some
        // widgets pinch-zoom internally without ever changing
        // canScroll{V,H} (maps that pan via JS rather than document
        // scrolling — Live GPS Map is the canonical example).
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN || ev.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            markClaimedToActivity()
            return
        }

        val wv = webView ?: return
        val canScrollV = wv.canScrollVertically(-1) || wv.canScrollVertically(1)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reset the imperative lock so the prior gesture's lock
                // state can't leak into this one. Widget JS that wants the
                // lock for THIS gesture sets it again on its own
                // pointerdown handler (which fires after this DOWN).
                widgetGestureLock = false
                initialX = ev.x
                initialY = ev.y
                // Hit-test against widget-declared touch-action regions:
                //  - `pan-x` rects → claim horizontal drags (sliders, chip
                //    rows, drag dials).
                //  - `none` / `pan-x pan-y` rects → claim every drag (maps,
                //    drawing canvases, full custom 2D-drag surfaces).
                inHClaimRegion = isInRect(hClaimRects, ev.x, ev.y)
                inFullClaimRegion = isInRect(fullClaimRects, ev.x, ev.y)
                // Optimistic disallow on DOWN — but ONLY when the cell
                // actually has something to claim. Without the gate, every
                // touch on a non-scrollable widget made the cell hold the
                // gesture for ~slop before the MOVE handler could release;
                // the user saw their finger drag the cell a few pixels
                // before VP2 page-swiped in. The race against VP2 only
                // matters when we'll subsequently keep the claim, so:
                //   - inFullClaimRegion / inHClaimRegion → claim regardless
                //     of axis or canScroll.
                //   - canScrollV → claim vertical drags later.
                //   - canScrollH at doc level → claim horizontal drags.
                // If none of these, VP2's RV is welcome to grab the gesture
                // on its own slop, and there's no jitter.
                val canScrollV = wv.canScrollVertically(-1) || wv.canScrollVertically(1)
                val canScrollH = wv.canScrollHorizontally(-1) || wv.canScrollHorizontally(1)
                if (inFullClaimRegion || inHClaimRegion || canScrollV || canScrollH) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // NB: we deliberately do NOT call markClaimedToActivity
                // here — the activity-side suppression flag must only be
                // set when we genuinely KEEP the claim on a MOVE.
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - initialX
                val dy = ev.y - initialY
                // AOSP sample assumes pager touch-slop is 2x child slop;
                // halve the parallel-axis distance before comparing.
                val scaledDx = kotlin.math.abs(dx) * 0.5f
                val scaledDy = kotlin.math.abs(dy)
                if (scaledDx > touchSlop || scaledDy > touchSlop) {
                    // Full-claim region (map / canvas / `touch-action: none`)
                    // owns every gesture regardless of axis or canScroll.
                    if (inFullClaimRegion) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        markClaimedToActivity()
                        return
                    }
                    if (scaledDy > scaledDx) {
                        // Vertical drag. Release only if document has no
                        // vertical scroll — that way drawer/search can fire
                        // from on top of non-scrolling widgets (clocks,
                        // simple tiles). For widgets that DO have document
                        // vertical scroll, claim and let WebView handle it.
                        if (canScrollV) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            markClaimedToActivity()
                        } else {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    } else {
                        // Horizontal drag. Claim if EITHER:
                        //  - the document can scroll horizontally in the
                        //    gesture's direction (rare — most widgets fit
                        //    width), OR
                        //  - the touch landed inside a region the widget
                        //    declared `touch-action: pan-x` (volume slider,
                        //    chip row, drag dial — the H_CLAIM_PROBE built
                        //    the rect list at page load).
                        // Otherwise release for VP2 page-swipe.
                        if (inHClaimRegion ||
                            wv.canScrollHorizontally(if (dx < 0) 1 else -1)
                        ) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            markClaimedToActivity()
                        } else {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Safety net: reset the imperative lock at gesture end in
                // case the widget forgot to call setSwipeLock(false) on
                // pointerup/cancel. Otherwise a stale `true` would persist
                // until the next ACTION_DOWN reset and might briefly affect
                // ambient launcher state queries between gestures.
                widgetGestureLock = false
            }
        }
    }

    /** Tell [LauncherActivity] (if we're hosted by one) that this gesture is
     *  ours. Activity uses this to suppress its drawer/search firing for the
     *  rest of the gesture. Safe to call multiple times in one stream. */
    private fun markClaimedToActivity() {
        (context as? com.iappyx.launcher.LauncherActivity)?.gestureClaimedByWidget = true
    }

    /** Compute how much the cell's on-screen position overlaps the system
     *  status / nav bar gesture-inset regions, and push those values (in
     *  CSS px) to the widget as `--iappyx-safe-top` / `--iappyx-safe-bottom`
     *  CSS variables. The widget body's padding picks them up. Cells in
     *  middle rows have safe insets = 0; only edge-row cells (visually
     *  under the system bars) get positive padding. Idempotent — safe to
     *  call from onApplyWindowInsets, layout-change, and onPageFinished
     *  callbacks; the WebView short-circuits if the values haven't changed.
     */
    private fun applyInsetsToWidget() {
        val wv = webView ?: return
        if (width == 0 || height == 0) return
        val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(this) ?: return
        val systemBars = rootInsets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars()
                or androidx.core.view.WindowInsetsCompat.Type.systemGestures(),
        )
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val cellTop = loc[1]
        val cellBottom = cellTop + height
        val screenH = resources.displayMetrics.heightPixels
        val safeTopPx = kotlin.math.max(0, systemBars.top - cellTop)
        val safeBottomPx = kotlin.math.max(0, systemBars.bottom - (screenH - cellBottom))
        val density = resources.displayMetrics.density
        val safeTopCss = (safeTopPx / density).toInt()
        val safeBottomCss = (safeBottomPx / density).toInt()
        wv.evaluateJavascript(
            "document.documentElement.style.setProperty('--iappyx-safe-top','${safeTopCss}px');" +
                "document.documentElement.style.setProperty('--iappyx-safe-bottom','${safeBottomCss}px');",
            null,
        )
    }

    /** True if the (x, y) touch (in cell-local device pixels) falls inside
     *  any rect in [rects]. Rect coords are device pixels (the JS probe
     *  multiplies CSS px by `devicePixelRatio` before reporting). */
    private fun isInRect(rects: List<android.graphics.RectF>, x: Float, y: Float): Boolean {
        if (rects.isEmpty()) return false
        for (r in rects) if (r.contains(x, y)) return true
        return false
    }

    /**
     * Bind this cell to a widget instance.
     *
     * @param activity the host activity (needed for runOnUiThread, startActivityForResult, etc.)
     * @param widgetId persistent identifier of this widget placement
     * @param html the widget's HTML document (with meta policy + inline JS)
     */
    fun bind(activity: android.app.Activity, widgetId: String, html: String) {
        this.widgetId = widgetId
        this.policy = MetaParser.parse(html)

        // A keepAlive widget (radio player, live tracker) must keep running
        // when the user navigates pages. ViewPager2 recycles off-screen pages
        // (onViewRecycled → destroyWidget → destroy()), which tears down a
        // keepAlive widget the moment its page scrolls out of the small
        // offscreen cache. Ask the launcher to keep all home pages resident so
        // this widget's page is never recycled. Driven off the actual bind so
        // the cost is only paid once a keepAlive widget is genuinely present.
        if (policy.keepAlive) {
            (activity as? com.iappyx.launcher.LauncherActivity)?.ensureKeepAlivePagesResident()
        }

        val sandbox = WidgetSandbox.sandboxFor(context, widgetId)

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            // Disable Chromium's bounce/glow at scroll boundaries.
            overScrollMode = View.OVER_SCROLL_NEVER
            // Hide scrollbars entirely. They flash briefly during initial
            // layout (WebView's compute*Range temporarily reports scrollable
            // before final measurement settles) which looks like jank in a
            // launcher cell. The content moves under the user's finger,
            // which is feedback enough — most modern Android UIs don't draw
            // scrollbars on home-screen surfaces.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // Round the WebView's own edges (not the cell's) so the rounded
            // shape coincides with the actual content surface, not the padded
            // cell area.
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, widgetCornerRadiusPx())
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
                // Deliberately NOT enabling setGeolocationEnabled: iappyxOS
                // forces all location access through iappyx.location.* so there's
                // a single permission + error model. Widgets that try
                // navigator.geolocation should fail — the AI is supposed to
                // catch that from the system prompt and use the bridge.
            }
            webViewClient = object : WebViewClient() {
                // Serve bundled theme fonts referenced by the injected
                // @font-face (https://widget.local/__themefont/<file>.ttf).
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    val url = request.url
                    if (url.host == "widget.local" && url.path?.startsWith("/__themefont/") == true) {
                        val file = url.lastPathSegment ?: return null
                        val stream = com.iappyx.launcher.theme.ThemeFonts.openFontStream(context, file) ?: return null
                        return android.webkit.WebResourceResponse("font/ttf", null, stream)
                    }
                    return null
                }
                override fun onPageFinished(view: WebView, url: String) {
                    // Re-evaluate at onPageFinished too, in case the document-start
                    // hook didn't fire (e.g. on devices that don't support it).
                    view.evaluateJavascript(com.iappyx.launcher.widget.BridgeShims.WIDGET_SHIM, null)
                    // The horizontal-claim probe must run AFTER the document
                    // has parsed enough to query getComputedStyle correctly,
                    // so we keep it here (post page finished) rather than at
                    // document-start. It's idempotent and self-disabling.
                    view.evaluateJavascript(com.iappyx.launcher.widget.BridgeShims.H_CLAIM_PROBE, null)
                    // Push system-bar safe insets to the freshly-loaded
                    // document. Insets that arrived before this point were
                    // queued for nothing (no DOM existed); calling here
                    // ensures the body picks up the right padding even on
                    // cold loads.
                    applyInsetsToWidget()
                }
                // Intercept clicks on external schemes (geo:, tel:, mailto:,
                // sms:, market:, intent:, etc.) so they dispatch via the
                // system handler instead of being loaded by this WebView.
                // Without this, a `geo:52.0,5.0?q=...` click loads inside the
                // widget cell, the WebView can't render it, and the widget
                // falls through to a "webpage not found" page that breaks
                // its UI. http/https/file/data/blob stay in the WebView so
                // the widget itself + any inline content keep working.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https" ||
                        scheme == "file" || scheme == "data" || scheme == "blob"
                    ) return false
                    return try {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW, request.url,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        true
                    } catch (_: Throwable) {
                        // No app handles the scheme (e.g. no Maps installed).
                        // Returning true keeps the user on the widget rather
                        // than rendering "webpage not found".
                        true
                    }
                }
            }
            // Inject the shim BEFORE any widget <script> runs. The widget's
            // own JS calls `typeof iappyx` early; if we waited for
            // onPageFinished it would see undefined, fall through to
            // navigator.geolocation (deliberately disabled), and never recover.
            // addDocumentStartJavaScript runs the shim at document_start of
            // every navigation that matches the allow-list.
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(
                        androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        this, com.iappyx.launcher.widget.BridgeShims.WIDGET_SHIM, setOf("*"),
                    )
                }
            } catch (_: Throwable) { /* fall back to onPageFinished injection */ }
            webChromeClient = object : WebChromeClient() {
                // JS getUserMedia() / camera feed request — route to WidgetHost which
                // checks / requests the matching Android runtime permission.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    widgetHost.handleWebPermissionRequest(request)
                }
                // Forward widget JS console messages to logcat so widget bugs
                // are debuggable without re-enabling debuggable. Tagged
                // "iappyxWidget" so users can filter via:
                //   adb logcat -s iappyxWidget
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    val level = m.messageLevel().name.lowercase()
                    android.util.Log.d(
                        "iappyxWidget",
                        "[$widgetId] $level: ${m.message()} @${m.sourceId()}:${m.lineNumber()}",
                    )
                    return true
                }
            }
            // WebView must not eat long-press — parent cell owns gesture for edit/remove
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
        }
        webView = wv

        widgetHost = WidgetHost(activity, wv, sandbox.dir, widgetId)
        widgetHost.registerBridges()
        // Private bridge used only by the horizontal-claim probe — kept
        // separate from the widget-facing iappyx.* surface. Underscore-
        // prefixed name signals "internal, don't call from widget code".
        wv.addJavascriptInterface(HClaimBridge(), "_iappyxHClaim")
        // Imperative gesture-lock bridge. The WIDGET_SHIM exposes this as
        // `iappyx.setSwipeLock(bool)`; widgets call it from pointerdown of
        // their drag handles to claim the gesture and from pointerup/cancel
        // to release it. Complements the declarative `touch-action`
        // mechanism for cases where the widget's draggable elements move,
        // appear, or disappear at runtime (sticky notes, drawing canvases,
        // image annotators).
        wv.addJavascriptInterface(GestureLockBridge(), "_iappyxGesture")

        // Default visual chrome injected on top of every widget's own <head>:
        //  • text-selection guard (long-press is owned by the cell)
        //  • Material You theme tokens as CSS custom properties — widgets can
        //    use `var(--iappyx-surface)` etc. to inherit system colors.
        //    Defaults to a sensible dark scheme on pre-API-31 / failure.
        //  • Default body styling (Material You tonal surface, on-surface text,
        //    sans-serif). Widget CSS can override anything it wants — these are
        //    just sane defaults so a widget that does nothing visual already
        //    looks like a stock Android widget.
        //
        // Corner rounding lives at the Cell level (clipToOutline + outlineProvider)
        // so the radius is consistent regardless of what the widget HTML does.
        val theme = readThemeTokens(context)
        // Palette (Material You) + design-system defaults + the user's theme
        // overrides, all as --iappyx-* declarations (overrides win, emitted last).
        val themeDecls = effectiveTokens(context, theme)
            .entries.joinToString("\n                ") { "${it.key}: ${it.value};" }
        val cssGuard = """
            <style>
              ${com.iappyx.launcher.theme.ThemeFonts.fontFaceCss(context)}
              :root {
                $themeDecls
                color-scheme: ${if (theme.isDark) "dark" else "light"};
                /* System-bar gesture insets in CSS px. Updated at runtime
                 * by [GeneratedWidgetCell.applyInsetsToWidget] for cells
                 * that overlap the status / nav bar (typically row 0 / row
                 * N-1 of the home grid). Default 0 so non-edge cells get
                 * no padding. Body padding picks them up below.
                 */
                --iappyx-safe-top: 0px;
                --iappyx-safe-bottom: 0px;
              }
              html, body {
                margin: 0; padding: 0;
                width: 100%;
                background: var(--iappyx-surface);
                color: var(--iappyx-on-surface);
                font-family: var(--iappyx-font);
                font-size: var(--iappyx-text-md); line-height: 1.35;
                -webkit-user-select: none !important; user-select: none !important;
                -webkit-touch-callout: none !important;
                -webkit-tap-highlight-color: transparent;
                /* Pin horizontal overflow at the document root. Widgets
                 * commonly fight intrinsic min-content sizes that exceed
                 * the cell width by a few pixels (e.g. a 5-button flex
                 * nav-bar). Without this, the document grows ~slightly
                 * wider than the viewport, WebView reports
                 * canScrollHorizontally=true, the cell claims the gesture,
                 * and the user sees a tiny horizontal pan inside the
                 * widget. Inner-element horizontal scroll (chip rows,
                 * sliders) opts in via `touch-action: pan-x` instead. */
                overflow-x: hidden;
              }
              /* Apply the runtime-resolved safe insets as body padding.
               * Cells that visually extend under the status bar / nav bar
               * (top or bottom row of the home grid) get a few CSS px of
               * top / bottom padding so widget content is never under the
               * system gesture-inset region — touches there get captured
               * by the system shade / back-gesture and never reach the
               * WebView. Cells in the middle have insets = 0 → no padding. */
              body {
                padding-top: var(--iappyx-safe-top);
                padding-bottom: var(--iappyx-safe-bottom);
              }
              * { -webkit-user-select: none !important; user-select: none !important; box-sizing: border-box; }
            </style>
        """.trimIndent()

        val headMatch = Regex("(?i)<head[^>]*>").find(html)
        val injected = if (headMatch != null) {
            val insertAt = headMatch.range.last + 1
            html.substring(0, insertAt) + cssGuard + html.substring(insertAt)
        } else {
            "<html><head>$cssGuard</head><body>$html</body></html>"
        }

        addView(wv)
        wv.loadDataWithBaseURL(
            "https://widget.local/",
            injected,
            "text/html",
            "UTF-8",
            null,
        )
    }

    /** @param permanent true (default) when the widget is genuinely going
     *  away (removed from home, activity destroyed) — does a FULL teardown
     *  including network resources. Pass false on a routine page recycle so a
     *  keepAlive widget's background work (radio stream, etc.) isn't killed. */
    fun destroyWidget(permanent: Boolean = true) {
        if (::widgetHost.isInitialized) widgetHost.destroy(permanent)
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        webView = null
    }

    companion object {
        /** Snapshot of the Material You palette + dark/light flag. Source for
         *  both the initial CSS-variable injection and the live theme update
         *  pushed to existing widgets when the user changes wallpaper / mode. */
        data class ThemeTokens(
            val isDark: Boolean,
            val surface: String, val background: String, val onSurface: String,
            val primary: String, val onPrimary: String,
            val secondary: String, val tertiary: String, val neutral: String,
        )

        /** Design-system tokens (typography, spacing, shape, glass, status, data
         *  series, motion). Constants — some reference the live palette. These +
         *  the palette + user overrides make the full token set. */
        val DESIGN_DEFAULTS: LinkedHashMap<String, String> = linkedMapOf(
            "--iappyx-font" to "-apple-system, \"Roboto\", \"Segoe UI\", system-ui, sans-serif",
            "--iappyx-text-xl" to "28px", "--iappyx-text-lg" to "20px",
            "--iappyx-text-md" to "15px", "--iappyx-text-sm" to "12px",
            "--iappyx-weight-normal" to "400", "--iappyx-weight-bold" to "700",
            "--iappyx-radius" to "20px", "--iappyx-radius-sm" to "12px",
            "--iappyx-space-sm" to "6px", "--iappyx-space-md" to "12px", "--iappyx-space-lg" to "20px",
            "--iappyx-glass-blur" to "14px", "--iappyx-glass-opacity" to "0.10",
            "--iappyx-shadow" to "0 8px 24px rgba(0,0,0,0.35)", "--iappyx-shadow-sm" to "0 2px 8px rgba(0,0,0,0.30)",
            "--iappyx-positive" to "#46d39a", "--iappyx-negative" to "var(--iappyx-tertiary)", "--iappyx-warning" to "#ffb347",
            "--iappyx-data-1" to "var(--iappyx-primary)", "--iappyx-data-2" to "var(--iappyx-secondary)",
            "--iappyx-data-3" to "var(--iappyx-tertiary)", "--iappyx-data-4" to "#c06bff",
            "--iappyx-transition" to "180ms ease",
        )

        private fun paletteMap(t: ThemeTokens): LinkedHashMap<String, String> = linkedMapOf(
            "--iappyx-surface" to t.surface, "--iappyx-background" to t.background,
            "--iappyx-on-surface" to t.onSurface, "--iappyx-on-background" to t.onSurface,
            "--iappyx-primary" to t.primary, "--iappyx-on-primary" to t.onPrimary,
            "--iappyx-secondary" to t.secondary, "--iappyx-tertiary" to t.tertiary,
            "--iappyx-neutral" to t.neutral,
        )

        /** Full token set: palette + design defaults + overrides (overrides win).
         *  Defaults to the stored overrides; the editor passes its working set. */
        fun effectiveTokens(
            context: android.content.Context,
            t: ThemeTokens,
            overrides: Map<String, String> = com.iappyx.launcher.theme.ThemeOverrides.get(context),
        ): LinkedHashMap<String, String> {
            val m = paletteMap(t)
            m.putAll(DESIGN_DEFAULTS)
            m.putAll(overrides)
            return m
        }

        /** Read the current Material You palette from system_* color resources
         *  (API 31+); fall back to a sensible dark/light scheme on older
         *  devices or if the resource lookup fails. */
        fun readThemeTokens(context: android.content.Context): ThemeTokens {
            val resources = context.resources
            val isDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            fun sysColor(resId: Int, fallback: String): String =
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    try {
                        val c = resources.getColor(resId, context.theme)
                        String.format("#%06X", 0xFFFFFF and c)
                    } catch (_: Throwable) { fallback }
                } else fallback
            return ThemeTokens(
                isDark = isDark,
                surface     = sysColor(android.R.color.system_neutral1_800, if (isDark) "#1A1A2E" else "#F2F2F7"),
                background  = sysColor(android.R.color.system_neutral1_900, if (isDark) "#0D0D1A" else "#FFFFFF"),
                onSurface   = sysColor(android.R.color.system_neutral1_50,  if (isDark) "#EAEAEA" else "#1A1A2E"),
                primary     = sysColor(android.R.color.system_accent1_500,  "#4FC3F7"),
                onPrimary   = sysColor(android.R.color.system_accent1_0,    "#FFFFFF"),
                secondary   = sysColor(android.R.color.system_accent2_500,  "#69F0AE"),
                tertiary    = sysColor(android.R.color.system_accent3_500,  "#FF6B6B"),
                neutral     = sysColor(android.R.color.system_neutral1_500, "#888888"),
            )
        }

        /** Build a JS snippet that updates the iappyx-* CSS custom properties on
         *  `:root` with the given palette. Designed to be evaluated against an
         *  already-loaded widget WebView so live widgets reflect wallpaper /
         *  Material-You changes without a reload. */
        fun buildThemeUpdateJs(t: ThemeTokens, overrides: Map<String, String> = emptyMap()): String =
            """(function(){var s=document.documentElement.style;
              s.setProperty('--iappyx-surface','${t.surface}');
              s.setProperty('--iappyx-background','${t.background}');
              s.setProperty('--iappyx-on-surface','${t.onSurface}');
              s.setProperty('--iappyx-on-background','${t.onSurface}');
              s.setProperty('--iappyx-primary','${t.primary}');
              s.setProperty('--iappyx-on-primary','${t.onPrimary}');
              s.setProperty('--iappyx-secondary','${t.secondary}');
              s.setProperty('--iappyx-tertiary','${t.tertiary}');
              s.setProperty('--iappyx-neutral','${t.neutral}');
              s.setProperty('color-scheme','${if (t.isDark) "dark" else "light"}');
            """ + overrides.entries.joinToString("") { "s.setProperty('${it.key}','${it.value}');" } + """
            })();"""

    }
}
