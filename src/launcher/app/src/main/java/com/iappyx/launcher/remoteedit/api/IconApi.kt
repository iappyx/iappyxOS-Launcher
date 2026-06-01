/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — serves app icons as PNG bytes, with the
 * launcher's active icon filter applied (shape mask + colour bake)
 * so the editor visually mirrors what the phone renders. Without
 * this, the editor showed raw PackageManager bitmaps and the
 * "squircle" / "star" / "tinted_mono" etc. filters never landed in
 * the browser — confusing parity gap for a launcher editor.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import android.graphics.Bitmap
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import java.io.ByteArrayOutputStream

class IconApi(private val context: Context) {

    companion object {
        /** Cache key is `(pkg, filterSlug)` so a filter swap naturally
         *  orphans the old entries. Static so [SettingsApi] /
         *  [IconFiltersApi] can call [invalidate] from their filter-
         *  set paths without holding the IconApi instance. */
        private val cache = mutableMapOf<String, ByteArray>()
        @Synchronized fun invalidate() { cache.clear() }
        @Synchronized internal fun get(key: String): ByteArray? = cache[key]
        @Synchronized internal fun put(key: String, bytes: ByteArray) { cache[key] = bytes }
    }

    fun iconForPackage(ex: MicroHttpServer.Exchange, pkg: String) {
        if (pkg.isBlank() || pkg.contains('/')) {
            JsonResponse.error(ex, 400, "bad pkg")
            return
        }
        // Explicit ?filter= overrides the active prefs value so the
        // editor can optimistically render with the NEW filter
        // immediately on tile-click without waiting for the active-set
        // POST to commit prefs. Falls back to prefs when absent.
        val filterFromQuery = ex.request.query
            .split('&')
            .firstOrNull { it.startsWith("filter=") }
            ?.removePrefix("filter=")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }
            ?.takeIf { it.isNotBlank() }
        val filterSlug = filterFromQuery ?: LauncherPrefs(context).iconFilter
        val cacheKey = "$pkg:$filterSlug"
        val bytes = get(cacheKey) ?: try {
            renderFiltered(pkg, filterSlug).also { put(cacheKey, it) }
        } catch (_: Throwable) {
            JsonResponse.error(ex, 404, "no icon")
            return
        }
        // Short cache TTL on the browser — filter changes need to
        // appear within seconds, not days. 60s is fine: the editor
        // explicitly busts the URL by appending the slug as a query
        // param after a filter change.
        ex.addHeader("Cache-Control", "public, max-age=60")
        JsonResponse.raw(ex, 200, "image/png", bytes)
    }

    private fun renderFiltered(pkg: String, filterSlug: String): ByteArray {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        // Same target px the on-device IconCell uses (128dp at default
        // density ~= 384px on a 3x screen). The editor doesn't know
        // the phone's density at request time; 192px is a sensible
        // compromise — sharp on the editor's 96px cells, light enough
        // on the wire.
        val sizePx = 192
        val spec = IconFilterRegistry.resolve(context, filterSlug)
        val bmp: Bitmap = IconMask.render(pkg, drawable, sizePx, spec)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return out.toByteArray()
    }
}
