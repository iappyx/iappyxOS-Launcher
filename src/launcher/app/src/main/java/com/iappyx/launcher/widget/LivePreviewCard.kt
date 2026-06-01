/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.WidgetHost
import com.iappyx.launcher.wallpaper.WallpaperBridge
import java.io.File
import java.util.UUID

/**
 * A single page in the manage-tab carousel: title + tags + a live WebView
 * preview (full bridge surface) + per-entry action buttons. Owns one WebView
 * for its lifetime; [bind] swaps in a new payload, [unbind] tears bridges
 * down and parks the WebView at about:blank ready for re-bind.
 *
 * RecyclerView (inside ViewPager2) reuses these cards across positions —
 * the bind / unbind pair handles that. With ViewPager2's default
 * `offscreenPageLimit=1`, only the current page + immediate neighbours are
 * alive, capping memory at ~3 WebViews.
 */
class LivePreviewCard(
    private val activity: LauncherActivity,
    private val type: PreviewType,
    private val aspect: Aspect,
) : LinearLayout(activity) {

    enum class PreviewType { WIDGET, WALLPAPER }
    enum class Aspect { SQUARE, PHONE }
    /**
     * Per-card action chip. When [iconRes] is non-zero it renders as an
     * icon stacked above a small text label (matching the top-bar icon
     * style across the manage tabs); when 0 it falls back to a text-only
     * pill (legacy shape, still used by tabs that haven't migrated to
     * the icon-row style yet). [destructive] flips the icon + label tint
     * to a soft red — used by Delete so users notice it.
     */
    data class Action(
        val label: String,
        val enabled: Boolean = true,
        @androidx.annotation.DrawableRes val iconRes: Int = 0,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    )
    data class Tag(val label: String, val accent: String = "#FF8FE3A0")

    private val dp = resources.displayMetrics.density
    private val titleLabel: TextView
    private val subtitleLabel: TextView
    private val tagRow: LinearLayout
    /** Exposed to the carousel so it can bounds-check touches: only swipes
     *  beginning inside this surface should claim the gesture from the outer
     *  home pager. Touches on the title / actions / dots fall through. */
    val previewSurface: FrameLayout
    private val webView: WebView
    private val actionRow: LinearLayout
    private var widgetHost: WidgetHost? = null
    private var wallpaperBridge: WallpaperBridge? = null
    private var sandboxDir: File? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(
            (16 * dp).toInt(), (12 * dp).toInt(),
            (16 * dp).toInt(), (12 * dp).toInt(),
        )

        titleLabel = TextView(activity).apply {
            setTextColor(Color.WHITE); textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(titleLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        subtitleLabel = TextView(activity).apply {
            setTextColor(Color.parseColor("#A0A0B8")); textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(subtitleLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        tagRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        addView(tagRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        previewSurface = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.BLACK)
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            clipToOutline = true
        }
        // Surface size is computed at bind time once we know the view's actual
        // height (set width/height via layoutParams in bind).
        addView(previewSurface, LayoutParams(0, 0).apply {
            topMargin = (12 * dp).toInt()
        })

        webView = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        previewSurface.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        actionRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Wrapped in HorizontalScrollView so 6+ action chips can overflow
        // without being cut off — the scroll gesture is horizontal but it
        // doesn't fight the carousel because it lives in the bottom action
        // strip, not in the preview-surface region claim.
        val actionScroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.widget.HorizontalScrollView.OVER_SCROLL_NEVER
            setPadding(0, (12 * dp).toInt(), 0, 0)
            addView(
                actionRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(actionScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Push new content into the card. Tears down any prior bridges, sets up
     *  fresh ones, and loads the new URL. Cheap to call repeatedly. */
    fun bind(
        title: String,
        subtitle: String,
        url: String,
        tags: List<Tag>,
        actions: List<Action>,
    ) {
        unbind()
        titleLabel.text = title
        subtitleLabel.text = subtitle

        tagRow.removeAllViews()
        for (tag in tags) tagRow.addView(makeTagChip(tag))

        // Resize the preview surface to the right aspect for THIS card's
        // available width. We don't know the carousel's measured width until
        // first layout — fall back to 240dp width while waiting.
        previewSurface.post { resizePreviewSurface() }

        actionRow.removeAllViews()
        for (a in actions) actionRow.addView(makeActionButton(a))

        // Bridges + load
        val previewWidgetId = "_preview_${UUID.randomUUID().toString().take(8)}"
        val sandbox = File(activity.filesDir, "widgets/$previewWidgetId").also { it.mkdirs() }
        sandboxDir = sandbox
        val host = WidgetHost(activity, webView, sandbox, previewWidgetId)
        widgetHost = host

        when (type) {
            PreviewType.WIDGET -> {
                host.registerBridges()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, _u: String?) {
                        view.evaluateJavascript(BridgeShims.WIDGET_SHIM, null)
                    }
                }
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(
                            androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                            webView, BridgeShims.WIDGET_SHIM, setOf("*"),
                        )
                    }
                } catch (_: Throwable) {}
                webView.loadUrl(url)
            }
            PreviewType.WALLPAPER -> {
                host.registerWallpaperBridges()
                val bridge = WallpaperBridge(activity, webView).also {
                    webView.addJavascriptInterface(it, "_iappyxBridge")
                }
                wallpaperBridge = bridge
                webView.webViewClient = WebViewClient()
                val html = readHtml(url)
                if (html != null) {
                    val baseUrl = url.substringBeforeLast('/') + "/"
                    webView.loadDataWithBaseURL(
                        baseUrl, injectWallpaperShim(html), "text/html", "UTF-8", null,
                    )
                } else {
                    webView.loadUrl(url)
                }
                bridge.pushVisibility(true)
            }
        }
    }

    /** Tear down bridges + the throw-away widget sandbox. Loads about:blank
     *  to dump JS state so the next bind starts clean. Also called from
     *  RecyclerView's onViewRecycled. */
    fun unbind() {
        try { wallpaperBridge?.pushVisibility(false) } catch (_: Throwable) {}
        try { wallpaperBridge?.teardown() } catch (_: Throwable) {}
        wallpaperBridge = null
        try { widgetHost?.destroy() } catch (_: Throwable) {}
        widgetHost = null
        try { sandboxDir?.deleteRecursively() } catch (_: Throwable) {}
        sandboxDir = null
        try {
            webView.stopLoading(); webView.loadUrl("about:blank")
            // Clear the JavascriptInterface name we registered for wallpapers
            // so a re-bind doesn't get confused.
            webView.removeJavascriptInterface("_iappyxBridge")
        } catch (_: Throwable) {}
    }

    /** ViewPager2 selected this page. Resume RAF / sensor stream. */
    fun onPageVisible() {
        try { webView.onResume() } catch (_: Throwable) {}
        try { wallpaperBridge?.pushVisibility(true) } catch (_: Throwable) {}
    }

    /** ViewPager2 left this page (still cached, but offscreen). Pause to
     *  save battery — sensors keep firing but RAF and JS execution slow
     *  down per Chromium's normal background heuristics. */
    fun onPageHidden() {
        try { wallpaperBridge?.pushVisibility(false) } catch (_: Throwable) {}
        try { webView.onPause() } catch (_: Throwable) {}
    }

    private fun resizePreviewSurface() {
        val parentWidth = (parent as? ViewGroup)?.width ?: width
        if (parentWidth <= 0) return
        // Reserve horizontal space on each side for the carousel chevrons so
        // the preview surface doesn't sit underneath them. ~52dp matches the
        // chevron tap target + a small margin.
        val chevronReserve = (52 * dp).toInt()
        val availW = parentWidth - paddingLeft - paddingRight - 2 * chevronReserve
        // Cap height so we leave room for title + tags + actions on a phone
        // screen. ~58% of the carousel's height feels right.
        val maxH = (resources.displayMetrics.heightPixels * 0.58f).toInt()

        val (w, h) = when (aspect) {
            Aspect.SQUARE -> {
                val side = minOf(availW, maxH); side to side
            }
            Aspect.PHONE -> {
                // PHONE = match the device's current screen aspect ratio.
                // Was hardcoded 9:16 (portrait) which made wallpaper
                // previews on landscape tablets look like a tall column
                // disconnected from the actual screen they'll render on.
                val dm = resources.displayMetrics
                val isLandscape = dm.widthPixels > dm.heightPixels
                if (isLandscape) {
                    val byHeight = (maxH * 16f / 9f).toInt()
                    if (byHeight <= availW) byHeight to maxH
                    else availW to (availW * 9f / 16f).toInt()
                } else {
                    val byHeight = (maxH * 9f / 16f).toInt()
                    if (byHeight <= availW) byHeight to maxH
                    else availW to (availW * 16f / 9f).toInt()
                }
            }
        }
        val lp = previewSurface.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            lp.gravity = Gravity.CENTER_HORIZONTAL
            previewSurface.layoutParams = lp
        }
    }

    private fun makeTagChip(tag: Tag): TextView = TextView(activity).apply {
        text = tag.label
        setTextColor(Color.parseColor(tag.accent))
        textSize = 10f
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor("#22FFFFFF"))
        }
        setPadding(
            (8 * dp).toInt(), (2 * dp).toInt(),
            (8 * dp).toInt(), (2 * dp).toInt(),
        )
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.rightMargin = (6 * dp).toInt()
        layoutParams = lp
    }

    /** Render an action — either as an icon stacked above a small label
     *  (when [Action.iconRes] is non-zero, the new "manage tab" style) or
     *  as a plain text pill (legacy shape, kept for any caller that still
     *  passes a text-only action). The choice is per-action so tabs can
     *  mix-and-match during migration. */
    private fun makeActionButton(action: Action): android.view.View =
        if (action.iconRes != 0) makeIconAction(action) else makeTextAction(action)

    /** Icon + label vertical stack — same visual recipe as
     *  ManageTabBase.makeIconButton(labelled form). Wrapper carries the
     *  ripple so a tap anywhere on icon + label feels like one element. */
    private fun makeIconAction(action: Action): android.view.View {
        val activeTint = if (action.destructive) Color.parseColor("#FF6B6B")
        else Color.WHITE
        val disabledTint = Color.parseColor("#66FFFFFF")
        val tint = if (action.enabled) activeTint else disabledTint
        val image = android.widget.ImageView(activity).apply {
            setImageResource(action.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val sz = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val label = TextView(activity).apply {
            text = action.label
            textSize = 10f
            setTextColor(tint)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = (1 * dp).toInt()
            layoutParams = lp
        }
        return LinearLayout(activity).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#22FFFFFF"),
            ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
            val padH = (8 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = action.enabled
            isFocusable = action.enabled
            alpha = if (action.enabled) 1f else 0.4f
            if (action.enabled) setOnClickListener { action.onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (4 * dp).toInt(); lp.rightMargin = (4 * dp).toInt()
            layoutParams = lp
            addView(image); addView(label)
        }
    }

    /** Legacy text-only pill — kept so the wallpaper / transition / icon-
     *  filter tabs (not yet migrated) still work without changes. New
     *  tabs use the icon form. */
    private fun makeTextAction(action: Action): TextView = TextView(activity).apply {
        text = action.label
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (action.enabled) Color.WHITE else Color.parseColor("#66FFFFFF"))
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor(if (action.enabled) "#22FFFFFF" else "#11FFFFFF"))
            setStroke((1 * dp).toInt(),
                Color.parseColor(if (action.enabled) "#33FFFFFF" else "#22FFFFFF"))
        }
        setPadding(
            (12 * dp).toInt(), (8 * dp).toInt(),
            (12 * dp).toInt(), (8 * dp).toInt(),
        )
        isClickable = action.enabled
        isFocusable = action.enabled
        if (action.enabled) setOnClickListener { action.onClick() }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (4 * dp).toInt(); lp.rightMargin = (4 * dp).toInt()
        layoutParams = lp
    }

    private fun readHtml(url: String): String? = try {
        when {
            url.startsWith("file:///android_asset/") -> {
                val path = url.removePrefix("file:///android_asset/")
                activity.assets.open(path).bufferedReader().use { it.readText() }
            }
            url.startsWith("file://") -> {
                File(url.removePrefix("file://")).readText(Charsets.UTF_8)
            }
            else -> null
        }
    } catch (_: Throwable) { null }

    private val INDEX_OF_HEAD = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    private fun injectWallpaperShim(html: String): String {
        val shim = "<script>${BridgeShims.WALLPAPER_SHIM}</script>"
        val headIdx = INDEX_OF_HEAD.find(html)?.range?.last
        return if (headIdx != null) {
            html.substring(0, headIdx + 1) + shim + html.substring(headIdx + 1)
        } else shim + html
    }
}

/** RecyclerView ViewHolder wrapper around [LivePreviewCard]. */
class LivePreviewViewHolder(
    val card: LivePreviewCard,
) : androidx.recyclerview.widget.RecyclerView.ViewHolder(card)
