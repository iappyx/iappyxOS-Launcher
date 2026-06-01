/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.webkit.JavascriptInterface

/**
 * Wallpaper-only bridge that exposes the launcher's home-screen layout as
 * bounding boxes only. Registered as `iappyxLayout` on the wallpaper WebView.
 *
 * The single method [get] returns the cached JSON string (see [LayoutSerializer]
 * for the shape). The cache is updated by [IappyxWallpaperService] when:
 *
 *  - the engine starts (seeds from `wallpaper_layout_snapshot.json`), and
 *  - the launcher broadcasts `LAYOUT_CHANGED_ACTION` (with the JSON in extras).
 *
 * After every cache update, the service also pushes `iappyx.onLayoutChanged`
 * so JS gets a notification in addition to the pull-style getter.
 */
class LayoutBridge {

    @Volatile
    private var cached: String = EMPTY_LAYOUT_JSON

    @JavascriptInterface
    fun get(): String = cached

    /** Called from the wallpaper service. Thread-safe (volatile field).
     *  Trim/null-guard happens here so JS always sees a parseable JSON
     *  string — never an empty payload that would crash a `JSON.parse()`. */
    fun update(json: String?) {
        cached = if (json.isNullOrBlank()) EMPTY_LAYOUT_JSON else json
    }

    companion object {
        /** Shape-compatible empty layout — `JSON.parse(get())` always works,
         *  even before the first broadcast. `cells` and `dock` are arrays so
         *  user code can `.forEach()` without a null check. */
        private const val EMPTY_LAYOUT_JSON =
            """{"screen":{"width":0,"height":0,"density":1},""" +
            """"pageCount":0,"pageWidth":0,"currentPage":0,""" +
            """"systemBars":{"top":0,"bottom":0},"cells":[],"dock":[]}"""
    }
}
