/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.iappyx.launcher.model.HomeLayout
import com.iappyx.launcher.wallpaper.LayoutSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Publishes the launcher's home layout (cell rectangles + dock + bars,
 * RECTANGLES ONLY — no app/widget identity) to the live wallpaper
 * process via [LauncherPrefs.LAYOUT_CHANGED_ACTION] broadcast and a
 * persisted snapshot file in `filesDir`.
 *
 * Wallpapers can use the snapshot to render layout-aware effects (a
 * particle field that orbits icons, a ball that dodges cells, etc.).
 *
 * **Coalescing.** Calls to [schedule] within 100ms collapse into one
 * broadcast (a multi-cell paste / a burst of layout commits during
 * edit mode shouldn't trigger N broadcasts).
 *
 * **Idle gating.** While the pager is mid-scroll the broadcast is
 * deferred — serializing then would emit cell coordinates with the
 * in-flight scroll translation baked in, and the wallpaper would
 * render an "in-between" frame until settle. The activity calls
 * [setScrollIdle] from its pager's `onPageScrollStateChanged`.
 *
 * **Dedupe.** If the serialized JSON is identical to the last one we
 * sent (most navigation events emit unchanged JSON), no IPC + no file
 * write happens.
 *
 * **Off-main-thread persistence.** The snapshot file write is on a
 * worker thread — synchronous I/O on the main thread caused a
 * documented swipe-settle flicker (see memory entry).
 *
 * Lifecycle: stateless beyond a Handler + cached JSON. No start/stop
 * needed; callers schedule on demand. The Handler is bound to the
 * main thread Looper so all `removeCallbacks` / `postDelayed` happen
 * on the same thread.
 */
class WallpaperLayoutPublisher(
    private val activity: Activity,
    private val layoutProvider: () -> HomeLayout,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { doBroadcast() }

    /** True when the home pager isn't actively scrolling. Updated by
     *  the activity's `onPageScrollStateChanged`. Wallpaper-layout
     *  broadcasts are deferred while this is false. `@Volatile` is
     *  defense-in-depth — both reads and writes happen on the main
     *  thread in practice, but a future off-main caller (a
     *  Choreographer post that survives a config change, say) needs
     *  the guarantee that it sees the latest value. */
    @Volatile
    private var scrollIdle: Boolean = true

    /** Last layout JSON actually broadcast — kept for dedupe. Most
     *  navigation events (page-settle, onResume, theme-recomputed
     *  re-render) end up scheduling a broadcast even when the layout
     *  structure is identical; bailing here saves ~5KB JSON serialize
     *  + atomic-move + IPC per redundant event. */
    private var lastJson: String? = null

    /** Update the scroll-idle gate. Activity calls this from
     *  `onPageScrollStateChanged`. */
    fun setScrollIdle(idle: Boolean) {
        scrollIdle = idle
    }

    /** Schedule a wallpaper-layout broadcast on the next idle tick.
     *  Safe to call from anywhere — repeat calls within 100ms coalesce.
     *  The post-delay also gives the most-recent render pass time to
     *  finish before we read pixel positions from HomeGrid children. */
    fun schedule() {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, 100)
    }

    private fun doBroadcast() {
        if (!scrollIdle) {
            // Pager mid-scroll — try again. The activity's
            // onPageScrollStateChanged also fires schedule() on IDLE.
            schedule()
            return
        }
        val json = LayoutSerializer.serialize(activity, layoutProvider())
            ?: return  // pager not yet laid out; next commit will retry
        if (json == lastJson) return
        lastJson = json
        // Persist the snapshot off the main thread (see class-level
        // comment / memory entry on swipe-flicker). Latch values into
        // locals so a later mutation can't race the worker.
        val ctx = activity.applicationContext
        val payload = json
        Thread({
            try {
                val target = File(ctx.filesDir, LauncherPrefs.LAYOUT_SNAPSHOT_FILE)
                val tmp = File(ctx.filesDir, "${LauncherPrefs.LAYOUT_SNAPSHOT_FILE}.tmp")
                tmp.writeText(payload, Charsets.UTF_8)
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Throwable) { /* best-effort cache only */ }
        }, "iappyx-wp-snapshot-write").start()
        val intent = Intent(LauncherPrefs.LAYOUT_CHANGED_ACTION)
            .setPackage(activity.packageName)
            .putExtra("json", json)
        activity.sendBroadcast(intent)
    }
}
