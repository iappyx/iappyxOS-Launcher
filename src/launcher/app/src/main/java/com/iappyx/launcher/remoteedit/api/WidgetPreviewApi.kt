/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — serves widget HTML wrapped with the iappyx-shim
 * so iframes in the editor get a real `window.iappyx` that proxies calls
 * to the phone.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.widget.WidgetLibrary
import org.json.JSONObject
import java.io.File

class WidgetPreviewApi(private val context: Context) {

    /** GET /api/widgets/{id}/preview.html?session=X
     *  Returns the widget's HTML with our shim script injected just after
     *  the opening `<head>` tag (or prepended if no head).
     *  The session id is also embedded as `window.__iappyxSession`.
     */
    fun preview(ex: MicroHttpServer.Exchange, widgetId: String) {
        if (widgetId.isBlank() || widgetId.contains('/')) {
            JsonResponse.error(ex, 400, "bad id"); return
        }
        val html = readWidgetHtml(widgetId)
        if (html.isBlank()) {
            JsonResponse.error(ex, 404, "no widget html"); return
        }
        val session = parseQuery(ex.request.query)["session"] ?: "default"
        // Sanitize session for safe injection: alphanumerics + dash/underscore.
        val safeSession = session.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

        // Pre-fetch the per-widget SharedPreferences. Widgets call
        // `iappyx.load(k)` synchronously (the launcher's @JavascriptInterface
        // returns String immediately); over async HTTP that doesn't work.
        // Ship the keyspace inline so the shim can satisfy load/save/remove
        // from a window-scoped cache, async-persisting in the background.
        //
        // CRITICAL: WidgetHost.getSharedPreferences overrides the file name
        // to `widget_{widgetId}_iappyx_store`. Reading `iappyx_store` directly
        // gets the wrong (empty) prefs — we must use the namespaced file.
        val storageCacheJson = dumpSharedStore(widgetId)

        // CSS reset for iframe previews: on the phone, the widget WebView
        // doesn't show scrollbars when content overflows — the cell just
        // clips. In a browser iframe the default is to show scrollbars
        // on overflow, which leaks editor chrome inside cells whose
        // content is even 1px taller than the cell. Hide overflow so the
        // editor matches phone fidelity. Widgets that legitimately want
        // scrolling can override `html, body { overflow: auto }` inside
        // their own styles.
        val injection = """<style>
html,body{margin:0;padding:0;overflow:hidden;width:100%;height:100%}
</style>
<script>
window.__iappyxSession=${quoteJs(safeSession)};
window.__iappyxWidgetId=${quoteJs(widgetId)};
window.__iappyxStorageCache=$storageCacheJson;
</script>
<script src="/static/iappyx-shim.js"></script>
"""

        // Insert just after opening <head>. If there is no <head>, prepend
        // a synthetic head with the injection.
        val headIdx = html.indexOf("<head", ignoreCase = true)
        val output = if (headIdx >= 0) {
            // find the > of the <head ...> tag
            val closeIdx = html.indexOf('>', startIndex = headIdx)
            if (closeIdx < 0) {
                // malformed; just prepend
                "<head>$injection</head>$html"
            } else {
                html.substring(0, closeIdx + 1) + injection + html.substring(closeIdx + 1)
            }
        } else {
            "<!doctype html><html><head>$injection</head><body>$html</body></html>"
        }

        ex.status = 200
        ex.setHeader("Content-Type", "text/html; charset=utf-8")
        // Don't cache: the session id changes per pairing.
        ex.setHeader("Cache-Control", "no-store")
        ex.setBody(output.toByteArray(Charsets.UTF_8))
    }

    private fun readWidgetHtml(widgetId: String): String {
        val assetPath = WidgetLibrary.bundledAssetPath(widgetId)
        if (assetPath != null) {
            return try {
                context.assets.open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) { "" }
        }
        val file = File(WidgetLibrary.rootDir(context), "$widgetId/widget.html")
        if (!file.exists()) return ""
        return try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null
            else it.substring(0, eq) to java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
    }

    private fun quoteJs(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Dump the widget's per-widget SharedPreferences as a JSON object.
     *  WidgetHost.getSharedPreferences overrides the file name to
     *  `widget_{widgetId}_iappyx_store` — that's where widgets actually
     *  store their data, NOT the bare `iappyx_store` file.
     *
     *  The result is inlined into HTML inside a `<script>` tag, so we
     *  must escape `</`, `<!--`, and `-->` so a stored value containing
     *  any of them can't prematurely terminate the script tag. */
    private fun dumpSharedStore(widgetId: String): String {
        val prefs = context.getSharedPreferences(
            "widget_${widgetId}_iappyx_store", Context.MODE_PRIVATE,
        )
        val obj = JSONObject()
        for ((k, v) in prefs.all) {
            if (v is String) obj.put(k, v)
        }
        return obj.toString()
            .replace("</", "<\\/")
            .replace("<!--", "<\\!--")
            .replace("-->", "--\\>")
    }
}

