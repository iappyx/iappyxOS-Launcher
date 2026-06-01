package com.iappyx.launcher

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.backup.BackupExporter
import com.iappyx.launcher.backup.BackupImporter
import com.iappyx.launcher.widget.showThemed

/**
 * Settings → Backup & Restore. Owns export, import, cleanup of orphaned
 * widget directories, and the chat-history clear. Migrated out of the
 * monolithic SettingsActivity in Plan A Phase 2 so the top-level Settings
 * stops being a single-scroll wall of cards.
 *
 * Lifecycle:
 *   - registerForActivityResult launchers must be initialized at field
 *     time (before STARTED) — same constraint as before.
 *   - destroyed flag guards async writer/reader threads from posting
 *     dialogs onto a torn-down activity.
 */
class BackupSettingsActivity : AppCompatActivity() {

    @Volatile private var destroyed = false

    private var pendingExportOptions: BackupExporter.Options? = null
    private var pendingExportRefresh: (() -> Unit)? = null

    private val exportLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data ?: return@registerForActivityResult
            val options = pendingExportOptions ?: return@registerForActivityResult
            pendingExportOptions = null
            runExport(uri, options)
        }

    private val importLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uri = res.data?.data ?: return@registerForActivityResult
            runImportPreview(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_settings)
        SettingsScaffold.attach(this, getString(R.string.settings_backup_section))
        setupBackupRestore()
        setupClearChatHistoryRow()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun setupBackupRestore() {
        val prefs = LauncherPrefs(this)
        val exportRow = findViewById<android.view.View>(R.id.backup_export_row)
        val exportSubtitle = findViewById<TextView>(R.id.backup_export_subtitle)
        val importRow = findViewById<android.view.View>(R.id.backup_import_row)
        val cleanupRow = findViewById<android.view.View>(R.id.backup_cleanup_row)
        val cleanupSubtitle = findViewById<TextView>(R.id.backup_cleanup_subtitle)

        fun refreshExportSubtitle() {
            val ts = prefs.lastBackupAt
            exportSubtitle.text = if (ts > 0) {
                val rel = android.text.format.DateUtils.getRelativeTimeSpanString(
                    ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                )
                getString(R.string.settings_backup_last_format, rel)
            } else getString(R.string.settings_backup_export_default_subtitle)
        }
        refreshExportSubtitle()

        fun refreshCleanupSubtitle() {
            val orphans = scanOrphanWidgetDirs()
            cleanupSubtitle.text = if (orphans.isEmpty()) {
                getString(R.string.settings_cleanup_none_subtitle)
            } else {
                val bytes = orphans.sumOf { folderSize(it) }
                getString(R.string.settings_cleanup_some_format, orphans.size, formatBytes(bytes))
            }
        }
        refreshCleanupSubtitle()

        exportRow.setOnClickListener { promptExportOptions(::refreshExportSubtitle) }
        importRow.setOnClickListener { launchImportPicker() }
        cleanupRow.setOnClickListener { promptCleanup(::refreshCleanupSubtitle) }
    }

    private fun setupClearChatHistoryRow() {
        val row = findViewById<android.view.View>(R.id.clear_chat_history_row)
        val subtitle = findViewById<TextView>(R.id.clear_chat_history_subtitle)
        fun refresh() {
            val db = com.iappyx.launcher.command.ChatDatabase(this)
            val n = try { db.count() } finally { db.close() }
            val sizeBytes = try { db.sizeBytes() } catch (_: Throwable) { 0L }
            subtitle.text = if (n == 0) {
                getString(R.string.cmd_history_subtitle_empty)
            } else {
                getString(R.string.cmd_history_subtitle_count, n, formatBytes(sizeBytes))
            }
        }
        refresh()
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.cmd_clear_history)
                .setMessage(R.string.cmd_clear_history_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cmd_clear_history) { _, _ ->
                    val db = com.iappyx.launcher.command.ChatDatabase(this)
                    try { db.clearAll() } finally { db.close() }
                    sendBroadcast(android.content.Intent(
                        LauncherActivity.ACTION_CHAT_HISTORY_CLEARED,
                    ).setPackage(packageName))
                    refresh()
                    Toast.makeText(this, R.string.cmd_history_cleared, Toast.LENGTH_SHORT).show()
                }
                .showThemed()
        }
    }

    // ── Orphan widget scan helpers ─────────────────────────────────

    private fun scanOrphanWidgetDirs(): List<java.io.File> {
        val widgetsRoot = java.io.File(filesDir, "widgets")
        if (!widgetsRoot.isDirectory) return emptyList()
        val inUse = collectInUseWidgetIds()
        val out = mutableListOf<java.io.File>()
        for (sub in widgetsRoot.listFiles().orEmpty()) {
            if (!sub.isDirectory) continue
            val hasHtml = java.io.File(sub, "widget.html").isFile
            val referenced = sub.name in inUse
            if (!hasHtml && !referenced) out.add(sub)
        }
        return out
    }

    private fun collectInUseWidgetIds(): Set<String> {
        val set = mutableSetOf<String>()
        val layout = try { PlacementStore(this).load() } catch (_: Throwable) { return set }
        for (page in layout.pages) for (p in page.placements) {
            p.generatedWidgetId?.let { set.add(it) }
        }
        for (dockPage in layout.dockPages) for (p in dockPage) {
            p.generatedWidgetId?.let { set.add(it) }
        }
        return set
    }

    private fun folderSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return getString(R.string.bytes_format, bytes.toInt())
        val kb = bytes / 1024.0
        if (kb < 1024) return getString(R.string.kilobytes_format, kb)
        val mb = kb / 1024.0
        return getString(R.string.megabytes_format, mb)
    }

    // ── Dialog flows ───────────────────────────────────────────────

    private fun promptCleanup(onDone: () -> Unit) {
        val orphans = scanOrphanWidgetDirs()
        if (orphans.isEmpty()) {
            Toast.makeText(this, R.string.settings_cleanup_nothing_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val totalBytes = orphans.sumOf { folderSize(it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_cleanup_dialog_title)
            .setMessage(getString(R.string.settings_cleanup_dialog_message,
                orphans.size, formatBytes(totalBytes)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val deleted = orphans.count { it.deleteRecursively() }
                Toast.makeText(this,
                    getString(R.string.settings_cleanup_done_toast_format,
                        deleted, formatBytes(totalBytes)),
                    Toast.LENGTH_SHORT).show()
                onDone()
            }
            .showThemed()
    }

    private fun promptExportOptions(onDone: () -> Unit) {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val keyCheck = CheckBox(this).apply {
            setText(R.string.settings_backup_export_include_key)
            isChecked = false
        }
        val runtimeCheck = CheckBox(this).apply {
            setText(R.string.settings_backup_export_include_runtime)
            isChecked = true
        }
        container.addView(keyCheck)
        container.addView(runtimeCheck)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_export_dialog_title)
            .setMessage(R.string.settings_backup_export_dialog_message)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_backup_choose_location) { _, _ ->
                pendingExportOptions = BackupExporter.Options(
                    includeApiKey = keyCheck.isChecked,
                    includeRuntimeData = runtimeCheck.isChecked,
                )
                val suggested = "iappyx-launcher-" +
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(java.util.Date()) + ".iappyxbackup"
                exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, suggested))
                pendingExportRefresh = onDone
            }
            .showThemed()
    }

    private fun runExport(uri: Uri, options: BackupExporter.Options) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_exporting_title)
            .setMessage(getString(R.string.settings_backup_writing_message))
            .setCancelable(false)
            .create()
        progress.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(progress) }
        progress.show()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                val result = contentResolver.openOutputStream(uri)?.use { out ->
                    BackupExporter.export(this, out, options)
                } ?: throw java.io.IOException(getString(R.string.settings_backup_cant_open_write))
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    Toast.makeText(this,
                        getString(R.string.settings_backup_export_done_toast_format,
                            result.widgetCount, result.wallpaperCount),
                        Toast.LENGTH_SHORT).show()
                    pendingExportRefresh?.invoke()
                    pendingExportRefresh = null
                }
            } catch (e: Throwable) {
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_export_failed_title)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            }
        }.start()
    }

    private fun launchImportPicker() {
        importLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"))
    }

    private fun runImportPreview(uri: Uri) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_reading_title)
            .setCancelable(false)
            .create()
        progress.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(progress) }
        progress.show()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                val summary = contentResolver.openInputStream(uri)?.use { input ->
                    BackupImporter.validate(this, input)
                } ?: throw java.io.IOException(getString(R.string.settings_backup_cant_open))
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    showImportConfirmDialog(uri, summary)
                }
            } catch (e: BackupImporter.ImportException) {
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_cant_import_title)
                        .setMessage(e.message ?: getString(R.string.settings_backup_unknown_error))
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            } catch (e: Throwable) {
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_cant_read_title)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            }
        }.start()
    }

    private fun showImportConfirmDialog(uri: Uri, summary: BackupImporter.Summary) {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val summaryText = buildString {
            val whenStr = if (summary.createdAt > 0) {
                java.text.SimpleDateFormat("MMM d, yyyy 'at' HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(summary.createdAt))
            } else getString(R.string.settings_backup_unknown_date)
            val deviceStr = summary.deviceModel.ifBlank {
                getString(R.string.settings_backup_unknown_device)
            }
            append(getString(R.string.settings_backup_summary_header_format, deviceStr, whenStr))
            append(getString(R.string.settings_backup_summary_widgets_format, summary.widgetCount))
            append(getString(R.string.settings_backup_summary_wallpapers_format, summary.wallpaperCount))
            append(getString(R.string.settings_backup_summary_transitions_format, summary.transitionCount))
            if (summary.iconFilterCount > 0) {
                append(getString(R.string.settings_backup_summary_icon_filters_format, summary.iconFilterCount))
            }
            if (summary.profileCount > 0) {
                append(getString(R.string.settings_backup_summary_profiles_format, summary.profileCount))
            }
            append(getString(R.string.settings_backup_summary_home_format, summary.homePageCount))
            if (summary.includesApiKey) append(getString(R.string.settings_backup_summary_includes_key))
            if (summary.includesRuntimeData) append(getString(R.string.settings_backup_summary_includes_runtime))
            if (summary.missingPackages.isNotEmpty()) {
                append(getString(R.string.settings_backup_summary_missing_header_format,
                    summary.missingPackages.size))
                summary.missingPackages.take(5).forEach {
                    append(getString(R.string.settings_backup_summary_missing_item_format, it))
                }
                if (summary.missingPackages.size > 5) {
                    append(getString(R.string.settings_backup_summary_missing_more_format,
                        summary.missingPackages.size - 5))
                }
                append(getString(R.string.settings_backup_summary_missing_footer))
            }
        }
        container.addView(TextView(this).apply { text = summaryText; textSize = 13f })

        val modeRadioGroup = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            val mergeBtn = android.widget.RadioButton(context).apply {
                id = 1; setText(R.string.settings_backup_import_mode_merge)
                isChecked = true
            }
            val replaceBtn = android.widget.RadioButton(context).apply {
                id = 2; setText(R.string.settings_backup_import_mode_replace)
            }
            addView(mergeBtn); addView(replaceBtn)
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        container.addView(modeRadioGroup)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_import_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_import) { _, _ ->
                val mode = if (modeRadioGroup.checkedRadioButtonId == 2) {
                    BackupImporter.Mode.REPLACE
                } else BackupImporter.Mode.MERGE
                runImportApply(uri, mode)
            }
            .showThemed()
    }

    private fun runImportApply(uri: Uri, mode: BackupImporter.Mode) {
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.settings_backup_restoring_title)
            .setMessage(getString(R.string.settings_backup_applying_message))
            .setCancelable(false)
            .create()
        progress.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(progress) }
        progress.show()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    BackupImporter.apply(this, input, mode)
                } ?: throw java.io.IOException(getString(R.string.settings_backup_cant_open))
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_restored_title)
                        .setMessage(R.string.settings_backup_restored_message)
                        .setPositiveButton(R.string.action_ok) { _, _ -> finish() }
                        .showThemed()
                }
            } catch (e: BackupImporter.ImportException) {
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_restore_failed_title)
                        .setMessage(e.message ?: getString(R.string.settings_backup_unknown_error))
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            } catch (e: Throwable) {
                main.post {
                    progress.dismiss()
                    if (destroyed) return@post
                    AlertDialog.Builder(this)
                        .setTitle(R.string.settings_backup_restore_failed_title)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(R.string.action_ok, null)
                        .showThemed()
                }
            }
        }.start()
    }
}
