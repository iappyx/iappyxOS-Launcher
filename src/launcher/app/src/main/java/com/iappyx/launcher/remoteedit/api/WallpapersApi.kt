/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — wallpapers tab. List, set active, delete
 * user-generated, serve preview HTML. Bundled wallpapers ship with the
 * launcher; user wallpapers are AI-generated and live in filesDir.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.wallpaper.WallpaperLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WallpapersApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val active = LauncherPrefs(context).activeWallpaperId
        val arr = JSONArray()
        for (e in WallpaperLibrary.all(context)) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("isUserGenerated", e.isUserGenerated)
                put("active", e.id == active)
            })
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("wallpapers", arr)
            put("activeId", active)
        })
    }

    fun setActive(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val id = obj.optString("id")
        if (id.isBlank()) return JsonResponse.error(ex, 400, "no id")
        if (!WallpaperLibrary.isKnown(context, id)) {
            return JsonResponse.error(ex, 404, "no such wallpaper")
        }
        val prefs = LauncherPrefs(context)
        prefs.activeWallpaperId = id
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.WALLPAPER_CHANGED_ACTION)
                    .setPackage(context.packageName)
                    .putExtra("id", id),
            )
        } catch (_: Throwable) {}
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun delete(ex: MicroHttpServer.Exchange, id: String) {
        if (!WallpaperLibrary.deleteUser(context, id)) {
            return JsonResponse.error(
                ex, 400,
                "can't delete: bundled, currently active, or not found",
            )
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun rename(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "no body")
        val title = obj.optString("title").trim()
        if (title.isEmpty()) return JsonResponse.error(ex, 400, "title required")
        if (!WallpaperLibrary.renameUser(context, id, title)) {
            return JsonResponse.error(ex, 400, "rename refused (bundled or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun updateDescription(ex: MicroHttpServer.Exchange, id: String) {
        val obj = JsonResponse.readJsonObject(ex)
            ?: return JsonResponse.error(ex, 400, "no body")
        val prompt = obj.optString("description", obj.optString("prompt"))
        if (!WallpaperLibrary.updatePrompt(context, id, prompt)) {
            return JsonResponse.error(ex, 400, "update refused (bundled or missing)")
        }
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Inline-serve the wallpaper HTML so the editor can show a live
     *  preview iframe.
     *
     *  Inject the iappyx-shim so the wallpaper's bridge calls
     *  (iappyxStorage / iappyxHttpClient / iappyxDevice / etc.)
     *  proxy through `/api/bridge/call` to a sandboxed WidgetHost
     *  in the launcher process. Without this, wallpapers that use
     *  the standard "load CDN library via iappyxHttpClient + cache
     *  with iappyxStorage" pattern fail silently (those globals
     *  are undefined in the iframe) and the wallpaper renders as
     *  a black screen.
     *
     *  Wallpaper-specific push events (onPageOffset, onAccelerometer,
     *  onVisibility, onLayoutChanged) don't fire from the editor —
     *  they require the on-device wallpaper engine. Wallpapers that
     *  hard-depend on those won't animate in the preview, but the
     *  HTML / library load itself works, which is the bigger pain
     *  point. */
    fun preview(ex: MicroHttpServer.Exchange, id: String) {
        val html = readWallpaperHtml(id)
            ?: return JsonResponse.error(ex, 404, "no such wallpaper")
        // Sanitise id for safe JS injection — same rule as the widget
        // shim. Wallpaper ids are uuids / known slugs already; this is
        // belt-and-suspenders.
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        // Read the cached layout snapshot the launcher persists for
        // the on-device wallpaper engine. Same source LayoutBridge
        // seeds from at engine-start; lets `iappyxLayout.get()`
        // return a real layout (with current icon bounding boxes)
        // in the editor preview. Falls back to the shape-compatible
        // empty layout if the file isn't there.
        val layoutSnapshot = readLayoutSnapshotJson()
        // Three-stage injection:
        //   1. CSS reset so the wallpaper fills the iframe with no
        //      stray scrollbars from sub-pixel overflow.
        //   2. Session globals + the standard iappyx-shim (gives
        //      iappyx.storage / iappyx.httpClient / iappyx.device /
        //      iappyx.media / all namespaced bridges, plus the
        //      synchronous storage cache).
        //   3. Wallpaper-only extension shim — matches the on-device
        //      WALLPAPER_SHIM in BridgeShims.kt. Without this,
        //      wallpapers that use `iappyx.log(...)` or
        //      `iappyx.cb(id => …)` or attach push-event handlers
        //      via `iappyx.onPageOffset = …` failed because the
        //      widget-style iappyx-shim only ships namespaced
        //      bridges (iappyx.storage etc.), not the wallpaper-
        //      specific top-level methods.
        //
        //   iappyx.log         → console.log (no native bridge in editor)
        //   iappyx.enableAccelerometer → no-op (no sensor available)
        //   iappyx.cb(fn)      → same Promise wrapper as phone
        //   iappyx.onPageOffset / onAccelerometer / onVisibility /
        //     onLayoutChanged  → settable; never fired in editor
        //     (would need wallpaper-engine plumbing for live preview).
        val injection = """<style>
html,body{margin:0;padding:0;overflow:hidden;width:100%;height:100%}
</style>
<script>
window.__iappyxSession='wallpaper-${safeId}';
window.__iappyxWidgetId=${quoteJs("wp_$safeId")};
window.__iappyxStorageCache={};
</script>
<script src="/static/iappyx-shim.js"></script>
<script>
(function(){
  var ix = window.iappyx;
  if (!ix) return;
  window._iappyxCb = window._iappyxCb || {};
  if (typeof ix.log !== 'function') {
    ix.log = function(m){ try { console.log('[wp]', String(m)); } catch(_){} };
  }
  if (typeof ix.enableAccelerometer !== 'function') {
    ix.enableAccelerometer = function(){ /* no sensor in editor preview */ };
  }
  if (typeof ix.cb !== 'function') {
    ix.cb = function(fn){
      return new Promise(function(resolve){
        var id = '_cb' + Date.now() + '_' + Math.random().toString(36).slice(2);
        window._iappyxCb[id] = resolve;
        try { fn(id); }
        catch(e){ delete window._iappyxCb[id]; resolve({ok:false, error: String(e)}); }
      });
    };
  }
  // iappyxLayout: wallpaper-only global. On the phone the wallpaper
  // service registers it as a native @JavascriptInterface. In the
  // editor we stub it as a plain object whose .get() returns the
  // most recent layout snapshot (seeded with the cached
  // wallpaper_layout_snapshot.json the launcher persists, then
  // refreshed in-place whenever the parent window forwards a
  // layout-changed postMessage — see below).
  window.__iappyxLayoutCache = ${quoteJs(layoutSnapshot)};
  if (!window.iappyxLayout) {
    window.iappyxLayout = { get: function(){ return window.__iappyxLayoutCache; } };
  }
  // Live wallpaper push events. The parent editor window subscribes
  // to /api/state/stream and forwards LAYOUT_CHANGED_ACTION
  // broadcasts as a postMessage with the full snapshot JSON. We
  // refresh the cache and fire iappyx.onLayoutChanged so wallpapers
  // that depend on icon bounding boxes (parallax masks, ripple
  // origins, layout-aware blurs) re-render the same way they would
  // on the phone.
  window.addEventListener('message', function(ev){
    var msg = ev && ev.data;
    if (!msg || msg.__iappyx !== true) return;
    if (msg.kind === 'layout-changed' && typeof msg.json === 'string') {
      window.__iappyxLayoutCache = msg.json;
      try {
        if (typeof window.iappyx.onLayoutChanged === 'function') {
          window.iappyx.onLayoutChanged(msg.json);
        }
      } catch (_) {}
    }
  });
})();
</script>
"""
        val headIdx = html.indexOf("<head", ignoreCase = true)
        val output = if (headIdx >= 0) {
            val closeIdx = html.indexOf('>', startIndex = headIdx)
            if (closeIdx < 0) {
                "<head>$injection</head>$html"
            } else {
                html.substring(0, closeIdx + 1) + injection + html.substring(closeIdx + 1)
            }
        } else {
            "<!doctype html><html><head>$injection</head><body>$html</body></html>"
        }
        ex.status = 200
        ex.setHeader("Content-Type", "text/html; charset=utf-8")
        ex.setHeader("Cache-Control", "no-store")
        ex.setBody(output.toByteArray(Charsets.UTF_8))
    }

    /** Same JSON the wallpaper engine reads on-start: the launcher
     *  writes `wallpaper_layout_snapshot.json` to filesDir on every
     *  layout commit (see WallpaperLayoutPublisher). When the file
     *  doesn't exist (first run, fresh install) we return a shape-
     *  compatible empty layout so `iappyxLayout.get()` always returns
     *  a parseable JSON string. */
    private fun readLayoutSnapshotJson(): String {
        return try {
            val f = File(context.filesDir, com.iappyx.launcher.LauncherPrefs.LAYOUT_SNAPSHOT_FILE)
            if (f.exists() && f.length() > 0) f.readText() else EMPTY_LAYOUT_JSON
        } catch (_: Throwable) {
            EMPTY_LAYOUT_JSON
        }
    }

    private fun quoteJs(s: String): String {
        // The snapshot JSON is multi-line and may contain backslashes /
        // single-quotes; escape minimally so the literal closes
        // correctly in the JS source. Also escapes the U+2028/2029
        // line separators that JSON allows but JS treats as line
        // terminators inside string literals.
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    companion object {
        private const val EMPTY_LAYOUT_JSON =
            """{"screen":{"width":0,"height":0,"density":1},""" +
            """"pageCount":0,"pageWidth":0,"currentPage":0,""" +
            """"systemBars":{"top":0,"bottom":0},"cells":[],"dock":[]}"""
    }

    private fun readWallpaperHtml(id: String): String? {
        // Bundled assets live at assets/wallpapers/{id}.html
        try {
            return context.assets.open("wallpapers/$id.html")
                .bufferedReader().use { it.readText() }
        } catch (_: Throwable) { /* not bundled — try user dir */ }
        val f = File(WallpaperLibrary.userDir(context), "$id.html")
        if (!f.exists()) return null
        return try { f.readText() } catch (_: Throwable) { null }
    }
}
