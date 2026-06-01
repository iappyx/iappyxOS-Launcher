/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — serves the web app from /assets/remoteedit/.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer

class AssetsApi(private val context: Context) {

    fun servePair(ex: MicroHttpServer.Exchange) = serve(ex, "remoteedit/pair.html", "text/html; charset=utf-8")
    fun serveIndex(ex: MicroHttpServer.Exchange) = serve(ex, "remoteedit/index.html", "text/html; charset=utf-8")

    fun serveStatic(ex: MicroHttpServer.Exchange, path: String) {
        // Whitelist by extension to keep this simple + safe.
        val cleanPath = path.replace("..", "").trim('/')
        val mime = when {
            cleanPath.endsWith(".js") -> "application/javascript; charset=utf-8"
            cleanPath.endsWith(".css") -> "text/css; charset=utf-8"
            cleanPath.endsWith(".html") -> "text/html; charset=utf-8"
            cleanPath.endsWith(".svg") -> "image/svg+xml"
            cleanPath.endsWith(".png") -> "image/png"
            cleanPath.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
        serve(ex, "remoteedit/$cleanPath", mime)
    }

    private fun serve(ex: MicroHttpServer.Exchange, assetPath: String, contentType: String) {
        try {
            context.assets.open(assetPath).use { inp ->
                val bytes = inp.readBytes()
                JsonResponse.raw(ex, 200, contentType, bytes)
            }
        } catch (_: Throwable) {
            JsonResponse.error(ex, 404, "asset not found")
        }
    }
}
