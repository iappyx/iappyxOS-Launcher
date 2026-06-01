/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.HtmlIterator
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.ai.WidgetPromptBuilder
import java.io.File

/**
 * Mirror of [com.iappyx.launcher.wallpaper.WallpaperGenerator] for widgets:
 * generation + AI-driven iteration of an existing widget. Sits next to
 * `WidgetLibrary` so the manage tab can refine widgets just like wallpapers.
 *
 * Generation here writes the widget HTML + meta but does NOT place a
 * placement on the home grid — that's the caller's job (the AI command
 * tool `create_generated_widget` still does the find-empty-spot + placement
 * work; this object is only the AI + filesystem half).
 */
object WidgetGenerator {

    // Client-side guard against pathological inputs. Roughly 10K tokens at
    // ~4 chars/token. The Anthropic API itself accepts 1M+ tokens; this is
    // just sanity. Iterate() embeds the current widget HTML in the message
    // separately — this cap is on the user's own typed instruction only.
    private const val PROMPT_MAX = 40000

    class GenerationException(msg: String) : Exception(msg)

    /** Iterate finished without modifying the widget — typically because
     *  the AI judged the change already in place, the instruction was
     *  ambiguous, or it declined for safety. NOT an error: the widget on
     *  disk is intentionally unchanged. Callers (UI / tool runner) should
     *  surface [reason] to the user without auto-retrying.
     *
     *  Separate exception type so the Command Bar's tool runner can return
     *  a success-shaped JSON (`ok:true, noop:true`) instead of an error,
     *  preventing the AI from looping on the same edit. */
    class NoOpException(val reason: String) : Exception(reason)

