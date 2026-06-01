/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

import org.json.JSONObject

/**
 * Diff-edit applier shared by [com.iappyx.launcher.widget.WidgetGenerator] and
 * [com.iappyx.launcher.wallpaper.WallpaperGenerator].
 *
 * Both flows ask the AI to modify an existing HTML document and emit JSON
 * with one of two shapes:
 *
 *  - `{"edits":[{"old_string":"...","new_string":"..."},...]}` — applied as
 *    literal find-and-replace, each `old_string` must occur exactly once.
 *  - `{"full_html":"<!doctype html>..."}` — full document replacement.
 *
 * Anything else (including legacy raw-HTML responses) falls into the
 * [Response.RawHtml] branch so callers can preserve back-compat.
 *
 * Drastically faster than full-rewrite mode for the 80 % case (small edits):
 * a 1-line CSS tweak emits ~50 output tokens instead of ~30 K, ~150× speedup
 * and ~150× cost reduction.
 */
object HtmlIterator {

    sealed class Response {
        data class Edits(val edits: List<Edit>, val reason: String? = null) : Response()
        data class FullHtml(val html: String) : Response()
        /** Fallback: response wasn't recognisable JSON. Treat as raw HTML
         *  (preserves the old behaviour for older / mis-prompted responses). */
        data class RawHtml(val text: String) : Response()
    }

    data class Edit(val oldString: String, val newString: String)

    /**
     * Parse the AI's iteration response into one of [Response]'s variants.
     * Strips an optional surrounding ```...``` code fence first; any
     * response that doesn't start with `{` after that → treated as raw HTML.
     */
    fun parseResponse(raw: String): Response {
        val candidate = raw.trim().let { stripCodeFence(it) }
        if (!candidate.startsWith("{")) return Response.RawHtml(candidate)
        return try {
            val obj = JSONObject(candidate)
            when {
                obj.has("edits") -> {
                    val arr = obj.getJSONArray("edits")
                    val list = mutableListOf<Edit>()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val oldS = e.optString("old_string", "")
                        val newS = e.optString("new_string", "")
                        // Empty old_string would match every position. Reject.
                        // Empty new_string IS valid (= deletion).
                        if (oldS.isEmpty()) return Response.RawHtml(candidate)
                        list.add(Edit(oldS, newS))
                    }
                    val reason = obj.optString("reason", "").takeIf { it.isNotBlank() }
                    Response.Edits(list, reason)
                }
                obj.has("full_html") -> {
                    val html = obj.optString("full_html", "")
                    if (html.isBlank()) Response.RawHtml(candidate)
                    else Response.FullHtml(html)
                }
                else -> Response.RawHtml(candidate)
            }
        } catch (_: Throwable) {
            Response.RawHtml(candidate)
        }
    }

    /** Apply each edit in order. Each `old_string` must occur exactly once
     *  in the (currently mutated) HTML; if not, we throw with the offending
     *  edit's index so the caller can surface it / retry with full_html.
     *  We deliberately use unique-match semantics — same as Claude Code's
     *  Edit tool — so the AI can't accidentally fan an edit out across
     *  multiple unrelated places.
     *
     *  @throws ApplyException with a human-readable description on miss/ambiguity.
     */
    @Throws(ApplyException::class)
    fun applyEdits(html: String, edits: List<Edit>): String {
        var result = html
        for ((i, edit) in edits.withIndex()) {
            val count = countOccurrences(result, edit.oldString)
            when {
                count == 0 -> throw ApplyException(
                    "Edit ${i + 1}/${edits.size} did not match — '${
                        edit.oldString.take(80)
                    }${if (edit.oldString.length > 80) "…" else ""}'",
                )
                count > 1 -> throw ApplyException(
                    "Edit ${i + 1}/${edits.size} matched $count places (must be unique) — '${
                        edit.oldString.take(80)
                    }${if (edit.oldString.length > 80) "…" else ""}'",
                )
            }
            val idx = result.indexOf(edit.oldString)
            result = result.substring(0, idx) + edit.newString +
                result.substring(idx + edit.oldString.length)
        }
        return result
    }

    /** Sanity-check the post-edit HTML. Catches catastrophic mistakes
     *  (the AI returned something that isn't HTML, or wiped the document
     *  via an over-broad delete). NOT a strict validator — we want artefacts
     *  to load even if their HTML has minor issues like a leading comment
     *  or a missing doctype.
     *
     *  @throws ValidationException with a human-readable description.
     */
    @Throws(ValidationException::class)
    fun validate(html: String, originalLength: Int) {
        // Look for an `<html` tag anywhere in the first 1 KB. This is
        // permissive on purpose: real artefacts in the showcase start with
        // a build-stamp comment ("<!-- Built with iappyxOS ... -->") on
        // line 1, then DOCTYPE, then <html>. A strict prefix-match-only
        // check rejects valid documents.
        val head = html.take(1024).lowercase()
        if (!head.contains("<html")) {
            throw ValidationException("AI did not return HTML")
        }
        if (!html.contains("</html>", ignoreCase = true)) {
            throw ValidationException("Result HTML is missing </html> — incomplete response?")
        }
        // Catastrophic-shrink guard: an edit that mistakenly deleted ≥ 50 %
        // of the document is almost certainly wrong.
        if (originalLength > 1000 && html.length < originalLength / 2) {
            throw ValidationException(
                "Result is suspiciously small (${html.length} vs $originalLength chars) — aborting",
            )
        }
    }

    /** Strip a single surrounding ```...``` code fence if present. */
    fun stripCodeFence(s: String): String {
        if (!s.startsWith("```")) return s
        val firstNl = s.indexOf('\n')
        if (firstNl < 0) return s
        val body = s.substring(firstNl + 1)
        val closer = body.lastIndexOf("```")
        return if (closer >= 0) body.substring(0, closer).trim() else body.trim()
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var i = 0
        while (true) {
            val idx = haystack.indexOf(needle, i)
            if (idx < 0) return count
            count++
            i = idx + needle.length
        }
    }

    class ApplyException(msg: String) : Exception(msg)
    class ValidationException(msg: String) : Exception(msg)
}
