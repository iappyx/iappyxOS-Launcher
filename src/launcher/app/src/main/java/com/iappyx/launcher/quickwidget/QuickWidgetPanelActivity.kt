/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: transparent activity that hosts a generated widget as a
 * panel. Launched by a Quick Settings tile (or anything else that fires
 * the EXTRA_WIDGET_ID intent). Tap outside, back press, or
 * `iappyx.close()` from the widget dismisses.
 *
 * Reuses the same widget runtime as the home grid: a WebView wired to
 * WidgetHost, with the same BridgeShims.WIDGET_SHIM injected at
 * document_start. The CSS theme guard mirrors GeneratedWidgetCell.bind
 * so colours and safe-insets match.
 *
 * Independent of LauncherActivity — uses Activity context for bridges
 * that need it (camera, file picker), so all bridges work the same as
 * on the home grid.
 */
package com.iappyx.launcher.quickwidget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.WidgetHost
import com.iappyx.launcher.widget.BridgeShims
import com.iappyx.launcher.widget.WidgetLibrary
import com.iappyx.launcher.widget.WidgetSandbox
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class QuickWidgetPanelActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"

        /** Build the intent a TileService fires when tapped. The flag
         *  combination is critical:
         *  - NEW_TASK: required for launching an Activity from a non-
         *    Activity context (the TileService).
         *  - MULTIPLE_TASK: forces a fresh task instead of routing
         *    through the launcher's existing task affinity — without
         *    this the system "yanks" the launcher's home Activity to
         *    the foreground before our panel paints.
         *  - NO_HISTORY: drop from back-stack on dismiss; never appears
         *    in Recents.
         *  Combined with `taskAffinity=""` + `launchMode="singleInstance"`
         *  in the manifest, the panel lives in its own anonymous task,
         *  not the launcher's. That's what makes it overlay the user's
         *  current app instead of routing through home. */
        fun intent(context: Context, widgetId: String): Intent =
            Intent(context, QuickWidgetPanelActivity::class.java).apply {
                putExtra(EXTRA_WIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
    }

    private var webView: WebView? = null
    private var widgetHost: WidgetHost? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent backdrop that dims content beneath; defined in the
        // activity's theme (Translucent.NoTitleBar). The dim is applied
        // via FLAG_DIM_BEHIND so the underlying app fades while the panel
        // is showing.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
        )
        window.attributes = window.attributes.also { it.dimAmount = 0.55f }

        val widgetId = intent.getStringExtra(EXTRA_WIDGET_ID)
        if (widgetId.isNullOrBlank()) { finish(); return }
        val html = loadWidgetHtml(widgetId)
        if (html == null) {
            renderFallback("Widget not found: $widgetId")
            return
        }

        val density = resources.displayMetrics.density
        // Panel sizing: widget can declare preferred dp dimensions in
        // meta.json (`"panelWidthDp": 360, "panelHeightDp": 520`).
        // Defaults to 320×480dp; always clamped to 90vw × 80vh.
        val preferred = readPreferredSizeDp(widgetId)
        val wantW = (preferred.first * density).toInt()
        val wantH = (preferred.second * density).toInt()
        val maxW = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        val panelW = wantW.coerceIn((200 * density).toInt(), maxW)
        val panelH = wantH.coerceIn((240 * density).toInt(), maxH)

        // Root: full-screen frame that catches taps for outside-dismiss.
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        // Panel: rounded card holding the WebView.
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(0xFF17171F.toInt())
                setStroke((1 * density).toInt(), 0x66FFFFFF)
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 24f * density)
                }
            }
            // Swallow taps so they don't bubble to root → outside-dismiss.
            setOnClickListener { /* eat */ }
        }
        root.addView(panel, FrameLayout.LayoutParams(panelW, panelH).apply {
            gravity = Gravity.CENTER
        })
        setContentView(root)

        // Build the widget WebView (same shape as GeneratedWidgetCell).
        val wv = buildWidgetWebView(widgetId)
        panel.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        webView = wv

        val sandbox = WidgetSandbox.sandboxFor(this, widgetId)
        val host = WidgetHost(this, wv, sandbox.dir, widgetId)
        host.registerBridges()
        widgetHost = host

        // Inject the standard theme/CSS guard so the widget inherits the
        // launcher's colours + safe-insets out of the box. Minimal copy
        // of GeneratedWidgetCell.bind's cssGuard — kept inline here so
        // quickwidget/ stays self-contained.
        val wrapped = wrapHtmlWithThemeGuard(html)
        wv.loadDataWithBaseURL(
            "https://widget.local/",
            wrapped,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun buildWidgetWebView(widgetId: String): WebView {
        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    val url = request.url
                    if (url.host == "widget.local" && url.path?.startsWith("/__themefont/") == true) {
                        val file = url.lastPathSegment ?: return null
                        val stream = com.iappyx.launcher.theme.ThemeFonts.openFontStream(this@QuickWidgetPanelActivity, file) ?: return null
                        return android.webkit.WebResourceResponse("font/ttf", null, stream)
                    }
                    return null
                }
                override fun onPageFinished(view: WebView, url: String) {
                    // Document-start injection isn't always supported on
                    // every device. Re-inject on page finished too so the
                    // widget's iappyx.* bridges are always available.
                    view.evaluateJavascript(BridgeShims.WIDGET_SHIM, null)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase() ?: return false
                    if (scheme in setOf("http", "https", "file", "data", "blob")) return false
                    return try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, request.url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    } catch (_: Throwable) { true }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "iappyxWidget",
                        "[$widgetId/panel] ${m.messageLevel().name.lowercase()}: ${m.message()} @${m.sourceId()}:${m.lineNumber()}",
                    )
                    return true
                }
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    widgetHost?.handleWebPermissionRequest(request)
                }
            }
            // Document-start shim injection — same as home-grid widgets so
            // `typeof iappyx` is defined before any widget <script> runs.
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(
                        androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
                ) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        this, BridgeShims.WIDGET_SHIM, setOf("*"),
                    )
                }
            } catch (_: Throwable) { /* onPageFinished fallback handles it */ }
            // Suppress long-press selection in the panel too (consistency
            // with home-grid widgets). No edit-mode here, so the parent
            // doesn't claim the gesture.
            isLongClickable = false
            isHapticFeedbackEnabled = false
            setOnLongClickListener { true }
        }
    }

    /** Preferred panel size in dp, read from the widget's meta.json
     *  (`panelWidthDp` / `panelHeightDp` keys). Defaults to 320×480 when
     *  the keys are absent or the file doesn't exist. Bundled widgets
     *  have no on-disk meta.json so they always get the default — they
     *  can ship a custom size by adding an entry to a future
     *  WidgetLibrary.BUNDLED metadata table. */
    private fun readPreferredSizeDp(widgetId: String): Pair<Int, Int> {
        val defaultPair = 320 to 480
        val metaFile = File(filesDir, "widgets/$widgetId/meta.json")
        if (!metaFile.exists()) return defaultPair
        return try {
            val j = org.json.JSONObject(metaFile.readText())
            val w = j.optInt("panelWidthDp", defaultPair.first).coerceIn(200, 720)
            val h = j.optInt("panelHeightDp", defaultPair.second).coerceIn(240, 1000)
            w to h
        } catch (_: Throwable) { defaultPair }
    }

    /** Resolve the widget's HTML — bundled-asset first, then sandbox
     *  (user-generated). Returns null when neither exists. */
    private fun loadWidgetHtml(widgetId: String): String? {
        val assetPath = WidgetLibrary.bundledAssetPath(widgetId)
        if (assetPath != null) {
            try {
                assets.open(assetPath).use { stream ->
                    return BufferedReader(InputStreamReader(stream)).readText()
                }
            } catch (_: Throwable) { /* fall through to sandbox */ }
        }
        val f = File(filesDir, "widgets/$widgetId/widget.html")
        return if (f.exists()) f.readText() else null
    }

    private fun wrapHtmlWithThemeGuard(html: String): String {
        // Use the SAME token source as a home-grid widget (Material You palette
        // + design defaults + the user's theme overrides) so quick widgets
        // follow the theme too. No safe-insets (panel doesn't extend under
        // system bars). Launcher stays dark-only → color-scheme: dark.
        val tokens = com.iappyx.launcher.cells.GeneratedWidgetCell.effectiveTokens(
            this, com.iappyx.launcher.cells.GeneratedWidgetCell.readThemeTokens(this),
        )
        val themeDecls = tokens.entries.joinToString("\n                ") { "${it.key}: ${it.value};" }
        val cssGuard = """
            <style>
              ${com.iappyx.launcher.theme.ThemeFonts.fontFaceCss(this)}
              :root {
                $themeDecls
                color-scheme: dark;
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
                -webkit-tap-highlight-color: transparent;
                overflow-x: hidden;
              }
              * { box-sizing: border-box; }
            </style>
        """.trimIndent()
        val headMatch = Regex("(?i)<head[^>]*>").find(html)
        return if (headMatch != null) {
            val insertAt = headMatch.range.last + 1
            html.substring(0, insertAt) + cssGuard + html.substring(insertAt)
        } else {
            "<html><head>$cssGuard</head><body>$html</body></html>"
        }
    }

    private fun renderFallback(message: String) {
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setPadding(48, 96, 48, 96)
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER })
            setOnClickListener { finish() }
        }
        setContentView(root)
    }

    override fun onDestroy() {
        try { widgetHost?.destroy() } catch (_: Throwable) {}
        widgetHost = null
        try { webView?.destroy() } catch (_: Throwable) {}
        webView = null
        super.onDestroy()
    }
}
