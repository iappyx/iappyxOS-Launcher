/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.WidgetHost
import java.io.File

/**
 * Phase-1 spike: hosts a single WebView as the device wallpaper.
 *
 * The interesting bit is HOW we get a live WebView running inside a
 * [WallpaperService.Engine]:
 *
 *  - WebView wants a real [android.view.Window] for `requestAnimationFrame`
 *    to fire and for hardware-accelerated rendering. A bare WebView attached
 *    to nothing tends to stall its RAF the moment it decides it isn't
 *    "visible enough".
 *  - So: build a [VirtualDisplay] backed by the wallpaper's own [SurfaceHolder]
 *    surface, then host a [Presentation] (a Dialog-style top-level Window) on
 *    that virtual display. The Presentation gets a real Window, the WebView
 *    sits in its content view, and what the system renders into the
 *    Presentation's display lands directly in the wallpaper surface.
 *
 * This pattern is well-trodden for Service-hosted live content. The
 * alternative — manually `webView.draw(holder.lockHardwareCanvas())` on a
 * `Choreographer` tick — works but commonly trips on RAF lifecycle quirks.
 *
 * Spike payload is hardcoded to `assets/wallpapers/clock.html`. Phase 3 swaps
 * this for a user-selectable HTML loaded from the launcher's library.
 *
 * Manifest: registered in its own `:wallpaper` process so a misbehaving
 * wallpaper can't take the launcher process down with it.
 */
class IappyxWallpaperService : WallpaperService() {

    override fun onCreate() {
        // CRITICAL: Chromium WebView refuses to run in more than one process
        // sharing the same data directory (https://crbug.com/558377). The
        // launcher process already uses WebView for AI-generated widget cells,
        // so we must put the :wallpaper process's WebView on a separate dir.
        // Must be called before any WebView API is touched in this process —
        // Service.onCreate runs before onCreateEngine, which runs before our
        // WebView is constructed inside the Presentation.
        try {
            WebView.setDataDirectorySuffix("wallpaper")
        } catch (e: IllegalStateException) {
            // Already initialised — safe to ignore on subsequent service binds
            // within the same process lifetime.
            Log.w(TAG, "setDataDirectorySuffix already set", e)
        }
        super.onCreate()
    }

    override fun onCreateEngine(): Engine = IappyxEngine()

