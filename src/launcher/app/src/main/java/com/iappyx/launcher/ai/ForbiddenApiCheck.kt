/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

/**
 * Static safety net — blocks AI-generated widget / wallpaper HTML from
 * shipping Web-standard APIs that don't work in the launcher's WebView
 * sandbox. The system prompts already tell the AI not to use these, but
 * prompts are best-effort; this is the deterministic floor.
 *
 * **Scanning is JS-aware, not just regex-over-HTML.** Three layers:
 *
 *   1. Extract executable JavaScript surfaces from the HTML:
 *      - `<script>` block contents (excluding non-JS types like
 *        `application/json` and `text/template`).
 *      - `on*` event-handler attribute values (`onclick`, `onload`, …).
 *      - `href="javascript:..."` values.
 *      Everything else (HTML text, button labels, regular attributes,
 *      CSS, comments outside scripts) is discarded.
 *   2. Strip JS string literals + comments from the extracted source so
 *      tokens that only appear in strings or comments don't false-
 *      positive. Comments → blanked. Single/double/template strings →
 *      blanked (template `${…}` interpolations are stripped along with
 *      the rest — see "Known limits" below).
 *   3. Run the forbidden-API regexes against the stripped JS skeleton.
 *      Word-boundary anchors stay as a final safety belt against state-
 *      machine bugs.
 *
 * Patterns covered:
 *  - `fetch(` and `XMLHttpRequest` — CORS-blocked on most public servers
 *    from `https://widget.local/`. Use `iappyx.httpClient.request()`.
 *  - `navigator.geolocation` — Web Geolocation isn't wired through the
 *    bridge. Use `iappyx.location.*`.
 *  - `new Notification(` / `Notification.requestPermission` — Web
 *    Notifications API isn't bound. Use `iappyx.notification.*`.
 *  - `new WebSocket(` — sockets to external services use `iappyx.tcp.*`
 *    or `iappyx.httpClient.*` polling.
 *
 * For [iterate] callers, pass the previous HTML as `oldHtml` so existing
 * pre-rule code (legacy widgets that already contain a `fetch()` call)
 * doesn't get rejected on every edit. Only NEWLY-introduced violations
 * fail the check; pre-existing ones pass through. The user can fix legacy
 * code by explicitly asking "replace fetch with the bridge".
 *
 * **Known limits (intentional):**
 *  - Template-literal `${expr}` content is stripped along with the rest
 *    of the template. A `` `${someVar.fetch()}` `` would slip through.
 *    Acceptable: AI rarely uses templates this way; correct handling
 *    would need a recursive parser.
 *  - JS regex literals (`/fetch\(/`) aren't distinguished from division.
 *    A literal `/fetch\(/` would false-positive. Acceptable: AI almost
 *    never emits regexes that match these patterns.
 *  - `</script>` inside a JS string ends the block early in the simple
 *    HTML regex. AI-generated code typically writes `"<\/script>"` so
 *    this is rare; if hit, content after the misread end-tag is missed
 *    (soft regression — could allow a forbidden API through). Worth
 *    revisiting if it ever bites.
 */
object ForbiddenApiCheck {

    /** A forbidden pattern + the human-readable message we surface when we
     *  catch it. The message hint includes the bridge replacement so the
     *  AI sees the right answer when the error round-trips through the
     *  Command Bar's tool-result loop. */
    private data class Rule(
        val name: String,
        val regex: Regex,
        val replacement: String,
    )

    /** Order matters only for error-message readability — first match wins
     *  the "use X instead" hint. Each regex anchors on word boundaries so
     *  a variable named `prefetchData` or a comment like `// no fetching`
     *  doesn't false-positive. */
    private val RULES = listOf(
        Rule(
            name = "fetch()",
            regex = Regex("""\bfetch\s*\("""),
            replacement = "iappyx.httpClient.request(JSON.stringify({url}), 'cb')",
        ),
        Rule(
            name = "XMLHttpRequest",
            regex = Regex("""\bnew\s+XMLHttpRequest\b|\bXMLHttpRequest\s*\("""),
            replacement = "iappyx.httpClient.request(JSON.stringify({url}), 'cb')",
        ),
        Rule(
            name = "navigator.geolocation",
            regex = Regex("""\bnavigator\s*\.\s*geolocation\b"""),
            replacement = "iappyx.location.getLocation('cb')",
        ),
        Rule(
            name = "new Notification(",
            regex = Regex("""\bnew\s+Notification\s*\("""),
            replacement = "iappyx.notification.show(...)",
        ),
        Rule(
            name = "Notification.requestPermission",
            regex = Regex("""\bNotification\s*\.\s*requestPermission\b"""),
            replacement = "iappyx.notification.* (no permission prompt needed)",
        ),
        Rule(
            name = "new WebSocket(",
            regex = Regex("""\bnew\s+WebSocket\s*\("""),
            replacement = "iappyx.tcp.* or iappyx.httpClient.* polling",
        ),
    )

