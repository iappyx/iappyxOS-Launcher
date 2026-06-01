/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.R
import java.io.File

/**
 * "Bundled files" management UI for a single widget. Mirrors the iappyxOS
 * create_screen.dart UX:
 *  - List the files currently in `<sandbox>/resources/` with sizes
 *  - "Add file" → SAF picker → 100 MB cap, sanitized name, overwrite prompt
 *  - Per-file ✕ removes with confirmation
 *  - Total size shown at the top
 *
 * Files dropped here are visible to the widget HTML via the existing
 * `iappyx.storage.readAsset(name)` / `extractAsset(name, dest)` /
 * `listAssets()` bridge methods (see [com.iappyx.launcher.WidgetHost]
 * StorageBridge → resources directory).
 */
object BundleFilesSheet {

    private const val MAX_BYTES_PER_FILE = 100L * 1024 * 1024  // 100 MB, matches iappyxOS

    fun show(activity: LauncherActivity, widgetId: String, widgetTitle: String) {
        val dp = activity.resources.displayMetrics.density
        val resourcesDir = File(activity.filesDir, "widgets/$widgetId/resources").also {
            it.mkdirs()
        }

        // ── Build the dialog content view ─────────────────────────
        val pad = (20 * dp).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }

        val totalSizeText = TextView(activity).apply {
            setTextColor(Palette.textSecondary(activity))
            textSize = 12f
            setPadding(0, 0, 0, (10 * dp).toInt())
        }
        content.addView(totalSizeText)

        val listScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (220 * dp).toInt(),  // bounded so very-long lists don't crowd out the buttons
            )
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        listScroll.addView(listContainer)
        content.addView(listScroll)

        val emptyHint = TextView(activity).apply {
            setText(R.string.bundle_files_empty)
            setTextColor(Palette.textSecondary(activity))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
        }

        val addBtn = TextView(activity).apply {
            setText(R.string.bundle_files_add_file)
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Palette.accent(activity))
            }
            val ph = (16 * dp).toInt(); val pv = (10 * dp).toInt()
            setPadding(ph, pv, ph, pv)
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
        content.addView(addBtn)

        // ── Refresh closure ──────────────────────────────────────
        // Re-reads the resources dir and rebuilds the list rows. Called
        // after add / remove so the user sees state updates inline.
        var refreshList: () -> Unit = {}
        refreshList = {
            listContainer.removeAllViews()
            val files = resourcesDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                .orEmpty()
            if (files.isEmpty()) {
                listContainer.addView(emptyHint.also { (it.parent as? ViewGroup)?.removeView(it) })
                totalSizeText.setText(R.string.bundle_files_empty_total)
            } else {
                var total = 0L
                for (f in files) {
                    total += f.length()
                    listContainer.addView(makeFileRow(activity, dp, f) {
                        confirmRemove(activity, f) { refreshList() }
                    })
                }
                totalSizeText.text = activity.getString(
                    R.string.bundle_files_total_format,
                    files.size, formatBytes(total),
                )
            }
        }
        refreshList()

        // ── Wrap content in a bordered card matching the launcher's
        // dialog look (matches editMetaDialog's container style). ──
        val outer = ScrollView(activity).apply { addView(content) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.bundle_files_dialog_title_format, widgetTitle))
            .setView(outer)
            .setPositiveButton(R.string.action_done, null)
            .create()

        // ── Wire Add file → SAF picker → copy into resources/ ────
        addBtn.setOnClickListener {
            activity.launchImport("*/*") { uri ->
                handlePickedUri(activity, resourcesDir, uri) { refreshList() }
            }
        }

        dialog.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(dialog) }
        dialog.show()
    }

    // ── File row UI ──────────────────────────────────────────────

    private fun makeFileRow(
        activity: LauncherActivity, dp: Float, file: File, onRemove: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        col.addView(TextView(activity).apply {
            text = file.name
            setTextColor(Palette.textPrimary(activity))
            textSize = 14f
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(activity).apply {
            text = formatBytes(file.length())
            setTextColor(Palette.textSecondary(activity))
            textSize = 11f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(col)

        val removeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Palette.textSecondary(activity))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = 999f
            }
            setOnClickListener { onRemove() }
        }
        row.addView(removeBtn)
        return row
    }

    // ── Add-file flow: read URI, sanitize, cap, write ───────────

    private fun handlePickedUri(
        activity: LauncherActivity, resourcesDir: File, uri: Uri, onDone: () -> Unit,
    ) {
        // Resolve display name from the URI. Falls back to the URI's last
        // path segment, then a generated name, so we never end up with an
        // empty filename.
        val rawName = queryDisplayName(activity, uri)
            ?: uri.lastPathSegment
            ?: "file_${System.currentTimeMillis()}"
        val safeName = sanitizeFilename(rawName)
        val target = File(resourcesDir, safeName)

        val proceed = {
            // Copy the URI's bytes into the target file on a background
            // thread so we don't block the UI on a 60 MB read. Stream-copy
            // 8 KB at a time to keep memory bounded; abort if we exceed
            // the per-file cap.
            Thread {
                var copied = 0L
                var truncated = false
                try {
                    activity.contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw java.io.IOException("could not open file")
                        java.io.FileOutputStream(target).use { fos ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                copied += n
                                if (copied > MAX_BYTES_PER_FILE) {
                                    truncated = true
                                    break
                                }
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    activity.runOnUiThread {
                        if (truncated) {
                            try { target.delete() } catch (_: Throwable) {}
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.bundle_files_too_large_format,
                                    formatBytes(MAX_BYTES_PER_FILE),
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(
                                    R.string.bundle_files_added_format,
                                    safeName, formatBytes(copied),
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        onDone()
                    }
                } catch (e: Throwable) {
                    try { target.delete() } catch (_: Throwable) {}
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.bundle_files_add_failed_format, e.message ?: ""),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }.start()
        }

        if (target.exists()) {
            // Overwrite confirmation — silent overwrite is hostile to a
            // user who picked the wrong file.
            AlertDialog.Builder(activity)
                .setTitle(R.string.bundle_files_overwrite_title)
                .setMessage(activity.getString(R.string.bundle_files_overwrite_message_format, safeName))
                .setPositiveButton(R.string.action_overwrite) { _, _ -> proceed() }
                .setNegativeButton(R.string.action_cancel, null)
                .showThemed()
        } else {
            proceed()
        }
    }

    private fun confirmRemove(
        activity: LauncherActivity, file: File, onDone: () -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.bundle_files_remove_title)
            .setMessage(activity.getString(R.string.bundle_files_remove_message_format, file.name))
            .setPositiveButton(R.string.action_remove) { _, _ ->
                try { file.delete() } catch (_: Throwable) {}
                onDone()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showThemed()
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Resolve the human-readable display name from a content:// or file://
     *  URI via OpenableColumns. Returns null if the column isn't available. */
    private fun queryDisplayName(activity: LauncherActivity, uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) { null }
    }

    /** Whitelist filenames to safe characters; cap at 100 chars. Matches
     *  iappyxOS create_screen.dart's `_addBundleFile` regex. */
    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\w.\\-]"), "_")
        return if (cleaned.length > 100) cleaned.substring(0, 100) else cleaned
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
