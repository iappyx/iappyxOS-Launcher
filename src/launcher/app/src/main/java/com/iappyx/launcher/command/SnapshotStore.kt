/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.command

import android.app.Activity
import android.util.Log
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.model.HomeLayout
import org.json.JSONObject
import java.io.File

/**
 * Ring-buffer snapshot stack for the AI Command Bar's undo flow. One
 * snapshot per user-action (one [CommandSession] runLoop = one entry).
 *
 * Captures:
 *  - The full home / dock layout as JSON.
 *  - The active wallpaper / transition / icon-filter ids.
 *  - File-content backups for tools that mutate existing files in place
 *    (edit_generated_widget, iterate_wallpaper, iterate_transition). The
 *    individual tool handlers call [addFileBackup] before mutating; on
 *    undo, the captured content is written back over the current file.
 *
 * What it does NOT capture:
 *  - New files created by `generate_*` tools (the file lingers on undo,
 *    becomes orphan; future cleanup pass can collect it).
 *  - Any non-launcher state (system wallpaper, foreground app, etc.).
 *
 * Thread model: all methods called on [CommandSession]'s single executor
 * thread. No external synchronisation needed.
 */
class SnapshotStore(private val activity: Activity) {

    /** Captured launcher state. Restored verbatim by [restore]. */
    data class Snapshot(
        val timestamp: Long,
        val description: String,
        val layoutJson: String,
        val activeWallpaperId: String,
        val activeTransitionId: String,
        val activeIconFilter: String,
        /** Map of relative path under filesDir → file content. Null content
         *  means "the file did not exist before this action" → restore by
         *  deletion. */
        val fileBackups: Map<String, String?>,
    )

    private val ring = ArrayDeque<Snapshot>()
    private var pending: PendingSnapshot? = null

    /** Start a new pending snapshot. Call at the start of a user-action
     *  (runLoop entry). Captures the current layout and prefs immediately;
     *  file backups are added by tool handlers as they fire. */
    fun begin(description: String) {
        pending = PendingSnapshot(
            timestamp = System.currentTimeMillis(),
            description = description,
            layoutJson = PlacementStore(activity).load().toJson().toString(),
            activeWallpaperId = LauncherPrefs(activity).activeWallpaperId,
            activeTransitionId = LauncherPrefs(activity).pageTransitionStyle,
            activeIconFilter = LauncherPrefs(activity).iconFilter,
            fileBackups = mutableMapOf(),
        )
    }

    /** Augment the pending snapshot with the current content of [file]
     *  (relative to filesDir). Subsequent edits to that file can be
     *  reverted on undo. Idempotent — if the same path is backed up twice
     *  in one user-action, the FIRST capture is kept (preserves the true
     *  pre-action state). */
    fun addFileBackup(relativePath: String) {
        val p = pending ?: return
        if (p.fileBackups.containsKey(relativePath)) return
        val f = File(activity.filesDir, relativePath)
        p.fileBackups[relativePath] = if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    /** Commit the pending snapshot to the ring. Call at the end of a
     *  user-action (runLoop exit). No-op if no pending snapshot. */
    fun commit() {
        val p = pending ?: return
        pending = null
        ring.addLast(Snapshot(
            timestamp = p.timestamp,
            description = p.description,
            layoutJson = p.layoutJson,
            activeWallpaperId = p.activeWallpaperId,
            activeTransitionId = p.activeTransitionId,
            activeIconFilter = p.activeIconFilter,
            fileBackups = p.fileBackups.toMap(),
        ))
        while (ring.size > MAX_RING) ring.removeFirst()
    }

    /** Discard the pending snapshot without committing. Used when a
     *  runLoop ends without making any user-visible changes (e.g. AI
     *  declined or returned only text). */
    fun discardPending() { pending = null }

    /** Pop the most recent snapshot from the ring and return it. The
     *  caller is responsible for actually applying it via [restore]. */
    fun popLatest(): Snapshot? {
        if (ring.isEmpty()) return null
        return ring.removeLast()
    }

    /** True iff there's at least one snapshot in the ring AVAILABLE
     *  to undo. The undo handler uses this to surface a friendly
     *  "nothing to undo" error. */
    fun canUndo(): Boolean = ring.isNotEmpty()

    /** Number of committed snapshots. For display / debug. */
    val size: Int get() = ring.size

    /** Apply [snap] to the launcher's live state. Restores layout, prefs,
     *  and any backed-up file contents. The caller (typically the undo
     *  tool handler) is responsible for triggering UI refreshes:
     *  `Listener.applyLayout(...)` after this returns, and broadcasting
     *  the wallpaper / transition change events. */
    fun restore(snap: Snapshot): RestoreResult {
        // 1) Files first — so when we apply the new layout, any references
        //    point at the right content.
        var filesRestored = 0
        for ((relativePath, content) in snap.fileBackups) {
            val f = File(activity.filesDir, relativePath)
            try {
                if (content == null) {
                    if (f.exists()) f.delete()
                } else {
                    f.parentFile?.mkdirs()
                    f.writeText(content, Charsets.UTF_8)
                    filesRestored++
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to restore $relativePath: ${e.message}")
            }
        }
        // 2) Prefs — active wallpaper / transition / icon filter ids.
        val prefs = LauncherPrefs(activity)
        val wpChanged = prefs.activeWallpaperId != snap.activeWallpaperId
        prefs.activeWallpaperId = snap.activeWallpaperId
        prefs.pageTransitionStyle = snap.activeTransitionId
        prefs.iconFilter = snap.activeIconFilter
        // 3) Layout — reconstruct from JSON, hand to the caller to apply.
        val layout = HomeLayout.fromJson(JSONObject(snap.layoutJson))
        return RestoreResult(layout = layout, wallpaperChanged = wpChanged, filesRestored = filesRestored)
    }

    data class RestoreResult(
        val layout: HomeLayout,
        val wallpaperChanged: Boolean,
        val filesRestored: Int,
    )

    private data class PendingSnapshot(
        val timestamp: Long,
        val description: String,
        val layoutJson: String,
        val activeWallpaperId: String,
        val activeTransitionId: String,
        val activeIconFilter: String,
        val fileBackups: MutableMap<String, String?>,
    )

    companion object {
        /** Keep the last 8 actions undoable. Anything older falls off
         *  the front of the ring. Average snapshot is layout-JSON
         *  (~5 KB) plus zero or one file backup (~50 KB) → ~50–500 KB
         *  total at the cap, fine to hold in memory. */
        private const val MAX_RING = 8
        private const val TAG = "iappyxSnapshotStore"
    }
}