    /** Generate a fresh widget from [description]. Returns the new widget id
     *  on success. Caller is responsible for placing it on the grid. */
    @Throws(GenerationException::class)
    fun generate(context: Context, description: String): String {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what the widget should do")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Description too long (${trimmed.length}/$PROMPT_MAX)")
        }

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val systemPrompt = WidgetPromptBuilder.load(context)
        val html = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = systemPrompt,
                messages = listOf(AiService.Message("user", trimmed)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Generation failed")
        }
        if (!html.trim().startsWith("<")) {
            throw GenerationException("Widget AI did not return HTML")
        }
        // Static safety net — block forbidden Web-standard APIs that the
        // prompt already says not to use. The AI sometimes copies fetch /
        // XMLHttpRequest / navigator.geolocation patterns from training
        // data despite the prompt. Reject hard so the broken widget never
        // touches disk; the caller (Command Bar) feeds the error back to
        // the AI and it self-corrects on the next turn.
        try { com.iappyx.launcher.ai.ForbiddenApiCheck.assertClean(html) }
        catch (e: IllegalStateException) {
            throw GenerationException(e.message ?: "AI used forbidden Web APIs")
        }

        val widgetId = WidgetSandbox.newId()
        val widgetsDir = File(context.filesDir, "widgets/$widgetId")
        try {
            if (!widgetsDir.exists() && !widgetsDir.mkdirs()) {
                throw GenerationException("Failed to create widget sandbox dir")
            }
            File(widgetsDir, "widget.html").writeText(html, Charsets.UTF_8)
            WidgetSandbox.sandboxFor(context, widgetId)
            WidgetLibrary.writeMeta(context, widgetId, prompt = trimmed, html = html)
        } catch (e: GenerationException) {
            widgetsDir.deleteRecursively()
            throw e
        } catch (t: Throwable) {
            widgetsDir.deleteRecursively()
            throw GenerationException("Failed to write widget HTML: ${t.message}")
        }
        return widgetId
    }

    /** Re-iterate an existing widget. Reads its HTML, asks the AI to apply
     *  [instruction], writes the modified HTML back over the same id.
     *  Returns the (unchanged) id on success.
     *
     *  Output protocol (see widget_iterate_prompt.md): the AI emits JSON with
     *  either an `edits` array (small, localised changes — applied as literal
     *  string find/replace operations) or a `full_html` string (structural
     *  rewrites). Diff mode is dramatically faster for the common case (a
     *  one-line CSS tweak emits ~50 output tokens instead of ~30 K). One
     *  automatic retry if the AI's edits don't apply cleanly, asking it to
     *  fall back to `full_html`. */
    @Throws(GenerationException::class)
    fun iterate(
        context: Context,
        id: String,
        instruction: String,
        onProgress: AiService.StreamProgress? = null,
    ): String {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what should change")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Instruction too long (${trimmed.length}/$PROMPT_MAX)")
        }
        val htmlFile = File(context.filesDir, "widgets/$id/widget.html")
        if (!htmlFile.exists()) throw GenerationException("Widget file missing")

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val currentHtml = htmlFile.readText(Charsets.UTF_8)
        val systemPrompt = WidgetPromptBuilder.loadIterate(context)
        // First attempt uses the configured iterate model (Haiku by default —
        // fast on the diff-emit path). If the AI's edits fail to apply, the
        // auto-retry inside runIteration drops to full_html mode and we let
        // the caller pass the fallback model so the rewrite gets the
        // higher-quality create model (Sonnet by default).
        val newHtml = runIteration(
            apiKey = key,
            iterateModel = store.iterateModel,
            fallbackModel = store.anthropicModel,
            systemPrompt = systemPrompt, currentHtml = currentHtml,
            instruction = trimmed, allowFullHtmlOnly = false,
            onProgress = onProgress,
        )
        // Same safety net as generate(), but compare against currentHtml so
        // we only flag NEWLY-introduced violations. A legacy widget that
        // already has fetch passes through unless the AI added a fresh
        // fetch in another spot. The user can clean up legacy code by
        // explicitly asking "replace fetch with the bridge".
        try { com.iappyx.launcher.ai.ForbiddenApiCheck.assertClean(newHtml, currentHtml) }
        catch (e: IllegalStateException) {
            throw GenerationException(e.message ?: "AI introduced forbidden Web APIs")
        }

        try {
            htmlFile.writeText(newHtml, Charsets.UTF_8)
        } catch (e: Throwable) {
            throw GenerationException("Failed to save: ${e.message}")
        }
        return id
    }

    /** One round-trip of the iterate loop. Returns the new HTML on success.
     *  If the AI emits `edits` and they fail to apply, retries ONCE with
     *  [allowFullHtmlOnly] = true to nudge the model toward a full rewrite. */
    private fun runIteration(
        apiKey: String,
        iterateModel: String,
        fallbackModel: String,
        systemPrompt: String,
        currentHtml: String,
        instruction: String,
        allowFullHtmlOnly: Boolean,
        onProgress: AiService.StreamProgress? = null,
    ): String {
        // Pick the model:
        //  - First attempt (allowFullHtmlOnly = false) → iterateModel.
        //    Diff-emit path is mechanical, Haiku handles it well, fast.
        //  - Retry attempt (allowFullHtmlOnly = true) → fallbackModel.
        //    Edits failed to apply; we're now asking for a complete
        //    document rewrite which benefits from the higher-quality
        //    Sonnet (or whatever the user configured for create).
        val model = if (allowFullHtmlOnly) fallbackModel else iterateModel
        // Split the user message into a cacheable prefix (the embedded HTML,
        // which doesn't change between consecutive edits to the same widget)
        // and a fresh suffix (the instruction + retry-mode nudge, which
        // varies). Anthropic's prompt cache hits on the prefix → ~90 %
        // input-cost savings on follow-up edits within the 5-minute TTL.
        val (cachedPrefix, freshSuffix) = buildIterateContent(
            currentHtml, instruction, allowFullHtmlOnly,
        )
        val raw = try {
            AiService.generate(
                apiKey = apiKey, model = model,
                systemPrompt = systemPrompt,
                messages = listOf(
                    AiService.Message(
                        role = "user",
                        content = freshSuffix,
                        cacheablePrefix = cachedPrefix,
                    ),
                ),
                onProgress = onProgress,
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Iteration failed")
        }
        return when (val parsed = HtmlIterator.parseResponse(raw)) {
            is HtmlIterator.Response.Edits -> {
                if (parsed.edits.isEmpty()) {
                    // Empty edits is the AI's "no-op" signal — usually
                    // because the change was already in place, the
                    // instruction was ambiguous, or it declined. We
                    // surface this as a NoOpException (NOT a generic
                    // GenerationException) so the Command Bar's tool
                    // runner can return ok:true and the AI doesn't retry
                    // the same call 3× thinking it failed. The widget on
                    // disk is intentionally untouched.
                    throw NoOpException(
                        parsed.reason ?: "the AI didn't modify anything",
                    )
                }
                val applied = try { HtmlIterator.applyEdits(currentHtml, parsed.edits) }
                catch (e: HtmlIterator.ApplyException) {
                    if (allowFullHtmlOnly) {
                        throw GenerationException(e.message ?: "Edit application failed")
                    }
                    // Auto-retry once, asking for full_html instead. The
                    // recursive call will pick fallbackModel via the
                    // allowFullHtmlOnly = true branch above.
                    return runIteration(
                        apiKey = apiKey,
                        iterateModel = iterateModel,
                        fallbackModel = fallbackModel,
                        systemPrompt = systemPrompt,
                        currentHtml = currentHtml,
                        instruction = instruction,
                        allowFullHtmlOnly = true,
                        onProgress = onProgress,
                    )
                }
                validateOrThrow(applied, currentHtml.length)
                applied
            }
            is HtmlIterator.Response.FullHtml -> {
                validateOrThrow(parsed.html, currentHtml.length)
                parsed.html
            }
            is HtmlIterator.Response.RawHtml -> {
                // AI ignored the JSON protocol and returned raw HTML.
                // Accept on the FIRST pass (back-compat with the old behaviour);
                // on the retry pass we already nudged for full_html, so reject
                // anything that's neither valid JSON nor a clean HTML doc.
                val cleaned = HtmlIterator.stripCodeFence(parsed.text)
                if (!cleaned.trimStart().startsWith("<")) {
                    throw GenerationException("AI returned malformed response")
                }
                validateOrThrow(cleaned, currentHtml.length)
                cleaned
            }
        }
    }

    /** Convert HtmlIterator.ValidationException → GenerationException so
     *  the existing UI error path keeps working unchanged. */
    private fun validateOrThrow(html: String, originalLength: Int) {
        try { HtmlIterator.validate(html, originalLength) }
        catch (e: HtmlIterator.ValidationException) {
            throw GenerationException(e.message ?: "Result HTML failed validation")
        }
    }

    /** Build the user message as a (cacheable prefix, fresh suffix) pair.
     *  Prefix = the embedded HTML in a fenced block, identical for every
     *  edit of the same widget within a 5-minute window. Suffix = the
     *  instruction + protocol nudge, which varies per call. The cache
     *  breakpoint sits between them. */
    private fun buildIterateContent(
        currentHtml: String, instruction: String, allowFullHtmlOnly: Boolean,
    ): Pair<String, String> {
        val prefix = buildString {
            append("Here is the current widget HTML:\n```html\n")
            append(currentHtml)
            append("\n```\n")
        }
        val suffix = buildString {
            append("\nThe user wants this change:\n")
            append(instruction)
            append("\n\n")
            if (allowFullHtmlOnly) {
                // Retry path — the AI's previous edits didn't apply cleanly.
                append("Your previous response emitted edits that did not match. ")
                append("Use the `full_html` mode instead — emit the COMPLETE updated ")
                append("widget HTML in a single JSON object: ")
                append("""{ "full_html": "<!DOCTYPE html>...<\/html>" }. """)
                append("Preserve <title> and <meta name=\"iappyx-widget\">.")
            } else {
                append("Respond with JSON only — `edits` for small changes, ")
                append("`full_html` for restructures (see system prompt).")
            }
        }
        return prefix to suffix
    }

    /** Copy a bundled widget's HTML out of `assets/` into the user widget
     *  library so it can be edited. Bundled widgets are read-only — they
     *  ship inside the APK at `assets/widgets/<slug>.html` — but this
     *  function makes an editable user-owned copy with a fresh UUID, ready
     *  for [iterate]. Mirrors the iOS "Customize" pattern: the first edit
     *  forks a default into a personal copy.
     *
     *  @param context any context (uses applicationContext for assets)
     *  @param assetPath the placement's `generatedWidgetAsset` (e.g.
     *         "widgets/clock.html"). Must resolve to an HTML file in the
     *         APK's assets.
     *  @param sourceTitle the bundled widget's display title; the new
     *         entry's title becomes "Custom <Title>" so the user sees the
     *         lineage in the manage carousel.
     *  @return the new user-owned widget id (UUID). Caller is responsible
     *         for swapping the placement's `generatedWidgetAsset → null`,
     *         `generatedWidgetId → returned id`. */
    @Throws(GenerationException::class)
    fun forkBundledWidget(
        context: Context, assetPath: String, sourceTitle: String,
    ): String {
        val html = try {
            context.applicationContext.assets.open(assetPath).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            throw GenerationException("Built-in widget asset missing: $assetPath")
        }
        val newId = java.util.UUID.randomUUID().toString()
        val dir = File(context.filesDir, "widgets/$newId").also { it.mkdirs() }
        try {
            File(dir, "widget.html").writeText(html, Charsets.UTF_8)
        } catch (e: Throwable) {
            throw GenerationException("Failed to fork widget: ${e.message}")
        }
        // Title prefix makes the user copy distinguishable in the manage
        // carousel from the original bundled entry. The prompt slot
        // records the lineage so future "what was this customised from?"
        // questions can be answered without metadata heroics.
        val seedPrompt = "Customised from built-in widget: $sourceTitle ($assetPath)"
        WidgetLibrary.writeMeta(context, newId, prompt = seedPrompt, html = html)
        return newId
    }

    /** Strip ```html ... ``` fences if the model emitted any. Used by the
     *  create-widget path; iterate goes through HtmlIterator's own stripper. */
    private fun sanitize(raw: String): String {
        val s = raw.trim()
        if (!s.startsWith("```")) return s
        val firstNl = s.indexOf('\n')
        if (firstNl < 0) return s
        val body = s.substring(firstNl + 1)
        val closer = body.lastIndexOf("```")
        return if (closer >= 0) body.substring(0, closer).trim() else body.trim()
    }
}
