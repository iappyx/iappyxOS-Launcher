/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Flow 1: hand off an artefact bundle to the system share-sheet via
 * `Intent.ACTION_SEND`. The OS handles the rest (Save to Files, AirDrop-
 * style nearby apps, Drive, Gmail, …) — we just produce a content URI for
 * the temp file [ArtefactBundle] wrote and let the user pick a destination.
 *
 * Files land in `cacheDir/shared/` so Android's cache eviction reaps them
 * eventually. The same dir gets reused across calls; a stale share file
 * sticks around until the OS cleans cache, which is fine — the URI grant
 * only stays valid for the life of the receiving app's task.
 */
object ShareHelper {

    private const val FP_AUTHORITY = "com.iappyx.launcher.provider"

    /** Build the bundle for a widget and offer it to the system share sheet. */
    fun shareWidget(context: Context, widgetId: String) {
        val out = ArtefactBundle.buildWidget(context, widgetId, sharedDir(context))
        sendFile(context, out, ArtefactBundle.Kind.WIDGET)
    }

    fun shareWallpaper(context: Context, wallpaperId: String) {
        val out = ArtefactBundle.buildWallpaper(context, wallpaperId, sharedDir(context))
        sendFile(context, out, ArtefactBundle.Kind.WALLPAPER)
    }

    fun shareTransition(context: Context, transitionId: String) {
        val out = ArtefactBundle.buildTransition(context, transitionId, sharedDir(context))
        sendFile(context, out, ArtefactBundle.Kind.TRANSITION)
    }

    fun shareIconFilter(context: Context, slug: String) {
        val out = ArtefactBundle.buildIconFilter(context, slug, sharedDir(context))
        sendFile(context, out, ArtefactBundle.Kind.ICON_FILTER)
    }

    private fun sendFile(context: Context, file: File, kind: ArtefactBundle.Kind) {
        val uri = FileProvider.getUriForFile(context, FP_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = kind.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            // Some receivers (Save to Files) consult the title.
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share ${kind.label}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").also { it.mkdirs() }
}
