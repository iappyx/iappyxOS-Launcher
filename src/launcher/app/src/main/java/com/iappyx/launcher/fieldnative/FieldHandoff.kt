/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * One-shot handoff from LauncherActivity to the native Field: the on-screen
 * positions of the icons on the home page being left (apps directly placed,
 * and apps inside folders mapped to the folder's position). The Field seeds
 * those apps' bubbles at these rects so they appear to fly out of the home
 * screen into the organism. Consumed (cleared) on read.
 */
package com.iappyx.launcher.fieldnative

object FieldHandoff {
    /** pkg -> [centerX, centerY, sizePx] in screen coordinates. */
    @Volatile
    var startRects: Map<String, FloatArray>? = null

    fun consume(): Map<String, FloatArray>? {
        val r = startRects; startRects = null; return r
    }
}
