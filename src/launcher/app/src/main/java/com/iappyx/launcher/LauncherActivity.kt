/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.viewpager2.widget.ViewPager2
import com.iappyx.launcher.cells.FolderCell
import com.iappyx.launcher.cells.GeneratedWidgetCell
import com.iappyx.launcher.cells.IconCell
import com.iappyx.launcher.cells.StockWidgetCell
import com.iappyx.launcher.model.FolderItem
import com.iappyx.launcher.widget.FolderOverlay
import com.iappyx.launcher.widget.expandFully
import com.iappyx.launcher.widget.showThemed
import com.iappyx.launcher.widget.themeContent
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.model.Page
import com.iappyx.launcher.model.Placement
import com.iappyx.launcher.widget.AppWidgetHostManager
import com.iappyx.launcher.widget.HomeGrid
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Session-2 launcher activity.
 *
 * Responsibilities:
 *  - Own the [AppWidgetHostManager] (startListening/stopListening)
 *  - Load layout from [PlacementStore] on create, persist on every change
 *  - Build a [ViewPager2] over the pages
 *  - Render each page onto a [HomeGrid] with the correct cell types
 *  - Handle long-press on empty cells → add dialog (icon / stock widget / generated)
 *  - Handle long-press on placed cells → remove dialog
 *  - Handle system widget picker result in onActivityResult
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: HomePagerAdapter
    private lateinit var hostManager: AppWidgetHostManager
    private lateinit var store: PlacementStore
    private lateinit var layout: HomeLayout

    /** Placement currently being edited/resolved via an async flow (picker, bind, configure). */
    private var pendingPlacement: PendingPlacement? = null

    private data class PendingPlacement(val pageIndex: Int, val row: Int, val col: Int, val appWidgetId: Int? = null)

    /** Drag mime carrying a dock placement id — for in-dock reorder + delete +
     *  dock→home moves in edit mode. */
    private val DOCK_DRAG_MIME = "application/vnd.iappyx.dock-placement"
    /** MIME for a widget moving between home pages via system drag-and-drop.
     *  Payload: a single ClipData.Item whose text is the source placement id. */
    val PAGE_WIDGET_DRAG_MIME = "application/vnd.iappyx.page-placement"

    /** Pending dock pick — slot on the current dock page. */
    private var pendingDockPick: Pair<Int, Int>? = null

    // ── ActivityResult launchers (replaces deprecated startActivityForResult /
    //     onActivityResult for the launcher's own flows; WidgetHost bridge
    //     requests still go through the legacy path until that's migrated). ──

    private val pickStockLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val provider = data?.let {
            IntentCompat.getParcelableExtra(
                it, StockWidgetPickerActivity.RESULT_PROVIDER,
                android.content.ComponentName::class.java
            )
        }
        if (result.resultCode == Activity.RESULT_OK && provider != null) {
            bindSelectedStockWidget(provider)
        } else {
            pendingPlacement = null
        }
    }

    private val pickAppLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val pkg = data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE)
        val act = data?.getStringExtra(AppPickerActivity.RESULT_ACTIVITY)
        val pending = pendingPlacement
        if (result.resultCode == Activity.RESULT_OK && pkg != null && pending != null) {
            addPlacement(pending.pageIndex, Placement(
                id = Placement.newId(),
                type = CellType.ICON,
                row = pending.row, col = pending.col,
                wSpan = 1, hSpan = 1,
                packageName = pkg, activityName = act,
            ))
        }
        pendingPlacement = null
    }

    private val pickAppForDockLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val pkg = data?.getStringExtra(AppPickerActivity.RESULT_PACKAGE)
        val act = data?.getStringExtra(AppPickerActivity.RESULT_ACTIVITY)
        val slotInfo = pendingDockPick
        if (result.resultCode == Activity.RESULT_OK && pkg != null && slotInfo != null) {
            val (dockPageIndex, slot) = slotInfo
            val dockPage = layout.dockPages.getOrNull(dockPageIndex) ?: run {
                val newPage = mutableListOf<Placement>()
                layout.dockPages.add(newPage)
                newPage
            }
            dockPage.removeAll { it.col == slot }
            dockPage.add(Placement(
                id = Placement.newId(),
                type = CellType.ICON,
                row = 0, col = slot, wSpan = 1, hSpan = 1,
                packageName = pkg, activityName = act,
            ))
            store.save(layout)
            dockAdapter.setLayout(layout)
            refreshDockIndicator()
        }
        pendingDockPick = null
    }

    private val bindWidgetLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingPlacement
        if (result.resultCode == Activity.RESULT_OK && pending?.appWidgetId != null) {
            onStockWidgetBound(pending.appWidgetId)
        } else if (pending?.appWidgetId != null) {
            hostManager.deleteId(pending.appWidgetId)
            pendingPlacement = null
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingPlacement
        if (result.resultCode == Activity.RESULT_OK && pending?.appWidgetId != null) {
            finishStockWidgetPlacement(pending.appWidgetId)
        } else if (pending?.appWidgetId != null) {
            hostManager.deleteId(pending.appWidgetId)
            pendingPlacement = null
        }
    }

    /** Widget bridge launches (camera, file picker, QR/OCR, classify/segment,
     *  speech-to-text, gallery pick) all funnel through a single launcher.
     *  Each launch pushes its globalRc onto [pendingWidgetRcs]; the callback
     *  pops in FIFO order — Android serializes foreground activity starts so
     *  results arrive in launch order. WidgetHost still owns the
     *  globalRc → host map ([WidgetHost.activeRequests]) and the
     *  global → local rc translation, so bridge code in
     *  [WidgetHost._onActivityResult] is unchanged.
     *
     *  Concurrent: bridge methods on the WebView background thread call
     *  [launchForWidgetHost]; the result callback runs on the main thread.
     *  ConcurrentLinkedDeque gives lock-free addLast/pollFirst across threads. */
    private val pendingWidgetRcs: java.util.concurrent.ConcurrentLinkedDeque<Int> =
        java.util.concurrent.ConcurrentLinkedDeque()

    private val widgetActivityLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val rc = pendingWidgetRcs.pollFirst() ?: return@registerForActivityResult
        WidgetHost.activeRequests.remove(rc)?.handleActivityResult(rc, result.resultCode, result.data)
    }

    /** Called by [WidgetHost.startActivityForResult] (Java side) to dispatch
     *  a bridge launch through the modern ActivityResult API. Rolls back the
     *  deque entry AND the WidgetHost.activeRequests entry if launch throws,
     *  so an orphan rc doesn't misroute the next legitimate launch's callback. */
    fun launchForWidgetHost(globalRc: Int, intent: Intent) {
        pendingWidgetRcs.addLast(globalRc)
        try {
            widgetActivityLauncher.launch(intent)
        } catch (t: Throwable) {
            pendingWidgetRcs.removeLastOccurrence(globalRc)
            WidgetHost.activeRequests.remove(globalRc)
            throw t
        }
    }

    /**
     * Watches ALL touches on the activity (including ones ViewPager2 is handling) for an
     * upward fling, which opens the app drawer. Living at dispatchTouchEvent level is the
     * only reliable spot — ViewPager2 intercepts horizontal swipes before HomeGrid's own
     * detector sees them.
     */
    private val rootGestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velX: Float, velY: Float): Boolean {
                if (e1 == null) return false
                // Command page (pager position 0) is a focused, dedicated screen —
                // neither the app drawer (swipe-up) nor universal search (swipe-down)
                // should open from here. Same for the Clippings page, which has its
                // own vertical-scrolling card list and would otherwise lose every
                // fast scroll to the drawer.
                if (pager.currentItem == 0) return false
                if (pager.currentItem == pagerAdapter.clippingsPosition()) return false
                // Ignore flings whenever a non-home surface owns the screen —
                // an existing overlay handles its own dismiss gestures, and
                // we shouldn't pile a new one on top of folders / overview /
                // an in-flight cell drag. Mirrors tryFireVerticalAction.
                if (appDrawer.visibility == android.view.View.VISIBLE) return false
                if (searchPanel.visibility == android.view.View.VISIBLE) return false
                if (overviewPanel.visibility == android.view.View.VISIBLE) return false
                if (currentFolderOverlay?.isShowing() == true) return false
                if (pageDragState != null) return false
                if (editMode) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                // Match the early-lock ratio in dispatchTouchEvent so a gesture
                // that *starts* vertical-leaning (and locked the pager out)
                // also satisfies the fling-time ratio check.
                val vertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f
                if (!vertical) return false
                // Swipe UP anywhere → app drawer
                if (dy < -150 && velY < -500) {
                    snapPagerBackToGestureStart()
                    showAppDrawer(); return true
                }
                // Swipe DOWN from below the status bar area → universal search.
                // Leave the top ~100dp so system notification-shade gesture wins.
                val statusBarGuard = 100 * resources.displayMetrics.density
                if (dy > 150 && velY > 500 && e1.y > statusBarGuard) {
                    snapPagerBackToGestureStart()
                    showSearch(); return true
                }
                return false
            }
        })
    }

    /** Set at ACTION_DOWN when the finger starts on a widget cell. While true,
     *  the root fling detectors (swipe-up → app drawer, swipe-down → search)
     *  are suppressed so internal scrolling in a stock or generated widget
     *  isn't hijacked by the launcher. */
    private var touchStartedOnWidget = false

    // Vertical-gesture handling. Lives at dispatchTouchEvent so we can lock
    // ViewPager2 out as soon as the finger reveals vertical intent, and so
    // we can fire the drawer / search action on a slow drag too — not just
    // a fast fling. The previous fling-only path missed slow swipes
    // (universal search was especially hard to trigger).
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureDirectionLocked = false
    /** True once we've decided this gesture is "vertical" — pager is then
     *  blocked from intercepting and we own the drawer/search action on UP. */
    private var verticalGesture = false
    /** ViewPager2's internal slop is ~8dp on BOTH axes, so by the time our
     *  lock fires the pager may have already grabbed a few px of horizontal
     *  scroll. We snap back to this page on ACTION_UP for any vertical
     *  gesture so the user lands on the same home page they started from. */
    private var gestureStartPage = -1
    /** Set true once a 2nd finger lands during the current gesture. Stays
     *  true until the next ACTION_DOWN. Suppresses drawer/search firing on
     *  pinch/zoom — without this, the centroid drift during a pinch easily
     *  exceeds the vertical-displacement threshold and opens the drawer. */
    private var wasMultiTouch = false
    /** Set by [GeneratedWidgetCell.dispatchTouchEvent] when the cell decides
     *  it owns the gesture (its WebView can scroll). Suppresses drawer/search
     *  firing for the gesture so a long internal scroll doesn't simultaneously
     *  open the drawer. */
    var gestureClaimedByWidget = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartedOnWidget = touchIsOnWidgetCell(ev.rawX, ev.rawY)
                gestureDownX = ev.rawX
                gestureDownY = ev.rawY
                gestureDirectionLocked = false
                verticalGesture = false
                gestureStartPage = pager.currentItem
                wasMultiTouch = false
                gestureClaimedByWidget = false
                // Note: vertical gesture interception is owned by the
                // OnItemTouchListener installed in installVerticalGestureGuard.
                // This block only TRACKS the gesture so we know on UP whether
                // to fire showAppDrawer / showSearch.
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                wasMultiTouch = true
            }
            MotionEvent.ACTION_MOVE -> {
                // Track ALL gestures (including widget-started ones) so a
                // very deliberate swipe over a widget can still reach the
                // drawer / search. The widget-vs-not distinction goes into
                // the firing threshold on UP, not into whether we track at
                // all — on tablets in landscape widgets cover so much of
                // the home grid that gating tracking on
                // `touchStartedOnWidget` made the swipe action effectively
                // unreachable.
                if (!gestureDirectionLocked) {
                    val dx = ev.rawX - gestureDownX
                    val dy = ev.rawY - gestureDownY
                    val absDx = kotlin.math.abs(dx)
                    val absDy = kotlin.math.abs(dy)
                    val slop = 8f * density
                    if (absDy > slop || absDx > slop) {
                        gestureDirectionLocked = true
                        if (absDy > absDx * 1.2f) verticalGesture = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (verticalGesture && ev.actionMasked == MotionEvent.ACTION_UP &&
                    !wasMultiTouch && !gestureClaimedByWidget) {
                    // Fire on TOTAL displacement, not velocity. A slow
                    // 80dp+ drag opens drawer / search; gestures that
                    // started ON a widget (where the widget's own scroll
                    // could conflict) require a longer 160dp swipe so a
                    // brief widget pan doesn't accidentally open the drawer.
                    // Suppressed entirely if the gesture was multi-touch
                    // (pinch) or if a widget cell claimed the gesture for
                    // its own scrolling — otherwise a long internal scroll
                    // would also pop the drawer.
                    val dy = ev.rawY - gestureDownY
                    val baseThreshold = if (touchStartedOnWidget) 160f else 80f
                    val threshold = baseThreshold * density
                    if (kotlin.math.abs(dy) > threshold) tryFireVerticalAction(dy)
                    // Snap back to the page the gesture started on — undoes
                    // any partial horizontal scroll the pager grabbed before
                    // our lock kicked in. Without this, opening the drawer
                    // with a slightly-diagonal swipe lands you on a neighbour
                    // page when you come back.
                    snapPagerBackToGestureStart()
                }
                touchStartedOnWidget = false
                gestureDirectionLocked = false
                verticalGesture = false
                // gestureStartPage is reset on the next ACTION_DOWN — leaving
                // it set here means the fling path (which runs after this
                // block via rootGestures.onTouchEvent) can still reach it.
            }
        }
        if (!touchStartedOnWidget) rootGestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Force the pager back to the page the vertical gesture started on.
     *
     *  With the inner-RV [OnItemTouchListener] installed by
     *  [installVerticalGestureGuard], ViewPager2 should never start a scroll
     *  on a vertical gesture, so this is now a defensive no-op in the
     *  expected case. Kept as a safety net for edge cases where the gesture
     *  guard's heuristic doesn't match (e.g. very early frames). */
    private fun snapPagerBackToGestureStart() {
        if (gestureStartPage < 0) return
        pager.setCurrentItem(gestureStartPage, false)
    }

    /** See the comment at the call site. Attaches an [OnItemTouchListener]
     *  to ViewPager2's inner RecyclerView; the listener consumes any touch
     *  stream where vertical motion outweighs horizontal, before the RV's
     *  own drag classifier ever runs. */
    private fun installVerticalGestureGuard() {
        val inner = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        inner.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.OnItemTouchListener {
            private var x0 = 0f
            private var y0 = 0f
            private var locked = false
            private var stealing = false

            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent): Boolean {
                // Command panel (pager position 0) has its own internal
                // ScrollView in the manage tabs — let vertical scrolls fall
                // through to it instead of consuming them here. Same applies
                // to the rightmost Clippings page: it hosts a vertical
                // RecyclerView and would never scroll if VP2 stole every
                // vertical drag at the slop boundary. The guard's job
                // (preventing VP2 from misreading vertical drags as
                // horizontal page changes) is still needed for actual home
                // pages.
                if (pager.currentItem == 0) return false
                if (pager.currentItem == pagerAdapter.clippingsPosition()) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x0 = e.x; y0 = e.y; locked = false; stealing = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!locked) {
                            val dx = kotlin.math.abs(e.x - x0)
                            val dy = kotlin.math.abs(e.y - y0)
                            if (dx > slop || dy > slop) {
                                locked = true
                                // 2·|dy| ≥ |dx| treats anything from straight-
                                // vertical down to ~63° as "vertical-ish".
                                stealing = dy * 2f >= dx
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        locked = false; stealing = false
                    }
                }
                return stealing
            }

            override fun onTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent) { /* not used */ }
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) { /* not used */ }
        })
    }

    /** Open drawer (swipe-up) or search (swipe-down). Called from the
     *  displacement-based path; the fling path in [rootGestures] uses
     *  identical pre-conditions and effectively becomes redundant for
     *  ordinary speeds — kept around for very fast flings that haven't
     *  travelled the full 80dp by the time the finger lifts. */
    private fun tryFireVerticalAction(dy: Float) {
        // Drawer / search are home-page-only. Anything else taking over the
        // screen — chat (pager 0), the clippings inbox (rightmost), an open
        // overlay, an active drag — should suppress the gesture so the user
        // doesn't accidentally pile a new overlay on top of what they're
        // already looking at, or — for clippings — so the inner card list
        // can scroll without the drawer popping in on every swipe.
        if (pager.currentItem == 0) return
        if (pager.currentItem == pagerAdapter.clippingsPosition()) return
        if (appDrawer.visibility == android.view.View.VISIBLE) return
        if (searchPanel.visibility == android.view.View.VISIBLE) return
        if (overviewPanel.visibility == android.view.View.VISIBLE) return
        if (currentFolderOverlay?.isShowing() == true) return
        if (pageDragState != null) return
        // Edit mode owns the gesture surface — drag-to-move, pinch-to-zoom-out
        // for the overview, and the edit bar's Done button. Drawer / search
        // would land on top of the edit canvas and confuse the flow.
        if (editMode) return
        if (dy < 0) {
            showAppDrawer()
        } else {
            // Swipe-DOWN reserves the top ~100dp of screen for the system
            // notification-shade gesture; only fire universal search when
            // the gesture STARTED below that strip.
            val statusBarGuard = 100f * resources.displayMetrics.density
            if (gestureDownY > statusBarGuard) showSearch()
        }
    }

    /** Hit-test the current home page's children: is (rawX, rawY) inside a
     *  StockWidgetCell or GeneratedWidgetCell? Those have internal scrolling
     *  (lists, WebView content) that must not collide with root-level flings. */
    private fun touchIsOnWidgetCell(rawX: Float, rawY: Float): Boolean {
        val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
        val holder = rv.findViewHolderForAdapterPosition(pager.currentItem) ?: return false
        val grid = (holder as? HomePagerAdapter.HomeHolder)?.grid ?: return false
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = rawX - loc[0]
        val y = rawY - loc[1]
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child is com.iappyx.launcher.cells.StockWidgetCell ||
                child is com.iappyx.launcher.cells.GeneratedWidgetCell) return true
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientationPref()
        lastConfigOrientation = resources.configuration.orientation
        // Capture the app context for icon-pack resolution before any icon
        // render runs (IconCell.bind happens after layout is bound below).
        com.iappyx.launcher.cells.IconMask.attach(this)
        setContentView(R.layout.activity_launcher)

        // Edge-to-edge: window content extends behind the status + nav bars so
        // the app-drawer panel (and anything else) can paint its own solid
        // background all the way to the screen edges. Each element that
        // actually needs to dodge the system bars gets the insets via listener.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        wireSystemBarInsets()
        installRootDragListener()

        hostManager = AppWidgetHostManager(this)
        // Start listening BEFORE the adapter binds its first widget-hosting page
        // so the host is already registered with the AppWidgetService when
        // provider.onUpdate fires. onStart calls it again (idempotent).
        try { hostManager.startListening() } catch (_: Exception) {}
        store = PlacementStore(this)
        layout = store.load()

        pager = findViewById(R.id.pager)
        pagerAdapter = HomePagerAdapter(this, layout)
        pager.adapter = pagerAdapter
        // Re-centre the chrome strip once the first page is laid out — the
        // function reads grid + dock measurements, so it must run AFTER
        // layout. Posting twice covers the case where the first post fires
        // before the grid view holder has been bound.
        pager.post { recenterChromeStrip() }
        pager.postDelayed({ recenterChromeStrip() }, 100)
        // Eat vertical-leaning gestures before ViewPager2's inner RecyclerView
        // sees them. ViewPager2's RV has its own scaledTouchSlop on dx and
        // can start a drag the instant |dx| > slop, even when the user's
        // intent is clearly vertical — `requestDisallowInterceptTouchEvent`
        // from the activity is racy because the inner-RV drag classifier may
        // already have committed. OnItemTouchListener consumes touch ahead
        // of that classifier. Heuristic mirrors Google's NestedScrollableHost
        // sample: any gesture where 2·|dy| >= |dx| is "vertical-ish" and
        // belongs to the launcher's drawer / search gesture, not to paging.
        installVerticalGestureGuard()
        // Land on the first home page (index 1), not the AI command page (index 0).
        // Command is reachable by swiping right.
        pager.setCurrentItem(1, false)
        // Initial chrome state for the home page (dock + gear visible).
        applyDockAndGearAlpha(1f)
        // Subscribe to display-state changes so screen-off pauses widget
        // sensors / RAF / JS timers. Activity.onPause does NOT fire on
        // screen-off when the launcher is the home app (the activity stays
        // resumed while the display dozes), so without this hook a compass
        // or sensor-driven widget keeps sampling for the entire screen-off
        // window. Detached in onDestroy.
        widgetLifecycle.attach()
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                refreshHomeIndicator()
                updateChromeForPage(position)
                // Refresh "page X/N" suffix in the edit bar.
                if (editMode) updateEditBar()
                // Per-swipe scheduleWallpaperLayoutBroadcast() removed —
                // the wallpaper's reaction to layout broadcasts caused
                // a visible tile-rebuild flicker on every page settle.
                // Layout broadcasts still fire on cell add/remove/move
                // and on resume — that's enough for cell-aware effects.
                // Pause widgets on the pages the user just left; resume the
                // current page's widgets. Respects each widget's meta-tag
                // keepAlive policy.
                applyWidgetVisibilityForCurrentPage()
                // Recenter the chrome strip for the now-visible page's
                // actual content. ChromeStripController computes off the
                // CURRENT page's max occupied row, so dense ↔ sparse
                // page transitions get a strip position that matches
                // each page's visible content. onPageSelected fires
                // ONCE per settle (not per-frame during scroll), so the
                // layoutParams mutation doesn't ripple as it would in
                // an onPageScrolled loop.
                scheduleChromeStripRecenter()
            }
            override fun onPageScrollStateChanged(state: Int) {
                // Track pager scroll state so wallpaper-layout broadcasts can
                // gate on IDLE — see [doBroadcastLayoutToWallpaper]. Without
                // this, ViewPager2 lazy-binding an adjacent page mid-swipe
                // triggers renderPage → broadcast → which captures grid
                // pixel positions while they're translated by the in-flight
                // scroll, sending corrupted cell coordinates to the
                // wallpaper. The wallpaper then renders an "in-between"
                // state until the next broadcast corrects it.
                val idle = state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
                wallpaperPublisher.setScrollIdle(idle)
                if (idle) {
                    // Per-swipe wallpaper broadcast removed (see above).
                    // Per-swipe recenterChromeStrip() removed — mutating
                    // strip.layoutParams on every settle forced an
                    // activity_root layout pass that rippled into the pager's
                    // WebView children and caused a visible tile-rebuild
                    // flicker. Initial post-create centering still runs.
                }
            }
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // Position 0 = command panel — indicator hides (the layout
                // doesn't reserve space for it there and it isn't needed:
                // the user is already at the command bar). Real pages start
                // at position 1. Clippings page (rightmost) keeps the
                // indicator visible so the paperclip can light up.
                val clipPos = pagerAdapter.clippingsPosition()
                if (position >= 1) {
                    homeIndicator.visibility = android.view.View.VISIBLE
                    // Park the worm on the last home dot once we're sliding
                    // into clippings — the worm must not cross into the
                    // paperclip slot.
                    val dotsCount = layout.pages.size + (if (editMode) 1 else 0)
                    val rawDotIdx = (position - 1).coerceAtLeast(0)
                    val dotIdx = rawDotIdx.coerceAtMost((dotsCount - 1).coerceAtLeast(0))
                    val dotOffset = if (position - 1 >= dotsCount - 1) 0f else positionOffset
                    homeIndicator.setScroll(dotIdx, dotOffset)
                } else if (position == 0 && positionOffset > 0f) {
                    homeIndicator.visibility = android.view.View.VISIBLE
                    homeIndicator.setScroll(0, positionOffset)
                } else {
                    homeIndicator.visibility = android.view.View.GONE
                }
                // AI-pill activity (1 = fully on command, 0 = on home).
                // ViewPager2: when position=0, offset rises 0→1 as user moves
                // toward home page 1 — so commandActivity = 1 - offset there.
                val cmdActivity = if (position == 0) (1f - positionOffset) else 0f
                homeIndicator.setCommandActivity(cmdActivity)
                // Paperclip activity mirrors AI pill on the right edge.
                //  - position == clipPos - 1, offset rising 0→1 → activity 0→1
                //  - position == clipPos                       → activity 1
                //  - anywhere else                              → 0
                val clipActivity = when {
                    position == clipPos -> 1f
                    position == clipPos - 1 -> positionOffset
                    else -> 0f
                }
                homeIndicator.setClippingsActivity(clipActivity)
                // Cross-fade the dock + gear smoothly while the command page is
                // being swiped in/out so the chrome change feels continuous.
                // ViewPager2 semantics: `position` is the first visible page,
                // `positionOffset` is the fraction toward the next page.
                //  - position=0, offset=0    → fully on command → crossfade=0 (no chrome)
                //  - position=0, offset=0.5  → halfway to home  → crossfade=0.5
                //  - position=0, offset=1    → at home page 1   → crossfade=1
                //  - position>=1             → past command     → crossfade=1
                val crossfade = if (position == 0) positionOffset else 1f
                applyDockAndGearAlpha(crossfade)
            }
        })

        // Configurable page transformer — style picked from LauncherPrefs.
        // See `LauncherPrefs.pageTransitionStyle` for the supported values and
        // the per-style branch below for the actual math. Settings change is
        // picked up on the very next swipe (the pref is read each frame).
        installNormalTransformer()

        dockPager = findViewById(R.id.dock_pager)
        dockAdapter = DockPagerAdapter(this, layout)
        dockPager.adapter = dockAdapter
        // Pre-warm the installed-apps + Settings-activities caches on a
        // background thread so the first swipe-up (drawer) and first
        // swipe-down (universal search) don't block on a synchronous PM
        // query + per-app loadIcon.
        com.iappyx.launcher.widget.AppRegistry.prewarm(this)
        // Pre-render every icon the home + dock layout will need into the
        // IconMask cache on a background thread. By the time the user does
        // their first home swipe, IconCell.bind hits a warm cache instead of
        // paying ~5-15ms per icon. Best-effort — cache misses fall back to
        // the existing cold-render path.
        com.iappyx.launcher.cells.IconMask.prewarm(this, layout)
        // After both adapters are wired and `layout` is live: drop any
        // ICON / folder-item placements that reference apps not installed
        // on this device. Catches imports from another phone where some
        // packages don't exist locally — those slots would otherwise sit
        // silently empty (occupying a dock slot but rendering as a blank
        // tile and blocking the "+" affordance).
        sweepMissingPackagesOnce()
        dockPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { refreshDockIndicator() }
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // Drive the worm-style dot indicator's animation as the dock
                // pager scrolls — same feel as the home page indicator.
                if (dockIndicator.visibility == android.view.View.VISIBLE) {
                    dockIndicator.setScroll(position, positionOffset)
                }
            }
        })

        // Wallpaper parallax — as the home pager scrolls, drift the wallpaper
        // horizontally so swiping feels like moving across a wider canvas.
        // Index 0 (command panel) is excluded; the parallax range maps the
        // home pages 1..N onto wallpaper offsets 0..1.
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                val homePages = layout.pages.size + (if (editMode) 1 else 0)
                val total = (homePages - 1).coerceAtLeast(1)
                val homePos = (position - 1 + positionOffset).coerceAtLeast(0f)
                val offset = (homePos / total).coerceIn(0f, 1f)
                try {
                    val wm = android.app.WallpaperManager.getInstance(this@LauncherActivity)
                    val token = window.decorView.windowToken ?: return
                    wm.setWallpaperOffsets(token, offset, 0.5f)
                } catch (_: Exception) { /* wallpaper engine may not support it */ }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                resetActiveBackTarget()
                // Folder overlay is now a View, not a Dialog — back used to
                // dismiss it for free; we have to do it explicitly.
                if (currentFolderOverlay?.isShowing() == true) {
                    currentFolderOverlay?.dismiss()
                    currentFolderOverlay = null
                    return
                }
                if (overviewPanel.visibility == android.view.View.VISIBLE) { onOverviewHide(); return }
                if (searchPanel.visibility == android.view.View.VISIBLE) { searchPanel.onRequestHide?.invoke(); return }
                // IMPORTANT: route through onRequestHide, not appDrawer.hide()
                // directly. The latter only slides the drawer down — it
                // skips animateHomeBehind(opening=false), which is what
                // brings the home pager back to alpha=1f after it was
                // dimmed to 0.15f on drawer-open. Bypassing it leaves the
                // home content visibly translucent.
                if (appDrawer.visibility == android.view.View.VISIBLE) {
                    appDrawer.onRequestHide?.invoke()
                    return
                }
                if (editMode) { setEditMode(false); return }
                // Default: no-op (HOME — don't let back close the launcher).
            }

            // Predictive back (Android 14+ swipe-from-edge gesture) —
            // smoothly previews the dismiss as the user swipes, springs
            // back if they cancel. Pre-API 34 these methods are no-ops
            // and the activity behaves exactly as before (instant
            // dismiss on press).
            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                applyBackProgress(0f)
            }
            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                applyBackProgress(backEvent.progress)
            }
            override fun handleOnBackCancelled() {
                resetActiveBackTarget()
            }
        })

        wireIndicators()
        refreshHomeIndicator()
        refreshDockIndicator()

        findViewById<android.view.View>(R.id.home_settings_btn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.home_profile_btn).setOnClickListener {
            showProfileQuickSwitch()
        }

        wireAppDrawerPanel()

        // Profile auto-switch: bridge owns ProfileWatcher + the
        // ProfilesActivity resume/reschedule receiver + idempotent
        // geofence re-registration. We get a callback when the matcher
        // picks a new profile.
        profileBridge.start()

        // Clippings-changed broadcast — fired by ShareReceiverActivity when
        // a new clipping is added. Bridge owns the receiver; we re-render
        // here when it fires.
        clippingsRefreshBridge.start()

        // Settings → Clear chat history broadcast — controller owns
        // the receiver and the lazy-session-aware clear.
        commandPanelController.start()
        // (geofence re-registration moved into ProfileBridge.start())

        offerSetAsDefaultIfNeeded()
        maybeShowFirstRunHero()
        if (!editMode) SupportPrompt.maybeShow(this)
        refreshProfileChipVisibility()
    }

    /** Atomically swap to [profile]: writes its snapshot to the live
     *  state via [com.iappyx.launcher.profile.ProfileApplier], then
     *  refreshes the visible UI (re-loads layout, repaints adapters,
     *  invalidates icon-mask cache, broadcasts wallpaper change).
     *  Also called manually from [ProfilesActivity]'s "Switch to" flow
     *  when the user is in the launcher window. */
    fun applyProfileSwap(profile: com.iappyx.launcher.model.Profile) {
        com.iappyx.launcher.profile.ProfileApplier.apply(this, profile)
        // Re-load the layout from the now-updated PlacementStore so the
        // activity's in-memory `layout` matches what was just written.
        layout = store.load()
        pagerAdapter.setLayout(layout)
        dockAdapter.setLayout(layout)
        notifyIconFiltersChanged()
        refreshHomeIndicator()
        refreshDockIndicator()
        // Wallpaper process needs the new layout JSON, not just the new
        // wallpaper ID — without this, the new wallpaper renders against
        // the previous profile's grid bounding boxes.
        scheduleWallpaperLayoutBroadcast()
        android.widget.Toast.makeText(
            this,
            getString(R.string.profile_swap_toast_format, profile.name),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** Show/hide the home-screen profile quick-switch chip. Visible only
     *  when at least one profile exists AND we're not in edit mode (the
     *  edit bar would clutter the strip). Called from onCreate, onResume,
     *  and the various drawer/overview enter/exit paths so the chip's
     *  visibility tracks the gear's. */
    private fun refreshProfileChipVisibility() {
        val chip = findViewById<android.view.View>(R.id.home_profile_btn) ?: return
        val hasProfiles = com.iappyx.launcher.profile.ProfileLibrary.all(this).isNotEmpty()
        chip.visibility = if (hasProfiles && !editMode) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    /** Bottom sheet listing all profiles for one-tap switching, with a
     *  "Manage profiles…" footer linking to ProfilesActivity. Active
     *  profile gets a subtle highlight + checkmark so the user can see
     *  what's currently applied. */
    private fun showProfileQuickSwitch() {
        val profiles = com.iappyx.launcher.profile.ProfileLibrary.all(this)
        if (profiles.isEmpty()) {
            startActivity(Intent(this, ProfilesActivity::class.java))
            return
        }
        val activeSlug = LauncherPrefs(this).activeProfileSlug
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.Theme_iappyxLauncher_BottomSheet,
        )
        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(com.iappyx.launcher.widget.Palette.bgHome(this@LauncherActivity))
        }
        // Grabber
        root.addView(android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(android.graphics.Color.argb(0x44, 0xFF, 0xFF, 0xFF))
            }
            val lp = android.widget.LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (16 * dp).toInt()
            layoutParams = lp
        })
        root.addView(android.widget.TextView(this).apply {
            setText(R.string.profile_quickswitch_title)
            setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(this@LauncherActivity))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        for (p in profiles) {
            val isActive = p.slug == activeSlug
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(com.iappyx.launcher.widget.Palette.bgCell(this@LauncherActivity))
                    val strokeColor =
                        if (isActive) com.iappyx.launcher.widget.Palette.accent(this@LauncherActivity)
                        else android.graphics.Color.argb(0x22, 0xFF, 0xFF, 0xFF)
                    setStroke((if (isActive) 2 else 1).times(dp.toInt()), strokeColor)
                }
                val pad = (16 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    sheet.dismiss()
                    if (!isActive) applyProfileSwap(p)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            val text = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            text.addView(android.widget.TextView(this).apply {
                this.text = p.name
                setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(this@LauncherActivity))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            text.addView(android.widget.TextView(this).apply {
                this.text = describeTriggerForChip(p.trigger)
                setTextColor(com.iappyx.launcher.widget.Palette.textSecondary(this@LauncherActivity))
                textSize = 12f
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            card.addView(text)
            if (isActive) {
                card.addView(android.widget.TextView(this).apply {
                    setText(R.string.profile_quickswitch_chip_active_check)
                    setTextColor(com.iappyx.launcher.widget.Palette.accent(this@LauncherActivity))
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                })
            }
            root.addView(card)
        }
        // Manage profiles footer
        root.addView(android.widget.TextView(this).apply {
            setText(R.string.profile_quickswitch_manage)
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@LauncherActivity))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1 * dp).toInt(), android.graphics.Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            }
            setOnClickListener {
                sheet.dismiss()
                startActivity(Intent(this@LauncherActivity, ProfilesActivity::class.java))
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * dp).toInt() }
        })
        sheet.setContentView(root)
        sheet.expandFully()
        sheet.show()
        sheet.themeContent()
    }

    private fun describeTriggerForChip(t: com.iappyx.launcher.model.ProfileTrigger): String =
        when (t) {
            is com.iappyx.launcher.model.ProfileTrigger.WifiSsid ->
                getString(R.string.profile_chip_wifi_format, t.ssid)
            is com.iappyx.launcher.model.ProfileTrigger.WifiDisconnected ->
                getString(R.string.profile_chip_wifi_disconnected)
            is com.iappyx.launcher.model.ProfileTrigger.AndroidAuto ->
                getString(R.string.profile_chip_android_auto)
            is com.iappyx.launcher.model.ProfileTrigger.Geofence ->
                getString(R.string.profile_chip_geofence)
            is com.iappyx.launcher.model.ProfileTrigger.BluetoothDeviceConnected ->
                getString(R.string.profile_chip_bt_format, t.label)
            is com.iappyx.launcher.model.ProfileTrigger.TimeOfDay ->
                getString(R.string.profile_chip_time_of_day)
            is com.iappyx.launcher.model.ProfileTrigger.ChargerConnected ->
                getString(R.string.profile_chip_charger)
            is com.iappyx.launcher.model.ProfileTrigger.Manual ->
                getString(R.string.profile_chip_manual)
        }

    // ── Predictive back (Android 14+) ──────────────────────────────
    //
    // Resolved per-frame from the active overlay state. Same priority
    // order as the back-press handler. We deliberately only animate
    // short-lived overlays (drawer / search / overview / folder) — the
    // home pager itself is excluded because an interrupted back
    // gesture (e.g. process paused mid-swipe) could otherwise leave
    // it stuck at partial alpha and freeze the home in a translucent
    // state. Edit-mode dismissal has its own scale animation; no
    // benefit from layering predictive-back on top.
    private fun activeBackTarget(): android.view.View? {
        if (currentFolderOverlay?.isShowing() == true) {
            return findViewById(R.id.activity_root)
        }
        if (overviewPanel.visibility == android.view.View.VISIBLE) return overviewPanel
        if (searchPanel.visibility == android.view.View.VISIBLE) return searchPanel
        if (appDrawer.visibility == android.view.View.VISIBLE) return appDrawer
        return null
    }

    /** Drive a subtle scale-down + fade on the active overlay so the
     *  user gets a live preview of where the gesture will land. Bounded
     *  shrink (8%) and fade (50%) so the overlay still reads while the
     *  finger is mid-swipe. */
    private fun applyBackProgress(progress: Float) {
        val target = activeBackTarget() ?: return
        val p = progress.coerceIn(0f, 1f)
        val scale = 1f - 0.08f * p
        target.scaleX = scale
        target.scaleY = scale
        target.alpha = 1f - 0.5f * p
    }

    /** Spring the overlay back to neutral when the user cancels the
     *  back gesture by lifting / sliding back. Also called immediately
     *  before [handleOnBackPressed] commits a real dismiss so the
     *  panel's own hide animation starts from a clean transform. */
    private fun resetActiveBackTarget() {
        val target = activeBackTarget() ?: return
        target.animate().cancel()
        target.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    private fun maybeShowFirstRunHero() {
        val prefs = LauncherPrefs(this)
        if (!prefs.firstRunPending) return
        val hero = findViewById<android.view.View>(R.id.first_run_hero)
        hero.visibility = android.view.View.VISIBLE
        hero.alpha = 0f
        hero.animate().alpha(1f).setDuration(380L).start()
        val dismiss: () -> Unit = {
            prefs.firstRunPending = false
            hero.animate().alpha(0f).setDuration(220L)
                .withEndAction { hero.visibility = android.view.View.GONE }.start()
        }
        hero.setOnClickListener { dismiss() }
    }

    // ── App drawer overlay ────────────────────────────────────

    private val appDrawer by lazy {
        findViewById<com.iappyx.launcher.widget.AppDrawerPanel>(R.id.app_drawer_panel)
    }

    // ── Pinch-to-overview ────────────────────────────────────

    private val overviewPanel by lazy {
        findViewById<com.iappyx.launcher.widget.OverviewPanel>(R.id.overview_panel)
    }

    private fun showOverview() {
        // Reuse the existing system-bar tint + home-content-step-back animation.
        findViewById<android.view.View>(R.id.home_settings_btn).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.home_profile_btn).visibility = android.view.View.GONE
        // Edit bar is for cell editing — irrelevant in the page reorder screen.
        // Hide it cleanly so it doesn't draw on top of the overview (it has
        // elevation=6dp and would otherwise float above) and so its "Done"
        // button can't be accidentally tapped (which would exit edit mode).
        if (editBar.visibility == android.view.View.VISIBLE) {
            editBar.animate().alpha(0f).setDuration(140L).withEndAction {
                editBar.visibility = android.view.View.INVISIBLE
            }.start()
        }
        setSystemBarsForDrawer(true)
        animateHomeBehind(opening = true, upward = true)
        wireOverviewPanel()
        overviewPanel.show(layout, currentPageIndex())
    }

    private fun onOverviewHide() {
        overviewPanel.hide()
        setSystemBarsForDrawer(false)
        animateHomeBehind(opening = false, upward = true)
        // Restore chrome state correctly for whatever mode we're returning to.
        // Edit mode → bring the edit bar back. Non-edit mode → bring the gear
        // back. Never both.
        if (editMode) {
            editBar.visibility = android.view.View.VISIBLE
            editBar.animate().alpha(1f).setDuration(160L).start()
        } else {
            findViewById<android.view.View>(R.id.home_settings_btn)
                .visibility = android.view.View.VISIBLE
            refreshProfileChipVisibility()
        }
    }

    private fun wireOverviewPanel() {
        overviewPanel.onRequestHide = { onOverviewHide() }
        overviewPanel.onJumpTo = { idx ->
            // OverviewPanel returns a home-page index (0-based); pager position is +1 (command at 0).
            pager.setCurrentItem(idx.coerceIn(0, layout.pages.size - 1) + 1, true)
            onOverviewHide()
        }
        overviewPanel.onReorder = { from, to ->
            if (from in layout.pages.indices && to in layout.pages.indices) {
                val moving = layout.pages.removeAt(from)
                layout.pages.add(to, moving)
                store.save(layout)
                pagerAdapter.setLayout(layout)
                refreshHomeIndicator()
            }
        }
        overviewPanel.onRename = { idx ->
            if (idx in layout.pages.indices) {
                val current = layout.pages[idx].name
                val input = android.widget.EditText(this).apply {
                    setText(current)
                    hint = getString(R.string.rename_page_dialog_hint)
                    setSelection(text.length)
                }
                val container = android.widget.FrameLayout(this).apply {
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, 0)
                    addView(input)
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.rename_page_dialog_title)
                    .setView(container)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_ok) { _, _ ->
                        val newName = input.text.toString().trim().take(40)
                        layout.pages[idx].name = newName
                        store.save(layout)
                        overviewPanel.refresh(layout)
                    }
                    .showThemed()
            }
        }
        overviewPanel.onAddPage = {
            // Append a new empty page; refresh thumbnails + pager. Stay in
            // overview so the user can keep arranging — they can tap the new
            // thumbnail to jump to it (or hit ← to return to edit mode there).
            layout.pages.add(com.iappyx.launcher.model.Page())
            store.save(layout)
            pagerAdapter.setLayout(layout)
            refreshHomeIndicator()
            overviewPanel.refresh(layout)
            overviewPanel.scrollToPage(layout.pages.size - 1)
        }
        overviewPanel.onDelete = { idx ->
            if (layout.pages.size > 1 && idx in layout.pages.indices) {
                // Only allow deleting empty pages without confirmation; the rest
                // ask first to avoid accidental wipes.
                val target = layout.pages[idx]
                if (target.placements.isEmpty()) {
                    layout.pages.removeAt(idx)
                    store.save(layout)
                    pagerAdapter.setLayout(layout)
                    overviewPanel.refresh(layout)
                    refreshHomeIndicator()
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.delete_page_dialog_title_format, idx + 1))
                        .setMessage(getString(R.string.delete_page_dialog_message_format, target.placements.size))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            // Clean up stock-widget allocations before drop.
                            target.placements.filter { it.type == CellType.STOCK_WIDGET }
                                .forEach { it.appWidgetId?.let(hostManager::deleteId) }
                            layout.pages.removeAt(idx)
                            store.save(layout)
                            pagerAdapter.setLayout(layout)
                            overviewPanel.refresh(layout)
                            refreshHomeIndicator()
                        }.showThemed()
                }
            }
        }
    }

    private fun wireAppDrawerPanel() {
        appDrawer.onRequestHide = {
            appDrawer.hide()
            setSystemBarsForDrawer(false)
            animateHomeBehind(opening = false, upward = true)
            // Restore the persistent top-right gear button now that the ✕ is gone.
            if (!editMode) findViewById<android.view.View>(R.id.home_settings_btn)
                .visibility = android.view.View.VISIBLE
            refreshProfileChipVisibility()
        }
        appDrawer.onAddToHome = { entry ->
            val pageIndex = currentPageIndex()
            val spot = findFirstEmptyCell(pageIndex)
            if (spot != null) {
                val (r, c) = spot
                addPlacement(pageIndex, Placement(
                    id = Placement.newId(),
                    type = CellType.ICON,
                    row = r, col = c, wSpan = 1, hSpan = 1,
                    packageName = entry.packageName,
                    activityName = entry.activityName,
                ))
            } else {
                android.widget.Toast.makeText(
                    this, R.string.no_empty_space_toast, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        installHomeGridDropListener()
    }

    /** Finds the first free (row, col) on [pageIndex], top-left scan. */
    private fun findFirstEmptyCell(pageIndex: Int): Pair<Int, Int>? {
        val page = layout.pages.getOrNull(pageIndex) ?: return null
        val occupied = Array(layout.rows) { BooleanArray(layout.cols) }
        for (p in page.placements) {
            for (r in p.row until p.row + p.hSpan) for (c in p.col until p.col + p.wSpan) {
                if (r in 0 until layout.rows && c in 0 until layout.cols) occupied[r][c] = true
            }
        }
        for (r in 0 until layout.rows) for (c in 0 until layout.cols) {
            if (!occupied[r][c]) return r to c
        }
        return null
    }

    /** Attaches an OnDragListener to the home pager so drops from the app
     *  drawer OR the dock land on the correct (row, col) of the current
     *  home grid. Also installs the remove-zone listener for dock deletes. */
    private fun installHomeGridDropListener() {
        pager.setOnDragListener { _, event ->
            val cd = event.clipDescription
            val fromDrawer = cd?.hasMimeType(com.iappyx.launcher.widget.AppDrawerPanel.DRAG_MIME) == true
            val fromDock = cd?.hasMimeType(DOCK_DRAG_MIME) == true
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> fromDrawer || fromDock
                android.view.DragEvent.ACTION_DRAG_ENTERED,
                android.view.DragEvent.ACTION_DRAG_LOCATION,
                android.view.DragEvent.ACTION_DRAG_EXITED -> true
                android.view.DragEvent.ACTION_DROP -> {
                    val clip = event.clipData
                    if (clip == null || clip.itemCount < 1) return@setOnDragListener false
                    val (row, col) = dropPointToCell(event.x, event.y) ?: return@setOnDragListener false
                    val pageIndex = currentPageIndex()
                    val page = layout.pages.getOrNull(pageIndex) ?: return@setOnDragListener false
                    // Anything covering (row, col) — 1×1 folder/icon we can merge
                    // into, or a larger widget we must NOT overwrite.
                    val blocker = page.placements.firstOrNull { p ->
                        row in p.row until (p.row + p.hSpan) && col in p.col until (p.col + p.wSpan)
                    }
                    val mergeable = blocker?.takeIf { it.wSpan == 1 && it.hSpan == 1 &&
                        (it.type == CellType.ICON || it.type == CellType.FOLDER) }
                    if (blocker != null && mergeable == null) {
                        // Target cell is inside a larger widget — refuse with haptic.
                        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                        return@setOnDragListener false
                    }

                    if (fromDock) {
                        // Move the existing dock placement to this cell on home.
                        val srcId = clip.getItemAt(0).text?.toString() ?: return@setOnDragListener false
                        val src = layout.dockPages.asSequence().flatten().firstOrNull { it.id == srcId }
                            ?: return@setOnDragListener false
                        // Detach from dock first.
                        deleteDockPlacement(srcId)
                        val placement = Placement(
                            id = Placement.newId(), type = CellType.ICON,
                            row = row, col = col, wSpan = 1, hSpan = 1,
                            packageName = src.packageName, activityName = src.activityName,
                        )
                        if (mergeable != null) {
                            mergeIntoFolder(pageIndex, placement, mergeable)
                        } else {
                            addPlacement(pageIndex, placement)
                        }
                        return@setOnDragListener true
                    }

                    // From the app drawer (or the folder drag-out — same MIME) —
                    // new icon. Guard against truncated clip payloads from
                    // cross-window drags.
                    val pkg = clip.getItemAt(0).text?.toString() ?: return@setOnDragListener false
                    val act = if (clip.itemCount >= 2) clip.getItemAt(1).text?.toString() else null

                    // Cancel-on-drop-back-to-source-folder: if the drag started
                    // inside a folder and the user dropped onto that SAME
                    // folder, treat it as a no-op. Without this guard,
                    // mergeIntoFolder would re-add the item AND
                    // applyPendingFolderItemRemoval would strip it out, so
                    // the icon ends up disappearing from its own folder.
                    val pending = pendingFolderItemRemoval
                    if (pending != null && mergeable != null &&
                        mergeable.id == pending.folderPlacementId &&
                        pending.packageName == pkg) {
                        pendingFolderItemRemoval = null
                        return@setOnDragListener true
                    }

                    val placement = Placement(
                        id = Placement.newId(), type = CellType.ICON,
                        row = row, col = col, wSpan = 1, hSpan = 1,
                        packageName = pkg, activityName = act,
                    )
                    if (mergeable != null) {
                        mergeIntoFolder(pageIndex, placement, mergeable)
                    } else {
                        addPlacement(pageIndex, placement)
                    }
                    // If this drag originated from a folder, the activity has
                    // a `pendingFolderItemRemoval` set; consume it now so the
                    // user sees the icon disappear from its source folder.
                    applyPendingFolderItemRemoval()
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    // Always clear the folder-removal pending flag, whether
                    // the drop landed on a valid cell or was rejected.
                    pendingFolderItemRemoval = null
                    true
                }
                else -> false
            }
        }
    }


    /** Translate a drop point (in pager coords) into a (row, col) on the current grid. */
    private fun dropPointToCell(x: Float, y: Float): Pair<Int, Int>? {
        val holder = (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.findViewHolderForAdapterPosition(pager.currentItem)
            ?: return null
        val grid = (holder as? HomePagerAdapter.HomeHolder)?.grid ?: return null
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val pagerLoc = IntArray(2).also { pager.getLocationOnScreen(it) }
        val screenX = pagerLoc[0] + x
        val screenY = pagerLoc[1] + y
        val lx = screenX - loc[0]
        val ly = screenY - loc[1]
        val spacing = (4 * resources.displayMetrics.density)
        val cw = (grid.width - spacing * (layout.cols + 1)) / layout.cols
        val ch = (grid.height - spacing * (layout.rows + 1)) / layout.rows
        val col = ((lx - spacing) / (cw + spacing)).toInt().coerceIn(0, layout.cols - 1)
        val row = ((ly - spacing) / (ch + spacing)).toInt().coerceIn(0, layout.rows - 1)
        return row to col
    }

    private var lastFieldLaunchAt = 0L

    /** Screen-coord positions of the current home page's app icons, for the
     *  Field morph. Direct ICON cells map their own rect; FOLDER cells map each
     *  contained app to the folder's rect (apps appear to emerge from folders). */
    private fun captureHomeIconRects(): HashMap<String, FloatArray> {
        val map = HashMap<String, FloatArray>()
        try {
            val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return map
            val holder = rv.findViewHolderForAdapterPosition(pager.currentItem) ?: return map
            val grid = (holder as? HomePagerAdapter.HomeHolder)?.grid ?: return map
            val loc = IntArray(2)
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i)
                val p = child.tag as? com.iappyx.launcher.model.Placement ?: continue
                child.getLocationOnScreen(loc)
                val cx = loc[0] + child.width / 2f
                val cy = loc[1] + child.height / 2f
                val size = minOf(child.width, child.height).toFloat()
                when (p.type) {
                    com.iappyx.launcher.model.CellType.ICON ->
                        p.packageName?.let { map[it] = floatArrayOf(cx, cy, size) }
                    com.iappyx.launcher.model.CellType.FOLDER -> {
                        // Fan the folder's apps into a small preview-sized cluster at the
                        // folder's spot so they emerge from it (not stacked as one icon).
                        val fsz = size * 0.46f
                        p.folderItems.forEachIndexed { idx, item ->
                            val ang = idx * 2.39996f
                            val rad = size * 0.13f * kotlin.math.sqrt(idx.toFloat())
                            map[item.packageName] = floatArrayOf(
                                cx + kotlin.math.cos(ang) * rad,
                                cy + kotlin.math.sin(ang) * rad, fsz,
                            )
                        }
                    }
                    else -> {}
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun showAppDrawer() {
        // Opt-in alternative: launch The Field instead of the standard drawer.
        if (LauncherPrefs(this).appDrawerStyle == "field") {
            // Debounce: the swipe-up path can fire showAppDrawer twice in quick
            // succession, which (with singleTop) races into TWO Field instances.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFieldLaunchAt < 800L) return
            lastFieldLaunchAt = now
            try {
                // Hand the Field the on-screen positions of this page's icons so
                // it can morph them out of the home screen into the organism.
                com.iappyx.launcher.fieldnative.FieldHandoff.startRects = captureHomeIconRects()
                startActivity(android.content.Intent(this, com.iappyx.launcher.fieldnative.FieldNativeActivity::class.java))
                overridePendingTransition(0, 0)   // no slide — the Field morphs in itself
                return
            } catch (_: Throwable) { /* fall through to standard drawer */ }
        }
        // Hide the persistent top-right gear so the drawer's ✕ is unambiguous.
        findViewById<android.view.View>(R.id.home_settings_btn).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.home_profile_btn).visibility = android.view.View.GONE
        setSystemBarsForDrawer(true)
        // Home content drifts UP + fades, making room for the drawer coming up.
        animateHomeBehind(opening = true, upward = true)
        appDrawer.show()
    }

    /**
     * Animate the home content (pager + indicator + dock) as a drawer opens
     * or closes. Opening: slight translate in the direction OPPOSITE to the
     * panel's entry — gives a tactile "push" feeling, and fades + scales down
     * so the home reads as "stepped back behind the drawer". Closing: reverse.
     *
     * @param upward true when the drawer rises from the bottom (home moves up);
     *               false when the drawer descends from the top (home moves down).
     */
    private fun animateHomeBehind(opening: Boolean, upward: Boolean) {
        val dp = resources.displayMetrics.density
        val offset = when {
            !opening -> 0f
            upward -> -32f * dp
            else -> 32f * dp
        }
        val alpha = if (opening) 0.15f else 1f
        val scale = if (opening) 0.94f else 1f
        val views = listOf<android.view.View>(
            pager,
            findViewById(R.id.home_indicator),
            findViewById(R.id.dock_bar),
        )
        for (v in views) {
            v.animate().cancel()
            v.animate()
                .translationY(offset).alpha(alpha).scaleX(scale).scaleY(scale)
                .setDuration(if (opening) 280L else 240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
    }

    // ── Universal search overlay ──────────────────────────────

    private val searchPanel by lazy {
        findViewById<com.iappyx.launcher.widget.SearchPanel>(R.id.search_panel)
    }

    private fun showSearch() {
        findViewById<android.view.View>(R.id.home_settings_btn).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.home_profile_btn).visibility = android.view.View.GONE
        setSystemBarsForDrawer(true)
        // Home drifts DOWN + fades, making room for the search panel descending.
        animateHomeBehind(opening = true, upward = false)
        searchPanel.onRequestHide = {
            searchPanel.hide()
            setSystemBarsForDrawer(false)
            animateHomeBehind(opening = false, upward = false)
            if (!editMode) findViewById<android.view.View>(R.id.home_settings_btn)
                .visibility = android.view.View.VISIBLE
            refreshProfileChipVisibility()
        }
        searchPanel.show()
    }

    /** Install the user's chosen page-transition transformer (the "normal" one). */
    private fun installNormalTransformer() {
        pager.setPageTransformer { page, position -> applyPageTransform(page, position) }
    }

    /** Install the edit-mode page transformer.
     *
     *  Pages slide flat side-by-side, no fancy effect. We can't just call
     *  `pager.setPageTransformer(null)` though — without a per-frame
     *  transformer, ViewPager2 leaves stale transforms (translationX, alpha,
     *  scaleX, rotationY) from the previous fancy transformer baked onto
     *  off-screen page wrappers AND onto individual cells inside each grid
     *  (staggered-slide / scatter / tilt set per-cell translationX). When the
     *  user swipes to a page in edit mode they'd see those residual
     *  transforms — narrow stripe, ghost positions, etc.
     *
     *  Instead we install a transformer whose only job is to reset every page
     *  + every cell to neutral on each frame. ViewPager2's natural side-by-
     *  side layout still drives the slide; we just keep visuals clean. */
    private fun installEditModeFlatTransformer() {
        pager.setPageTransformer { page, _ ->
            page.translationX = 0f; page.translationY = 0f; page.translationZ = 0f
            page.rotationX = 0f; page.rotationY = 0f
            page.scaleX = 1f; page.scaleY = 1f; page.alpha = 1f
            // Cells inside the home grid wrapper. The command page (pager
            // index 0) isn't a HomeGrid wrapper — skip it.
            val grid = (page as? android.view.ViewGroup)?.let { vg ->
                if (vg is com.iappyx.launcher.widget.HomeGrid) vg
                else (0 until vg.childCount).asSequence()
                    .map { vg.getChildAt(it) }
                    .filterIsInstance<com.iappyx.launcher.widget.HomeGrid>()
                    .firstOrNull()
            } ?: return@setPageTransformer
            for (i in 0 until grid.childCount) {
                val c = grid.getChildAt(i)
                c.translationX = 0f; c.translationY = 0f; c.rotationY = 0f
                c.scaleX = 1f; c.scaleY = 1f
            }
        }
    }

    /** Routes ViewPager2 swipes through the JSON-spec engine. Every transition
     *  (bundled in `assets/transitions/` or AI-generated in
     *  `filesDir/transitions/`) is a JSON spec; the spec owns every page +
     *  cell property it touches. We just clear stale state from the previous
     *  spec, then hand off to [TransitionSpec.apply]. */
    private fun applyPageTransform(page: android.view.View, position: Float) {
        val grid = (page as? android.view.ViewGroup)?.let { vg ->
            if (vg is com.iappyx.launcher.widget.HomeGrid) vg
            else (0 until vg.childCount).asSequence()
                .map { vg.getChildAt(it) }
                .filterIsInstance<com.iappyx.launcher.widget.HomeGrid>()
                .firstOrNull()
        }
        val pageHeight = page.height.toFloat()
        val pageWidth = page.width.toFloat()
        if (pageHeight <= 0f || pageWidth <= 0f) return
        val abs = kotlin.math.abs(position)

        fun resetPage() {
            page.translationX = 0f; page.translationY = 0f; page.translationZ = 0f
            page.alpha = 1f
            page.scaleX = 1f; page.scaleY = 1f
            page.rotationX = 0f; page.rotationY = 0f
            page.pivotX = pageWidth / 2f; page.pivotY = pageHeight / 2f
            if (Build.VERSION.SDK_INT >= 31) page.setRenderEffect(null)
        }
        fun resetCells() {
            if (grid == null) return
            for (i in 0 until grid.childCount) {
                val c = grid.getChildAt(i)
                // Symmetric with TransitionSpec.resetView — clears every
                // property that ANY spec might touch. Without translationZ
                // here, switching from a transition that sets cell.translationZ
                // (e.g. an AI-generated one with depth-pop semantics) to one
                // that doesn't (e.g. horizontal) leaves a hardware-layer
                // promotion stuck on the cell, which displaces the inner
                // WebView's surface and makes widget content appear empty
                // or vertically cropped while the cell's own background still
                // draws. Same logic for rotationX, pivots, cameraDistance,
                // and the blur RenderEffect.
                c.translationX = 0f; c.translationY = 0f; c.translationZ = 0f
                c.rotationX = 0f; c.rotationY = 0f
                c.scaleX = 1f; c.scaleY = 1f
                c.alpha = 1f; c.rotation = 0f
                c.pivotX = c.width / 2f; c.pivotY = c.height / 2f
                c.cameraDistance = 1280f * resources.displayMetrics.density
                if (Build.VERSION.SDK_INT >= 31) c.setRenderEffect(null)
            }
        }

        if (abs >= 0.999f) {
            resetPage(); page.alpha = 0f; resetCells(); return
        }

        resetCells()
        val style = LauncherPrefs(this).pageTransitionStyle
        val spec = com.iappyx.launcher.transitions.TransitionLibrary.specFor(this, style)
            ?: com.iappyx.launcher.transitions.TransitionLibrary.specFor(this, "horizontal")
        resetPage()
        spec?.apply(page, position)

        // After a swipe settles (abs(p)≈0), nudge each widget's WebView so
        // Chromium re-measures the cell and re-allocates its compositor
        // tiles. Cell-transforming transitions (anything with `cell:` in
        // the JSON spec) push enough simultaneous hardware layers that
        // Chromium's tile-memory budget gets exceeded mid-swipe — content
        // drops out (compass / calendar visibly compressed to top half)
        // and doesn't recover without a forced layout pass. nudgeResize
        // does a requestLayout + invalidate + JS resize event.
        //
        // Gate on `spec.hasCellEvaluators` so page-only transitions (fade,
        // blur, zoom — they don't touch cells, don't pressure tile memory)
        // skip the recovery and avoid the cosmetic redraw blip it causes.
        // Internal debounce in nudgeResize caps this to one dispatch per
        // 250 ms per cell, so spam-firing on every settle frame is safe.
        if (abs < 0.001f && grid != null && spec?.hasCellEvaluators == true) {
            for (i in 0 until grid.childCount) {
                val c = grid.getChildAt(i)
                if (c is com.iappyx.launcher.cells.GeneratedWidgetCell) {
                    c.nudgeResize()
                }
            }
        }
    }

    /** Toggle launcher chrome (dock + persistent gear) + system-bar tint based
     *  on whether the user is on the AI command page (pager position 0) or a
     *  home page. The command page is full-bleed: dock hidden, bars tinted to
     *  bg_home so behind-the-clock + behind-the-nav-bar both pick up the
     *  panel's solid background. */
    private fun updateChromeForPage(position: Int) {
        val onCommand = position == 0
        applyDockAndGearAlpha(if (onCommand) 0f else 1f)
        // Tint system bars to match: they're transparent on the home pages
        // (wallpaper shows) and bg_home on the command page (so the clock
        // sits on the panel's solid background, not the wallpaper).
        setSystemBarsForDrawer(onCommand)
    }

    /** [factor] = 1 → home chrome fully visible, 0 → command chrome (no dock,
     *  no gear, full-bleed bg). Used for the cross-fade as the user swipes
     *  between the command page and home. */
    private fun applyDockAndGearAlpha(factor: Float) {
        val dock = findViewById<android.view.View>(R.id.dock_bar)
        val gear = findViewById<android.view.View>(R.id.home_settings_btn)
        val profile = findViewById<android.view.View>(R.id.home_profile_btn)
        val backdrop = findViewById<android.view.View>(R.id.command_backdrop)
        val dockIndicator = findViewById<android.view.View>(R.id.dock_indicator)
        dock.alpha = factor
        dockIndicator.alpha = factor
        // Backdrop is inverse — fully opaque on the command page so the
        // wallpaper (and status/nav bar area) is covered by solid bg_home.
        backdrop.alpha = 1f - factor
        // When fully at rest on the command page, GONE the dock + its indicator
        // so their ViewPager2 / child views stop intercepting touches meant for
        // the command input field. During the swipe (factor > 0) we keep them
        // VISIBLE so alpha can crossfade smoothly.
        val resting = factor <= 0f
        val targetVis = if (resting) android.view.View.GONE else android.view.View.VISIBLE
        if (dock.visibility != targetVis) dock.visibility = targetVis
        if (dockIndicator.visibility != targetVis) dockIndicator.visibility = targetVis
        // Gear is a single Button — isClickable=false is enough to stop touch
        // consumption. Avoid toggling its visibility here because edit mode
        // also drives it; leave that to the edit-mode codepath.
        gear.alpha = factor * 0.75f
        gear.isClickable = factor > 0.5f
        // Profile chip mirrors the gear's behavior on the command-page
        // crossfade. Visibility is owned by refreshProfileChipVisibility()
        // (which keys off profile count + editMode), same separation as the
        // gear.
        profile.alpha = factor * 0.75f
        profile.isClickable = factor > 0.5f
    }

    /** Tint the system status + nav bar to match the drawer panel background
     *  when it's open, so the full screen looks like one cohesive sheet. Revert
     *  to transparent on the home screen so the wallpaper flows edge-to-edge.
     *
     *  Setters are deprecated on SDK 35 edge-to-edge (no-ops; the drawer panel's
     *  bg_home background paints behind the bars). Kept for API 29-34 where they
     *  still suppress the system's auto contrast scrim. */
    @Suppress("DEPRECATION")
    private fun setSystemBarsForDrawer(drawerShown: Boolean) {
        val color = if (drawerShown)
            androidx.core.content.ContextCompat.getColor(this, R.color.bg_home)
        else android.graphics.Color.TRANSPARENT
        window.statusBarColor = color
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+ draws an automatic translucent scrim behind the bars
            // if it decides the app content needs contrast. We want the EXACT
            // bg_home color (drawer) or pure transparency (home) — disable that.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * Distribute system-bar insets to the elements that actually need to dodge
     * them. Root FrameLayout is not fitsSystemWindows, so the app-drawer panel
     * (which sits inside the root) extends edge-to-edge and its solid bg_home
     * background paints the status + nav bar areas.
     */
    private fun wireSystemBarInsets() {
        val root = findViewById<android.view.View>(R.id.activity_root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val top = bars.top
            val bottom = bars.bottom

            // Pager — pad inside so widgets don't go under the clock or nav bar.
            findViewById<ViewPager2>(R.id.pager).setPadding(0, top, 0, bottom)
            // Edit bar sits at the window top — its layout paddingTop / Bottom
            // were the visual breathing room around the chips; the status-bar
            // inset is ADDED on top so the bar still dodges the clock without
            // collapsing the chips inside.
            val editBarView = findViewById<android.view.View>(R.id.edit_bar)
            val basePadV = (12 * resources.displayMetrics.density).toInt()
            editBarView.setPadding(
                editBarView.paddingLeft,
                top + basePadV,
                editBarView.paddingRight,
                basePadV,
            )
            // The indicator + settings strip sits at marginBottom=126dp (well
            // above any nav/gesture inset on supported devices), so we leave
            // it where the XML places it. Adding `bottom` here would shift
            // the dots UP by the nav-bar height, which is exactly the visual
            // jump the user wants to avoid.
            insets
        }
    }

    // ── Dock ──────────────────────────────────────────────────

    private lateinit var dockPager: androidx.viewpager2.widget.ViewPager2
    private lateinit var dockAdapter: DockPagerAdapter

    /** Called by DockPagerAdapter when binding a page. */
    fun renderDockPage(row: android.widget.LinearLayout, dockPageIndex: Int) {
        row.removeAllViews()
        // Dock changes are visible to the wallpaper too — refresh its
        // bounding-box snapshot. Debounced; multiple dock-page rebuilds
        // (e.g. dockSlots change) collapse into one broadcast.
        scheduleWallpaperLayoutBroadcast()
        val dp = resources.displayMetrics.density
        // Virtual trailing page (edit mode) has no entry in dockPages yet — treat
        // it as an empty page so every slot still shows a "+" affordance.
        val dockPage = layout.dockPages.getOrNull(dockPageIndex) ?: emptyList<Placement>()
        val slotMargin = (4 * dp).toInt()
        val showLabels = LauncherPrefs(this).showDockLabels

        // Size slots to fit the pager width — fall back to 72dp if width is 0
        // (first measure). This makes the dock look right at any dockSlots value.
        val pagerWidth = if (dockPager.width > 0) dockPager.width else resources.displayMetrics.widthPixels
        val perSlotBudget = pagerWidth / layout.dockSlots
        val slotSize = (perSlotBudget - slotMargin * 2).coerceIn((52 * dp).toInt(), (76 * dp).toInt())

        for (slot in 0 until layout.dockSlots) {
            val placement = dockPage.firstOrNull { it.col == slot }
            val slotView = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(slotSize, slotSize).apply {
                    marginStart = slotMargin
                    marginEnd = slotMargin
                }
                // Drop target: receive dock placements for reorder/swap.
                setOnDragListener(makeDockSlotDropListener(dockPageIndex, slot))
            }
            if (placement != null && placement.type == CellType.APP_DRAWER) {
                // App-drawer launcher tile in a dock slot. Tap → open drawer.
                // Long-press → enter edit mode + select for remove/move.
                val cell = com.iappyx.launcher.cells.AppDrawerCell(this).apply {
                    showLabel = showLabels
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    tag = placement
                    setOnLongClickListener { view ->
                        if (editMode) {
                            startDockIconDrag(view, placement)
                        } else {
                            setEditMode(true)
                            selectedPlacement = placement
                            updateEditBar()
                        }
                        true
                    }
                    setOnClickListener {
                        if (editMode) {
                            selectedPlacement = placement
                            updateEditBar()
                        } else {
                            showAppDrawer()
                        }
                    }
                }
                slotView.addView(cell)
            } else if (placement != null && placement.type == CellType.ICON) {
                // Dock is a 1-row strip; rainbow gradient falls back to a
                // linear hue sweep across the slots (see RainbowMatrix).
                val dockGridPos = com.iappyx.launcher.cells.GridPos(
                    pageIndex = dockPageIndex, row = 0, col = placement.col,
                    cols = layout.dockSlots, rows = 1,
                )
                val icon = IconCell(this).apply {
                    launchOnClick = false // activity handles click (edit-aware)
                    showLabel = showLabels
                    placement.packageName?.let { bind(it, dockGridPos) }
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    tag = placement
                    setOnLongClickListener { view ->
                        if (editMode) {
                            // Already editing → start a system drag for reorder/delete.
                            startDockIconDrag(view, placement)
                        } else {
                            setEditMode(true)
                            selectedPlacement = placement
                            updateEditBar()
                        }
                        true
                    }
                    setOnClickListener {
                        if (editMode) {
                            selectedPlacement = placement
                            updateEditBar()
                        } else {
                            placement.packageName?.let { pkg ->
                                val i = packageManager.getLaunchIntentForPackage(pkg)
                                i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (i != null) {
                                    LauncherPrefs(this@LauncherActivity).recordAppLaunch(pkg)
                                    startActivity(i)
                                }
                            }
                        }
                    }
                }
                slotView.addView(icon)
            } else if (editMode) {
                // Only show the "+" add-affordance in edit mode — consistent with
                // the home grid's edit-only empty-cell chips, and keeps the dock
                // visually clean while browsing.
                val plus = android.widget.TextView(this).apply {
                    text = "+"
                    textSize = 26f
                    setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.dock_slot_bg)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    setOnClickListener { showDockAddChooser(dockPageIndex, slot) }
                }
                slotView.addView(plus)
            }
            row.addView(slotView)
        }

        // "Add slot" appendix — only when in edit mode AND the dock page
        // has zero remaining empty slots. Without this, a user with a full
        // dock has no obvious way to add more icons; they'd have to dive
        // into Settings → Apply grid. Tap → grows dockSlots by 1 (capped
        // by available pager width so slots can't get squashed below the
        // 52dp readability minimum).
        val occupiedCount = dockPage.size
        if (editMode && occupiedCount >= layout.dockSlots) {
            val canFit = run {
                val nextCount = layout.dockSlots + 1
                val needed = nextCount * ((52 + 2 * 4) * dp).toInt()
                needed <= pagerWidth
            }
            if (canFit) {
                val addSlot = android.widget.TextView(this).apply {
                    text = "+"
                    textSize = 22f
                    setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * dp
                        setStroke((1 * dp).toInt(),
                            android.graphics.Color.parseColor("#3FFFFFFF"))
                        setColor(android.graphics.Color.parseColor("#1FFFFFFF"))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        slotSize, slotSize,
                    ).apply {
                        marginStart = slotMargin; marginEnd = slotMargin
                    }
                    contentDescription = getString(R.string.dock_add_slot_cd)
                    setOnClickListener { growDock() }
                }
                row.addView(addSlot)
            }
        }
    }

    /** Add one slot to the dock, persist, and refresh. Bound to the "Add
     *  slot" appendix in [renderDockPage]; only invoked when there's room
     *  for one more slot at the readable minimum size. */
    private fun growDock() {
        val newSlots = layout.dockSlots + 1
        val newLayout = layout.copy(dockSlots = newSlots)
        layout = newLayout
        store.save(layout)
        dockAdapter.setLayout(layout)
        // Page indicator + grid summary in the edit bar may both reflect
        // the new dock width.
        refreshDockIndicator()
        updateEditBar()
    }

    // ── Dock edit mode: drag to reorder / delete / move-to-home ───────

    /** Starts a system drag carrying the dock placement id. Any slot / the
     *  home pager / the remove-zone registered as a drop target handles it. */
    private fun startDockIconDrag(view: android.view.View, p: Placement) {
        val clip = android.content.ClipData(
            android.content.ClipDescription("iappyxDock", arrayOf(DOCK_DRAG_MIME)),
            android.content.ClipData.Item(p.id),
        )
        val shadow = android.view.View.DragShadowBuilder(view)
        view.startDragAndDrop(clip, shadow, p, 0)
    }

    /** Drop listener for a single dock slot. Accepts a dock-placement drag and
     *  performs a swap: if the target slot is empty, the source moves in; if
     *  occupied, the two placements trade positions. */
    private fun makeDockSlotDropListener(dockPageIndex: Int, slot: Int): android.view.View.OnDragListener {
        return android.view.View.OnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(DOCK_DRAG_MIME) == true
                android.view.DragEvent.ACTION_DRAG_ENTERED,
                android.view.DragEvent.ACTION_DRAG_LOCATION,
                android.view.DragEvent.ACTION_DRAG_EXITED -> true
                android.view.DragEvent.ACTION_DROP -> {
                    val srcId = event.clipData?.getItemAt(0)?.text?.toString() ?: return@OnDragListener false
                    moveDockPlacement(srcId, dockPageIndex, slot)
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }
    }

    /** Move the placement with [srcId] to (destPage, destSlot). If another
     *  placement occupies the destination slot, swap them. */
    private fun moveDockPlacement(srcId: String, destPage: Int, destSlot: Int) {
        var src: Placement? = null
        var srcPage = -1
        for ((i, page) in layout.dockPages.withIndex()) {
            val found = page.firstOrNull { it.id == srcId } ?: continue
            src = found; srcPage = i; break
        }
        if (src == null || srcPage < 0) return
        // Materialize a virtual trailing dock page on drop.
        while (layout.dockPages.size <= destPage) layout.dockPages.add(mutableListOf())
        if (srcPage == destPage && src.col == destSlot) return
        val destList = layout.dockPages[destPage]
        val destExisting = destList.firstOrNull { it.col == destSlot }
        layout.dockPages[srcPage].remove(src)
        if (destExisting != null) {
            // Swap: put dest's icon where src came from.
            destList.remove(destExisting)
            layout.dockPages[srcPage].add(destExisting.copy(col = src.col))
        }
        destList.add(src.copy(col = destSlot))
        store.save(layout)
        dockAdapter.setLayout(layout)
        refreshDockIndicator()
    }

    /** Delete a dock placement by id (called when the user drops on remove-zone). */
    private fun deleteDockPlacement(srcId: String) {
        var removed = false
        for (page in layout.dockPages) {
            val found = page.firstOrNull { it.id == srcId }
            if (found != null) { page.remove(found); removed = true; break }
        }
        if (!removed) return
        // Prune trailing empty dock pages — but ONLY when not in edit mode.
        // While editing, the trailing virtual page is rendered as an empty
        // page so the user has somewhere to drop a new icon; pruning it
        // here would yank it out from under the drop. Mirrors the
        // exit-edit-mode prune (setEditMode(false)).
        if (!editMode) {
            while (layout.dockPages.size > 1 && layout.dockPages.last().isEmpty()) {
                layout.dockPages.removeAt(layout.dockPages.size - 1)
            }
        }
        store.save(layout)
        dockAdapter.setLayout(layout)
        refreshDockIndicator()
    }

    private fun showDockAppPicker(dockPageIndex: Int, slot: Int) {
        pendingDockPick = dockPageIndex to slot
        pickAppForDockLauncher.launch(
            Intent(this, AppPickerActivity::class.java).apply {
                putExtra(AppPickerActivity.EXTRA_TITLE, getString(R.string.app_picker_title_add_to_dock))
            }
        )
    }

    /** Shown when the user taps an empty dock slot in edit mode. Two options:
     *  pick an app, or drop in the special "All apps" launcher tile. */
    private fun showDockAddChooser(dockPageIndex: Int, slot: Int) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.Theme_iappyxLauncher_BottomSheet,
        )
        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(com.iappyx.launcher.widget.Palette.bgHome(this@LauncherActivity))
        }
        // Grabber
        root.addView(android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(android.graphics.Color.argb(0x44, 0xFF, 0xFF, 0xFF))
            }
            val lp = android.widget.LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = (16 * dp).toInt()
            layoutParams = lp
        })
        root.addView(android.widget.TextView(this).apply {
            setText(R.string.dock_add_sheet_title)
            setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(this@LauncherActivity))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        fun row(@androidx.annotation.DrawableRes iconRes: Int, title: String, subtitle: String, onClick: () -> Unit): android.widget.LinearLayout {
            val card = android.widget.LinearLayout(this@LauncherActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16 * dp
                    setColor(com.iappyx.launcher.widget.Palette.bgCell(this@LauncherActivity))
                    setStroke((1 * dp).toInt(), android.graphics.Color.argb(0x22, 0xFF, 0xFF, 0xFF))
                }
                val p = (16 * dp).toInt()
                setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
                setOnClickListener { sheet.dismiss(); onClick() }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * dp).toInt() }
            }
            val accent = com.iappyx.launcher.widget.Palette.accent(this@LauncherActivity)
            card.addView(android.widget.ImageView(this@LauncherActivity).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x33))
                }
                val s = (44 * dp).toInt()
                val pad = (10 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = android.widget.LinearLayout.LayoutParams(s, s).apply {
                    marginEnd = (14 * dp).toInt()
                }
            })
            val text = android.widget.LinearLayout(this@LauncherActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            text.addView(android.widget.TextView(this@LauncherActivity).apply {
                this.text = title
                setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(this@LauncherActivity))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            text.addView(android.widget.TextView(this@LauncherActivity).apply {
                this.text = subtitle
                setTextColor(com.iappyx.launcher.widget.Palette.textSecondary(this@LauncherActivity))
                textSize = 12f
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            card.addView(text)
            return card
        }
        root.addView(row(R.drawable.ic_smartphone,
            getString(R.string.dock_add_app_icon_title),
            getString(R.string.dock_add_app_icon_subtitle)) {
            showDockAppPicker(dockPageIndex, slot)
        })
        root.addView(row(R.drawable.ic_apps,
            getString(R.string.dock_add_all_apps_title),
            getString(R.string.dock_add_all_apps_subtitle)) {
            addAppDrawerToDock(dockPageIndex, slot)
        })
        sheet.setContentView(root)
        sheet.expandFully()
        sheet.show()
        sheet.themeContent()
    }

    private fun addAppDrawerToDock(dockPageIndex: Int, slot: Int) {
        // Materialize a dock page if dropping on the trailing virtual one.
        if (dockPageIndex == layout.dockPages.size) {
            layout.dockPages.add(mutableListOf())
        } else if (dockPageIndex !in layout.dockPages.indices) {
            return
        }
        val dockPage = layout.dockPages[dockPageIndex]
        // Replace anything already in this slot (defensive — empty slots are
        // the only ones that show the "+" affordance, but keep it safe).
        dockPage.removeAll { it.col == slot }
        dockPage.add(Placement(
            id = Placement.newId(),
            type = CellType.APP_DRAWER,
            row = 0, col = slot, wSpan = 1, hSpan = 1,
        ))
        store.save(layout)
        dockAdapter.setLayout(layout)
        refreshDockIndicator()
    }

    fun refreshDock() { dockAdapter.setLayout(layout) }

    // ── Indicators (thin bottom bars) ─────────────────────────

    /** Recomputes the home chrome strip's vertical position; never call
     *  on a per-swipe path (mutates layoutParams → tile-rebuild flicker). */
    private val chromeStripController by lazy {
        ChromeStripController(
            activity = this,
            pager = pager,
            stripId = R.id.home_chrome_strip,
            dockBarId = R.id.dock_bar,
            layoutProvider = { layout },
        )
    }

    private fun recenterChromeStrip() = chromeStripController.recenter()

    /** Keep every home page resident so a keepAlive widget (radio player,
     *  live tracker) isn't destroyed when its page scrolls out of ViewPager2's
     *  small offscreen cache (onViewRecycled → destroyWidget). Called from
     *  [GeneratedWidgetCell.bind] when a keepAlive widget actually binds, so
     *  the extra memory is only spent once such a widget is present. Idempotent
     *  and only ever raises the limit. */
    fun ensureKeepAlivePagesResident() {
        val want = pagerAdapter.itemCount.coerceAtLeast(1)
        if (pager.offscreenPageLimit < want) pager.offscreenPageLimit = want
    }

    /** Schedule a chrome-strip recenter after the current layout pass.
     *  Called from every user-driven layout mutation (add / remove /
     *  move / resize / folder merge) so the page indicator strip tracks
     *  changes in row occupancy. Without this hook, recenter only fires
     *  at startup — adding a cell to the bottom row later leaves the
     *  strip at its initial (now-overlapping) position. */
    private fun scheduleChromeStripRecenter() {
        pager.post { recenterChromeStrip() }
    }

    private val homeIndicator by lazy {
        findViewById<com.iappyx.launcher.widget.WormIndicator>(R.id.home_indicator).also { ind ->
            // Tap-to-jump: dot index 0 = first home page = pager position 1
            // (position 0 is the command panel). Mapping is applied here so
            // the indicator stays naive about the command-panel offset.
            ind.onDotClick = { pageIndex ->
                val target = pageIndex + 1
                if (pager.currentItem != target) pager.setCurrentItem(target, true)
            }
            // The leading "AI" pill represents the AI Command page (pager
            // pos 0). Tap → animate pager onto the command page. Activity
            // (0..1) is fed from onPageScrolled below.
            ind.showCommandGlyph = true
            ind.onCommandClick = {
                if (pager.currentItem != 0) pager.setCurrentItem(0, true)
            }
            // Long-press → voice command. Held-to-talk pattern.
            ind.onCommandLongPress = { startVoiceCommand() }
            ind.onCommandLongPressEnd = { finishVoiceCommand() }
            ind.onCommandLongPressCancel = { cancelVoiceCommand() }
            // Trailing paperclip = Clippings page (rightmost pager position).
            // Activity (0..1) is fed from onPageScrolled, mirroring the AI pill.
            ind.showClippingsGlyph = true
            ind.onClippingsClick = {
                val target = pagerAdapter.clippingsPosition()
                if (pager.currentItem != target) pager.setCurrentItem(target, true)
            }
        }
    }

    // ── Voice command (long-press AI pill) ─────────────────────────
    private var pendingVoiceStart: Boolean = false

    private val voiceController = VoiceController(
        activity = this,
        rootViewId = R.id.activity_root,
        onRecordingStateChanged = { recording -> homeIndicator.setRecording(recording) },
        onTranscript = { text ->
            // Navigate to command page only after a real result lands —
            // doing it earlier was racing the live-recognizer touch gesture
            // and generating spurious ACTION_CANCEL on the indicator.
            if (pager.currentItem != 0) pager.setCurrentItem(0, true)
            commandSession.send(text)
        },
    )

    private val requestRecordAudioLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingVoiceStart
        pendingVoiceStart = false
        if (granted && pending) {
            voiceController.start()
        } else if (!granted) {
            android.widget.Toast.makeText(
                this, "Voice input needs microphone permission",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            homeIndicator.setRecording(false)
        }
    }

    /** Long-press fired on AI pill — kick off speech recognition (or
     *  request mic permission first). */
    private fun startVoiceCommand() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceStart = true
            requestRecordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceController.start()
    }

    private fun finishVoiceCommand() { voiceController.finish() }
    private fun cancelVoiceCommand() {
        // Clear the pending-grant flag too — releasing the AI pill while
        // the mic-permission dialog is still up would otherwise let a
        // later grant fire startVoiceCommand() with no finger on the pill.
        pendingVoiceStart = false
        voiceController.cancel()
    }

    private val dockIndicator by lazy {
        findViewById<com.iappyx.launcher.widget.WormIndicator>(R.id.dock_indicator).also { ind ->
            // Dock pager has no command-panel offset — index maps 1:1.
            ind.onDotClick = { pageIndex ->
                if (dockPager.currentItem != pageIndex) {
                    dockPager.setCurrentItem(pageIndex, true)
                }
            }
        }
    }

    private fun refreshHomeIndicator() {
        val realCount = layout.pages.size
        val virtualCount = realCount + (if (editMode) 1 else 0)
        // Dots only represent real home pages (+ optional virtual edit-mode
        // page). The AI pill on the left and the paperclip on the right are
        // separate glyphs in the indicator; they shouldn't show up as dots.
        homeIndicator.pageCount = virtualCount
        val onCommand = pager.currentItem == 0
        val onClippings = pager.currentItem == pagerAdapter.clippingsPosition()
        // The strip itself stays VISIBLE on the clippings page so the
        // paperclip lights up; only hide it on the AI command page where
        // the indicator+pill are intentionally absent.
        if (virtualCount <= 1 && !showClippingsAsAccent()) {
            // No home pages and no clippings glyph → strip is empty. Hide.
            homeIndicator.visibility = android.view.View.GONE
            return
        }
        if (onCommand) {
            homeIndicator.visibility = android.view.View.GONE
            return
        }
        homeIndicator.visibility = android.view.View.VISIBLE
        // Map pager position → dot index. Positions 1..N are home pages,
        // position N+1 (no edit) or N+2 (edit) is clippings → clamp to last
        // dot so the worm parks on the rightmost home dot while the user is
        // on the clippings page.
        val rawDotIdx = (pager.currentItem - 1).coerceAtLeast(0)
        val dotIdx = if (onClippings) (virtualCount - 1).coerceAtLeast(0) else rawDotIdx
        homeIndicator.setScroll(dotIdx, 0f)
    }

    /** True while `home_indicator.showClippingsGlyph` is on — the paperclip
     *  is visible and should keep the indicator strip drawn even if there
     *  are zero home pages. */
    private fun showClippingsAsAccent(): Boolean = homeIndicator.showClippingsGlyph

    /** Find the currently bound clippings page (if any) and trigger a list
     *  refresh. Cheap no-op when the holder isn't materialised — the next
     *  bind will read the latest clippings off disk anyway. */
    private fun refreshClippingsPage() {
        val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        val pos = pagerAdapter.clippingsPosition()
        val holder = rv.findViewHolderForAdapterPosition(pos) as?
            HomePagerAdapter.ClippingsHolder ?: return
        holder.view.refresh()
    }

    /** Authoritative clipping deletion. ClippingsPageView used to do this
     *  itself via a fresh PlacementStore.load() → mutate → save, but
     *  that left [layout] (the in-memory copy used by the rest of the
     *  launcher) stale — any subsequent edit-mode commit, drag-drop, etc.
     *  would silently re-introduce the deleted clipping when it saved
     *  the stale in-memory state. Centralising the mutation here keeps
     *  in-memory and on-disk state in sync. */
    fun deleteClipping(widgetId: String) {
        val before = layout.clippings.size
        layout.clippings.removeAll { it.widgetId == widgetId }
        if (layout.clippings.size != before) store.save(layout)
        // Now safe to delete the on-disk widget directory — no live cell
        // can be re-bound to it after [refreshClippingsPage] runs.
        val dir = java.io.File(filesDir, "widgets/$widgetId")
        if (dir.exists()) dir.deleteRecursively()
        refreshClippingsPage()
    }

    private fun refreshDockIndicator() {
        val realCount = layout.dockPages.size
        // In edit mode the trailing virtual page also counts so the user sees
        // a dot they can swipe to in order to create a new dock page.
        val virtualCount = realCount + (if (editMode) 1 else 0)
        // Always sync pageCount even when hiding — applyDockAndGearAlpha flips
        // visibility VISIBLE/GONE during the command-page crossfade without
        // going through this function, so a stale count would surface as wrong
        // dots later (e.g. exiting edit mode then crossing back from command).
        dockIndicator.pageCount = virtualCount
        if (virtualCount <= 1) {
            dockIndicator.visibility = android.view.View.GONE
            return
        }
        dockIndicator.visibility = android.view.View.VISIBLE
        dockIndicator.setScroll(dockPager.currentItem, 0f)
    }

    private fun wireIndicators() {
        // The dock indicator is now a WormIndicator (display-only); no
        // per-page click / long-click hooks. Pages are reached by swiping the
        // dock pager itself, and trailing empties are auto-pruned on exit
        // from edit mode.
    }

    private fun offerSetAsDefaultIfNeeded() {
        val prefs: SharedPreferences = getSharedPreferences("iappyx_launcher_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("default_prompt_shown", false)) return

        val pm = packageManager
        val resolved = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val weAreDefault = resolved?.activityInfo?.packageName == packageName
        if (weAreDefault) {
            prefs.edit().putBoolean("default_prompt_shown", true).apply()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.set_home_dialog_title)
            .setMessage(R.string.set_home_dialog_message)
            .setPositiveButton(R.string.set_home_action_open_settings) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                catch (_: Exception) { /* not all devices expose this intent */ }
                prefs.edit().putBoolean("default_prompt_shown", true).apply()
            }
            .setNegativeButton(R.string.set_home_action_later) { _, _ ->
                prefs.edit().putBoolean("default_prompt_shown", true).apply()
            }
            .showThemed()
    }

    /** Forwards alarm-fired broadcasts to live widgets while foregrounded. */
    private val alarmDispatchBridge = com.iappyx.launcher.AlarmDispatchBridge(this)

    /** Forward permission results to the WidgetHost that requested them. */
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Search panel's own READ_CONTACTS request — nudge it to re-run the
        // current query so the Contacts section appears immediately on grant.
        if (requestCode == com.iappyx.launcher.widget.SearchPanel.REQ_READ_CONTACTS) {
            if (searchPanel.visibility == android.view.View.VISIBLE) searchPanel.rerunCurrentSearch()
            return
        }
        WidgetHost.activeRequests.remove(requestCode)?.handleRequestPermissionsResult(
            requestCode, permissions as Array<String>, grantResults
        )
    }

    override fun onStart() {
        super.onStart()
        hostManager.startListening()
        alarmDispatchBridge.start()
        // Battery saver awareness — bridge owns the cached value + receiver.
        powerSaveBridge.start()
        // Package install/uninstall broadcasts — bridge owns the receiver +
        // AppRegistry/IconMask invalidation, calls back for the layout work.
        packageReceiverBridge.start()
        registerWallpaperColorsListener()
    }

    override fun onStop() {
        alarmDispatchBridge.stop()
        packageReceiverBridge.stop()
        powerSaveBridge.stop()
        hostManager.stopListening()
        unregisterWallpaperColorsListener()
        super.onStop()
    }

    /** Sweep the layout for ICON placements + folder items whose packages
     *  aren't installed on this device, drop them, and notify the user.
     *  Runs once on activity start — catches the case where a backup
     *  imported from another device referenced apps that aren't on this
     *  one. Without this, those slots sit silently empty in the dock /
     *  home grid because they're "occupied" by an unresolvable placement
     *  (no "+" affordance, can't be deleted easily).
     *
     *  The per-package install check happens once per unique package via
     *  [Set.contains], not once per placement, so even a heavy layout
     *  resolves in <10ms on cold cache. */
    private fun sweepMissingPackagesOnce() {
        val pm = packageManager
        val seen = mutableMapOf<String, Boolean>()
        fun installed(pkg: String): Boolean = seen.getOrPut(pkg) {
            try { pm.getApplicationInfo(pkg, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        var removedIcons = 0
        var removedFolderItems = 0
        var changed = false
        fun scrub(placements: MutableList<com.iappyx.launcher.model.Placement>) {
            placements.removeAll { p ->
                val miss = p.type == CellType.ICON &&
                    p.packageName != null && !installed(p.packageName)
                if (miss) { removedIcons++; changed = true }
                miss
            }
            for (p in placements.toList()) {
                if (p.type != CellType.FOLDER) continue
                val before = p.folderItems.size
                p.folderItems.removeAll { !installed(it.packageName) }
                val dropped = before - p.folderItems.size
                if (dropped > 0) {
                    removedFolderItems += dropped; changed = true
                }
                if (p.folderItems.isEmpty() && before > 0) {
                    placements.remove(p)
                }
            }
        }
        for (page in layout.pages) scrub(page.placements)
        for (dockPage in layout.dockPages) scrub(dockPage)
        if (!changed) return
        store.save(layout)
        pagerAdapter.setLayout(layout)
        dockAdapter.setLayout(layout)
        scheduleWallpaperLayoutBroadcast()
        val parts = mutableListOf<String>()
        if (removedIcons > 0) parts += "$removedIcons icon${if (removedIcons == 1) "" else "s"}"
        if (removedFolderItems > 0) parts += "$removedFolderItems folder item${if (removedFolderItems == 1) "" else "s"}"
        android.widget.Toast.makeText(
            this,
            getString(R.string.missing_packages_swept_toast_format, parts.joinToString(" and ")),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun purgePackageFromLayout(pkg: String) {
        var changed = false
        // Helper: scrub a single page's placements list. Returns true if any
        // change happened. Removes ICON placements pointing at `pkg`,
        // removes `pkg` from FOLDER folderItems, and collapses now-empty
        // folders. Snapshot via `.toList()` so we can mutate while iterating.
        fun scrub(placements: MutableList<com.iappyx.launcher.model.Placement>): Boolean {
            var local = false
            placements.removeAll {
                val match = it.type == CellType.ICON && it.packageName == pkg
                if (match) local = true
                match
            }
            for (p in placements.toList()) {
                if (p.type != CellType.FOLDER) continue
                val before = p.folderItems.size
                p.folderItems.removeAll { it.packageName == pkg }
                if (p.folderItems.isEmpty()) {
                    placements.remove(p); local = true
                } else if (p.folderItems.size != before) {
                    local = true
                }
            }
            return local
        }
        for (page in layout.pages) if (scrub(page.placements)) changed = true
        for (dockPage in layout.dockPages) if (scrub(dockPage)) changed = true
        if (!changed) return
        store.save(layout)
        pagerAdapter.setLayout(layout)
        dockAdapter.setLayout(layout)
        // The wallpaper sees these placements as bounding boxes — let it
        // know one's gone so layout-aware effects don't keep drawing the
        // ghost rect.
        scheduleWallpaperLayoutBroadcast()
    }

    /** Drives WebView pause/resume across the pager's realized pages. */
    private val widgetLifecycle by lazy {
        WidgetLifecycleController(pager) { powerSaveBridge.isOn() }
    }

    private fun applyWidgetVisibilityForCurrentPage(): Unit = widgetLifecycle.applyForCurrentPage()

    /** Tracks battery-saver state for [applyWidgetVisibilityForCurrentPage] —
     *  saver-on forces every realized widget to pause regardless of which
     *  page is current. Started in onStart, stopped in onStop. */
    private val powerSaveBridge = com.iappyx.launcher.PowerSaveBridge(this) {
        applyWidgetVisibilityForCurrentPage()
    }

    private fun isPowerSaveMode(): Boolean = powerSaveBridge.isOn()

    /** Owns ACTION_PACKAGE_* receivers — keeps AppRegistry + icon cache
     *  fresh on install/uninstall and asks us to refresh layout state. */
    private val packageReceiverBridge = com.iappyx.launcher.PackageReceiverBridge(
        context = this,
        onPackageRemoved = { pkg -> purgePackageFromLayout(pkg) },
        onPackageAdded = {
            pagerAdapter.notifyDataSetChanged()
            dockAdapter.notifyDataSetChanged()
        },
    )

    private fun pauseAllRealizedWidgets(): Unit = widgetLifecycle.pauseAll()

    override fun onPause() {
        // Pause every realized widget so JS timers / RAF / animations don't
        // keep running while the user is in another app. Resumed on the
        // current page in onResume; off-screen pages stay paused until
        // their page is selected (handled in pager.onPageSelected).
        pauseAllRealizedWidgets()
        widgetLifecycle.onActivityPaused()
        // Tear down the voice recognizer + overlay if they're live. Without
        // this, leaving the activity mid-recognition leaves a full-screen
        // clickable overlay attached, so when we resume swipes never reach
        // the home pager.
        voiceController.cancel()
        voiceController.forceHideOverlay()
        super.onPause()
    }

    /** Tear down every realized widget cell when the activity is destroyed.
     *  Without this, generated widgets that were on-screen at destroy time
     *  stay in [WidgetHost.hostsByWidgetId] forever (their normal teardown
     *  path is [HomePagerAdapter.onViewRecycled], which doesn't run when the
     *  whole RecyclerView is discarded with the activity). The leaked hosts
     *  keep their sensors/audio/network listeners registered against a dead
     *  WebView and pin Activity-shaped state until process death. */
    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        widgetLifecycle.detach()
        voiceController.destroy()
        profileBridge.stop()
        clippingsRefreshBridge.stop()
        commandPanelController.stop()
        try {
            val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            if (rv != null) {
                for (i in 0 until rv.childCount) {
                    val pageRoot = rv.getChildAt(i) as? android.view.ViewGroup ?: continue
                    fun destroyCellsIn(vg: android.view.ViewGroup) {
                        for (j in 0 until vg.childCount) {
                            when (val c = vg.getChildAt(j)) {
                                is com.iappyx.launcher.cells.GeneratedWidgetCell -> c.destroyWidget()
                                is com.iappyx.launcher.cells.StockWidgetCell -> c.release()
                                is android.view.ViewGroup -> destroyCellsIn(c)
                            }
                        }
                    }
                    destroyCellsIn(pageRoot)
                }
            }
        } catch (_: Throwable) { /* best-effort cleanup */ }
        super.onDestroy()
    }

    /** Push the current Material You palette + dark/light mode to every live
     *  generated widget. Each widget's `:root` CSS variables get updated via a
     *  small JS snippet — no full reload, so widget state is preserved. */
    private fun broadcastThemeToWidgets() {
        val tokens = com.iappyx.launcher.cells.GeneratedWidgetCell.readThemeTokens(this)
        val overrides = com.iappyx.launcher.theme.ThemeOverrides.get(this)
        val js = com.iappyx.launcher.cells.GeneratedWidgetCell.buildThemeUpdateJs(tokens, overrides)
        for (host in WidgetHost.hostsByWidgetId.values) {
            host.evaluateJavaScript(js)
        }
    }

    /** Apply a theme change live: push tokens to widgets + re-theme native UI.
     *  Called by the on-device editor flow (onResume) and by the remote-edit
     *  web interface (ThemeApi) so both stay in sync. Must run on the UI thread. */
    fun applyThemeLive() {
        broadcastThemeToWidgets()
        window.decorView.setTag(R.id.theme_sig_tag, null)
        com.iappyx.launcher.widget.Palette.applyThemeToTree(window.decorView)
        applyNativeAccent()
        // The clippings filter pills set their accent fill at build time (the
        // tree-walk can't recolor a non-brand drawable fill), so rebuild them.
        (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.let { rv ->
            (rv.findViewHolderForAdapterPosition(pagerAdapter.clippingsPosition())
                as? HomePagerAdapter.ClippingsHolder)?.view?.reapplyTheme()
        }
    }

    private var lastConfigOrientation: Int = android.content.res.Configuration.ORIENTATION_UNDEFINED

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Covers dark/light flip (uiMode is in this activity's configChanges
        // declaration, so no recreate). Material You wallpaper changes without
        // a uiMode flip come through the WallpaperManager listener instead.
        broadcastThemeToWidgets()

        // Orientation flip while the activity is alive: PlacementStore now
        // returns layouts in *current-orientation* coordinates, so reloading
        // gets us the rotated (or un-rotated) view automatically. Then push
        // the new layout into both adapters and reset the pager so the
        // HomeGrid views re-create with the new cols/rows.
        if (lastConfigOrientation != android.content.res.Configuration.ORIENTATION_UNDEFINED &&
            newConfig.orientation != lastConfigOrientation
        ) {
            // DO NOT save here. The on-disk file is already in dominant
            // coords from every prior edit's save(). The in-memory
            // `layout` is in OLD-current coords. PlacementStore.save uses
            // the CURRENT context to decide rotation — by the time this
            // callback fires the new orientation is already active, so
            // save would mislabel old-current coords as new-current and
            // corrupt the file (the bug that produced the 4×6 → rotated
            // disaster). Just reload to pick up new-current coords.
            layout = store.load()
            pagerAdapter = HomePagerAdapter(this, layout)
            pagerAdapter.editMode = editMode
            pager.adapter = pagerAdapter
            pager.setCurrentItem(1, false)
            dockAdapter = DockPagerAdapter(this, layout)
            dockAdapter.editMode = editMode
            dockPager.adapter = dockAdapter
            refreshHomeIndicator()
            refreshDockIndicator()
            scheduleWallpaperLayoutBroadcast()
            // Re-attach the inner-RV vertical gesture guard. Setting a new
            // adapter doesn't always preserve the OnItemTouchListener — and
            // without the guard, vertical drags get hijacked by ViewPager2
            // and the swipe-up / swipe-down actions stop firing. Defensive
            // re-install: even if the listener stuck, the new copy uses the
            // same predicate and de-dupes harmlessly.
            pager.post { installVerticalGestureGuard() }
        }
        lastConfigOrientation = newConfig.orientation
    }

    /** Read [LauncherPrefs.allowRotation] + [LauncherPrefs.dominantOrientation]
     *  and lock / unlock the activity's orientation accordingly. Called from
     *  [onCreate] AND from [onResume] so changes made in Settings take effect
     *  on return without forcing a recreate.
     *
     *   - Allow rotation off + Portrait dominant → SCREEN_ORIENTATION_PORTRAIT
     *   - Allow rotation off + Landscape dominant → SCREEN_ORIENTATION_LANDSCAPE
     *   - Allow rotation on (any dominant) → SCREEN_ORIENTATION_UNSPECIFIED
     *     (system rotation toggle decides; the device sensor orients the view).
     *
     *  We don't reverse-portrait / reverse-landscape here — those are jarring
     *  on phones and rare on tablets. UNSPECIFIED already covers both
     *  natural and reverse on most devices. */
    private fun applyOrientationPref() {
        val prefs = LauncherPrefs(this)
        val target = if (prefs.allowRotation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else when (prefs.dominantOrientation) {
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else        -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != target) {
            requestedOrientation = target
        }
    }

    private var wallpaperColorsListener: android.app.WallpaperManager.OnColorsChangedListener? = null

    private fun registerWallpaperColorsListener() {
        if (Build.VERSION.SDK_INT < 27) return
        if (wallpaperColorsListener != null) return
        try {
            val wm = getSystemService(android.app.WallpaperManager::class.java) ?: return
            val listener = android.app.WallpaperManager.OnColorsChangedListener { _, _ ->
                // Material You re-derives system_accent1_* etc. on the next
                // resource lookup — push the new values to all live widgets.
                runOnUiThread {
                    broadcastThemeToWidgets()
                    // Wallpaper-themed icon filter: drop the cached palette
                    // and re-render so the new wallpaper colours are picked
                    // up immediately.
                    com.iappyx.launcher.cells.WallpaperPalette.invalidate()
                    if (LauncherPrefs(this).iconFilter == "wallpaper_themed" ||
                        LauncherPrefs(this).iconFilter == "mono_accent"
                    ) {
                        com.iappyx.launcher.cells.IconMask.clearCache()
                        pagerAdapter.notifyDataSetChanged()
                        dockAdapter.notifyDataSetChanged()
                    }
                }
            }
            wm.addOnColorsChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
            wallpaperColorsListener = listener
        } catch (_: Throwable) { /* device doesn't support it — no-op */ }
    }

    private fun unregisterWallpaperColorsListener() {
        val l = wallpaperColorsListener ?: return
        try {
            val wm = getSystemService(android.app.WallpaperManager::class.java)
            wm?.removeOnColorsChangedListener(l)
        } catch (_: Throwable) {}
        wallpaperColorsListener = null
    }

    /**
     * LauncherActivity is singleTask — once created it normally stays resident
     * for the life of the process, so onCreate only runs once. When Settings
     * saves a new grid, we need to pick it up here on return.
     */
    /** Last-seen icon filter so we can detect changes made in Settings and
     *  refresh visible icons through the new render path. */
    private var lastIconFilter: String? = null

    /** Last-seen icon-pack pref + mask-unthemed toggle, so a pack change made
     *  in Settings re-renders the home pages on resume (the dock rebinds on
     *  resume regardless, but the home pager keeps its pages to preserve
     *  state — it needs an explicit notify). */
    private var lastIconPack: String? = null
    private var lastMaskUnthemed: Boolean? = null

    /** Owns the WiFi + Android Auto + geofence trigger watch for the
     *  active profile. Started in onCreate, stopped in onDestroy. */
    /** Owns ProfileWatcher + auto-switch resume receiver + geofence
     *  re-registration. Started in onCreate, stopped in onDestroy. */
    private val profileBridge = ProfileBridge(this) { matched -> applyProfileSwap(matched) }
    /** Owns the CLIPPINGS_CHANGED broadcast receiver. */
    private val clippingsRefreshBridge = ClippingsRefreshBridge(this) {
        layout = store.load()
        pagerAdapter.setLayout(layout)
        refreshHomeIndicator()
        refreshClippingsPage()
    }

    /** Force every home + dock cell to re-render under the current
     *  [LauncherPrefs.iconFilter]. Called by [ManageIconFiltersTab] and
     *  the `generate_icon_filter` tool path after the user picks/creates
     *  a new filter so the change shows up instantly without waiting
     *  for an onResume cycle.
     *
     *  Scopes the pager-adapter notify to the home-page range only —
     *  pager position 0 hosts the AI command panel, and recycling that
     *  view wipes the running chat (every [createCommandPanel] call
     *  builds a fresh [CommandPanelHost]). The dock has no equivalent
     *  command surface so a full reset there is fine. */
    fun notifyIconFiltersChanged() {
        // Adapter notifies must hit the main thread. The AI tool path
        // (`generate_icon_filter`) calls us from the command-session
        // worker, which would otherwise throw CalledFromWrongThreadException.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { notifyIconFiltersChanged() }
            return
        }
        com.iappyx.launcher.cells.IconMask.clearCache()
        com.iappyx.launcher.cells.IconFilterRegistry.invalidateAll()
        val homePagesCount = pagerAdapter.itemCount - 1
        if (homePagesCount > 0) {
            pagerAdapter.notifyItemRangeChanged(1, homePagesCount)
        }
        dockAdapter.notifyDataSetChanged()
        lastIconFilter = LauncherPrefs(this).iconFilter
    }

    /** Last-seen useLongPressMenu pref. renderPage reads this each call, but
     *  HomeGrid keeps the callback from its last bind — toggling the pref in
     *  Settings doesn't take effect until we force a re-bind. */
    private var lastLongPressMenuPref: Boolean? = null

    // ── Wallpaper layout broadcast ───────────────────────────────
    // The live wallpaper subscribes to bounding-box updates so it can do
    // collision-aware animations (orbit-around, fill-the-gaps, bouncing
    // ball that dodges icons). RECTANGLES ONLY — no app/widget identity —
    // see [LayoutSerializer]. Debounced to ~100ms so a burst of layout
    // commits during, say, a multi-cell paste collapses into one broadcast.
    /** Publishes layout snapshots to the live-wallpaper process. Owns
     *  the coalesce handler, scroll-idle gate, dedupe state, and the
     *  off-main-thread snapshot writer. */
    private val wallpaperPublisher = WallpaperLayoutPublisher(
        activity = this,
        layoutProvider = { layout },
    )

    /** Convenience for callers that already named the old function;
     *  also keeps existing call-site grep findable. */
    fun scheduleWallpaperLayoutBroadcast() = wallpaperPublisher.schedule()

    override fun onResume() {
        super.onResume()
        runningInstance = this
        widgetLifecycle.onActivityResumed()
        // Theme editor changed the overrides → live-push to visible widgets +
        // force the persistent home content (grid/dock/drawer labels, edit bar)
        // to re-apply the native theme now. Those views were bound before the
        // change, so the app-wide walk's per-tree signature would otherwise skip
        // the already-themed decor; clearing the tag forces a fresh walk. This
        // only re-tints + re-fonts native views — it does NOT reload widgets.
        if (com.iappyx.launcher.theme.ThemeOverrides.consumeDirty()) {
            broadcastThemeToWidgets()
            window.decorView.setTag(R.id.theme_sig_tag, null)
            com.iappyx.launcher.widget.Palette.applyThemeToTree(window.decorView)
        }
        // Re-tint native chrome (edit bar etc.) so a custom accent follows here
        // too. Cheap no-op when no override is set.
        applyNativeAccent()
        // One-shot migration: if older builds dropped ambient (share-to-
        // launcher) widgets onto home pages, lift them off into the
        // dedicated clippings inbox. Idempotent — no-op when there's
        // nothing to migrate.
        try {
            val migrated = com.iappyx.launcher.widget.WidgetLibrary
                .migrateAmbientWidgetsToClippings(this)
            if (migrated > 0) {
                layout = store.load()
                pagerAdapter.setLayout(layout)
                dockAdapter.setLayout(layout)
                refreshHomeIndicator()
                refreshDockIndicator()
            }
        } catch (_: Throwable) { /* never block resume on a migration failure */ }
        // Sweep ambient clippings whose TTL expired while we were paused.
        // Cheap when there are zero clippings — early-returns.
        try {
            val expired = com.iappyx.launcher.widget.WidgetLibrary.expireAmbientWidgets(this)
            if (expired > 0) {
                layout = store.load()
                pagerAdapter.setLayout(layout)
                dockAdapter.setLayout(layout)
                refreshHomeIndicator()
                refreshDockIndicator()
            }
        } catch (_: Throwable) { /* never block resume on a sweep failure */ }
        // A new share may have been received while we were paused — pick up
        // the freshly-added clipping by reloading the layout (and force the
        // currently-bound clippings page, if any, to re-read its list).
        try {
            val freshLayout = store.load()
            val clipCountChanged = freshLayout.clippings.size != layout.clippings.size
            if (clipCountChanged) {
                layout = freshLayout
                pagerAdapter.setLayout(layout)
            }
            // Even when count didn't change, ask the page to refresh — locked
            // toggles / metadata edits don't change list size.
            refreshClippingsPage()
        } catch (_: Throwable) { /* best-effort */ }
        // Refresh widget.html of existing share widgets to the latest
        // bundled template — ensures old share cards pick up new rendering
        // (e.g. inline YouTube playback, restyle) on app upgrade. Identity
        // check via byte-equals avoids unnecessary writes.
        try {
            val refreshed = com.iappyx.launcher.widget.WidgetLibrary.refreshAmbientShareWidgets(this)
            if (refreshed > 0) {
                // Force generated widget cells to reload from disk by
                // triggering an adapter refresh; the WebViews bound to the
                // refreshed ids will reload from the new file.
                pagerAdapter.setLayout(layout)
                dockAdapter.setLayout(layout)
            }
        } catch (_: Throwable) { /* never block resume */ }
        // Defensive: if a predictive-back gesture was interrupted by an
        // activity pause / kill, the active overlay (drawer / search /
        // etc.) could have been left at scaleX/Y < 1 and alpha < 1 from
        // applyBackProgress. Restore neutral state so the user never sees
        // a translucent home or panel after returning.
        for (v in listOf<android.view.View>(
            pager, appDrawer, searchPanel, overviewPanel,
            findViewById(R.id.activity_root),
        )) {
            v.animate().cancel()
            v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        }
        // Voice overlay is full-screen and `isClickable = true`, so if it's
        // stuck at visibility=VISIBLE (recognizer didn't fire a terminal
        // callback before activity pause, or alpha animation was interrupted)
        // it silently swallows every touch — drawer/search swipes appear
        // dead. Force-hide on every resume; cheap when already gone.
        voiceController.cancel()
        voiceController.forceHideOverlay()
        homeIndicator.setRecording(false)
        // Pick up orientation pref changes made in Settings while we were
        // paused. Idempotent — applyOrientationPref bails when already on the
        // right target — so no work happens when prefs didn't change.
        applyOrientationPref()
        // Profiles list may have grown/shrunk while we were paused (in the
        // Profiles screen) — refresh the chip's visibility accordingly.
        refreshProfileChipVisibility()
        // API key may have been added or removed in Settings; reflect that
        // in the AI tab's empty-state. Cheap — refreshChatPane is a single
        // visibility toggle.
        commandPanelHost?.refreshChatPane()
        // Whatever side-activity was just dismissed (Nearby / QR receive,
        // file import, Settings) may have added or removed library entries.
        // showPane only fires refresh() on tab CHANGE, so a user who stays
        // on the same manage tab would miss the new entry. Refresh the
        // active pane on every resume — re-reads the directory listing
        // and rebinds the carousel adapter.
        commandPanelHost?.refreshActivePane()
        // Kick the notification listener to republish counts in case it was
        // bound while we weren't visible (or never had a chance to fire its
        // initial onListenerConnected before our cells got laid out).
        com.iappyx.launcher.notify.NotificationBadgeListener.forceRecount()
        // The wallpaper engine may have booted while we were paused (or have
        // been killed and restarted). Re-broadcast our current layout so its
        // bounding-box cache is fresh.
        scheduleWallpaperLayoutBroadcast()
        // Resume widgets on the currently-visible page (paired with the
        // pause-all in onPause). Posted so the pager has at least one
        // frame to settle before we walk its children — on cold start
        // pager.getChildAt(0) is null until the first layout pass.
        pager.post { applyWidgetVisibilityForCurrentPage() }
        // Detect icon-filter changes from Settings — drop the bitmap cache
        // and notify adapters so cells re-bind under the new filter.
        run {
            val nowFilter = LauncherPrefs(this).iconFilter
            if (lastIconFilter != null && lastIconFilter != nowFilter) {
                com.iappyx.launcher.cells.IconMask.clearCache()
                pagerAdapter.notifyDataSetChanged()
                dockAdapter.notifyDataSetChanged()
            }
            lastIconFilter = nowFilter
        }
        run {
            val nowPack = LauncherPrefs(this).iconPack
            val nowMask = LauncherPrefs(this).maskUnthemed
            val changed = (lastIconPack != null && lastIconPack != nowPack) ||
                (lastMaskUnthemed != null && lastMaskUnthemed != nowMask)
            if (changed) {
                com.iappyx.launcher.cells.IconMask.clearCache()
                pagerAdapter.notifyDataSetChanged()
                dockAdapter.notifyDataSetChanged()
            }
            lastIconPack = nowPack
            lastMaskUnthemed = nowMask
        }
        run {
            val nowLongPress = LauncherPrefs(this).useLongPressMenu
            if (lastLongPressMenuPref != null && lastLongPressMenuPref != nowLongPress) {
                pagerAdapter.notifyDataSetChanged()
            }
            lastLongPressMenuPref = nowLongPress
        }
        val fresh = store.load()
        val gridChanged = fresh.cols != layout.cols ||
            fresh.rows != layout.rows ||
            fresh.dockSlots != layout.dockSlots
        // Whole-layout content change (not just grid dims) — backup import
        // is the canonical case: pages + dock placements change but cols/
        // rows/dockSlots may stay equal, so the gridChanged check alone
        // would leave the activity holding a stale `layout` and the user
        // wouldn't see the imported icons until a process restart.
        val contentChanged = !gridChanged &&
            fresh.toJson().toString() != layout.toJson().toString()
        // Clean up AppWidget allocations for STOCK_WIDGETs that were in the old
        // layout but not in the new one (e.g. dropped by Settings grid shrink).
        // Without this, the allocation stays reserved in AppWidgetService and
        // the cached AppWidgetHostView leaks in our manager.
        run {
            val oldIds = layout.pages.asSequence().flatMap { it.placements.asSequence() }
                .filter { it.type == CellType.STOCK_WIDGET }
                .mapNotNull { it.appWidgetId }.toSet()
            val newIds = fresh.pages.asSequence().flatMap { it.placements.asSequence() }
                .filter { it.type == CellType.STOCK_WIDGET }
                .mapNotNull { it.appWidgetId }.toSet()
            for (orphan in oldIds - newIds) hostManager.deleteId(orphan)
        }
        if (gridChanged) {
            layout = fresh
            // Assigning a new adapter instance forces ViewPager2 to drop the old
            // HomeGrid views (which had the old cols/rows baked in) and create
            // fresh ones with the new dimensions.
            pagerAdapter = HomePagerAdapter(this, layout)
            pagerAdapter.editMode = editMode
            pager.adapter = pagerAdapter
            // Restore home position (index 1) — new adapter resets currentItem to 0 (command page).
            pager.setCurrentItem(1, false)
            dockAdapter = DockPagerAdapter(this, layout)
            dockAdapter.editMode = editMode
            dockPager.adapter = dockAdapter
            refreshHomeIndicator()
            refreshDockIndicator()
            sweepMissingPackagesOnce()
        } else if (contentChanged) {
            // Same grid, different placements — backup import path. Push
            // the new layout into both adapters and refresh; cells get
            // re-bound with the new content. No need to swap adapter
            // instances because cell shapes haven't changed.
            layout = fresh
            pagerAdapter.setLayout(layout)
            dockAdapter.setLayout(layout)
            refreshHomeIndicator()
            refreshDockIndicator()
            sweepMissingPackagesOnce()
        } else {
            // Cheap re-render so prefs changes (e.g. dock-label toggle) reflect.
            dockAdapter.setLayout(layout)
        }
    }

    // WebView teardown runs via HomePagerAdapter.onViewRecycled as the pager moves
    // between pages. For launcher activity finish (rare — singleTask), the process
    // typically stays alive and the OS handles WebView cleanup.

    // ── Called by HomePagerAdapter ────────────────────────────

    /** Lazily-created [com.iappyx.launcher.command.CommandSession] driving the
     *  AI command panel. Conversation state lives here so it survives pager
     *  recycle / re-bind. */
    /** AI command-panel surface (lazy session, host reference, factory,
     *  chat-clear receiver) — owned by the controller, accessed here via
     *  [commandSession] / [createCommandPanel] / [commandPanelHost].
     *  `by lazy` so we don't capture `store` during property init (it's
     *  a `lateinit var` initialized in `onCreate`). */
    private val commandPanelController by lazy {
        CommandPanelController(
            activity = this,
            store = store,
            listener = object : CommandPanelController.Listener {
                override fun getLayout() = layout
                override fun applyLayout(newLayout: com.iappyx.launcher.model.HomeLayout) {
                    layout = newLayout
                    store.save(layout)
                    pagerAdapter.setLayout(layout)
                    dockAdapter.setLayout(layout)
                    refreshHomeIndicator()
                    refreshDockIndicator()
                }
                override fun currentHomePageIndex() = currentPageIndex()
            },
        )
    }

    private val commandSession: com.iappyx.launcher.command.CommandSession
        get() = commandPanelController.session

    private val commandPanelHost: com.iappyx.launcher.widget.CommandPanelHost?
        get() = commandPanelController.host

    private fun clearCommandSessionIfInitialized() = commandPanelController.clearHistoryIfInitialized()

    // ── Storage Access Framework launchers ──────────────────────
    // Wallpaper / widget import + export from the manage tabs go through
    // these. Each pair stores the pending handler in a field so we can route
    // the SAF result back to the call site that initiated it (manage-tab
    // views can't register their own ActivityResultLaunchers — only the host
    // Activity can, and only before STARTED).
    private var pendingImportHandler: ((android.net.Uri) -> Unit)? = null
    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val handler = pendingImportHandler
        pendingImportHandler = null
        if (result.resultCode == RESULT_OK && handler != null) {
            result.data?.data?.let(handler)
        }
    }
    private var pendingExportHandler: ((android.net.Uri) -> Unit)? = null
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val handler = pendingExportHandler
        pendingExportHandler = null
        if (result.resultCode == RESULT_OK && handler != null) {
            result.data?.data?.let(handler)
        }
    }

    /** Image picker launcher for the AI Command Bar's "+" button. Uses the
     *  modern [PickVisualMedia] contract so on Android 13+ the user gets the
     *  system photo picker (privacy-preserving — no broad storage permission
     *  required). On older devices the contract transparently falls back to
     *  ACTION_GET_CONTENT. The chosen Uri is short-lived; the panel reads its
     *  bytes immediately. */
    private var pendingImagePickHandler: ((android.net.Uri?) -> Unit)? = null
    private val imagePickLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val handler = pendingImagePickHandler
        pendingImagePickHandler = null
        handler?.invoke(uri)
    }

    /** Open the system photo picker and call [handler] with the chosen Uri (or
     *  null if the user cancelled). Read the Uri's bytes immediately — the
     *  permission grant is one-shot and doesn't survive process restarts. */
    fun launchImagePicker(handler: (android.net.Uri?) -> Unit) {
        pendingImagePickHandler = handler
        imagePickLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts
                    .PickVisualMedia.ImageOnly,
            ),
        )
    }

    /** Open a system file picker for the given mimeType (use a wildcard like
     *  application-slash-star or pass star-slash-star to disable filtering).
     *  Invokes [handler] with the chosen Uri on the main thread. The Uri is
     *  short-lived — read its bytes immediately, don't store the Uri. */
    fun launchImport(mimeType: String, handler: (android.net.Uri) -> Unit) {
        pendingImportHandler = handler
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        importLauncher.launch(intent)
    }

    /** Open a system "save as" picker pre-populated with [suggestedName] (the
     *  extension picks the file type), then invoke [handler] with the chosen
     *  output Uri. Caller is responsible for actually writing bytes through
     *  contentResolver.openOutputStream(uri). */
    fun launchExport(mimeType: String, suggestedName: String, handler: (android.net.Uri) -> Unit) {
        pendingExportHandler = handler
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, suggestedName)
        exportLauncher.launch(intent)
    }

    /** Called by [HomePagerAdapter] for pager position 0. Delegates to
     *  [commandPanelController]. */
    fun createCommandPanel(): com.iappyx.launcher.widget.CommandPanelHost =
        commandPanelController.createPanel()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Settings → Wallpapers footer link arrives here. Switch the pager
        // to position 0 (the command panel) and select the requested tab.
        val tab = intent.getStringExtra(EXTRA_OPEN_TAB) ?: return
        val index = when (tab) {
            "ai" -> 0; "widgets" -> 1; "wallpapers" -> 2; "transitions" -> 3
            "icons" -> 4
            else -> return
        }
        pager.setCurrentItem(0, false)
        commandPanelHost?.switchToTab(index)
    }

    companion object {
        /** Most-recently-resumed instance — used by the Remote Edit feature
         *  to find live widget cells for thumbnail capture. Best-effort: may
         *  be null between `onCreate` and `onResume`, or after `onDestroy`.
         *  Access only on the main thread. */
        @JvmStatic var runningInstance: LauncherActivity? = null
            private set

        /** Optional Intent extra read in [onNewIntent] — values: "ai",
         *  "widgets", "wallpapers". Set by external surfaces (Settings sheet
         *  footer) that want the command panel to land on a specific tab. */
        const val EXTRA_OPEN_TAB = "open_tab"
        /** Sent by Settings → Clear chat history. Tells the running launcher
         *  process (same process, but possibly a different Activity in the
         *  back-stack) to wipe the in-memory chat session + clear the visible
         *  RecyclerView. Package-scoped at the sender to keep external apps
         *  out. */
        const val ACTION_CHAT_HISTORY_CLEARED =
            "com.iappyx.launcher.action.CHAT_HISTORY_CLEARED"
    }

    /** Switch the outer pager from the command panel (position 0) to the
     *  first home page. The manage-tab carousels claim horizontal swipes
     *  for themselves, so this button is the user's escape hatch back to
     *  the actual home grid. */
    fun goToFirstHomePage() {
        // pager.setCurrentItem expects an absolute index into the pager;
        // index 1 is the first home page (index 0 is the command panel).
        if (pager.currentItem != 1) pager.setCurrentItem(1, true)
    }

    /** When the user opens the Add-to-home sheet and picks "Use existing
     *  widget", we route them to the manage tab and stash the cell they
     *  tapped here. The next [placeExistingWidget] call drains this and
     *  places at exactly that slot. Cleared on consume so a subsequent
     *  Place tap from the manage tab (with no pending pickup) falls back
     *  to the find-a-free-spot path. */
    private data class PendingPlacementTarget(val pageIndex: Int, val row: Int, val col: Int)
    private var pendingPlacementTarget: PendingPlacementTarget? = null

    fun setPendingPlacementTarget(pageIndex: Int, row: Int, col: Int) {
        pendingPlacementTarget = PendingPlacementTarget(pageIndex, row, col)
    }

    /** Place an existing widget (by widgetId, code already in
     *  filesDir/widgets/{id}/) onto a free spot on the home grid. Returns
     *  true if placed, false if no free w×h rectangle exists across the
     *  current pages. If [setPendingPlacementTarget] was called recently
     *  (e.g. user came in via "Use existing widget" from the Add-to-home
     *  sheet), that exact slot is used IF it's still free; otherwise we
     *  fall through to the normal find-a-free-spot scan. */
    fun placeExistingWidget(widgetId: String, wSpan: Int = 2, hSpan: Int = 2): Boolean {
        val current = store.load()
        // Bundled widgets ship in `assets/widgets/{slug}.html` — placements
        // for them carry the asset path so addCellView's loadAsset branch
        // picks them up. User widgets keep `generatedWidgetAsset = null`,
        // which routes through the per-instance sandbox dir.
        val asset = com.iappyx.launcher.widget.WidgetLibrary.bundledAssetPath(widgetId)

        fun makePlacement(row: Int, col: Int) = com.iappyx.launcher.model.Placement(
            id = com.iappyx.launcher.model.Placement.newId(),
            type = com.iappyx.launcher.model.CellType.GENERATED_WIDGET,
            row = row, col = col, wSpan = wSpan, hSpan = hSpan,
            generatedWidgetId = widgetId,
            generatedWidgetAsset = asset,
        )

        // Honour the pending target first if set — it lets the Add-to-home
        // → Use existing widget flow drop the widget exactly where the
        // user tapped. Cleared regardless of success so a stale target
        // doesn't haunt later place taps.
        val target = pendingPlacementTarget
        pendingPlacementTarget = null
        if (target != null) {
            // The trailing virtual "+" page in edit mode lives at index
            // `layout.pages.size` and isn't part of the persisted layout
            // until something gets dropped on it. Materialise it here so a
            // tap on its empty cells lands the widget on a real new page
            // instead of silently falling through to the global scan.
            if (target.pageIndex == current.pages.size) {
                current.pages.add(com.iappyx.launcher.model.Page())
            }
            val targetPage = current.pages.getOrNull(target.pageIndex)
            if (targetPage != null &&
                rectFree(targetPage.placements, target.row, target.col, wSpan, hSpan, current.cols, current.rows)) {
                targetPage.placements.add(makePlacement(target.row, target.col))
                return commitAndJump(current, target.pageIndex)
            }
            // Pending target was named but its cell isn't free. Don't fall
            // through to the global scan — the user explicitly tapped
            // *here*, so a quietly-lands-elsewhere outcome is wrong.
            // Auto-create a fresh page below instead so the widget still
            // gets placed and the result is predictable.
        }

        // Walk pages newest-first index, find first row/col that fits.
        for ((pageIdx, page) in current.pages.withIndex()) {
            for (row in 0..(current.rows - hSpan)) {
                for (col in 0..(current.cols - wSpan)) {
                    if (rectFree(page.placements, row, col, wSpan, hSpan, current.cols, current.rows)) {
                        page.placements.add(makePlacement(row, col))
                        return commitAndJump(current, pageIdx)
                    }
                }
            }
        }

        // No existing page has room — auto-create one and place at top-left.
        // Without this, the user gets a "no free space" toast and has to
        // create a page manually before retrying. A fresh page is always
        // empty so a 2×2 widget always fits.
        val newPage = com.iappyx.launcher.model.Page()
        newPage.placements.add(makePlacement(row = 0, col = 0))
        current.pages.add(newPage)
        return commitAndJump(current, current.pages.size - 1)
    }

    /** Persist the new layout, swap it into the live pager + dock adapters,
     *  and snap the home pager to [destPageIndex] so the user sees the result.
     *  Returns true to flow through `placeExistingWidget`'s return chain. */
    private fun commitAndJump(updated: com.iappyx.launcher.model.HomeLayout, destPageIndex: Int): Boolean {
        store.save(updated)
        layout = updated
        pagerAdapter.setLayout(updated)
        refreshHomeIndicator()
        val pagerPos = destPageIndex + 1 // +1 because pager position 0 is command panel
        if (pagerPos in 0 until pagerAdapter.itemCount) {
            pager.setCurrentItem(pagerPos, false)
        }
        scheduleWallpaperLayoutBroadcast()
        return true
    }

    private fun rectFree(
        existing: List<com.iappyx.launcher.model.Placement>,
        row: Int, col: Int, w: Int, h: Int, cols: Int, rows: Int,
    ): Boolean {
        if (col + w > cols || row + h > rows) return false
        for (p in existing) {
            val overlap = !(p.col + p.wSpan <= col || col + w <= p.col ||
                            p.row + p.hSpan <= row || row + h <= p.row)
            if (overlap) return false
        }
        return true
    }

    fun createEmptyGrid(cols: Int, rows: Int): HomeGrid = HomeGrid(this).apply {
        this.cols = cols; this.rows = rows
    }

    fun renderPage(grid: HomeGrid, pageIndex: Int, page: Page) {
        grid.removeAllViews()
        page.placements.forEach { p -> addCellView(grid, p, pageIndex) }
        grid.editMode = editMode
        // Cells changed → wallpaper needs a fresh layout snapshot. Debounced
        // via scheduleWallpaperLayoutBroadcast so a multi-page render storm
        // collapses into a single broadcast.
        scheduleWallpaperLayoutBroadcast()
        // Re-attach the selection outline to the new View for this placement,
        // or clear it if the placement was just removed. Otherwise a stale
        // blue rectangle lingers at the old coordinates until next interaction.
        grid.rebindSelection()
        grid.onEmptyLongPress = { row, col -> showAddDialog(pageIndex, row, col) }
        grid.onEmptyTap = { row, col -> if (editMode) showAddDialog(pageIndex, row, col) }
        grid.onEnterEditMode = { if (!editMode) setEditMode(true) }
        // Long-press on a filled cell → context popup (App info / Uninstall /
        // Customize home / etc.). Toggleable via Settings; when off, the
        // callback stays null so HomeGrid falls back to its legacy
        // "long-press = enter edit mode" path.
        if (LauncherPrefs(this).useLongPressMenu) {
            grid.onCellLongPress = { placement, anchorView ->
                // Returning the popup arms HomeGrid's "keep holding to drag"
                // path — it dismisses the popup and starts a system DnD via
                // onStartSystemMoveDrag (which we wire below) when the user
                // moves their finger past tap-slop while still held.
                showHomeCellContextMenu(grid, placement, anchorView)
            }
        } else {
            grid.onCellLongPress = null
        }
        grid.onSelectionChanged = { p ->
            selectedPlacement = p
            updateEditBar()
            updatePagerLock()
        }
        grid.onCellMoveRequest = { placement, newRow, newCol ->
            tryMoveWithReflow(pageIndex, placement, newRow, newCol)
        }
        grid.onCellResizeRequest = { placement, newRow, newCol, newW, newH ->
            tryResizeWithReflow(pageIndex, placement, newRow, newCol, newW, newH)
        }
        grid.onCellMergeRequest = { src, dst -> mergeIntoFolder(pageIndex, src, dst) }
        // Edit-badge tap on the selected GENERATED_WIDGET → open the manual
        // AI widget editor seeded with the current HTML. Same flow the old
        // long-press menu's "Edit" item used to invoke.
        grid.onEditBadgeTap = { placement -> launchEditWidget(placement) }
        // Cross-page move: HomeGrid escalates body-move to a system DnD once
        // tap-slop is exceeded. The activity owns the actual startDragAndDrop
        // call so we can pin the source-grid + page index in our own state
        // for the drop handler.
        grid.onStartSystemMoveDrag = { placement, sourceView ->
            beginSystemPageDrag(pageIndex, grid, placement, sourceView)
        }
        // Edge-swipe at dispatch level: fires for EVERY drag event flowing
        // through the grid, including ones a widget cell (WebView,
        // AppWidgetHostView) would otherwise consume before the grid's
        // setOnDragListener got a chance. Without this hook, dragging a
        // widget that already sits at the page edge can never trigger
        // auto-paging because the pointer is over the consuming child the
        // entire time.
        grid.onAnyDragEvent = { dragEvent ->
            handleGridDragForEdgeSwipe(grid, dragEvent)
        }
        // Drop target — accepts widget drags from this grid OR from another
        // page's grid (cross-page move).
        installPageDragListener(grid, pageIndex)
    }

    private fun addCellView(grid: HomeGrid, p: Placement, pageIndex: Int = 0) {
        // Long-press on every cell enters edit mode via HomeGrid's gesture
        // detector — cells must NOT install their own setOnLongClickListener.
        // A child long-click consumes the gesture and races with the parent
        // (entering edit mode AND showing a stale "remove?" dialog), and on
        // IconCell that race leaves the grid blank until the user backs out.
        // Position info for icon-filter tints (rainbow matrix etc).
        val gridPos = com.iappyx.launcher.cells.GridPos(
            pageIndex = pageIndex, row = p.row, col = p.col,
            cols = layout.cols, rows = layout.rows,
        )
        val view: View = when (p.type) {
            CellType.ICON -> IconCell(this).apply {
                p.packageName?.let { bind(it, gridPos) }
            }
            CellType.STOCK_WIDGET -> StockWidgetCell(this).apply {
                p.appWidgetId?.let { bind(hostManager, it) }
                setOnCellDoubleTap { /* stock widgets aren't expandable in this build */ }
            }
            CellType.GENERATED_WIDGET -> GeneratedWidgetCell(this).apply {
                val id = p.generatedWidgetId ?: p.id
                val html = when {
                    p.generatedWidgetAsset != null -> loadAsset(p.generatedWidgetAsset)
                    else -> loadFromSandbox(id)
                }
                bind(this@LauncherActivity, widgetId = id, html = html)
                // Double-tap zooms the widget into the full-screen expand
                // overlay (same flow the old long-press → "Expand" menu item used).
                setOnCellDoubleTap { expandGeneratedWidget(p) }
            }
            CellType.FOLDER -> FolderCell(this).apply {
                bind(p.folderName ?: "Folder", p.folderItems, gridPos)
                setOnClickListener { view -> if (!editMode) openFolder(p, view) }
            }
            CellType.APP_DRAWER -> com.iappyx.launcher.cells.AppDrawerCell(this).apply {
                showLabel = true
                setOnClickListener { if (!editMode) showAppDrawer() }
            }
        }
        view.tag = p // HomeGrid reads this when moving/resizing
        grid.addView(view, HomeGrid.GridLayoutParams(p.row, p.col, p.wSpan, p.hSpan))
    }

    /** Opens a folder in a modal overlay. Persists renames + item removals. */
    private var currentFolderOverlay: FolderOverlay? = null

    private fun openFolder(p: Placement, sourceView: View? = null) {
        val overlay = FolderOverlay(
            activity = this,
            placement = p,
            sourceView = sourceView,
            onChanged = { newName, newItems ->
                p.folderItems.clear()
                p.folderItems.addAll(newItems)
                val updated = p.copy(folderName = newName)
                layout.pages.forEach { page ->
                    val idx = page.placements.indexOfFirst { it.id == p.id }
                    if (idx >= 0) page.placements[idx] = updated
                }
                // Folders with only one app no longer make sense — collapse
                // them back into a plain icon at the same cell.
                collapseSingleItemFolders()
                store.save(layout)
                pagerAdapter.notifyItemChanged(currentPageIndex() + 1)
            },
            onStartDragOut = { item, srcView ->
                // Remember which folder + which item this drag came from —
                // the home grid's app-drawer drop handler reads this and
                // removes the item from the folder on a successful drop.
                pendingFolderItemRemoval = PendingFolderItemRemoval(p.id, item.packageName)
                val clip = android.content.ClipData(
                    android.content.ClipDescription(
                        com.iappyx.launcher.widget.AppDrawerPanel.DRAG_LABEL,
                        arrayOf(com.iappyx.launcher.widget.AppDrawerPanel.DRAG_MIME),
                    ),
                    android.content.ClipData.Item(item.packageName),
                ).apply {
                    addItem(android.content.ClipData.Item(item.activityName ?: ""))
                }
                // Source view lives in the activity's main window now (the
                // folder overlay is a regular child of activity_root), so
                // a normal system DnD started here reaches the home grid's
                // existing drop listeners — same path the app drawer uses.
                val shadow = View.DragShadowBuilder(srcView)
                srcView.startDragAndDrop(clip, shadow, null, 0)
            },
        )
        currentFolderOverlay = overlay
        overlay.show()
    }

    /** Pending "remove from folder on successful drop" tracking. Set when a
     *  folder-item drag-out begins; consumed by the home-grid drop handler
     *  on success; always cleared on ACTION_DRAG_ENDED. */
    private data class PendingFolderItemRemoval(
        val folderPlacementId: String,
        val packageName: String,
    )
    private var pendingFolderItemRemoval: PendingFolderItemRemoval? = null

    /** Apply [pendingFolderItemRemoval] (if any) — called from the home grid
     *  drop handler right after a successful app-drawer-style drop. Removes
     *  the item from the source folder. If the folder ends up empty, the
     *  folder placement is removed. If only one item is left, the folder is
     *  collapsed into a plain icon — folders with one app aren't useful. */
    private fun applyPendingFolderItemRemoval() {
        val pending = pendingFolderItemRemoval ?: return
        pendingFolderItemRemoval = null
        var changed = false
        for (page in layout.pages) {
            val folder = page.placements.firstOrNull { it.id == pending.folderPlacementId } ?: continue
            val before = folder.folderItems.size
            folder.folderItems.removeAll { it.packageName == pending.packageName }
            if (folder.folderItems.size != before) changed = true
            if (folder.folderItems.isEmpty()) {
                page.placements.remove(folder)
                changed = true
            }
            break
        }
        if (collapseSingleItemFolders()) changed = true
        if (changed) {
            store.save(layout)
            pagerAdapter.setLayout(layout)
        }
    }

    /** Walk every page and replace any FOLDER placement whose `folderItems`
     *  has exactly 1 entry with a plain ICON pointing at that one app. Same
     *  cell position; size is forced to 1×1 (icons can't span larger). Used
     *  after a drag-out / context-menu remove / overlay edit so the user
     *  doesn't end up with a "folder of one". Returns true if anything
     *  changed (caller persists + refreshes). */
    private fun collapseSingleItemFolders(): Boolean {
        var changed = false
        for (page in layout.pages) {
            for (i in page.placements.indices) {
                val p = page.placements[i]
                if (p.type != CellType.FOLDER) continue
                if (p.folderItems.size != 1) continue
                val item = p.folderItems.first()
                page.placements[i] = p.copy(
                    type = CellType.ICON,
                    packageName = item.packageName,
                    activityName = item.activityName,
                    folderName = null,
                    folderItems = mutableListOf(),
                    wSpan = 1,
                    hSpan = 1,
                )
                changed = true
            }
        }
        return changed
    }

    // ── Edit mode ──────────────────────────────────────────────

    private var editMode: Boolean = false
    private var selectedPlacement: Placement? = null

    // ── Cross-page system drag-and-drop state ─────────────────
    /** Tracks an in-flight system DnD started from a HomeGrid. */
    private data class PageDragState(
        val srcPageIndex: Int,
        val srcGrid: HomeGrid,
        val srcPlacement: Placement,
        val srcView: View,
    )
    private var pageDragState: PageDragState? = null
    private var pendingEdgeSwipeDir: Int = 0
    /** Wall-clock at which the edge-swipe trigger may re-arm. After an
     *  auto-page fires, we keep the trigger disabled briefly so finger
     *  momentum that carried the user past the edge doesn't immediately
     *  page-flip again on the new page. Without this, dragging across two
     *  pages feels like a sliding door — pages flip past as fast as the
     *  finger moves, and the user can never park the icon. */
    private var edgeSwipeCooldownUntilMs: Long = 0L
    /** Index of a trailing empty page we materialised on demand during a
     *  long-press → drag (non-edit-mode equivalent of edit mode's virtual
     *  "+" page). Set when the user holds at the right edge of the last
     *  home page; cleared on ACTION_DRAG_ENDED. If still empty at that
     *  point (no widget was dropped there), we drop the page so it doesn't
     *  linger as a phantom blank. */
    private var dragMaterialisedTrailingIdx: Int? = null

    private val edgeSwipeRunnable: Runnable = object : Runnable {
        override fun run() {
            // Fired after the user holds at an edge zone — auto-page.
            // Clamp to home pages: position 0 is the AI command page (not a
            // drop target), and the upper bound is the last home page or the
            // virtual trailing page in edit mode.
            val dir = pendingEdgeSwipeDir
            if (dir == 0) return
            // Non-edit-mode trailing-page materialisation. If the user is
            // dragging right past the last home page, add an empty page on
            // the fly so the drag has somewhere to land. Edit mode already
            // exposes a virtual "+" page via HomePagerAdapter.editMode.
            val state = pageDragState
            val onLastHomePage = pager.currentItem == layout.pages.size
            if (state != null && dir > 0 && onLastHomePage && !editMode &&
                dragMaterialisedTrailingIdx == null
            ) {
                layout.pages.add(com.iappyx.launcher.model.Page())
                pagerAdapter.updateLayoutSilent(layout)
                pagerAdapter.notifyItemInserted(1 + layout.pages.size - 1)
                dragMaterialisedTrailingIdx = layout.pages.size - 1
                refreshHomeIndicator()
            }
            val maxIdx = (pagerAdapter.itemCount - 1).coerceAtLeast(1)
            val target = (pager.currentItem + dir).coerceIn(1, maxIdx)
            if (target != pager.currentItem) {
                pager.setCurrentItem(target, true)
                window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                // Suppress re-arm for the duration of the page-snap animation
                // plus a settle window. ViewPager2 default smoothScroll is
                // ~600ms; 800ms gives a small grace period so the user can
                // visually orient on the new page before another flip can
                // queue.
                edgeSwipeCooldownUntilMs = android.os.SystemClock.uptimeMillis() + 800L
            }
            pendingEdgeSwipeDir = 0
        }
    }

    /** Called by HomeGrid when a body-move drag exceeds tap-slop. Starts the
     *  system DnD with the placement id as ClipData payload, hides the source
     *  view while in flight. */
    private fun beginSystemPageDrag(pageIndex: Int, grid: HomeGrid, placement: Placement, sourceView: View) {
        val clip = android.content.ClipData(
            android.content.ClipDescription("iappyxPage", arrayOf(PAGE_WIDGET_DRAG_MIME)),
            android.content.ClipData.Item(placement.id),
        )
        val shadow = View.DragShadowBuilder(sourceView)
        pageDragState = PageDragState(pageIndex, grid, placement, sourceView)
        grid.systemMoveInFlight = true
        // Suppress "+" chips on EVERY currently-live grid while the drag is
        // happening — only the ghost preview should indicate valid drop spots.
        forEveryLiveHomeGrid { it.pageDragInFlight = true }
        // We deliberately do NOT set sourceView.alpha = 0 here. The drag
        // shadow already gives the user a moving preview, and hiding the
        // source view created a class of "widget disappears after a
        // failed/no-op drop" bugs where alpha=1 wasn't being restored
        // visibly (notably for slight-overlap drops, drops on the cell
        // already occupied by the source, and same-page moves where a
        // mid-DnD rebind detached the original srcView reference).
        // Mirrors the app drawer's drag-from-icon flow which never
        // hides the source either.
        sourceView.startDragAndDrop(clip, shadow, placement, 0)
    }

    /** Find the live (currently bound) [HomeGrid] for [pageIndex] (where
     *  pageIndex is the index into [HomeLayout.pages], NOT the pager
     *  position — the +1 offset for the command page is added internally).
     *  Returns null if the page isn't currently bound (e.g. off-screen
     *  past the offscreen limit). */
    private fun findLiveHomeGrid(pageIndex: Int): HomeGrid? {
        val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return null
        val targetPagerPos = pageIndex + 1
        for (i in 0 until rv.childCount) {
            val itemView = rv.getChildAt(i)
            val pos = rv.getChildAdapterPosition(itemView)
            if (pos != targetPagerPos) continue
            return when (itemView) {
                is HomeGrid -> itemView
                is android.view.ViewGroup -> (0 until itemView.childCount).asSequence()
                    .map { itemView.getChildAt(it) }
                    .filterIsInstance<HomeGrid>()
                    .firstOrNull()
                else -> null
            }
        }
        return null
    }

    /** Walk every HomeGrid currently attached to the pager's RecyclerView and
     *  invoke [block]. Used to broadcast drag-state flags. */
    private fun forEveryLiveHomeGrid(block: (HomeGrid) -> Unit) {
        val rv = pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val itemView = rv.getChildAt(i)
            // HomeHolder wraps the grid in a FrameLayout (for dock padding).
            val grid: HomeGrid? = when (itemView) {
                is HomeGrid -> itemView
                is android.view.ViewGroup -> (0 until itemView.childCount).asSequence()
                    .map { itemView.getChildAt(it) }
                    .filterIsInstance<HomeGrid>()
                    .firstOrNull()
                else -> null
            }
            grid?.let(block)
        }
    }

    /** Each HomeGrid registers this listener so we can route widget drops
     *  (potentially from a different page) into the correct page's layout.
     *  Edge-swipe / global state is handled by [installRootDragListener]. */
    /** Drag-dispatch observer wired on every live HomeGrid. Detects edge
     *  proximity and schedules an auto-page flip via [edgeSwipeRunnable],
     *  AND owns the cross-drag-state cleanup on ACTION_DRAG_ENDED. The
     *  activity_root drag listener never sees ACTION_DRAG_ENDED for our
     *  PAGE_WIDGET_DRAG_MIME drags (Android routes post-START events to
     *  the deepest listener, which is the grid). Without doing the
     *  cleanup here, [pageDragState] stayed non-null after every same-
     *  page widget move and silently blocked every subsequent swipe-up /
     *  swipe-down via [tryFireVerticalAction]'s gates. */
    private fun handleGridDragForEdgeSwipe(
        grid: com.iappyx.launcher.widget.HomeGrid,
        event: android.view.DragEvent,
    ) {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                // Filter by MIME — only edge-swipe for OUR widget drags,
                // not app-drawer or dock drags that this grid also sees.
                val cd = event.clipDescription
                if (cd?.hasMimeType(PAGE_WIDGET_DRAG_MIME) != true) return
                val edge = (36f * resources.displayMetrics.density)
                val w = grid.width
                if (w <= 0) return
                val now = android.os.SystemClock.uptimeMillis()
                val inCooldown = now < edgeSwipeCooldownUntilMs
                val dir = when {
                    inCooldown -> 0
                    event.x < edge -> -1
                    event.x > w - edge -> +1
                    else -> 0
                }
                if (dir != pendingEdgeSwipeDir) {
                    main.removeCallbacks(edgeSwipeRunnable)
                    pendingEdgeSwipeDir = dir
                    if (dir != 0) main.postDelayed(edgeSwipeRunnable, 750L)
                }
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                main.removeCallbacks(edgeSwipeRunnable)
                pendingEdgeSwipeDir = 0
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                // Do NOT filter by MIME here — Android nulls
                // clipDescription on ACTION_DRAG_ENDED, so any MIME check
                // would reject the event we need most. finalizePageDrag
                // is idempotent and only acts when pageDragState is set,
                // so app-drawer / dock drag-ends are no-ops.
                finalizePageDrag()
            }
        }
    }

    /** Tear down all per-drag state. Idempotent — safe to call from both
     *  the dispatch-level observer (primary path, always fires) and the
     *  activity_root drag listener (kept as a backup in case the drag
     *  ended without crossing any HomeGrid). */
    private fun finalizePageDrag() {
        if (pageDragState == null && dragMaterialisedTrailingIdx == null &&
            pendingEdgeSwipeDir == 0
        ) return
        pendingEdgeSwipeDir = 0
        main.removeCallbacks(edgeSwipeRunnable)
        val state = pageDragState
        if (state != null) {
            state.srcGrid.systemMoveInFlight = false
        }
        forEveryLiveHomeGrid { it.pageDragInFlight = false }
        pageDragState = null
        // Trailing-page cleanup: drop the empty page we materialised mid-
        // drag if nothing landed there. See edgeSwipeRunnable.
        val matIdx = dragMaterialisedTrailingIdx
        if (matIdx != null) {
            dragMaterialisedTrailingIdx = null
            val page = layout.pages.getOrNull(matIdx)
            if (page != null && page.placements.isEmpty()) {
                val pagerPosOfMat = 1 + matIdx
                if (pager.currentItem == pagerPosOfMat) {
                    pager.setCurrentItem(
                        (pagerPosOfMat - 1).coerceAtLeast(1), false,
                    )
                }
                layout.pages.removeAt(matIdx)
                pagerAdapter.updateLayoutSilent(layout)
                pagerAdapter.notifyItemRemoved(1 + matIdx)
                refreshHomeIndicator()
            }
        }
    }

    private fun installPageDragListener(grid: HomeGrid, pageIndex: Int) {
        grid.setOnDragListener { _, event ->
            val cd = event.clipDescription
            val mine = cd?.hasMimeType(PAGE_WIDGET_DRAG_MIME) == true
            if (!mine) return@setOnDragListener false
            val state = pageDragState
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DRAG_ENTERED -> true
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    if (state != null) {
                        // Show snap preview at the hovered cell.
                        grid.computeDropTarget(
                            event.x, event.y,
                            state.srcPlacement.wSpan, state.srcPlacement.hSpan,
                            state.srcPlacement.id,
                        )
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    grid.clearDropPreview()
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    if (state == null) return@setOnDragListener false
                    grid.clearDropPreview()
                    val src = state.srcPlacement

                    // 1) Merge-into-folder: source AND target are 1×1 icons
                    //    or folders, and the user dropped on top of the
                    //    target. Same path the app drawer uses, plus a
                    //    cross-page-aware mergeIntoFolder.
                    val (row, col) = grid.cellAt(event.x, event.y)
                    val targetPlacement = grid.placementAt(row, col)
                    val srcMergeable = src.wSpan == 1 && src.hSpan == 1 &&
                        (src.type == CellType.ICON || src.type == CellType.FOLDER)
                    val dstMergeable = targetPlacement != null &&
                        targetPlacement.id != src.id &&
                        targetPlacement.wSpan == 1 && targetPlacement.hSpan == 1 &&
                        (targetPlacement.type == CellType.ICON || targetPlacement.type == CellType.FOLDER)
                    if (srcMergeable && dstMergeable && targetPlacement != null) {
                        if (mergeIntoFolder(pageIndex, src, targetPlacement)) {
                            window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            return@setOnDragListener true
                        }
                    }

                    // 2) Plain move into an empty cell (same page or
                    //    cross-page). computeDropTarget enforces collision.
                    val target = grid.computeDropTarget(
                        event.x, event.y,
                        src.wSpan, src.hSpan,
                        src.id,
                    )
                    if (target != null) {
                        commitPageMove(state, pageIndex, target.first, target.second)
                        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        true
                    } else {
                        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
                        false
                    }
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    grid.clearDropPreview()
                    true
                }
                else -> false
            }
        }
    }

    /** Activity-root listener — observes the SAME drag event stream the page
     *  grids see, but only cares about edge proximity for auto-paging. */
    private fun installRootDragListener() {
        findViewById<android.view.View>(R.id.activity_root).setOnDragListener { _, event ->
            val cd = event.clipDescription
            if (cd?.hasMimeType(PAGE_WIDGET_DRAG_MIME) != true) return@setOnDragListener false
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    // Auto-page edge zone. Tightened from 60dp+400ms because
                    // the previous values made it nearly impossible to drop a
                    // widget on the leftmost or rightmost cell of a page —
                    // finger-momentum into the edge would auto-flip before
                    // the user could park. 36dp narrows the trigger area;
                    // 750ms makes it deliberate; the cooldown after a flip
                    // (set in edgeSwipeRunnable) prevents back-to-back paging.
                    val edge = (36f * resources.displayMetrics.density)
                    val w = pager.width
                    val now = android.os.SystemClock.uptimeMillis()
                    val inCooldown = now < edgeSwipeCooldownUntilMs
                    val dir = when {
                        inCooldown -> 0
                        event.x < edge -> -1
                        event.x > w - edge -> +1
                        else -> 0
                    }
                    if (dir != pendingEdgeSwipeDir) {
                        main.removeCallbacks(edgeSwipeRunnable)
                        pendingEdgeSwipeDir = dir
                        if (dir != 0) main.postDelayed(edgeSwipeRunnable, 750L)
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    // Backup cleanup. The dispatch-level observer
                    // (handleGridDragForEdgeSwipe) is the primary path —
                    // Android routes post-START events to the deepest
                    // listener so this activity_root listener typically
                    // never sees DRAG_ENDED for our drags. finalizePageDrag
                    // is idempotent.
                    finalizePageDrag()
                    true
                }
                else -> true
            }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Move [state.srcPlacement] from its current page to [destPageIndex] at
     *  (row, col). Same-page move uses the existing reflow path; cross-page
     *  removes from source then adds to destination with reflow. If the user
     *  dropped on the virtual trailing page in edit mode, materialize a new
     *  [Page] first so the widget has somewhere to land. */
    private fun commitPageMove(state: PageDragState, destPageIndex: Int, row: Int, col: Int) {
        if (destPageIndex == state.srcPageIndex) {
            // Same-page drop. Avoid renderPage entirely — even deferred via
            // pager.post, the subsequent removeAllViews / addCellView
            // recreates WebViews and AppWidgetHostViews unnecessarily and
            // produced a widget-disappears regression. Instead, walk the
            // existing children of the live grid and just update their tag
            // + GridLayoutParams to match the reflowed placements. No view
            // is recreated; the WebView keeps loading; the moved widget
            // animates to its new cell on the next layout pass.
            val page = layout.pages.getOrNull(destPageIndex) ?: return
            val moved = state.srcPlacement.copy(row = row, col = col)
            val reflowed = reflow(page.placements, state.srcPlacement.id, moved)
            if (reflowed == null) {
                showReflowRejected()
                return
            }
            page.placements.clear()
            page.placements.addAll(reflowed)
            store.save(layout)
            pagerAdapter.updateLayoutSilent(layout)
            applyPlacementChangesInPlace(state.srcGrid, reflowed)
            return
        }
        // Cross-page move.
        val srcPage = layout.pages.getOrNull(state.srcPageIndex) ?: return

        // Materialize a new page if dropped on the virtual trailing page.
        val materialisedNewPage = destPageIndex == layout.pages.size
        if (materialisedNewPage) {
            layout.pages.add(com.iappyx.launcher.model.Page())
        } else if (destPageIndex !in layout.pages.indices) {
            return
        }

        srcPage.placements.removeAll { it.id == state.srcPlacement.id }
        val moved = state.srcPlacement.copy(row = row, col = col)
        val destPage = layout.pages[destPageIndex]
        val newDest = reflow(destPage.placements + moved, moved.id, moved)
        if (newDest == null) {
            // Reflow couldn't accommodate the drop on the destination page.
            // Restore the source placement back to its original page rather
            // than letting the previous fallback path commit overlapping
            // cells (which broke the grid's positioning math). If we
            // materialized a brand-new trailing page above, drop it again
            // so the layout doesn't gain a phantom empty page.
            srcPage.placements.add(state.srcPlacement)
            if (materialisedNewPage) {
                layout.pages.removeAt(destPageIndex)
            }
            showReflowRejected()
            return
        }
        destPage.placements.clear()
        destPage.placements.addAll(newDest)
        store.save(layout)

        // Avoid notifyDataSetChanged here — calling it inside the DnD
        // ACTION_DROP handler recycles the visible page's HomeGrid (with
        // its WebView teardown), and the rebind that follows can fail mid-
        // DnD-callback, leaving the page blank until edit mode is exited.
        // Same race the setEditMode comment block describes. Instead:
        //   1. Update the adapter's layout reference silently.
        //   2. For the source + destination pages, rebind in place if
        //      they're currently bound (no recycle, WebViews preserved on
        //      pages we're not actually changing). Off-screen pages get a
        //      notifyItemChanged so they pick up the new placements when
        //      next bound.
        //   3. If we materialised a new trailing page, notifyItemInserted
        //      so the pager grows by one slot (the trailing virtual page
        //      shifts after it).
        pagerAdapter.updateLayoutSilent(layout)
        if (materialisedNewPage) {
            pagerAdapter.notifyItemInserted(1 + destPageIndex)
        }
        rebindOrNotify(state.srcPageIndex)
        if (!materialisedNewPage) rebindOrNotify(destPageIndex)
        refreshHomeIndicator()
    }

    /** Re-render a page in place if currently bound, else fall back to a
     *  targeted [HomePagerAdapter.notifyItemChanged] so the next bind
     *  picks up the new state. Used by [commitPageMove] to swap content
     *  without recycling the visible HomeGrid (which would tear down its
     *  WebView children mid-DnD and risk a blank rebind). */
    private fun rebindOrNotify(pageIndex: Int) {
        val page = layout.pages.getOrNull(pageIndex) ?: return
        val live = findLiveHomeGrid(pageIndex)
        if (live != null) {
            renderPage(live, pageIndex, page)
        } else {
            pagerAdapter.notifyItemChanged(1 + pageIndex)
        }
    }

    // Lazily-looked-up edit-bar views
    private val editBar by lazy { findViewById<android.view.View>(R.id.edit_bar) }
    private val editBarTitle by lazy { findViewById<android.widget.TextView>(R.id.edit_bar_title) }
    private val editBarSettings by lazy { findViewById<android.view.View>(R.id.edit_bar_settings) }
    private val editBarOverview by lazy { findViewById<android.widget.ImageView>(R.id.edit_bar_overview) }
    private val editBarAdd by lazy { findViewById<android.widget.TextView>(R.id.edit_bar_add) }
    private val editBarRemove by lazy { findViewById<android.widget.TextView>(R.id.edit_bar_remove) }
    private val editBarDone by lazy { findViewById<android.widget.TextView>(R.id.edit_bar_done) }
    private val editScrim by lazy { findViewById<android.view.View>(R.id.edit_scrim) }

    /** Bounces the pager off position 0 (the AI command page) while in edit
     *  mode — that page isn't editable and shouldn't be reachable. Registered
     *  on entry, unregistered on exit. */
    private val editModePageGuard = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE &&
                editMode && pager.currentItem == 0) {
                pager.setCurrentItem(1, false)
            }
        }
    }

    /**
     * Toggle edit mode WITHOUT triggering a `notifyDataSetChanged` on visible
     * pages. Only the trailing virtual "+" page (and any pruned empty pages on
     * exit) gets a targeted item notification. The visible HomeGrid stays
     * attached and its children are never detached + re-added — that re-add
     * cycle was the structural source of the intermittent "blank screen on
     * long-press" race.
     */
    private fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (enabled) {
            if (pager.currentItem < 1) pager.setCurrentItem(1, false)
            pager.registerOnPageChangeCallback(editModePageGuard)
        } else {
            pager.unregisterOnPageChangeCallback(editModePageGuard)
        }
        updatePagerLock()
        editModeBackCallback.isEnabled = enabled
        findViewById<android.view.View>(R.id.home_settings_btn).visibility =
            if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        refreshProfileChipVisibility()
        animateEditModeScale(enabled)
        if (enabled) installEditModeFlatTransformer() else installNormalTransformer()

        if (enabled) {
            editBar.visibility = android.view.View.VISIBLE
            editScrim.animate().alpha(1f).setDuration(160).start()
            // Adapter-state-before-flag: the virtual page lands at index
            // `1 + layout.pages.size` (1 = command page). Set the flag silently,
            // then notify just the new slot. Same logic for the dock.
            val homeVirtualIdx = 1 + layout.pages.size
            val dockVirtualIdx = layout.dockPages.size
            pagerAdapter.editMode = true
            dockAdapter.editMode = true
            pagerAdapter.notifyItemInserted(homeVirtualIdx)
            dockAdapter.notifyItemInserted(dockVirtualIdx)
            // Force existing dock pages to re-render so empty slots get
            // their "+" affordance. Unlike HomeGrid (which reads editMode
            // directly via forEveryLiveHomeGrid below), the dock builds
            // slot views eagerly inside renderDockPage — only a re-bind
            // surfaces the "+". Without this the user sees an empty,
            // unresponsive dock until they swipe pages or rotate.
            if (layout.dockPages.isNotEmpty()) {
                dockAdapter.notifyItemRangeChanged(0, layout.dockPages.size)
            }
        } else {
            selectedPlacement = null
            editBar.visibility = android.view.View.GONE
            editScrim.animate().alpha(0f).setDuration(160).start()
            // Snapshot pre-prune sizes so we know how many slots collapse
            // (pruned trailing empties + virtual page).
            val oldHomePages = layout.pages.size
            val oldDockPages = layout.dockPages.size
            while (layout.pages.size > 1 && layout.pages.last().placements.isEmpty()) {
                layout.pages.removeAt(layout.pages.size - 1)
            }
            while (layout.dockPages.size > 1 && layout.dockPages.last().isEmpty()) {
                layout.dockPages.removeAt(layout.dockPages.size - 1)
            }
            store.save(layout)
            pagerAdapter.editMode = false
            dockAdapter.editMode = false
            // Removed range = pruned + virtual = (old - new) + 1 starting at
            // the new last+1 slot.
            val homeRemoved = (oldHomePages - layout.pages.size) + 1
            val dockRemoved = (oldDockPages - layout.dockPages.size) + 1
            pagerAdapter.notifyItemRangeRemoved(1 + layout.pages.size, homeRemoved)
            dockAdapter.notifyItemRangeRemoved(layout.dockPages.size, dockRemoved)
            // Defensive clamp: if the user was on a trailing empty page or
            // the virtual "+" page that just got pruned, currentItem now
            // points past the new range. ViewPager2 usually clamps via
            // RecyclerView's adapter-helper, but that's implementation
            // behavior — make the invariant explicit. Pages occupy pager
            // indices 1..layout.pages.size (index 0 = command page).
            val maxValidIndex = layout.pages.size
            if (pager.currentItem > maxValidIndex) {
                pager.setCurrentItem(maxValidIndex, false)
            }
            // Mirror of the enter-path rebind: existing dock pages need
            // to re-render so "+" affordances disappear and any in-flight
            // edit-mode visuals clear.
            if (layout.dockPages.isNotEmpty()) {
                dockAdapter.notifyItemRangeChanged(0, layout.dockPages.size)
            }
        }
        updateEditBar()
        refreshHomeIndicator()
        refreshDockIndicator()
        // Propagate the editMode flag to every currently-live HomeGrid so
        // wiggle / breathe / "+" chips kick in immediately on the visible
        // page (no bind-cycle delay).
        forEveryLiveHomeGrid { it.editMode = enabled }
    }

    /** Pager horizontal swipe is allowed in non-edit mode and in edit mode with
     *  NO widget selected (so users can reach the virtual trailing page).
     *  Locked while a widget is selected so resize-handle / move drags don't
     *  get hijacked by a page swipe. */
    private fun updatePagerLock() {
        pager.isUserInputEnabled = !(editMode && selectedPlacement != null)
    }

    private fun updateEditBar() {
        if (!editMode) return
        val sel = selectedPlacement
        // "Page X / N" suffix so the user always knows where they are while
        // they peek at neighbouring pages and drag things around.
        val pageIdx = currentPageIndex()
        val totalPages = layout.pages.size.coerceAtLeast(1)
        val pageSuffix = " · page ${pageIdx + 1}/$totalPages"
        if (sel == null) {
            editBarTitle.text = getString(R.string.edit_bar_title) + pageSuffix
            editBarAdd.visibility = android.view.View.GONE
            editBarRemove.visibility = android.view.View.GONE
        } else {
            editBarTitle.text = describeSelection(sel) + pageSuffix
            editBarAdd.visibility = if (sel.type == CellType.GENERATED_WIDGET) android.view.View.VISIBLE else android.view.View.GONE
            editBarRemove.visibility = android.view.View.VISIBLE
        }
        editBarDone.setOnClickListener { setEditMode(false) }
        editBarSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        editBarOverview.setOnClickListener { showOverview() }
        editBarAdd.setOnClickListener { sel?.let { launchEditWidget(it); setEditMode(false) } }
        editBarRemove.setOnClickListener { sel?.let { removePlacement(it); selectedPlacement = null; updateEditBar() } }
    }

    private fun describeSelection(p: Placement): String {
        val size = "${p.wSpan}×${p.hSpan}"
        val kind = when (p.type) {
            CellType.ICON -> {
                val pkg = p.packageName
                if (pkg != null) try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { getString(R.string.cell_kind_app) }
                else getString(R.string.cell_kind_app)
            }
            CellType.STOCK_WIDGET -> getString(R.string.cell_kind_widget)
            CellType.GENERATED_WIDGET -> getString(R.string.cell_kind_ai_widget)
            CellType.FOLDER -> p.folderName ?: getString(R.string.folder_default_name)
            CellType.APP_DRAWER -> getString(R.string.cell_all_apps)
        }
        return "$kind · $size"
    }

    private val editModeBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setEditMode(false) }
        // Predictive back deliberately not wired here — see [activeBackTarget].
    }

    /** Animate the home pager into / out of edit mode by shrinking it
     *  (aspect-ratio preserved) and translating it down so its top sits just
     *  below the edit bar. Without this, widgets in row 0 would be hidden
     *  behind the bar — the bar overlays the pager. Reverses on exit.
     *
     *  Scale anchor: top centre. Touch coordinates are inverse-mapped through
     *  the View transform automatically, so taps + the resize handles still
     *  work at the visual position. */
    private fun animateEditModeScale(entering: Boolean) {
        val pagerHeight = pager.height.toFloat().takeIf { it > 0f } ?: return

        val (scale, translateY) = if (entering) {
            // Approximate edit-bar bottom Y. The bar's visible bottom is the
            // status-bar inset + 64dp minHeight + 12dp top padding above (the
            // bottom 12dp paddingBottom is already inside that minHeight floor).
            val dp = resources.displayMetrics.density
            val barHeight = (64f + 24f) * dp        // minHeight + top+bottom padding
            val statusInset = pager.paddingTop      // bars.top from wireSystemBarInsets
            val editBarBottomY = statusInset + barHeight
            // Available height between edit bar bottom and the pager's
            // padded-bottom (which already accounts for the dock + nav bar).
            val available = pagerHeight - editBarBottomY - pager.paddingBottom
            val targetVisibleHeight = available.coerceAtLeast(pagerHeight * 0.6f)
            // Solve: editBarBottomY + scale * pagerHeight = editBarBottomY + targetVisibleHeight
            //   ⇒ scale = targetVisibleHeight / pagerHeight (rounded a touch
            //     looser so the resize handles never bump the bar).
            val s = (targetVisibleHeight / pagerHeight).coerceIn(0.7f, 0.98f)
            s to editBarBottomY
        } else {
            1f to 0f
        }

        pager.pivotX = pager.width / 2f
        pager.pivotY = 0f
        pager.animate()
            .scaleX(scale).scaleY(scale)
            .translationY(translateY)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .start()
    }

    private fun currentHomeGrid(): com.iappyx.launcher.widget.HomeGrid? {
        val rv = (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView) ?: return null
        val holder = rv.findViewHolderForAdapterPosition(pager.currentItem)
            as? HomePagerAdapter.HomeHolder ?: return null
        return holder.grid
    }


    /**
     * Attempt to move a placement to (newRow,newCol). If the destination collides
     * with others, try to shift them downward (reflow). Returns true if applied.
     */
    private fun tryMoveWithReflow(pageIndex: Int, placement: Placement, newRow: Int, newCol: Int): Boolean {
        val page = layout.pages.getOrNull(pageIndex) ?: return false
        val moved = placement.copy(row = newRow, col = newCol)
        val newPlacements = reflow(page.placements, placement.id, moved)
        if (newPlacements == null) {
            // Drop ghost was green but reflow couldn't make room (cascade
            // would push something below the grid). Without surfacing this
            // the user sees the widget snap back with only a haptic — a
            // toast makes the rejection unambiguous.
            showReflowRejected()
            return false
        }
        page.placements.clear()
        page.placements.addAll(newPlacements)
        store.save(layout)
        pagerAdapter.updateLayoutSilent(layout)
        val live = findLiveHomeGrid(pageIndex)
        if (live != null) {
            applyPlacementChangesInPlace(live, newPlacements)
        } else {
            pagerAdapter.notifyItemChanged(pageIndex + 1) // +1: pager position 0 is the command page
        }
        scheduleChromeStripRecenter()
        return true
    }

    /** Dropped [src] on top of [dst] — create a folder (or extend an existing
     *  folder) at the destination, absorb both icons, and remove the originals.
     *  Returns true on success. */
    /** Merge two icon-or-folder placements into a folder at `dst`'s cell on
     *  page [dstPageIndex]. `src` may live on the same page or on any other
     *  page — the function searches every page and removes the src wherever
     *  it is. Returns true if a merge happened. */
    private fun mergeIntoFolder(dstPageIndex: Int, src: Placement, dst: Placement): Boolean {
        val dstPage = layout.pages.getOrNull(dstPageIndex) ?: return false
        fun itemsOf(p: Placement): List<FolderItem> {
            return when (p.type) {
                CellType.ICON -> {
                    val pkg = p.packageName ?: return emptyList()
                    listOf(FolderItem(pkg, p.activityName))
                }
                CellType.FOLDER -> p.folderItems.toList()
                else -> emptyList()
            }
        }
        val combined = (itemsOf(dst) + itemsOf(src)).distinctBy { it.packageName }
        if (combined.isEmpty()) return false

        val existingName = dst.folderName ?: src.folderName ?: "Folder"
        val merged = Placement(
            id = if (dst.type == CellType.FOLDER) dst.id else Placement.newId(),
            type = CellType.FOLDER,
            row = dst.row, col = dst.col,
            wSpan = 1, hSpan = 1,
            folderName = existingName,
            folderItems = combined.toMutableList(),
        )
        // Locate src's page BEFORE removal so we can perform a targeted
        // in-place update (no full-page recycle — preserves sibling stock
        // widgets' AppWidgetHostViews on both affected pages).
        val srcPageIndex = layout.pages.indexOfFirst { p ->
            p.placements.any { it.id == src.id }
        }
        layout.pages.forEach { page -> page.placements.removeAll { it.id == src.id } }
        dstPage.placements.removeAll { it.id == dst.id }
        dstPage.placements.add(merged)
        store.save(layout)
        pagerAdapter.updateLayoutSilent(layout)
        // dst page: remove old src cell (if same-page merge), remove old
        // dst cell, add the new merged folder cell. Cross-page merges
        // touch the src page separately below.
        val liveDst = findLiveHomeGrid(dstPageIndex)
        if (liveDst != null) {
            if (srcPageIndex == dstPageIndex) removeCellFromGrid(liveDst, src)
            removeCellFromGrid(liveDst, dst)
            addCellView(liveDst, merged, dstPageIndex)
        } else {
            pagerAdapter.notifyItemChanged(1 + dstPageIndex)
        }
        if (srcPageIndex >= 0 && srcPageIndex != dstPageIndex) {
            val liveSrc = findLiveHomeGrid(srcPageIndex)
            if (liveSrc != null) {
                removeCellFromGrid(liveSrc, src)
            } else {
                pagerAdapter.notifyItemChanged(1 + srcPageIndex)
            }
        }
        scheduleChromeStripRecenter()
        return true
    }

    private fun tryResizeWithReflow(pageIndex: Int, placement: Placement, newRow: Int, newCol: Int, newW: Int, newH: Int): Boolean {
        // Folders are not resizable — defensive guard. The grid's hit-test
        // already skips resize handles for folders, but this catches any
        // programmatic / future caller too.
        if (placement.type == CellType.FOLDER) return false
        val page = layout.pages.getOrNull(pageIndex) ?: return false
        val resized = placement.copy(row = newRow, col = newCol, wSpan = newW, hSpan = newH)
        val newPlacements = reflow(page.placements, placement.id, resized)
        if (newPlacements == null) {
            showReflowRejected()
            return false
        }
        page.placements.clear()
        page.placements.addAll(newPlacements)
        store.save(layout)
        pagerAdapter.updateLayoutSilent(layout)
        val live = findLiveHomeGrid(pageIndex)
        if (live != null) {
            applyPlacementChangesInPlace(live, newPlacements)
        } else {
            pagerAdapter.notifyItemChanged(pageIndex + 1)
        }
        scheduleChromeStripRecenter()
        return true
    }

    /** User-visible feedback for a reflow that genuinely couldn't fit the
     *  drop. Shown when `reflow()` returns null — almost always because the
     *  cascade would push a placement past the bottom row. The home grid's
     *  ghost preview goes red in this case but a confirmation toast makes
     *  the rejection unambiguous (haptic alone gets lost in normal scroll
     *  feedback). Throttled implicitly by Toast.LENGTH_SHORT — Android
     *  drops successive toasts that fire within the previous one's window. */
    private fun showReflowRejected() {
        android.widget.Toast.makeText(
            this, R.string.no_room_here_toast,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Greedy reflow: anchor [changed] at its new position, then push conflicting
     * placements downward (one row at a time) until they no longer overlap.
     * Returns the new list, or null if reflow can't resolve (e.g. would push
     * something below row limit).
     */
    private fun reflow(current: List<Placement>, changedId: String, changed: Placement): List<Placement>? {
        if (changed.row < 0 || changed.col < 0 || changed.row + changed.hSpan > layout.rows || changed.col + changed.wSpan > layout.cols) return null
        val result = current.map { if (it.id == changedId) changed else it }.toMutableList()
        fun collides(a: Placement, b: Placement): Boolean {
            if (a.col + a.wSpan <= b.col || a.col >= b.col + b.wSpan) return false
            if (a.row + a.hSpan <= b.row || a.row >= b.row + b.hSpan) return false
            return true
        }
        // Push each collider downward; allow up to layout.rows iterations total (bounded)
        var iteration = 0
        while (iteration < layout.rows * 4) {
            var pushed = false
            for (i in result.indices) {
                if (result[i].id == changedId) continue
                val other = result[i]
                val conflictWith = result.firstOrNull { p ->
                    p.id != other.id && collides(other, p) &&
                        (p.id == changedId || (result.indexOf(p) < i))
                } ?: continue
                val newRow = conflictWith.row + conflictWith.hSpan
                if (newRow + other.hSpan > layout.rows) return null
                result[i] = other.copy(row = newRow)
                pushed = true
            }
            if (!pushed) break
            iteration++
        }
        // Final validation: nothing overlaps
        for (i in result.indices) for (j in i + 1 until result.size) {
            if (collides(result[i], result[j])) return null
        }
        return result
    }

    // ── Long-press flows ───────────────────────────────────────

    private fun showAddDialog(pageIndex: Int, row: Int, col: Int) {
        com.iappyx.launcher.widget.AddToHomeSheet(
            activity = this,
            onUseExistingWidget = {
                // Stash the target cell so when the user taps Place on a
                // card in the manage tab, the existing-widget flow lands
                // exactly here instead of finding a free spot.
                setPendingPlacementTarget(pageIndex, row, col)
                openManageWidgetsTab()
            },
            onAskIappyx = { askIappyxForCell(pageIndex, row, col) },
            onAiWidgetManual = { launchAiWidgetCreator(pageIndex, row, col) },
            onAiWallpaperManual = {
                enterManualAiMode(com.iappyx.launcher.widget.ManualAiCanvas.Type.WALLPAPER)
            },
            onAiTransitionManual = {
                enterManualAiMode(com.iappyx.launcher.widget.ManualAiCanvas.Type.TRANSITION)
            },
            onApp = { showAppPicker(pageIndex, row, col) },
            onStockWidget = { launchStockWidgetPicker(pageIndex, row, col) },
            onAppDrawer = { addAppDrawerToHome(pageIndex, row, col) },
        ).show()
    }

    /** Routes the user to the Widgets tab in the command panel so they can
     *  pick an existing widget to place. Pairs with [pendingPlacementTarget]
     *  set by the caller — the manage tab's Place action drains that. */
    private fun openManageWidgetsTab() {
        // Exit edit mode if needed so the pager can scroll to position 0
        // (the command page); otherwise editModePageGuard pins us out.
        if (editMode) setEditMode(false)
        pager.setCurrentItem(0, true)
        // Use a small post so the pager has a frame to settle before we
        // tell the host to switch tabs (otherwise the host may not exist
        // yet on first open).
        pager.post {
            commandPanelHost?.switchToTab(1) // 0=AI, 1=Widgets, 2=Wallpapers, 3=Transitions
        }
    }

    /** Switches the home pager to the AI tab and puts the chat pane into
     *  manual mode for [type]. Used by AddToHomeSheet's "Generate with
     *  external AI" submenu (which passes a [placement] so the result
     *  lands on the empty cell that triggered the sheet) AND by the AI
     *  tab's own empty-state / toggle chip (no [placement] — result
     *  lands in the library and the user places it from the Manage tab).
     *
     *  Manual flow now lives inline in the AI tab, not in a separate
     *  Activity, so HOME → external AI → return reliably preserves the
     *  user's input. */
    fun enterManualAiMode(
        type: com.iappyx.launcher.widget.ManualAiCanvas.Type,
        placement: com.iappyx.launcher.widget.ManualAiCanvas.PlacementTarget? = null,
        edit: com.iappyx.launcher.widget.ManualAiCanvas.WidgetEdit? = null,
    ) {
        // Snap to the AI tab (pager position 0) — the host then selects
        // the AI sub-tab and configures the manual canvas.
        if (pager.currentItem != 0) pager.setCurrentItem(0, true)
        commandPanelHost?.enterManualMode(type, placement, edit)
    }

    /** Called by [com.iappyx.launcher.widget.ManualAiCanvas] after a Widget
     *  save when there's a target cell — drops a [Placement] for the new
     *  widget id onto the home grid. */
    fun commitManualWidgetPlacement(
        widgetId: String,
        target: com.iappyx.launcher.widget.ManualAiCanvas.PlacementTarget,
    ) {
        addPlacement(target.pageIndex, Placement(
            id = Placement.newId(),
            type = CellType.GENERATED_WIDGET,
            row = target.row, col = target.col,
            wSpan = 2, hSpan = 2,
            generatedWidgetId = widgetId,
        ))
    }

    /** Called by [com.iappyx.launcher.widget.ManualAiCanvas] after editing
     *  an existing widget in-place. Re-renders the visible home page so
     *  the cell's WebView reloads from the updated sandbox HTML. */
    fun refreshHomePagesAfterEdit() {
        pagerAdapter.notifyItemChanged(currentPageIndex() + 1)
    }

    /** Re-tint native chrome to the user's custom accent. The generic tree
     *  walk handles the edit-bar settings/overview icons + "Edit" label; the
     *  "Done" chip uses a solid-accent background drawable (no tint list for
     *  the walker to detect) so we tint it explicitly. */
    private fun applyNativeAccent() {
        // The generic accent + font tree walk runs app-wide via IappyxApp's
        // lifecycle hook; here we only do the launcher-specific bits the walk
        // can't: the "Done" chip's solid-accent background drawable + nudging
        // the indicators (which read the accent per-draw) to repaint.
        val accent = com.iappyx.launcher.widget.Palette.accent(this)
        findViewById<android.widget.TextView>(R.id.edit_bar_done)?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(accent)
        // The page/dock indicators read the accent per-draw; nudge a repaint so
        // a theme change shows immediately without waiting for a scroll.
        findViewById<android.view.View>(R.id.home_indicator)?.invalidate()
        findViewById<android.view.View>(R.id.dock_indicator)?.invalidate()
    }

    /** True when the user has saved an Anthropic API key in Settings.
     *  CommandPanel hides the chat scrollback + input when this is false
     *  and shows an empty-state card pointing at Settings + manual flow. */
    fun hasApiKey(): Boolean =
        com.iappyx.launcher.ai.SecureStore(this).anthropicKey?.isNotBlank() == true

    /** Add a 1×1 APP_DRAWER tile at the given home cell. */
    private fun addAppDrawerToHome(pageIndex: Int, row: Int, col: Int) {
        addPlacement(pageIndex, Placement(
            id = Placement.newId(),
            type = CellType.APP_DRAWER,
            row = row, col = col, wSpan = 1, hSpan = 1,
        ))
    }

    /** Open the AI Command Bar with a one-shot placement hint so the next
     *  widget the AI creates lands on this exact cell. The chat prefix isn't
     *  shown to the user — they just type their description and the model
     *  silently sees the coordinates. */
    private fun askIappyxForCell(pageIndex: Int, row: Int, col: Int) {
        commandSession.setPendingPlacement(
            com.iappyx.launcher.command.CommandSession.PlacementHint(pageIndex, row, col)
        )
        // Edit mode pins the pager off the command page (position 0 is
        // disabled by editModePageGuard). Exit edit mode first so the swipe
        // to chat actually lands. The placement hint persists across the
        // transition — it's a one-shot field on the CommandSession.
        if (editMode) setEditMode(false)
        // Swipe the pager to the command page (position 0) with a smooth animation.
        pager.setCurrentItem(0, true)
        // Force the AI tab specifically — the command panel may have been
        // left on Widgets / Wallpapers / Transitions from a previous visit.
        // Posted so the host has time to materialise on first open.
        pager.post {
            commandPanelHost?.switchToTab(0)
        }
    }

    /** Zoom the tapped widget into a near-full-screen card, reparenting its
     *  WebView so state survives. Widgets receive the natural window.resize
     *  event and can reflow for the larger area. */
    private fun expandGeneratedWidget(p: Placement) {
        val grid = currentHomeGrid() ?: return
        var cell: com.iappyx.launcher.cells.GeneratedWidgetCell? = null
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            if ((v.tag as? Placement)?.id == p.id && v is com.iappyx.launcher.cells.GeneratedWidgetCell) {
                cell = v; break
            }
        }
        cell?.let { com.iappyx.launcher.widget.WidgetZoomOverlay(this, it).show() }
    }

    private fun launchEditWidget(placement: Placement) {
        val id = placement.generatedWidgetId ?: placement.id
        val html = when {
            placement.generatedWidgetAsset != null -> loadAsset(placement.generatedWidgetAsset)
            else -> loadFromSandbox(id)
        }
        enterManualAiMode(
            type = com.iappyx.launcher.widget.ManualAiCanvas.Type.WIDGET,
            edit = com.iappyx.launcher.widget.ManualAiCanvas.WidgetEdit(id, html),
        )
    }

    /** Position 0 in the pager is the AI Command panel; layout.pages live at
     *  positions 1..N. This converts the pager's current item to the index
     *  inside [HomeLayout.pages], clamped to a valid range. */
    private fun currentPageIndex(): Int =
        (pager.currentItem - 1).coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0))

    /** Open the long-press context popup for a filled home-grid cell. The
     *  activity owns the popup (rather than HomeGrid itself) so it can wire
     *  into the same edit-mode + remove + manual-AI plumbing the rest of the
     *  launcher uses. */
    /** Update an existing live HomeGrid's children's tags + GridLayoutParams
     *  to match [newPlacements], without removing or recreating any view.
     *  Use this for same-page moves (and any other operation where the set
     *  of placement ids doesn't change) so WebViews + AppWidgetHostViews
     *  inside generated/stock widgets aren't torn down — a renderPage
     *  during a system-DnD ACTION_DROP callback was producing visible
     *  "widget disappears" regressions because mid-flight WebView teardowns
     *  raced with the DnD callback chain. */
    private fun applyPlacementChangesInPlace(
        grid: com.iappyx.launcher.widget.HomeGrid,
        newPlacements: List<Placement>,
    ) {
        val byId = newPlacements.associateBy { it.id }
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val oldTag = child.tag as? Placement ?: continue
            val updated = byId[oldTag.id] ?: continue
            child.tag = updated
            // Always reassign layoutParams so reflow-pushed siblings settle
            // at their new rows. Comparing first would skip pure no-ops but
            // also skip cases where only a sibling moved.
            child.layoutParams = com.iappyx.launcher.widget.HomeGrid.GridLayoutParams(
                updated.row, updated.col, updated.wSpan, updated.hSpan,
            )
        }
        grid.requestLayout()
        grid.invalidate()
    }

    /** Remove the child view whose tag matches [placement] from a live
     *  [HomeGrid], with proper teardown of WebView / AppWidgetHostView
     *  children. Companion to [applyPlacementChangesInPlace] for the
     *  add/remove paths: lets us avoid a full notifyItemChanged on the
     *  affected page, which would recycle every cell (and momentarily
     *  detach every stock widget's AppWidgetHostView until the next
     *  bind cycle settles). No-op if no matching child is found. */
    private fun removeCellFromGrid(
        grid: com.iappyx.launcher.widget.HomeGrid,
        placement: Placement,
    ) {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val tagP = child.tag as? Placement ?: continue
            if (tagP.id != placement.id) continue
            when (child) {
                is GeneratedWidgetCell -> child.destroyWidget()
                is StockWidgetCell -> child.release()
            }
            grid.removeViewAt(i)
            grid.requestLayout()
            grid.invalidate()
            return
        }
    }

    private fun showHomeCellContextMenu(
        grid: com.iappyx.launcher.widget.HomeGrid,
        placement: Placement,
        anchor: android.view.View,
    ): android.widget.PopupWindow {
        return com.iappyx.launcher.widget.HomeCellContextMenu.show(
            context = this,
            anchor = anchor,
            placement = placement,
            onCustomizeHome = {
                if (!editMode) setEditMode(true)
                // setEditMode triggers a rebind that detaches+re-adds children;
                // defer the selection so it operates on the fresh views.
                grid.post { grid.selectPlacement(placement) }
            },
            onRemove = { removePlacement(placement) },
            onEditGenerated = { launchEditWidget(placement) },
            onOpenAppInfo = { pkg ->
                val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$pkg")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { startActivity(intent) } catch (_: Throwable) { /* no-op */ }
            },
            onUninstall = { pkg ->
                val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                    data = android.net.Uri.parse("package:$pkg")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                } catch (_: Throwable) {
                    android.widget.Toast.makeText(
                        this,
                        "Couldn't uninstall — try App info instead.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onChangeIcon = { pkg ->
                val prefs = LauncherPrefs(this)
                com.iappyx.launcher.widget.IconOverrideSheet(
                    activity = this,
                    packPkg = prefs.iconPack,
                    onPick = { drawableName ->
                        prefs.setIconOverride(pkg, drawableName)
                        notifyIconFiltersChanged()
                    },
                    onReset = {
                        prefs.setIconOverride(pkg, null)
                        notifyIconFiltersChanged()
                    },
                ).show()
            },
            onRename = { pkg -> showRenameDialog(pkg) },
        )
    }

    /** Per-app custom label dialog. Prefilled with the current label (the
     *  existing override, else the system label). Save stores the override;
     *  Reset clears it. Refreshes home + dock; the drawer rebuilds on next open. */
    private fun showRenameDialog(pkg: String) {
        val prefs = LauncherPrefs(this)
        val systemLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
        val current = prefs.appLabel(pkg, systemLabel).toString()
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(text.length)
            setSingleLine(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0) }
        box.addView(input)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.cell_rename_dialog_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.setAppLabel(pkg, input.text?.toString())
                notifyIconFiltersChanged()
                refreshDock()
            }
            .setNeutralButton(R.string.cell_rename_dialog_reset) { _, _ ->
                prefs.setAppLabel(pkg, null)
                notifyIconFiltersChanged()
                refreshDock()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showThemed()
    }

    private fun removePlacement(placement: Placement) {
        // Locate the home page containing this placement BEFORE removing so
        // we can perform a targeted in-place removal (no full-page recycle
        // — preserves sibling stock widgets' AppWidgetHostViews).
        val affectedPageIndex = layout.pages.indexOfFirst { page ->
            page.placements.any { it.id == placement.id }
        }
        layout.pages.forEach { page -> page.placements.removeAll { it.id == placement.id } }
        layout.dockPages.forEach { dockPage -> dockPage.removeAll { it.id == placement.id } }
        if (placement.type == CellType.STOCK_WIDGET) {
            placement.appWidgetId?.let { hostManager.deleteId(it) }
        }
        store.save(layout)
        pagerAdapter.updateLayoutSilent(layout)
        if (affectedPageIndex >= 0) {
            val live = findLiveHomeGrid(affectedPageIndex)
            if (live != null) {
                removeCellFromGrid(live, placement)
            } else {
                pagerAdapter.notifyItemChanged(1 + affectedPageIndex)
            }
        }
        dockAdapter.setLayout(layout)
        refreshHomeIndicator()
        refreshDockIndicator()
        scheduleChromeStripRecenter()
    }

    // ── App icon flow ──────────────────────────────────────────

    private fun showAppPicker(pageIndex: Int, row: Int, col: Int) {
        pendingPlacement = PendingPlacement(pageIndex, row, col, null)
        pickAppLauncher.launch(
            Intent(this, AppPickerActivity::class.java).apply {
                putExtra(AppPickerActivity.EXTRA_TITLE, getString(R.string.app_picker_title_add_app))
            }
        )
    }

    // ── Stock widget flow ──────────────────────────────────────

    private fun launchStockWidgetPicker(pageIndex: Int, row: Int, col: Int) {
        // Use our custom picker — the system ACTION_APPWIDGET_PICK fails with
        // "Cannot add widget" on non-system launchers because we don't hold the
        // BIND_APPWIDGET system permission. We list providers ourselves, then
        // call bindAppWidgetIdIfAllowed / ACTION_APPWIDGET_BIND on the selected one.
        pendingPlacement = PendingPlacement(pageIndex, row, col, null)
        pickStockLauncher.launch(
            Intent(this, StockWidgetPickerActivity::class.java)
        )
    }

    private fun bindSelectedStockWidget(provider: android.content.ComponentName) {
        val id = hostManager.allocateId()
        // Seed the bind with a reasonable default size so the provider can
        // generate correctly-sized RemoteViews on its FIRST onUpdate rather
        // than emitting a generic layout then re-emitting once we report size
        // (the extra update is a common cause of inflation races).
        val dp = resources.displayMetrics.density
        val cellW = (pager.width.toFloat() / layout.cols.coerceAtLeast(1) / dp).toInt().coerceAtLeast(40)
        val cellH = (pager.height.toFloat() / layout.rows.coerceAtLeast(1) / dp).toInt().coerceAtLeast(40)
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, cellW * 2)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, cellW * 2)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, cellH * 2)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, cellH * 2)
        }
        val bound = try {
            hostManager.manager.bindAppWidgetIdIfAllowed(id, provider, options)
        } catch (e: SecurityException) {
            android.util.Log.w("iappyxLauncher", "bindAppWidgetIdIfAllowed threw", e)
            false
        }
        if (bound) {
            onStockWidgetBound(id)
        } else {
            // Ask the user for permission to bind this specific widget.
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            }
            pendingPlacement = pendingPlacement?.copy(appWidgetId = id)
            bindWidgetLauncher.launch(intent)
        }
    }

    private fun onStockWidgetBound(widgetId: Int) {
        pendingPlacement = pendingPlacement?.copy(appWidgetId = widgetId)
        val info = hostManager.manager.getAppWidgetInfo(widgetId)
        if (info?.configure != null) {
            val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            configureWidgetLauncher.launch(configIntent)
        } else {
            finishStockWidgetPlacement(widgetId)
        }
    }


    private fun finishStockWidgetPlacement(widgetId: Int) {
        val pending = pendingPlacement ?: return
        // Stock widget sizing: default 2x2, real size comes from AppWidgetProviderInfo
        // minWidth / minHeight in a future resize gesture.
        val wSpan = 2
        val hSpan = 2
        val placement = Placement(
            id = Placement.newId(),
            type = CellType.STOCK_WIDGET,
            row = pending.row, col = pending.col,
            wSpan = wSpan, hSpan = hSpan,
            appWidgetId = widgetId,
        )
        addPlacement(pending.pageIndex, placement)
        pendingPlacement = null
    }

    // ── Generated widget flows ─────────────────────────────────

    /** Demo clock — loads from assets, same HTML every time, no AI call. */
    private fun addDemoClockWidget(pageIndex: Int, row: Int, col: Int) {
        val placement = Placement(
            id = Placement.newId(),
            type = CellType.GENERATED_WIDGET,
            row = row, col = col, wSpan = 2, hSpan = 2,
            generatedWidgetId = "clock",
            generatedWidgetAsset = "widgets/clock.html",
        )
        addPlacement(pageIndex, placement)
    }

    /** AI widget (manual flow) — switches to the AI tab and configures the
     *  inline manual canvas with a placement target so save commits onto
     *  the originating home cell. The automated path lives in the AI
     *  command bar's chat (also at pager position 0) and is invoked
     *  separately via [askIappyxForCell]. */
    private fun launchAiWidgetCreator(pageIndex: Int, row: Int, col: Int) {
        enterManualAiMode(
            type = com.iappyx.launcher.widget.ManualAiCanvas.Type.WIDGET,
            placement = com.iappyx.launcher.widget.ManualAiCanvas.PlacementTarget(
                pageIndex, row, col,
            ),
        )
    }

    /** Reads an AI-saved widget.html out of its sandbox dir. */
    private fun loadFromSandbox(widgetId: String): String {
        val f = File(filesDir, "widgets/$widgetId/widget.html")
        return if (f.exists()) f.readText()
        else "<html><body style='color:white;font-family:sans-serif;padding:16px'>" +
            "<p>Widget content missing.</p></body></html>"
    }

    // ── Placement commit ───────────────────────────────────────

    private fun addPlacement(pageIndex: Int, placement: Placement) {
        // Virtual trailing page in edit mode — materialize a new Page before inserting.
        val materialisedNewPage = pageIndex == layout.pages.size
        if (materialisedNewPage) {
            layout.pages.add(Page())
        } else if (pageIndex !in layout.pages.indices) {
            return
        }
        layout.pages[pageIndex].placements.add(placement)
        store.save(layout)
        // Avoid pagerAdapter.setLayout (notifyDataSetChanged) — it recycles
        // every currently-bound HomeHolder, which tears down sibling stock
        // widgets' AppWidgetHostViews and produces a visible blink until
        // the rebind settles. Same logic the DnD commitPageMove path uses.
        pagerAdapter.updateLayoutSilent(layout)
        if (materialisedNewPage) {
            // Brand new trailing page — pager has no holder for this slot
            // yet, so let RecyclerView allocate one. Sibling pages are
            // untouched.
            pagerAdapter.notifyItemInserted(1 + pageIndex)
        } else {
            val live = findLiveHomeGrid(pageIndex)
            if (live != null) {
                addCellView(live, placement, pageIndex)
            } else {
                pagerAdapter.notifyItemChanged(1 + pageIndex)
            }
        }
        refreshHomeIndicator()
        scheduleChromeStripRecenter()
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun loadAsset(name: String): String = try {
        assets.open(name).use { BufferedReader(InputStreamReader(it)).readText() }
    } catch (e: Exception) {
        "<html><body style='color:white;font-family:sans-serif;padding:16px'>" +
            "<p>Missing asset: $name</p></body></html>"
    }
}
