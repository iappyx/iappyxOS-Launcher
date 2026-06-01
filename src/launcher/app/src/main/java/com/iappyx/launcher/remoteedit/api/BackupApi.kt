/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — backup tab. Streams the launcher backup zip
 * out on GET; accepts a zip back on POST and runs through
 * BackupImporter.apply().
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.backup.BackupExporter
import com.iappyx.launcher.backup.BackupImporter
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-process bus that broadcasts backup-import progress events
 *  to any state-stream SSE subscribers. Kept here (not in StateStreamApi)
 *  so the editor module owns its own lifecycle — only the BackupApi.import
 *  handler writes to it, and StateStreamApi attaches a transient listener
 *  per subscriber. Multiple concurrent imports aren't supported (the
 *  editor only has one upload at a time); concurrent listeners are. */
object BackupProgressBus {
    interface Listener { fun onProgress(phase: String, done: Long, total: Long) }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    fun emit(phase: String, done: Long, total: Long) {
        for (l in listeners) {
            try { l.onProgress(phase, done, total) } catch (_: Throwable) {}
        }
    }
}

class BackupApi(private val context: Context) {

    /** GET /api/backup/orphans — count + total bytes of widget folders
     *  that are neither referenced by any placement nor contain a
     *  widget.html. Mirrors the device "Cleanup" row's pre-scan. */
    fun orphans(ex: MicroHttpServer.Exchange) {
        val list = OrphanScanner.scan(context)
        val bytes = list.sumOf { OrphanScanner.folderSize(it) }
        JsonResponse.ok(ex, JSONObject().apply {
            put("count", list.size)
            put("bytes", bytes)
        })
    }

    /** POST /api/backup/cleanup — deletes whatever orphans the scan
     *  finds. Two-step (this endpoint does NOT confirm) — the web side
     *  shows the count from /orphans and asks the user, then calls
     *  this. Returns count actually deleted (failures are silent in
     *  deleteRecursively, so a partial delete is possible but rare). */
    fun cleanup(ex: MicroHttpServer.Exchange) {
        val list = OrphanScanner.scan(context)
        val deleted = list.count { it.deleteRecursively() }
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("deleted", deleted)
            put("scanned", list.size)
        })
    }

    /** Stream the backup as `application/zip`. The exporter writes
     *  directly to a ByteArrayOutputStream — backups are small (mostly
     *  text + tiny widget HTMLs). For very large backups (>50MB) we'd
     *  want to chunk to disk first; not the common case. */
    fun export(ex: MicroHttpServer.Exchange) {
        val q = ex.request.query
        val includeApiKey = q.contains("includeApiKey=true")
        val includeRuntime = !q.contains("includeRuntimeData=false")
        val opts = BackupExporter.Options(
            includeApiKey = includeApiKey,
            includeRuntimeData = includeRuntime,
        )
        val baos = ByteArrayOutputStream()
        try {
            BackupExporter.export(context, baos, opts)
        } catch (e: Throwable) {
            JsonResponse.error(ex, 500, "export failed: ${e.message}")
            return
        }
        // Mark the launcher's "last backup" wall-clock so the on-device
        // Settings row stays consistent with editor-initiated exports.
        LauncherPrefs(context).lastBackupAt = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        ex.status = 200
        ex.setHeader("Content-Type", "application/zip")
        ex.setHeader(
            "Content-Disposition",
            "attachment; filename=\"iappyx-launcher-$stamp.iappyxbackup\"",
        )
        ex.setBody(baos.toByteArray())
    }

    /** Apply an uploaded backup. Body is the raw zip bytes; query string
     *  carries `?mode=replace|merge`. Returns the validate-stage summary
     *  on success so the UI can show "imported 12 widgets, 3 wallpapers". */
    fun import(ex: MicroHttpServer.Exchange) {
        val zipBytes = ex.request.body
        if (zipBytes.isEmpty()) return JsonResponse.error(ex, 400, "empty body")
        val mode = if (ex.request.query.contains("mode=replace")) {
            BackupImporter.Mode.REPLACE
        } else {
            BackupImporter.Mode.MERGE
        }
        val summary = try {
            BackupImporter.validate(context, ByteArrayInputStream(zipBytes))
        } catch (e: BackupImporter.ImportException) {
            return JsonResponse.error(ex, 400, "invalid backup: ${e.message}")
        } catch (e: Throwable) {
            return JsonResponse.error(ex, 500, "validate failed: ${e.message}")
        }
        try {
            BackupProgressBus.emit("starting", 0L, zipBytes.size.toLong())
            BackupImporter.apply(
                context = context,
                input = ByteArrayInputStream(zipBytes),
                mode = mode,
                totalBytes = zipBytes.size.toLong(),
            ) { phase, done, total -> BackupProgressBus.emit(phase, done, total) }
        } catch (e: Throwable) {
            BackupProgressBus.emit("failed", 0L, 0L)
            return JsonResponse.error(ex, 500, "import failed: ${e.message}")
        }
        BackupProgressBus.emit("done", 1L, 1L)
        // Broadcast so the on-device launcher refreshes its pager.
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("widgetCount", summary.widgetCount)
            put("wallpaperCount", summary.wallpaperCount)
            put("transitionCount", summary.transitionCount)
            put("iconFilterCount", summary.iconFilterCount)
            put("profileCount", summary.profileCount)
            put("homePageCount", summary.homePageCount)
        })
    }
}
