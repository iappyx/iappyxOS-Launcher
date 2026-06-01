/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.SettingsActivity
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.transitions.TransitionLibrary
import com.iappyx.launcher.wallpaper.WallpaperLibrary
import com.iappyx.launcher.widget.WidgetLibrary
import com.iappyx.launcher.widget.showThemed
import org.json.JSONObject
import java.io.File

/**
 * "Submit to Showcase" form launched from the [ShareSheet]. Pre-fills
 * title + description from the artefact's existing meta, lets the user
 * tweak before opening the PR. The actual GitHub upload happens in a
 * background thread via [GithubClient].
 *
 * Pre-flights done before the form even opens:
 *   - GitHub token must be set in Settings (else show a "Set token" dialog)
 *   - Artefact must not be bundled (already in repo) — caller is expected
 *     to disable the menu item; we double-check defensively
 */
class ShowcaseSubmitDialog(
    private val activity: LauncherActivity,
    private val kind: ArtefactBundle.Kind,
    private val artefactId: String,
) {

    fun show() {
        // Bundled artefacts are already in the repo — refuse cleanly.
        if (isBundled()) {
            Toast.makeText(
                activity,
                "${kind.label.replaceFirstChar { it.uppercase() }} is already in the showcase.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val token = SecureStore(activity).githubToken
        if (token.isNullOrBlank()) {
            promptForToken(); return
        }
        showForm(token)
    }

    private fun isBundled(): Boolean = when (kind) {
        ArtefactBundle.Kind.WIDGET -> WidgetLibrary.isBundled(artefactId)
        ArtefactBundle.Kind.WALLPAPER -> {
            // Bundled wallpapers all live in the asset dir — id won't be a UUID.
            WallpaperLibrary.all(activity).firstOrNull { it.id == artefactId }
                ?.isUserGenerated == false
        }
        ArtefactBundle.Kind.TRANSITION -> !TransitionLibrary.isUserGenerated(artefactId)
        ArtefactBundle.Kind.ICON_FILTER ->
            !com.iappyx.launcher.cells.IconFilterRegistry.isUserGenerated(artefactId)
        // PLUGINS: BEGIN — bundled plugin ids match BUNDLED_PLUGINS.
        ArtefactBundle.Kind.PLUGIN ->
            artefactId in com.iappyx.launcher.sharing.ShowcaseInstalledIndex.BUNDLED_PLUGINS
        // PLUGINS: END
    }

    private fun promptForToken() {
        AlertDialog.Builder(activity)
            .setTitle(com.iappyx.launcher.R.string.submit_token_needed_title)
            .setMessage(com.iappyx.launcher.R.string.submit_token_needed_long_message)
            .setNegativeButton(com.iappyx.launcher.R.string.action_cancel, null)
            .setPositiveButton(com.iappyx.launcher.R.string.submit_open_settings) { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .showThemed()
    }

    private fun showForm(token: String) {
        // Read existing meta + content for pre-fill.
        val (existingTitle, existingDescription, contentText, contentFileName, attribution) =
            readSource() ?: run {
                Toast.makeText(activity, com.iappyx.launcher.R.string.submit_couldnt_read_sources,
                    Toast.LENGTH_LONG).show()
                return
            }
        val dp = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val titleField = makeField(activity,
            activity.getString(com.iappyx.launcher.R.string.submit_field_title)).apply { setText(existingTitle) }
        val slugField = makeField(activity,
            activity.getString(com.iappyx.launcher.R.string.submit_field_slug)).apply {
            setText(slugify(existingTitle))
        }
        val descField = makeField(activity,
            activity.getString(com.iappyx.launcher.R.string.submit_field_description), multi = true).apply {
            setText(existingDescription)
        }
        container.addView(titleField)
        container.addView(slugField)
        container.addView(descField)
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(com.iappyx.launcher.R.string.submit_dialog_title_format, kind.label))
            .setView(container)
            .setNegativeButton(com.iappyx.launcher.R.string.action_cancel, null)
            .setPositiveButton(com.iappyx.launcher.R.string.submit_action) { _, _ ->
                runSubmit(
                    token = token,
                    title = titleField.text.toString().trim().ifBlank { existingTitle },
                    slug = slugify(slugField.text.toString().trim()).ifBlank { slugify(existingTitle) },
                    description = descField.text.toString().trim().ifBlank { existingDescription },
                    contentText = contentText,
                    contentFileName = contentFileName,
                    attribution = attribution,
                )
            }
            .showThemed()
    }

    /** Run the submission on a worker thread; show a progress dialog in
     *  the meantime. On success surfaces the PR URL with an "Open" CTA. */
    private fun runSubmit(
        token: String, title: String, slug: String, description: String,
        contentText: String, contentFileName: String, attribution: List<String>,
    ) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(com.iappyx.launcher.R.string.submit_progress_title)
            .setMessage(com.iappyx.launcher.R.string.submit_progress_message)
            .setCancelable(false)
            .create()
        progress.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(progress) }
        progress.show()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            try {
                val client = GithubClient(token)
                val meta = JSONObject().apply {
                    put("title", title)
                    put("description", description)
                    put("author", "community")
                    if (attribution.isNotEmpty()) {
                        put("uses", org.json.JSONArray(attribution))
                    }
                    put("added", java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.US).format(java.util.Date()))
                }.toString(2)
                val prUrl = client.submitArtefact(
                    kindFolder = kindFolder(),
                    slug = slug,
                    contentFileName = contentFileName,
                    contentText = contentText,
                    metaJson = meta,
                    title = title,
                    description = description,
                    attribution = attribution,
                )
                main.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle(com.iappyx.launcher.R.string.submit_pr_opened_title)
                        .setMessage(prUrl)
                        .setNegativeButton(com.iappyx.launcher.R.string.submit_action_close, null)
                        .setPositiveButton(com.iappyx.launcher.R.string.submit_action_view_pr) { _, _ ->
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prUrl)))
                        }
                        .showThemed()
                }
            } catch (e: GithubException) {
                main.post {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle(com.iappyx.launcher.R.string.submit_failed_title)
                        .setMessage(e.message)
                        .setPositiveButton(com.iappyx.launcher.R.string.action_ok, null)
                        .showThemed()
                }
            } catch (e: Throwable) {
                main.post {
                    progress.dismiss()
                    Toast.makeText(activity,
                        activity.getString(com.iappyx.launcher.R.string.unexpected_error_toast_format,
                            e.message ?: activity.getString(com.iappyx.launcher.R.string.unknown_error_short)),
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Pull source content + meta off disk into a flat tuple. Returns null
     *  if the artefact files have gone missing (shouldn't happen — caller
     *  surfaced this from the manage tab). */
    private data class Source(
        val title: String,
        val description: String,
        val content: String,
        val contentFileName: String,
        val attribution: List<String>,
    )

    private fun readSource(): Source? {
        val ctx: Context = activity
        return when (kind) {
            ArtefactBundle.Kind.WIDGET -> {
                val dir = File(ctx.filesDir, "widgets/$artefactId")
                val htmlFile = File(dir, "widget.html")
                val metaFile = File(dir, "meta.json")
                if (!htmlFile.exists()) return null
                val meta = if (metaFile.exists()) {
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                } else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: "Generated widget",
                    description = meta?.optString("prompt").orEmpty(),
                    content = htmlFile.readText(),
                    contentFileName = "widget.html",
                    attribution = emptyList(),
                )
            }
            ArtefactBundle.Kind.WALLPAPER -> {
                val htmlFile = File(ctx.filesDir, "wallpapers/$artefactId.html")
                val metaFile = File(ctx.filesDir, "wallpapers/$artefactId.json")
                if (!htmlFile.exists()) return null
                val meta = if (metaFile.exists()) {
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                } else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: "Generated wallpaper",
                    description = meta?.optString("prompt").orEmpty(),
                    content = htmlFile.readText(),
                    contentFileName = "wallpaper.html",
                    attribution = emptyList(),
                )
            }
            ArtefactBundle.Kind.TRANSITION -> {
                val specFile = File(ctx.filesDir, "transitions/$artefactId.json")
                val metaFile = File(ctx.filesDir, "transitions/$artefactId.meta.json")
                if (!specFile.exists()) return null
                val meta = if (metaFile.exists()) {
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                } else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: "Generated transition",
                    description = meta?.optString("prompt").orEmpty(),
                    content = specFile.readText(),
                    contentFileName = "spec.json",
                    attribution = emptyList(),
                )
            }
            ArtefactBundle.Kind.ICON_FILTER -> {
                val dir = File(ctx.filesDir, "icon_filters/$artefactId")
                val specFile = File(dir, "spec.json")
                val metaFile = File(dir, "meta.json")
                if (!specFile.exists()) return null
                val meta = if (metaFile.exists()) {
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                } else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: "Generated icon style",
                    description = meta?.optString("prompt").orEmpty(),
                    content = specFile.readText(),
                    contentFileName = "spec.json",
                    attribution = emptyList(),
                )
            }
            // PLUGINS: BEGIN — on-device submit for plugins reads the
            // plugin folder. Submit from the editor (Settings → Plugins
            // tab in remote-edit) is the preferred path; this on-device
            // dialog is also wired for parity.
            ArtefactBundle.Kind.PLUGIN -> {
                val dir = File(ctx.filesDir, "plugins/$artefactId")
                val pluginFile = File(dir, "plugin.html")
                val manifestFile = File(dir, "manifest.json")
                if (!pluginFile.exists() || !manifestFile.exists()) return null
                val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
                Source(
                    title = manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: "Plugin",
                    description = manifest?.optString("description").orEmpty(),
                    content = pluginFile.readText(),
                    contentFileName = "plugin.html",
                    attribution = emptyList(),
                )
            }
            // PLUGINS: END
        }
    }

    private fun kindFolder(): String = when (kind) {
        ArtefactBundle.Kind.WIDGET -> "widgets"
        ArtefactBundle.Kind.WALLPAPER -> "wallpapers"
        ArtefactBundle.Kind.TRANSITION -> "transitions"
        ArtefactBundle.Kind.ICON_FILTER -> "icon_filters"
        // PLUGINS: BEGIN
        ArtefactBundle.Kind.PLUGIN -> "plugins"
        // PLUGINS: END
    }

    private fun slugify(input: String): String {
        if (input.isBlank()) return "untitled"
        return input.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "untitled" }
            .take(60)
    }

    private fun makeField(context: Context, hint: String, multi: Boolean = false): EditText {
        val dp = context.resources.displayMetrics.density
        return EditText(context).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            maxLines = if (multi) 6 else 1
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
    }
}