    /** `<script ATTRS>BODY</script>` — captures attrs (group 1) and body
     *  (group 2). DOTALL so the body can span newlines; case-insensitive
     *  so `<SCRIPT>` is caught too. The body match is non-greedy so two
     *  separate scripts on one page don't merge into one giant block. */
    private val SCRIPT_REGEX = Regex(
        """<script\b([^>]*)>([\s\S]*?)</script\s*>""",
        RegexOption.IGNORE_CASE,
    )

    /** Pulls the `type` attribute value (if any) out of a script tag's
     *  attribute string. Used to skip non-JS scripts like
     *  `<script type="application/json">{ data }</script>` — those are
     *  data, not code, and a literal "fetch" inside them is fine. */
    private val TYPE_ATTR_REGEX = Regex(
        """\btype\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    /** Inline event-handler attributes, double-quoted form. The attribute
     *  name `on[a-z]+` is captured group 1; the JS source is group 2.
     *  Restricted to lowercase to avoid catching unrelated attributes like
     *  `lang="onomatopoeia"`. */
    private val ON_ATTR_DOUBLE = Regex(
        """\son[a-z]+\s*=\s*"([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    /** Same as above, single-quoted form. Most AI output is double-quoted
     *  but we cover both for robustness. */
    private val ON_ATTR_SINGLE = Regex(
        """\son[a-z]+\s*=\s*'([^']*)'""",
        RegexOption.IGNORE_CASE,
    )

    /** `href="javascript:..."` — the JS source after the scheme is group 1
     *  (double-quoted) or group 2 (single-quoted). Rare but legitimately
     *  executable so we scan it. */
    private val JS_HREF_DOUBLE = Regex(
        """href\s*=\s*"javascript:([^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val JS_HREF_SINGLE = Regex(
        """href\s*=\s*'javascript:([^']*)'""",
        RegexOption.IGNORE_CASE,
    )

    /** Walk [html], extract every region that's actually executable JS,
     *  strip strings + comments from each region, return the concatenated
     *  result. The output isn't valid JS (regions are joined with
     *  newlines and have blanked-out spans) — it's only for regex
     *  scanning. Empty output means there's no code at all. */
    private fun extractJsForScanning(html: String): String {
        val sb = StringBuilder()

        // 1) <script> bodies, filtered to JS-only types.
        SCRIPT_REGEX.findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            if (!isJsScriptTag(attrs)) return@forEach
            sb.append(stripJsLiterals(m.groupValues[2]))
            sb.append('\n')
        }

        // 2) on* event-handler attribute values. Double-quoted first,
        //    then single-quoted — both regex passes find disjoint matches
        //    so order doesn't matter, just need to do both.
        for (re in arrayOf(ON_ATTR_DOUBLE, ON_ATTR_SINGLE)) {
            re.findAll(html).forEach { m ->
                val v = m.groupValues[1]
                if (v.isEmpty()) return@forEach
                sb.append(stripJsLiterals(v))
                sb.append('\n')
            }
        }

        // 3) javascript: hrefs.
        for (re in arrayOf(JS_HREF_DOUBLE, JS_HREF_SINGLE)) {
            re.findAll(html).forEach { m ->
                val v = m.groupValues[1]
                if (v.isEmpty()) return@forEach
                sb.append(stripJsLiterals(v))
                sb.append('\n')
            }
        }

        return sb.toString()
    }

    /** True if a `<script>` tag's attribute string identifies it as
     *  executable JavaScript. Missing `type` defaults to JS (HTML5
     *  default). Empty `type=""` is also JS. Anything else is JS only
     *  if it's `text/javascript`, `application/javascript`, or `module`
     *  (case-insensitive). */
    private fun isJsScriptTag(attrs: String): Boolean {
        val typeMatch = TYPE_ATTR_REGEX.find(attrs) ?: return true
        val t = typeMatch.groupValues[1].trim().lowercase()
        if (t.isEmpty()) return true
        return when (t) {
            "text/javascript", "application/javascript", "module" -> true
            else -> false
        }
    }

    /** Char-by-char state machine that replaces JS string literals and
     *  comments with spaces (newlines preserved so any future error-line
     *  reporting stays accurate). Output length matches input length —
     *  every original character is replaced with exactly one output
     *  character, never adding or removing. The forbidden-API regex then
     *  runs over a structurally identical skeleton with strings/comments
     *  blanked. */
    private fun stripJsLiterals(js: String): String {
        val n = js.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = js[i]
            val next = if (i + 1 < n) js[i + 1] else ' '
            when {
                // Line comment: `// ... \n` — emit spaces up to (but not
                // including) the newline; the newline itself drops to the
                // next iteration as a normal char.
                c == '/' && next == '/' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && js[i] != '\n') {
                        out.append(' '); i++
                    }
                }
                // Block comment: `/* ... */`. Preserve embedded newlines.
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n) {
                        if (i + 1 < n && js[i] == '*' && js[i + 1] == '/') {
                            out.append("  "); i += 2; break
                        }
                        out.append(if (js[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                // Single- and double-quoted strings. Escape sequences
                // (`\'`, `\"`, `\\`, `\n`, …) advance two chars at a time
                // so a `\"` inside a double-quoted string doesn't end it.
                c == '\'' -> i = consumeQuotedString(js, i, '\'', out)
                c == '"' -> i = consumeQuotedString(js, i, '"', out)
                // Template literal: strip the WHOLE thing (including any
                // ${expr} interpolations). Recursive interpolation parsing
                // would need a real JS parser; we accept the rare miss.
                c == '`' -> i = consumeQuotedString(js, i, '`', out)
                // Anything else: passes through verbatim.
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Consume a string literal starting at [start] (which points at the
     *  opening quote of [quote]), emit blank spaces (newlines preserved),
     *  return the index just past the closing quote. If the string never
     *  closes (truncated source), consume to end-of-input. */
    private fun consumeQuotedString(
        js: String, start: Int, quote: Char, out: StringBuilder,
    ): Int {
        out.append(' ') // opening quote
        var i = start + 1
        val n = js.length
        while (i < n) {
            val ch = js[i]
            // Backslash escapes the next character regardless of what it
            // is. Two output chars per escape sequence to keep the length
            // matching the input.
            if (ch == '\\' && i + 1 < n) {
                out.append("  "); i += 2; continue
            }
            if (ch == quote) {
                out.append(' '); return i + 1
            }
            out.append(if (ch == '\n') '\n' else ' ')
            i++
        }
        return i
    }

    /** Scan [html] for forbidden API uses. When [oldHtml] is provided
     *  (iterate path), only patterns that appear in [html] but NOT in
     *  [oldHtml] are flagged — pre-existing legacy uses pass through.
     *  Both inputs go through the JS-extraction + literal-stripping
     *  pipeline before regexes run.
     *
     *  Returns the list of violations: each entry is a one-line
     *  `"<api>: use <replacement>"` string ready to embed in an error
     *  message. Empty list = clean. */
    fun scan(html: String, oldHtml: String? = null): List<String> {
        val newJs = extractJsForScanning(html)
        val oldJs = oldHtml?.let { extractJsForScanning(it) }
        val out = mutableListOf<String>()
        for (rule in RULES) {
            if (!rule.regex.containsMatchIn(newJs)) continue
            if (oldJs != null && rule.regex.containsMatchIn(oldJs)) continue
            out.add("${rule.name} → use ${rule.replacement}")
        }
        return out
    }

    /** Convenience for callers — throws [IllegalStateException] with a
     *  pre-formatted message if [scan] finds violations. The throwing
     *  call site re-wraps as the caller's domain exception (e.g.
     *  `WidgetGenerator.GenerationException`) so the Command Bar's tool
     *  result is shaped right. */
    fun assertClean(html: String, oldHtml: String? = null) {
        val v = scan(html, oldHtml)
        if (v.isEmpty()) return
        val joined = v.joinToString("; ")
        throw IllegalStateException(
            "AI used forbidden Web APIs that don't work in the widget sandbox " +
                "($joined). Retry the request — the AI will switch to the bridge.",
        )
    }
}
