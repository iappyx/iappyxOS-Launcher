/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.iappyx.launcher.LauncherActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.ai.WidgetPromptBuilder
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.Placement
import com.iappyx.launcher.transitions.TransitionGenerator
import com.iappyx.launcher.transitions.TransitionLibrary
import com.iappyx.launcher.transitions.TransitionSpec
import com.iappyx.launcher.wallpaper.WallpaperGenerator
import com.iappyx.launcher.wallpaper.WallpaperLibrary
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The "manual AI" workflow lifted out of three standalone Activities into a
 * single in-tab View. Lives inside [CommandPanel]; activated via
 * [CommandPanel.enterManualMode].
 *
 * Why inline instead of an Activity: the user typically copies the prompt,
 * leaves the launcher to paste into ChatGPT/Claude/etc., then comes back. As
 * an Activity, the back-stack pop and Android's background-process killing
 * would lose their input. As a View inside the singleTask LauncherActivity,
 * pressing HOME and returning lands them on the AI tab in the exact same
 * state — description + paste field intact.
 *
 * One class, three flavours via [Type]:
 *  - WIDGET: prompt = WidgetPromptBuilder.SYSTEM_PROMPT + description; preview
 *    is a WebView; save writes to `filesDir/widgets/{id}/widget.html` and
 *    optionally drops a [Placement] onto the home grid (when reached from
 *    AddToHomeSheet's empty-cell flow).
 *  - WALLPAPER: prompt = WallpaperGenerator.buildManualPrompt; preview is a
 *    WebView; save writes to wallpaper library.
 *  - TRANSITION: prompt = TransitionGenerator.buildManualPrompt; "preview"
 *    is just a green/red validity badge (the JSON spec runs through
 *    TransitionSpec.parse); save writes to transition library.
 */
class ManualAiCanvas(context: Context) : FrameLayout(context) {

    enum class Type { WIDGET, WALLPAPER, TRANSITION, ICON_FILTER }

    /** Lets a Widget save commit straight to a home cell — populated by
     *  AddToHomeSheet's "Generate with external AI → Widget" flow. Null
     *  when the canvas was opened standalone (result lands in the library
     *  and the user places it later from the Manage tab). */
    data class PlacementTarget(val pageIndex: Int, val row: Int, val col: Int)

    /** Edit-an-existing-widget seed. When non-null we load the current HTML
     *  into the paste field, change the description hint, and overwrite the
     *  same widget id on save instead of allocating a new one. */
    data class WidgetEdit(val widgetId: String, val currentHtml: String)

    private val density = resources.displayMetrics.density

    // Widgets we toggle / clean up. Built inside [build] for the active type.
    private lateinit var prompt: EditText
    private lateinit var pasteField: EditText
    private lateinit var copyBtn: Button
    private lateinit var save: Button
    private lateinit var status: TextView
    private lateinit var pasteValid: TextView
    private lateinit var previewContainer: FrameLayout
    private var previewView: WebView? = null

    // Save handles per type — set after [build].
    private var currentHtml: String? = null
    private var currentJson: String? = null

    private var type: Type = Type.WIDGET
    private var placementTarget: PlacementTarget? = null
    private var editTarget: WidgetEdit? = null

    /** Caller — back chip (top of canvas) routes to this so the panel can
     *  flip back to Chat / Empty state. */
    var onLeave: (() -> Unit)? = null

    /** Build (or rebuild) the canvas for [type]. Idempotent — safe to call
     *  again with a different type; old views are removed first. */
    fun configure(
        type: Type,
        placementTarget: PlacementTarget? = null,
        editTarget: WidgetEdit? = null,
    ) {
        // Clean up the previous WebView preview so we don't leak Chromium
        // state when switching from Wallpaper → Widget within the same
        // session.
        previewView?.also {
            it.stopLoading(); it.loadUrl("about:blank"); it.destroy()
        }
        previewView = null
        currentHtml = null
        currentJson = null
        removeAllViews()
        this.type = type
        this.placementTarget = placementTarget
        this.editTarget = editTarget
        addView(build(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Edit mode — seed paste field with the current HTML, enable Save
        // immediately, change the description hint to "what should change".
        if (type == Type.WIDGET && editTarget != null) {
            currentHtml = editTarget.currentHtml
            pasteField.setText(editTarget.currentHtml)
            showPreviewHtml(editTarget.currentHtml)
            save.isEnabled = true
            prompt.hint = "What should change? e.g. make the numbers larger"
        }
    }

    private fun build(): View {
        val ctx = context
        val dp = density
        val bg = ctx.resources.getColor(R.color.bg_home, ctx.theme)
        val textPri = Palette.textPrimary(ctx)
        val textSec = Palette.textSecondary(ctx)
        val accent = Palette.accent(ctx)

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(),
                (20 * dp).toInt(), (20 * dp).toInt())
        }

        // Back chip — leaves Manual mode and returns to Chat / Empty state.
        column.addView(makeBackChip(dp))

        column.addView(TextView(ctx).apply {
            text = when (type) {
                Type.WIDGET -> "Generate widget (manual AI)"
                Type.WALLPAPER -> "Generate wallpaper (manual AI)"
                Type.TRANSITION -> "Generate transition (manual AI)"
                Type.ICON_FILTER -> "Generate icon style (manual AI)"
            }
            setTextColor(textPri); textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })
        column.addView(TextView(ctx).apply {
            text = when (type) {
                Type.WIDGET -> "Copy the prompt, paste it into ChatGPT / Claude / any AI, " +
                    "then paste the HTML response below. Leave the launcher to copy — " +
                    "your input is preserved when you come back."
                Type.WALLPAPER -> "Copy the prompt, paste it into ChatGPT / Claude / any AI, " +
                    "then paste the HTML response below. We'll save it to your wallpaper library."
                Type.TRANSITION -> "Copy the prompt, paste it into ChatGPT / Claude / any AI, " +
                    "then paste the JSON spec below. We'll validate and save it."
                Type.ICON_FILTER -> "Copy the prompt, paste it into ChatGPT / Claude / any AI, " +
                    "then paste the JSON spec below. We'll validate and apply it as your active icon style."
            }
            setTextColor(textSec); textSize = 13f
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        column.addView(label(ctx, when (type) {
            Type.WIDGET -> "What should the widget do?"
            Type.WALLPAPER -> "What should the wallpaper show?"
            Type.TRANSITION -> "What should the transition do?"
            Type.ICON_FILTER -> "How should the icon style look?"
        }, dp))
        prompt = EditText(ctx).apply {
            hint = when (type) {
                Type.WIDGET -> "A water tracker — tap a cup to mark it drunk"
                Type.WALLPAPER -> "Slow-drifting aurora over a dark sky"
                Type.TRANSITION -> "Cells fall like dominoes from top to bottom"
                Type.ICON_FILTER -> "Cinematic teal-and-orange split tone with a subtle vignette"
            }
            setHintTextColor(textSec); setTextColor(textPri); textSize = 14f
            background = inputBg(dp)
            val pad = (14 * dp).toInt(); setPadding(pad, pad, pad, pad)
            minLines = 2; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        column.addView(prompt)

        copyBtn = Button(ctx).apply {
            text = "Copy prompt to clipboard"
            setTextColor(bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
            setOnClickListener { onCopyPrompt() }
        }
        column.addView(copyBtn)

        status = TextView(ctx).apply {
            visibility = View.GONE
            setTextColor(accent); textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(),
                (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.argb(0x22, 0x4F, 0xC3, 0xF7))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
        column.addView(status)

        column.addView(divider(dp))

        column.addView(label(ctx, when (type) {
            Type.WIDGET, Type.WALLPAPER -> "Paste the AI's HTML here"
            Type.TRANSITION, Type.ICON_FILTER -> "Paste the AI's JSON spec here"
        }, dp))
        pasteField = EditText(ctx).apply {
            hint = when (type) {
                Type.WIDGET, Type.WALLPAPER -> "<!DOCTYPE html>\n<html>..."
                Type.TRANSITION -> "{\"page\": {...}, \"cell\": {...}}"
                Type.ICON_FILTER -> "{\"name\": \"...\", \"bake\": [...], \"tint\": null}"
            }
            setHintTextColor(textSec); setTextColor(textPri); textSize = 13f
            typeface = Typeface.MONOSPACE
            background = inputBg(dp)
            val pad = (14 * dp).toInt(); setPadding(pad, pad, pad, pad)
            minLines = 6; maxLines = 14; gravity = Gravity.TOP
            isVerticalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        column.addView(pasteField)

        // Type-specific paste validation badge (used by transition; wallpapers /
        // widgets repurpose it as a "looks like HTML" hint when the paste isn't HTML).
        pasteValid = TextView(ctx).apply {
            visibility = View.GONE
            textSize = 12f
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        column.addView(pasteValid)

        // Preview surface — WebView for HTML types; transitions and icon
        // filters use the validity badge above as their preview.
        if (type != Type.TRANSITION && type != Type.ICON_FILTER) {
            column.addView(TextView(ctx).apply {
                text = "Preview"
                setTextColor(textSec); textSize = 11f
                setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
            })
            previewContainer = FrameLayout(ctx).apply {
                background = inputBg(dp)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (240 * dp).toInt(),
                )
                layoutParams = lp
            }
            column.addView(previewContainer)
        } else {
            // Initialise the field so [configure] can null-check freely.
            previewContainer = FrameLayout(ctx)
        }

        save = Button(ctx).apply {
            text = when (type) {
                Type.WIDGET -> if (editTarget != null) "Update widget" else "Save widget"
                Type.WALLPAPER -> "Save wallpaper"
                Type.TRANSITION -> "Save transition"
                Type.ICON_FILTER -> "Save icon style"
            }
            isEnabled = false
            setTextColor(bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
            setOnClickListener { onSave() }
        }
        column.addView(save)

        wirePasteAutoDetect()

        return ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun makeBackChip(dp: Float): View {
        val ctx = context
        return TextView(ctx).apply {
            // "Back" rather than "Back to chat" — when no API key is set
            // the back action lands on the empty state, not the chat.
            text = "‹  Back"
            textSize = 13f
            setTextColor(Palette.accent(ctx))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    Palette.accent(ctx), 0x1F))
                setStroke((1 * dp).toInt(),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.accent(ctx), 0x66))
            }
            val hp = (14 * dp).toInt(); val vp = (8 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            isClickable = true; isFocusable = true
            setOnClickListener { onLeave?.invoke() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
        }
    }

    private fun onCopyPrompt() {
        val description = prompt.text.toString().trim()
        if (description.isEmpty() && editTarget == null) {
            showStatus("Describe it first.")
            return
        }
        val full = when (type) {
            Type.WIDGET -> {
                WidgetPromptBuilder.load(context)
                buildString {
                    append(WidgetPromptBuilder.SYSTEM_PROMPT.trim())
                    append("\n\n---\n\n")
                    val edit = editTarget
                    if (edit != null) {
                        append("Current widget HTML (modify this, don't rewrite from scratch):\n\n")
                        append(edit.currentHtml)
                        append("\n\nChange request: ")
                        append(description.ifEmpty { "improve this widget" })
                    } else {
                        append("Widget description: ")
                        append(description)
                    }
                }
            }
            Type.WALLPAPER -> WallpaperGenerator.buildManualPrompt(context, description)
            Type.TRANSITION -> TransitionGenerator.buildManualPrompt(description)
            Type.ICON_FILTER ->
                com.iappyx.launcher.cells.IconFilterGenerator.buildManualPrompt(description)
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("iappyx prompt", full))
        showStatus("Prompt copied — paste it into your AI, then come back and paste the response below.")
        pasteField.requestFocus()
    }

    private fun wirePasteAutoDetect() {
        pasteField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString().orEmpty().trim()
                if (raw.isEmpty()) {
                    save.isEnabled = false
                    pasteValid.visibility = View.GONE
                    return
                }
                when (type) {
                    Type.WIDGET -> {
                        val cleaned = stripMarkdownFences(raw)
                        if (looksLikeHtml(cleaned)) {
                            currentHtml = cleaned
                            showPreviewHtml(cleaned)
                            save.isEnabled = true
                            pasteValid.visibility = View.GONE
                        } else {
                            save.isEnabled = false
                            pasteValid.text = "Paste should start with <!DOCTYPE html> — copy the AI's full response."
                            pasteValid.setTextColor(Color.parseColor("#FFE57373"))
                            pasteValid.visibility = View.VISIBLE
                        }
                    }
                    Type.WALLPAPER -> {
                        val cleaned = WallpaperGenerator.sanitizeHtml(raw)
                        if (looksLikeHtml(cleaned)) {
                            currentHtml = cleaned
                            showPreviewHtml(cleaned)
                            save.isEnabled = true
                            pasteValid.visibility = View.GONE
                        } else {
                            save.isEnabled = false
                            pasteValid.text = "Paste should start with <!DOCTYPE html> — copy the AI's full response."
                            pasteValid.setTextColor(Color.parseColor("#FFE57373"))
                            pasteValid.visibility = View.VISIBLE
                        }
                    }
                    Type.TRANSITION -> {
                        val cleaned = TransitionGenerator.sanitiseJson(raw)
                        if (TransitionSpec.parse(cleaned) != null) {
                            currentJson = cleaned
                            save.isEnabled = true
                            pasteValid.text = "✓ Valid spec"
                            pasteValid.setTextColor(Color.parseColor("#FF66BB6A"))
                            pasteValid.visibility = View.VISIBLE
                        } else {
                            save.isEnabled = false
                            pasteValid.text = "Couldn't parse a valid spec — check that the JSON looks like {\"page\": {...}, \"cell\": {...}}"
                            pasteValid.setTextColor(Color.parseColor("#FFE57373"))
                            pasteValid.visibility = View.VISIBLE
                        }
                    }
                    Type.ICON_FILTER -> {
                        val cleaned = stripMarkdownFences(raw)
                        // The registry's spec parser requires `slug` — the
                        // AI's prompt deliberately doesn't ask for one (we
                        // assign UUIDs ourselves). Stamp a placeholder
                        // BEFORE validating so a clean response passes.
                        val parsed = try {
                            val obj = JSONObject(cleaned).apply {
                                if (optString("slug").isBlank()) put("slug", "_pending_")
                            }
                            com.iappyx.launcher.cells.IconFilterSpec.fromJson(obj)
                        } catch (_: Throwable) { null }
                        if (parsed != null) {
                            currentJson = cleaned
                            save.isEnabled = true
                            pasteValid.text = "✓ Valid spec"
                            pasteValid.setTextColor(Color.parseColor("#FF66BB6A"))
                            pasteValid.visibility = View.VISIBLE
                        } else {
                            save.isEnabled = false
                            pasteValid.text = "Couldn't parse a valid spec — check that the JSON includes a \"name\" and a \"bake\" array of ops."
                            pasteValid.setTextColor(Color.parseColor("#FFE57373"))
                            pasteValid.visibility = View.VISIBLE
                        }
                    }
                }
            }
        })
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.take(64).lowercase()
        return head.startsWith("<!doctype html") ||
            head.startsWith("<html") ||
            head.startsWith("<!doctype")
    }

    private fun stripMarkdownFences(raw: String): String {
        val trimmed = raw.trim()
        val fenceStart = Regex("^```(?:html)?\\s*\\n?", RegexOption.IGNORE_CASE)
        val fenceEnd = Regex("\\n?```\\s*$")
        return trimmed.replaceFirst(fenceStart, "").replaceFirst(fenceEnd, "").trim()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPreviewHtml(html: String) {
        if (type == Type.TRANSITION) return
        previewContainer.removeAllViews()
        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0x00000000)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            isLongClickable = false
            setOnLongClickListener { true }
        }
        previewView = wv
        previewContainer.addView(wv)
        val baseUrl = if (type == Type.WIDGET) "https://widget.local/" else "https://wallpaper.local/"
        wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    /** Save click handler. For Widget + Wallpaper types this runs the
     *  forbidden-API precheck FIRST and gates [performSave] behind a
     *  user-confirmation dialog if violations are found. Transition +
     *  IconFilter types skip the precheck (their payload is JSON DSL, not
     *  JS — nothing to scan). */
    private fun onSave() {
        if (type == Type.WIDGET || type == Type.WALLPAPER) {
            val html = currentHtml
            if (html != null) {
                val violations = com.iappyx.launcher.ai.ForbiddenApiCheck.scan(html)
                if (violations.isNotEmpty()) {
                    showForbiddenApiWarning(violations) { saveAnyway ->
                        if (saveAnyway) performSave()
                    }
                    return
                }
            }
        }
        performSave()
    }

    private fun performSave() {
        val ctx = context
        when (type) {
            Type.WIDGET -> {
                val html = currentHtml ?: return
                val edit = editTarget
                val id = edit?.widgetId ?: WidgetSandbox.newId()
                val dir = File(ctx.filesDir, "widgets/$id").also { it.mkdirs() }
                File(dir, "widget.html").writeText(html)
                val description = prompt.text.toString().trim()
                WidgetLibrary.writeMeta(ctx, id, prompt = description, html = html)
                val activity = ctx as? LauncherActivity
                val placement = placementTarget
                if (placement != null && edit == null) {
                    activity?.commitManualWidgetPlacement(id, placement)
                } else if (edit != null) {
                    // Edit-in-place — re-render the visible page so the WebView reloads.
                    activity?.refreshHomePagesAfterEdit()
                }
                // Reset the canvas + leave manual mode.
                showStatus(if (edit != null) "Updated" else "Saved to library")
                onLeave?.invoke()
            }
            Type.WALLPAPER -> {
                val html = currentHtml ?: return
                val description = prompt.text.toString().trim()
                val id = UUID.randomUUID().toString()
                val dir = WallpaperLibrary.userDir(ctx)
                try {
                    File(dir, "$id.html").writeText(html, Charsets.UTF_8)
                    val title = WallpaperGenerator.extractHtmlTitle(html)
                        ?: WallpaperGenerator.smartTitle(description)
                    val meta = JSONObject().apply {
                        put("title", title)
                        put("prompt", description)
                        put("createdAt", System.currentTimeMillis())
                    }
                    File(dir, "$id.json").writeText(meta.toString(), Charsets.UTF_8)
                } catch (e: Throwable) {
                    File(dir, "$id.html").delete()
                    File(dir, "$id.json").delete()
                    showStatus("Save failed: ${e.message}")
                    return
                }
                showStatus("Saved to library")
                onLeave?.invoke()
            }
            Type.TRANSITION -> {
                val json = currentJson ?: return
                val description = prompt.text.toString().trim()
                val id = UUID.randomUUID().toString()
                val dir = TransitionLibrary.userDir(ctx)
                try {
                    File(dir, "$id.json").writeText(json, Charsets.UTF_8)
                    TransitionLibrary.writeMeta(ctx, id, description)
                } catch (e: Throwable) {
                    File(dir, "$id.json").delete()
                    File(dir, "$id.meta.json").delete()
                    showStatus("Save failed: ${e.message}")
                    return
                }
                showStatus("Saved to library")
                onLeave?.invoke()
            }
            Type.ICON_FILTER -> {
                val json = currentJson ?: return
                val description = prompt.text.toString().trim()
                val slug = UUID.randomUUID().toString()
                val dir = java.io.File(
                    com.iappyx.launcher.cells.IconFilterRegistry.userDir(ctx), slug,
                ).also { it.mkdirs() }
                try {
                    // Stamp the slug into the spec so registry lookups by
                    // slug return this file's contents.
                    val obj = JSONObject(json).apply { put("slug", slug) }
                    val title = obj.optString("name").ifBlank {
                        if (description.isBlank()) "Generated icon style" else description.take(60)
                    }
                    File(dir, "spec.json").writeText(obj.toString(2), Charsets.UTF_8)
                    com.iappyx.launcher.cells.IconFilterRegistry.writeMeta(
                        ctx, slug, prompt = description, title = title,
                    )
                    com.iappyx.launcher.cells.IconFilterRegistry.invalidate(slug)
                    // Auto-set as active so the user sees the new style
                    // immediately when they swipe to home.
                    com.iappyx.launcher.LauncherPrefs(ctx).iconFilter = slug
                    (ctx as? LauncherActivity)?.notifyIconFiltersChanged()
                } catch (e: Throwable) {
                    dir.deleteRecursively()
                    showStatus("Save failed: ${e.message}")
                    return
                }
                showStatus("Saved and applied")
                onLeave?.invoke()
            }
        }
    }

    /** Show a Material AlertDialog warning the user that their pasted code
     *  uses Web APIs blocked in the launcher's WebView sandbox. The dialog
     *  body is a custom view: violations list + AI-paste-ready fix hint
     *  in a monospace card + a "Copy hint" button that does NOT dismiss
     *  the dialog (so the user can copy, switch to their AI chat, fix
     *  there, come back, and Cancel out to re-paste corrected code).
     *  [onResult] is invoked with `true` on "Save anyway", `false` on
     *  Cancel / dismiss. */
    private fun showForbiddenApiWarning(
        violations: List<String>,
        onResult: (Boolean) -> Unit,
    ) {
        val ctx = context
        val dp = density
        val container = ScrollView(ctx).apply {
            isFillViewport = true
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Intro
        column.addView(TextView(ctx).apply {
            setText(R.string.manual_forbidden_intro)
            textSize = 14f
        })
        // Violations list — one bullet per match. Reusing the strings from
        // ForbiddenApiCheck.scan() so the wording stays consistent with
        // what the AI auto-flow surfaces.
        val violationsBlock = TextView(ctx).apply {
            text = violations.joinToString("\n") { "•  $it" }
            textSize = 13f
            setTypeface(typeface, Typeface.NORMAL)
            val pad = (10 * dp).toInt()
            setPadding(0, pad, 0, pad)
        }
        column.addView(violationsBlock)
        // Section divider
        column.addView(TextView(ctx).apply {
            setText(R.string.manual_forbidden_fix_header)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            val pad = (12 * dp).toInt()
            setPadding(0, pad, 0, (4 * dp).toInt())
        })
        // Fix preamble
        column.addView(TextView(ctx).apply {
            setText(R.string.manual_forbidden_fix_preamble)
            textSize = 13f
        })
        // Hint card — monospace, light background, padded. Visually
        // distinct so it reads as "this is the thing to copy" rather
        // than as more body prose.
        val hintText = buildFixHint(violations)
        val hintCard = TextView(ctx).apply {
            text = hintText
            textSize = 12f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Color.parseColor("#22FFFFFF"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
            setTextIsSelectable(true)
        }
        column.addView(hintCard)
        // Copy button — sits inside the custom view, NOT as a dialog
        // button, so tapping it doesn't auto-dismiss the dialog.
        val copyHintBtn = Button(ctx).apply {
            setText(R.string.manual_forbidden_copy_hint)
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            lp.gravity = Gravity.END
            layoutParams = lp
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("iappyx fix hint", hintText))
                android.widget.Toast.makeText(
                    ctx, R.string.manual_forbidden_copied_toast,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        column.addView(copyHintBtn)
        container.addView(column)

        // Build the AlertDialog. setView on the body, regular Cancel +
        // Save anyway buttons. Both result paths call back to the
        // original onSave caller.
        var resolved = false
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.manual_forbidden_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                resolved = true
                onResult(false)
            }
            .setPositiveButton(R.string.manual_forbidden_save_anyway) { _, _ ->
                resolved = true
                onResult(true)
            }
            // If the user dismisses by tapping outside / back button,
            // treat as Cancel — never silently save.
            .setOnDismissListener { if (!resolved) onResult(false) }
            .create()
        dialog.setOnShowListener { com.iappyx.launcher.widget.Palette.applyThemeToDialog(dialog) }
        dialog.show()
    }

    /** Build the AI-paste-ready fix instruction. Filters lines to ONLY
     *  the violations actually present in the user's paste so they don't
     *  see boilerplate about APIs they didn't use.
     *
     *  Output is plain English — independent of the user's UI language —
     *  because the user pastes this into an external AI chat (Claude.ai,
     *  ChatGPT, etc.) which understands English universally. Localising
     *  the hint would require translating per-target-AI capability,
     *  which is overkill for v1. */
    private fun buildFixHint(violations: List<String>): String {
        val sb = StringBuilder()
        sb.append("Please rewrite this widget code so it uses the iappyx bridge ")
        sb.append("instead of these blocked Web APIs:\n\n")
        // Match the violation strings (which start with the API name)
        // against each known rule and append only the relevant lines.
        for (v in violations) {
            when {
                v.startsWith("fetch()") || v.startsWith("XMLHttpRequest") ->
                    sb.append("- fetch() / XMLHttpRequest → " +
                        "iappyx.httpClient.request(JSON.stringify({url, method, headers, body}), 'cbName')\n")
                v.startsWith("navigator.geolocation") ->
                    sb.append("- navigator.geolocation → " +
                        "iappyx.location.getLocation('cbName')\n")
                v.startsWith("new Notification(") ||
                    v.startsWith("Notification.requestPermission") ->
                    sb.append("- Notification API → iappyx.notification.show(...)\n")
                v.startsWith("new WebSocket(") ->
                    sb.append("- WebSocket → iappyx.tcp.* or iappyx.httpClient.* polling\n")
            }
        }
        sb.append("\n")
        sb.append("Bridge methods take a cbId (a global function name) instead of returning ")
        sb.append("a Promise. Set window._iappyxCb = window._iappyxCb || {}; ")
        sb.append("window._iappyxCb.cbName = function(res){ /* handle res.ok / res.body */ }; ")
        sb.append("BEFORE calling the bridge. Never use await — bridge calls aren't Promises.\n\n")
        sb.append("Keep the rest of the code the same — only swap the blocked APIs.")
        return sb.toString()
    }

    /** Tear down the WebView preview on detach so its Chromium state doesn't
     *  leak across orientation changes or pager-recycle events. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        previewView?.also {
            it.stopLoading(); it.loadUrl("about:blank"); it.destroy()
        }
        previewView = null
    }

    // ── Layout helpers ──────────────────────────────────────────

    private fun label(ctx: Context, text: String, dp: Float): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Palette.textPrimary(ctx))
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, (6 * dp).toInt())
    }

    private fun inputBg(dp: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12 * dp
        setColor(Color.argb(0x1F, 0xFF, 0xFF, 0xFF))
        setStroke((1 * dp).toInt(), Color.argb(0x33, 0xFF, 0xFF, 0xFF))
    }

    private fun divider(dp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt(),
        ).apply {
            topMargin = (22 * dp).toInt()
            bottomMargin = (22 * dp).toInt()
        }
        setBackgroundColor(Color.argb(0x22, 0xFF, 0xFF, 0xFF))
    }
}
