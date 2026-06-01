/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — orphan widget scanner. Local copy of the logic
 * that lives in BackupSettingsActivity, kept here so deleting
 * remoteedit/ doesn't drag in shared-helper extractions elsewhere in
 * the launcher (see remoteedit/README.md independence rule).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.PlacementStore
import java.io.File

internal object OrphanScanner {

    /** Widget directories on disk that are neither referenced by any
     *  placement (home or dock) nor contain a `widget.html` — the latter
     *  guard keeps user-generated widgets that haven't been placed yet
     *  out of the cleanup list. */
    fun scan(context: Context): List<File> {
        val widgetsRoot = File(context.filesDir, "widgets")
        if (!widgetsRoot.isDirectory) return emptyList()
        val inUse = collectInUseWidgetIds(context)
        val out = mutableListOf<File>()
        for (sub in widgetsRoot.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val hasHtml = File(sub, "widget.html").isFile
            val referenced = sub.name in inUse
            if (!hasHtml && !referenced) out.add(sub)
        }
        return out
    }

    fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun collectInUseWidgetIds(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        val layout = try { PlacementStore(context).load() } catch (_: Throwable) { return set }
        for (page in layout.pages) for (p in page.placements) {
            p.generatedWidgetId?.let { set.add(it) }
        }
        for (dockPage in layout.dockPages) for (p in dockPage) {
            p.generatedWidgetId?.let { set.add(it) }
        }
        return set
    }
}
