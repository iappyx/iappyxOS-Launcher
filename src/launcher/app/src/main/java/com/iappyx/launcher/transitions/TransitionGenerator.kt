/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.transitions

import android.content.Context
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.SecureStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * AI-driven generation + iteration of page transition specs (JSON math
 * expressions). Mirrors the wallpaper / widget generator pattern.
 *
 * Output is a JSON object with optional `page` and `cell` blocks — see
 * [TransitionSpec] for the full DSL. We sanitise markdown fences, strip
 * any commentary above the first `{`, validate by attempting to compile
 * before saving.
 */
object TransitionGenerator {

    private const val PROMPT_MAX = 2000

    class GenerationException(msg: String) : Exception(msg)

    @Throws(GenerationException::class)
    fun generate(context: Context, description: String): String {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what the transition should do")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Description too long (${trimmed.length}/$PROMPT_MAX)")
        }
        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val raw = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(AiService.Message("user", trimmed)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Generation failed")
        }
        val json = sanitise(raw)
        val spec = TransitionSpec.parse(json)
            ?: throw GenerationException("AI output didn't compile to a valid transition spec")

        val id = UUID.randomUUID().toString()
        val dir = TransitionLibrary.userDir(context)
        try {
            File(dir, "$id.json").writeText(json, Charsets.UTF_8)
            // Try to extract a "title" the AI may have included as a top-level
            // key (some models add one even when not asked); fall back to the
            // smartTitle derived from the prompt.
            val titleHint = runCatching { JSONObject(json).optString("title") }.getOrNull()
                ?.takeIf { it.isNotBlank() && it.length <= 60 }
            val meta = JSONObject().apply {
                put("title", titleHint ?: "")
                put("prompt", trimmed)
                put("createdAt", System.currentTimeMillis())
            }
            File(dir, "$id.meta.json").writeText(meta.toString(), Charsets.UTF_8)
            // If we didn't get a title from the AI, the library's smartTitle
            // fallback applies on next read.
            if (titleHint != null) {
                // Already wrote it above.
            } else {
                TransitionLibrary.writeMeta(context, id, trimmed)
            }
            // Sanity: parse the spec via the library to make sure it's
            // resolvable. Spec is non-null by check above; this just exercises
            // the read path.
            TransitionLibrary.specFor(context, id)
        } catch (e: Throwable) {
            File(dir, "$id.json").delete()
            File(dir, "$id.meta.json").delete()
            throw GenerationException("Failed to save: ${e.message}")
        }
        return id
    }

    /** Result of [iterate]. Carries the working id so the caller can pick
     *  it up — when a bundled transition was forked on first edit, the
     *  returned id is the NEW user-owned id (the bundled one is left
     *  untouched). [forked] tells the caller whether to swap the active-
     *  transition pref to the new id (so the user sees their customised
     *  copy) or whether the edit happened in place. */
    data class IterateResult(val id: String, val forked: Boolean)

    /** Read the source JSON for a bundled transition out of `assets/` and
     *  write it to the user-transitions dir under a fresh UUID, with a
     *  meta sidecar that records the lineage. Mirrors the bundled-widget
     *  fork in [com.iappyx.launcher.widget.WidgetGenerator.forkBundledWidget].
     *
     *  Throws when the bundled id has no spec asset (hand-coded transitions
     *  in LauncherActivity's `when` block — those aren't editable since
     *  there's no JSON representation to copy). */
    @Throws(GenerationException::class)
    private fun forkBundledTransition(context: Context, bundledId: String): String {
        val current = TransitionLibrary.rawJsonFor(context, bundledId)
            ?: throw GenerationException(
                "This transition is hand-coded and can't be customised — generate a new one instead",
            )
        val newId = UUID.randomUUID().toString()
        val dir = TransitionLibrary.userDir(context)
        try {
            File(dir, "$newId.json").writeText(current, Charsets.UTF_8)
            val sourceTitle = TransitionLibrary.all(context)
                .firstOrNull { it.id == bundledId }?.title ?: bundledId
            val meta = JSONObject().apply {
                put("title", "Custom $sourceTitle")
                put("prompt", "Customised from built-in transition: $sourceTitle ($bundledId)")
                put("createdAt", System.currentTimeMillis())
            }
            File(dir, "$newId.meta.json").writeText(meta.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            File(dir, "$newId.json").delete()
            File(dir, "$newId.meta.json").delete()
            throw GenerationException("Failed to fork transition: ${e.message}")
        }
        return newId
    }

    @Throws(GenerationException::class)
    fun iterate(context: Context, id: String, instruction: String): String =
        iterateWithFork(context, id, instruction).id

    /** Variant of [iterate] that also reports whether a bundled fork
     *  happened. Callers that need to update the active-transition pref
     *  on fork (the Command Bar tool runner) use this; legacy callers
     *  that just want the id can use [iterate]. */
    @Throws(GenerationException::class)
    fun iterateWithFork(
        context: Context, id: String, instruction: String,
    ): IterateResult {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what should change")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Instruction too long (${trimmed.length}/$PROMPT_MAX)")
        }
        // Bundled transitions are read-only; auto-fork into a writable
        // user copy on first edit. The caller is responsible for swapping
        // pageTransitionStyle to the new id so the user actually sees the
        // customised version.
        var forked = false
        val workingId = if (!TransitionLibrary.isUserGenerated(id)) {
            forked = true
            forkBundledTransition(context, id)
        } else id
        val current = TransitionLibrary.rawJsonFor(context, workingId)
            ?: throw GenerationException("Transition not found")

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val userMsg = buildString {
            append("Here is the current transition spec:\n```json\n")
            append(current)
            append("\n```\n\nThe user wants this change:\n")
            append(trimmed)
            append("\n\nReturn the FULL updated JSON spec, no commentary, no markdown fences.")
        }
        val raw = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(AiService.Message("user", userMsg)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Iteration failed")
        }
        val newJson = sanitise(raw)
        TransitionSpec.parse(newJson)
            ?: throw GenerationException("AI output didn't compile to a valid spec")
        try {
            File(TransitionLibrary.userDir(context), "$workingId.json")
                .writeText(newJson, Charsets.UTF_8)
            // Drop the cached compiled spec so the new JSON is picked up on
            // the next page swipe (without this the cell still animates with
            // the old spec until process restart).
            TransitionLibrary.invalidate(workingId)
        } catch (e: Throwable) {
            throw GenerationException("Failed to save: ${e.message}")
        }
        return IterateResult(workingId, forked)
    }

    /** Build the full prompt the manual-AI flow copies to the clipboard.
     *  Same content the automated flow sends to Anthropic — caller pastes
     *  it into ChatGPT / Claude / any external AI and brings the result
     *  back. */
    fun buildManualPrompt(description: String): String =
        SYSTEM_PROMPT.trim() + "\n\n---\n\nTransition description: " + description.trim()

    /** Internal sanitiser exposed so the manual paste path can apply the
     *  same fence-stripping the automated path uses. */
    internal fun sanitiseJson(raw: String): String = sanitise(raw)

    /** Strip ```json … ``` fences AND lop off any commentary before the first
     *  `{` / after the last `}`. Some models prefix "Here's the spec:" no
     *  matter how clearly you ask them not to. */
    private fun sanitise(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1)
                val closer = s.lastIndexOf("```")
                if (closer >= 0) s = s.substring(0, closer)
                s = s.trim()
            }
        }
        val open = s.indexOf('{')
        val close = s.lastIndexOf('}')
        return if (open >= 0 && close > open) s.substring(open, close + 1) else s
    }

    private val SYSTEM_PROMPT = """
You design page transitions for an Android home-screen launcher. Output is a JSON object with optional `page` and `cell` blocks. Each block maps view properties to math expressions evaluated per frame as the user swipes between home pages.

Variables (always available):
  p   — swipe position in [-1..+1]; 0 = current page, ±1 = adjacent page just off-screen
  w, h — page width / height in pixels
  pi  — π

Cell-block extras (running once per icon on the page):
  cellCol, cellRow   — approximate grid coordinates of the cell
  cellIndex          — flat index in the page (0..cellTotal-1)
  cellTotal          — number of cells on this page
  cols, rows         — grid dimensions (e.g. 5, 6)

Functions: abs, sin, cos, tan, sqrt, sign, min(a,b), max(a,b), pow(x,y), clamp(x,lo,hi), lerp(a,b,t), mod(x,y).

Allowed properties (page or cell):
  alpha, scaleX, scaleY, translationX, translationY, translationZ, rotation, rotationX, rotationY, pivotX, pivotY

Style guidance:
  - Keep it snappy. Most animations should look complete by abs(p) ≈ 0.7.
  - Don't drive alpha to zero faster than abs(p) — the page disappears too early.
  - Rotations beyond ±60° feel chaotic on a phone.
  - Translations beyond ±w * 0.6 leave the page out-of-frame too long.
  - Cell-block effects are most striking when each cell offsets / rotates by an amount tied to its position (cellCol, cellRow, cellIndex). Per-cell uniformly equal to per-page is wasteful — use the page block instead.
  - Cells in a 5×6 grid: cellCol ranges 0..4, cellRow ranges 0..5. Use (cellCol - cols/2 + 0.5) and similar to centre values around 0.

Output: ONLY the JSON object. No commentary, no markdown fences.

Example — explosion (cells fly out from centre):
{
  "page": { "alpha": "1 - abs(p)", "translationX": "p * w" },
  "cell": {
    "translationX": "p * w * 0.5 * (cellCol - cols / 2 + 0.5)",
    "translationY": "p * h * 0.4 * (cellRow - rows / 2 + 0.5)",
    "rotation":     "p * 45 * sign(cellCol - cols / 2 + 0.5)",
    "alpha":        "1 - abs(p)"
  }
}

Example — cascade (cells fall away, top-row first):
{
  "page": { "translationX": "p * w" },
  "cell": {
    "translationY": "p * h * 0.5 * (1 - cellRow / rows * 0.7)",
    "alpha":        "clamp(1 - abs(p) * (1 + cellRow / rows * 0.5), 0, 1)"
  }
}

Example — wave (sine across columns):
{
  "page": { "translationX": "p * w" },
  "cell": {
    "translationY": "sin(cellCol * 0.9 + p * pi) * h * 0.06 * abs(p)",
    "rotation":     "sin(cellCol * 0.9 + p * pi) * 6 * abs(p)",
    "alpha":        "1 - 0.4 * abs(p)"
  }
}

CRITICAL: every expression must evaluate to NEUTRAL at p=0 (alpha=1, scale=1, translation=0, rotation=0). When the page is settled the user expects icons in their natural positions. If you use sin/cos/oscillating terms, multiply them by abs(p) (or p) so they fade to 0 at rest. Don't write "sin(cellCol) * 30" — it's non-zero even at p=0; write "sin(cellCol) * 30 * abs(p)" instead.
""".trimIndent()
}
