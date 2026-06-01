/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — widget thumbnail capture.
 *
 * Renders the widget's HTML in an offscreen WebView (hosted by the
 * RemoteEdit activity), waits for page-finished plus a short settle,
 * then captures a Bitmap via View.draw on a software canvas.
 *
 * Bridges are NOT connected — the WebView has no `iappyx` JS interface.
 * Most widgets fail their bridge calls silently or with caught errors;
 * the static layout / styles still render, which is enough for a
 * recognizable thumbnail.
 */
package com.iappyx.launcher.remoteedit.api

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.widget.WidgetLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ThumbnailApi(private val activity: Activity) {

    private data class Cached(val bytes: ByteArray, val timestamp: Long)
    private val cache = mutableMapOf<String, Cached>()
    private val cacheTtlMs = 60_000L

    fun widgetThumb(ex: MicroHttpServer.Exchange, widgetId: String) {
        if (widgetId.isBlank() || widgetId.contains('/')) {
            JsonResponse.error(ex, 400, "bad id"); return
        }
        val now = System.currentTimeMillis()
        cache[widgetId]?.let { c ->
            if (now - c.timestamp < cacheTtlMs) {
                ex.addHeader("Cache-Control", "public, max-age=60")
                JsonResponse.raw(ex, 200, "image/png", c.bytes); return
            }
        }
        val html = readWidgetHtml(widgetId)
        if (html.isBlank()) {
            JsonResponse.error(ex, 404, "no widget html")
            return
        }
        val bytes = renderToBitmap(html)
        if (bytes == null) {
            JsonResponse.error(ex, 500, "render failed")
            return
        }
        cache[widgetId] = Cached(bytes, now)
        ex.addHeader("Cache-Control", "public, max-age=60")
        JsonResponse.raw(ex, 200, "image/png", bytes)
    }

    /** Read widget HTML — assets path for bundled, file path for user-generated. */
    private fun readWidgetHtml(widgetId: String): String {
        val assetPath = WidgetLibrary.bundledAssetPath(widgetId)
        if (assetPath != null) {
            return try {
                activity.assets.open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) { "" }
        }
        val file = File(WidgetLibrary.rootDir(activity), "$widgetId/widget.html")
        if (!file.exists()) return ""
        return try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
    }

    /** Render HTML in an offscreen WebView and capture to PNG. Blocks for
     *  up to 6s (page load + settle). Returns null on timeout / error. */
    private fun renderToBitmap(html: String): ByteArray? {
        val pngBytes = arrayOfNulls<ByteArray>(1)
        val latch = CountDownLatch(1)
        val width = 360
        val height = 360

        Handler(Looper.getMainLooper()).post {
            val container = activity.findViewById<ViewGroup>(android.R.id.content)
            val wv = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                // Position offscreen but with a real width/height — Android
                // refuses to draw views with no laid-out dimensions.
                layoutParams = ViewGroup.LayoutParams(width, height)
                visibility = View.INVISIBLE
                x = -10000f; y = -10000f  // belt and suspenders
            }
            // Add to root view tree so it gets measured + laid out.
            container?.addView(wv)
            // Force a measure + layout pass.
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, width, height)

            val captured = java.util.concurrent.atomic.AtomicBoolean(false)
            val capture = Runnable {
                if (!captured.compareAndSet(false, true)) return@Runnable
                try {
                    if (wv.width > 0 && wv.height > 0) {
                        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        wv.draw(canvas)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 75, out)
                        pngBytes[0] = out.toByteArray()
                    }
                } catch (_: Throwable) { /* best-effort */ }
                try { container?.removeView(wv) } catch (_: Throwable) {}
                try { wv.destroy() } catch (_: Throwable) {}
                latch.countDown()
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Give the page 800ms to finish first paint + initial JS.
                    Handler(Looper.getMainLooper()).postDelayed(capture, 800)
                }
            }
            // Load HTML with the same base URL the launcher uses for widgets,
            // so any relative resources (CDN libraries the widget injects via
            // runScript would use this) resolve correctly.
            wv.loadDataWithBaseURL(
                "https://widget.local/", html, "text/html", "UTF-8", null,
            )
            // Hard timeout in case onPageFinished never fires.
            Handler(Looper.getMainLooper()).postDelayed(capture, 5500)
        }
        latch.await(6, TimeUnit.SECONDS)
        return pngBytes[0]
    }
}