    private inner class IappyxEngine : Engine() {

        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: WallpaperPresentation? = null
        private var width = 0
        private var height = 0
        private var wallpaperChangedReceiver: BroadcastReceiver? = null
        private var layoutChangedReceiver: BroadcastReceiver? = null
        /** Surface we passed into [DisplayManager.createVirtualDisplay] in
         *  [buildPresentation]. Cached so we can detect on visibility change
         *  whether the system has silently swapped the engine's surface
         *  underneath us — the BufferQueue-no-producer state we hit after
         *  launcher-process restarts (adb install -r, force-stop, OOM kill).
         *  When the cached surface differs from `surfaceHolder.surface`, our
         *  rendering pipe is dead and we rebuild. */
        private var lastBuildSurface: android.view.Surface? = null
        /** Most-recent layout JSON received via broadcast. The receiver fires
         *  on the engine the moment we register, possibly BEFORE the
         *  Presentation has been built (surface/onSurfaceChanged hasn't
         *  arrived yet). Stash the payload here and replay it when the
         *  Presentation is created so the first-frame layout is correct. */
        private var pendingLayoutJson: String? = null
        // In-process source of truth for the active wallpaper id. We read
        // SharedPreferences once on engine create to seed it, then keep it
        // up to date via broadcasts. Cross-process SharedPreferences caching
        // means re-reading prefs in onSurfaceChanged (rotation) would return
        // a stale value and revert the wallpaper to whatever was loaded when
        // the :wallpaper process first booted.
        private var activeId: String = "clock"

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            // Seed the in-process activeId from prefs ONCE — on subsequent
            // changes the launcher broadcasts the new value to us so we
            // never have to re-read prefs across processes.
            activeId = LauncherPrefs(this@IappyxWallpaperService).activeWallpaperId

            // Listen for the launcher process broadcasting "the user picked a
            // different HTML payload" — same-package sendBroadcast across
            // processes. Hot-reload the WebView when it fires.
            //
            // Implementation note: take the new id from the intent extra
            // rather than re-reading SharedPreferences. Cross-process pref
            // reads are NOT guaranteed to see writes from other processes
            // without an explicit reload, and that's the most likely thing
            // to bite us silently.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val fromIntent = intent.getStringExtra("id")
                    val newId = fromIntent ?: LauncherPrefs(this@IappyxWallpaperService).activeWallpaperId
                    Log.d(TAG, "received WALLPAPER_CHANGED id=$newId (from intent=${fromIntent != null})")
                    activeId = newId
                    val url = WallpaperLibrary.urlFor(this@IappyxWallpaperService, newId)
                    presentation?.loadUrl(url) ?: Log.w(TAG, "no presentation to reload")
                }
            }
            wallpaperChangedReceiver = receiver
            val filter = IntentFilter(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }

            // Layout-changed receiver: launcher sends the home grid + dock
            // bounding boxes whenever a layout commits. The JSON payload is
            // the canonical source — we forward verbatim into the bridge
            // cache and push `iappyx.onLayoutChanged` so user code can react
            // without polling. Reading SharedPreferences across processes
            // is unreliable; the broadcast extra is the only sync channel.
            val layoutRx = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val json = intent.getStringExtra("json") ?: return
                    // Always cache the latest payload — buildPresentation()
                    // replays it when (or if) the Presentation is created.
                    // Without this, the very first broadcast can be lost if
                    // it arrives before onSurfaceChanged builds the surface.
                    pendingLayoutJson = json
                    presentation?.updateLayout(json)
                }
            }
            layoutChangedReceiver = layoutRx
            val layoutFilter = IntentFilter(LauncherPrefs.LAYOUT_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(layoutRx, layoutFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(layoutRx, layoutFilter)
            }
            Log.d(TAG, "engine onCreate: wallpaper-change + layout-change receivers registered")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            // Surface dimensions can change across orientation / split-screen;
            // rebuild the virtual display + presentation when they do.
            if (w == width && h == height && presentation != null) return
            width = w; height = h
            tearDown()
            buildPresentation(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            tearDown()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            // Detect-and-recover: the engine survives launcher-process restarts
            // (separate :wallpaper process), but the system can silently sever
            // the producer connection on our Presentation's surface — we get
            // no callback, just a stuck wallpaper. Use becoming-visible as
            // the heal opportunity. See [isPresentationStale] for signals.
            if (visible && isPresentationStale()) {
                Log.w(TAG, "onVisibilityChanged(true): rendering pipe stale, rebuilding")
                val holder = surfaceHolder
                if (holder != null && holder.surface.isValid && width > 0 && height > 0) {
                    tearDown()
                    buildPresentation(holder)
                }
            }
            // Pause the WebView (and therefore RAF) while invisible — saves
            // battery while the user is in another app. Also notify the
            // payload so it can stop expensive work of its own.
            presentation?.setActive(visible)
            presentation?.bridge?.pushVisibility(visible)
            // Tear down OS-level sensor registration too. Without this,
            // tilt-aware wallpapers keep firing accelerometer events into
            // a paused WebView (~16Hz now, was ~50Hz before P5) for as long
            // as the user is in another app. The bridge tracks the JS-
            // intended state and re-registers on next visibility=true.
            presentation?.bridge?.setEngineVisible(visible)
        }

        /** True if the Presentation+VirtualDisplay state is dead or stale and
         *  cannot produce frames. Multiple signals — any one trips a rebuild:
         *
         *  1. presentation / virtualDisplay null → no pipe at all.
         *  2. virtualDisplay's display is OFF → driver state isn't usable.
         *  3. surfaceHolder.surface differs from the surface we built our
         *     VirtualDisplay against → system replaced it without firing
         *     onSurfaceDestroyed/Created. This is the post-install case.
         *  4. surface is invalid → marker for a teardown we missed. */
        private fun isPresentationStale(): Boolean {
            if (presentation == null) return true
            val vd = virtualDisplay ?: return true
            val display = vd.display ?: return true
            if (display.state == Display.STATE_OFF) return true
            val holderSurface = surfaceHolder?.surface ?: return true
            if (!holderSurface.isValid) return true
            if (lastBuildSurface !== holderSurface) return true
            return false
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(
                xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset,
            )
            // Launcher already calls WallpaperManager.setWallpaperOffsets on
            // pager scroll (see LauncherActivity), which fires this. Forward
            // the horizontal component to JS as `iappyx.onPageOffset(x)`.
            presentation?.bridge?.pushPageOffset(xOffset)
        }

        override fun onDestroy() {
            wallpaperChangedReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Throwable) {}
            }
            wallpaperChangedReceiver = null
            layoutChangedReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Throwable) {}
            }
            layoutChangedReceiver = null
            tearDown()
            super.onDestroy()
        }

        private fun buildPresentation(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid || width <= 0 || height <= 0) return
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            try {
                virtualDisplay = dm.createVirtualDisplay(
                    "iappyx-wallpaper",
                    width, height,
                    resources.displayMetrics.densityDpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                )
                lastBuildSurface = surface
                val display = virtualDisplay?.display ?: return
                presentation = WallpaperPresentation(this@IappyxWallpaperService, display).also {
                    it.onRenderGone = {
                        val h = surfaceHolder
                        if (h != null && h.surface.isValid && width > 0 && height > 0) {
                            tearDown()
                            buildPresentation(h)
                        }
                    }
                    it.show()
                    // Use the in-process activeId, not a fresh prefs read —
                    // see field comment above for why.
                    it.loadUrl(WallpaperLibrary.urlFor(this@IappyxWallpaperService, activeId))
                    // Replay any layout broadcast that arrived before this
                    // Presentation existed. Without this, the very first
                    // layout snapshot can be lost in the gap between
                    // engine.onCreate (where the receiver is registered) and
                    // onSurfaceChanged (where the Presentation is built).
                    pendingLayoutJson?.let { json -> it.updateLayout(json) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to build wallpaper presentation", e)
                tearDown()
            }
        }

        private fun tearDown() {
            try { presentation?.dismiss() } catch (_: Throwable) {}
            presentation = null
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            virtualDisplay = null
            lastBuildSurface = null
        }
    }

    /**
     * The window that actually owns the WebView. A [Presentation] is the
     * canonical way to put a real Window on a non-default display from a
     * Service context — exactly what we need here.
     *
     * Also owns the [WallpaperBridge]: the bridge is registered as
     * `_iappyxBridge` on the WebView (a JavascriptInterface object can't have
     * properties added to it from JS), and a small shim runs on every page
     * load that exposes its methods on a regular JS object, `window.iappyx`,
     * which user payloads can also assign event handlers to (e.g.
     * `iappyx.onPageOffset = x => …`).
     */
    private class WallpaperPresentation(
        ctx: Context,
        display: Display,
    ) : Presentation(ctx, display) {

        private lateinit var web: WebView
        var bridge: WallpaperBridge? = null
            private set
        private var layoutBridge: LayoutBridge? = null
        private var widgetHost: WidgetHost? = null
        /** Set by the engine. Invoked (posted to the main thread) when the
         *  WebView's render process dies, so the engine can rebuild the
         *  Presentation with a fresh WebView instead of leaving a black
         *  wallpaper + a killed :wallpaper process. */
        var onRenderGone: (() -> Unit)? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            web = WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // file:///android_asset/* works without this, but
                // file:///data/data/.../files/wallpapers/{uuid}.html (user-
                // generated payloads) needs allowFileAccess=true on modern
                // Android. Safe here — we only ever load our own files.
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                // No more onPageFinished injection — that fires AFTER the
                // page's inline scripts run, by which point user code's
                // `if (window.iappyx) { … }` guard has already failed.
                // Shim is now prepended into the HTML at load time, see
                // [loadUrl] below.
                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?,
                    ): Boolean {
                        // A long-running WebGL/canvas wallpaper can exhaust GPU
                        // memory and crash its renderer. Returning true keeps
                        // the :wallpaper PROCESS alive (the platform default
                        // would kill it → permanently black wallpaper). Post a
                        // rebuild with a fresh WebView so frames resume.
                        Log.w(TAG, "wallpaper render process gone; rebuilding")
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .post { onRenderGone?.invoke() }
                        return true
                    }
                }
            }
            bridge = WallpaperBridge(context, web).also {
                web.addJavascriptInterface(it, "_iappyxBridge")
            }
            // Layout bridge: bounding boxes of home-grid + dock cells, so the
            // wallpaper can run collision-aware animations. Privacy contract:
            // RECTANGLES ONLY — no app/widget identity. Seed from the snapshot
            // file (covers the case where the wallpaper engine boots before
            // the launcher activity does). Updated thereafter via the
            // LAYOUT_CHANGED broadcast received by the engine.
            layoutBridge = LayoutBridge().also {
                it.update(readLayoutSnapshot(context))
                web.addJavascriptInterface(it, "iappyxLayout")
            }
            // Wire the safe subset of the launcher's full bridge surface so
            // wallpaper HTML can use storage, sensors, location, calendar
            // (read), HTTP, sqlite, etc. — same toolkit widgets get, minus
            // anything that needs an Activity or is hostile-to-wallpaper UX.
            // The `:wallpaper` process inherits the launcher package's
            // permission grants, so a permission granted by SettingsActivity
            // is immediately usable here.
            val sandbox = File(context.filesDir, "wallpaper_sandbox").also { it.mkdirs() }
            widgetHost = WidgetHost(context, web, sandbox, "_wallpaper").also {
                it.registerWallpaperBridges()
            }
            setContentView(
                web,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        /** Called by the engine's broadcast receiver. Updates the bridge
         *  cache so `iappyxLayout.get()` returns fresh data, and pushes
         *  `iappyx.onLayoutChanged(layout)` so subscribers don't have to
         *  poll. Safe to call before [onCreate] runs — we no-op until the
         *  bridge exists. */
        fun updateLayout(json: String) {
            layoutBridge?.update(json)
            bridge?.pushLayoutChanged(json)
        }

        private fun readLayoutSnapshot(ctx: Context): String? = try {
            val f = File(ctx.filesDir, LauncherPrefs.LAYOUT_SNAPSHOT_FILE)
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        } catch (_: Throwable) { null }

        fun loadUrl(url: String) {
            // Read the HTML body, prepend the bridge bootstrap shim, then
            // load via loadDataWithBaseURL. Doing it this way (instead of
            // webView.loadUrl + onPageStarted/onPageFinished evaluateJavascript)
            // guarantees `window.iappyx` is defined BEFORE any inline
            // `<script>` runs — fixes the "wallpaper ignores accelerometer"
            // bug where user code's `if (window.iappyx) { … }` was racing
            // and losing.
            val html = try { readHtml(url) } catch (e: Throwable) {
                Log.e(TAG, "failed to read $url", e); null
            }
            if (html == null) {
                web.loadUrl(url) // best-effort fallback if read fails
                return
            }
            val baseUrl = url.substringBeforeLast('/') + "/"
            web.loadDataWithBaseURL(baseUrl, injectShim(html), "text/html", "UTF-8", null)
        }

        private fun readHtml(url: String): String = when {
            url.startsWith("file:///android_asset/") -> {
                val path = url.removePrefix("file:///android_asset/")
                context.assets.open(path).bufferedReader().use { it.readText() }
            }
            url.startsWith("file://") -> {
                java.io.File(url.removePrefix("file://"))
                    .readText(Charsets.UTF_8)
            }
            else -> error("unsupported wallpaper URL: $url")
        }

        /** Insert the bridge bootstrap as the first thing inside <head>, or
         *  prepend it if the HTML has no <head> tag. The shim resolves
         *  `window.iappyx` synchronously, so the user's `if (window.iappyx)`
         *  check passes the first time it runs. Idempotent — re-running
         *  doesn't double-up the methods, just refreshes them. */
        private fun injectShim(html: String): String {
            val shim = "<script>${com.iappyx.launcher.widget.BridgeShims.WALLPAPER_SHIM}</script>"
            val headIdx = INDEX_OF_HEAD.find(html)?.range?.last
            return if (headIdx != null) {
                html.substring(0, headIdx + 1) + shim + html.substring(headIdx + 1)
            } else {
                shim + html
            }
        }

        fun setActive(active: Boolean) {
            if (!::web.isInitialized) return
            if (active) {
                web.onResume()
                // Re-arm the WidgetHost-registered OS sensors (gyro/compass/
                // light) + watchPosition GPS that pauseBridges() unwound.
                widgetHost?.resumeBridges()
            } else {
                web.onPause()
                // Pause OS sensors + watchPosition while the wallpaper is
                // invisible (user in another app). Without this they keep
                // firing into a paused WebView — the dominant off-screen
                // battery drain. (Foreground Location/Audio services are
                // intentionally left running: they're explicit opt-in with a
                // visible notification, and a background FGS-restart on
                // re-show is unreliable on API 31+.)
                widgetHost?.pauseBridges()
            }
        }

        override fun dismiss() {
            bridge?.teardown()
            bridge = null
            try { widgetHost?.destroy() } catch (_: Throwable) {}
            widgetHost = null
            // Explicitly destroy the WebView before the Presentation tears
            // down — Chromium otherwise leaks the renderer context. Each
            // wallpaper swap rebuilds the Presentation, so without this
            // every swap accumulates a dead WebView.
            if (::web.isInitialized) {
                try { web.destroy() } catch (_: Throwable) {}
            }
            super.dismiss()
        }

        companion object {
            /** Match an opening `<head>` tag (case-insensitive, allow
             *  attributes). Used by [injectShim] to slot the bootstrap
             *  inside the head, so it runs before body scripts. The shim
             *  body itself lives in [com.iappyx.launcher.widget.BridgeShims]
             *  so the wallpaper service AND the preview dialog stay in lock-
             *  step on what `window.iappyx` looks like. */
            private val INDEX_OF_HEAD = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        }
    }

    companion object { private const val TAG = "IappyxWallpaper" }
}
