/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.Context
import android.util.Log
import com.iappyx.launcher.model.HomeLayout
import org.json.JSONObject
import java.io.File

/**
 * Reads/writes the home screen layout to a JSON file in app storage.
 * JSON for now to avoid schema-migration overhead of Room; swap in later if needed.
 */
class PlacementStore(private val context: Context) {
    private val file = File(context.filesDir, "home_layout.json")

    /** Load the persisted layout in *current-orientation* coordinates. The
     *  on-disk representation is always in the user's dominant orientation
     *  (set in Settings); when the device is currently in the non-dominant
     *  orientation, [OrientationTransform.rotateLayoutCW] rotates the loaded
     *  layout 90° so the activity sees `cols`/`rows` matching the screen. */
    fun load(): HomeLayout {
        val raw = if (!file.exists()) {
            val d = HomeLayout.defaultLayout(context)
            saveRaw(d)
            d
        } else {
            try {
                HomeLayout.fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                Log.e("iappyxLauncher", "layout load failed: ${e.message}")
                try {
                    val backup = File(context.filesDir,
                        "home_layout.corrupt.${System.currentTimeMillis()}.json")
                    file.copyTo(backup, overwrite = true)
                    Log.w("iappyxLauncher", "corrupted layout backed up to ${backup.name}")
                } catch (_: Exception) { /* best-effort */ }
                HomeLayout.defaultLayout(context)
            }
        }
        return if (com.iappyx.launcher.widget.OrientationTransform.currentMatchesDominant(context)) {
            raw
        } else {
            com.iappyx.launcher.widget.OrientationTransform.rotateLayoutCW(raw)
        }
    }

    /** Persist the in-memory layout. If the device isn't currently in the
     *  dominant orientation, the layout is rotated back via
     *  [OrientationTransform.rotateLayoutCCW] before serialisation so the
     *  on-disk file always stays in dominant coordinates. */
    fun save(layout: HomeLayout) {
        val toStore = if (
            com.iappyx.launcher.widget.OrientationTransform.currentMatchesDominant(context)
        ) {
            layout
        } else {
            com.iappyx.launcher.widget.OrientationTransform.rotateLayoutCCW(layout)
        }
        saveRaw(toStore)
        // Sync edits into the currently-active profile so a layout
        // change made while a profile is auto-active stays attached to
        // that profile (per the user's chosen edits-write-to-active
        // policy). No-op when no profile is active. The user can branch
        // off at any time via Settings → Profiles → Save current as.
        try { com.iappyx.launcher.profile.ProfileApplier.captureIntoActive(context) }
        catch (_: Throwable) { /* best-effort — must never break a save */ }
    }

    private fun saveRaw(layout: HomeLayout) {
        try {
            // Write to a sibling tmp file then atomically swap it in. If the
            // process dies mid-write the tmp file is dropped on next launch
            // and the previous good `home_layout.json` is intact — without
            // this, an interrupted writeText leaves a half-written JSON that
            // load() can't parse, wiping the user's placements.
            val tmp = File(context.filesDir, "home_layout.json.tmp")
            // Compact JSON, not pretty-printed. The file isn't user-facing
            // (we write it on every drag-drop / edit-mode exit / etc.),
            // and toString() is meaningfully faster than toString(2) on
            // larger layouts — relevant because save() is on the main
            // thread for now.
            tmp.writeText(layout.toJson().toString())
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: Exception) {
            Log.e("iappyxLauncher", "layout save failed: ${e.message}")
        }
    }
}
