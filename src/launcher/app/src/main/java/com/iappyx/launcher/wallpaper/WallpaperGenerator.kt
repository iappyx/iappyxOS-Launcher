/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.content.Context
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.HtmlIterator
import com.iappyx.launcher.ai.SecureStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

/**
 * Generates a single self-contained HTML5 wallpaper from a natural-language
 * description, using the same Anthropic credentials the rest of the launcher
 * uses (see [SecureStore]).
 *
 * Output lands as a pair of files in the launcher's `filesDir/wallpapers/`:
 *   - `{id}.html` — the wallpaper payload
 *   - `{id}.json` — metadata `{title, prompt, createdAt}` for the picker
 *
 * The id is a fresh UUID (no truncation, no slug guessing — keeps the
 * filesystem layout boring). [WallpaperLibrary.all] enumerates these alongside
 * the bundled ones.
 *
 * **Threading**: [generate] is synchronous and blocks for the network call;
 * call it off the main thread. UI in [com.iappyx.launcher.widget.WallpaperSheet]
 * runs it on a worker Thread.
 */
object WallpaperGenerator {

    /** Hard cap on prompt length — generous enough for any real creative
     *  brief (typical creation prompts are ~50–500 chars; refine instructions
     *  are usually shorter still), but room to spare for users who want to
     *  paste a long brief, an example HTML snippet they want adapted, or a
     *  detailed multi-paragraph spec. Anthropic's own context is much larger;
     *  this is paste-accident hygiene, not a model limit. */
    private const val PROMPT_MAX = 16000

    class GenerationException(msg: String) : Exception(msg)

    /** Generate, save, return the new wallpaper id (UUID). */
    @Throws(GenerationException::class)
    fun generate(context: Context, prompt: String): String {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what you want first")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Prompt too long (${trimmed.length}/$PROMPT_MAX)")
        }

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val raw = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = systemPromptWithPlugins(context),
                messages = listOf(AiService.Message("user", trimmed)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Generation failed")
        }

        val html = sanitize(raw)
        if (!html.trimStart().startsWith("<")) {
            throw GenerationException("AI did not return HTML")
        }
        // Reject forbidden Web APIs before persisting. The wallpaper
        // sandbox rejects fetch / navigator.geolocation / etc. silently
        // at runtime — surfacing the violation here means the user gets a
        // clear error and the AI can self-correct on the next turn.
        try { com.iappyx.launcher.ai.ForbiddenApiCheck.assertClean(html) }
        catch (e: IllegalStateException) {
            throw GenerationException(e.message ?: "AI used forbidden Web APIs")
        }

        val id = UUID.randomUUID().toString()
        val dir = WallpaperLibrary.userDir(context)
        try {
            File(dir, "$id.html").writeText(html, Charsets.UTF_8)
            // Prefer the <title> tag the AI puts in <head> (clean, evocative);
            // fall back to a prompt-derived smart title if it's missing.
            val title = extractHtmlTitle(html) ?: smartTitle(trimmed)
            val meta = JSONObject().apply {
                put("title", title)
                put("prompt", trimmed)
                put("createdAt", System.currentTimeMillis())
            }
            File(dir, "$id.json").writeText(meta.toString(), Charsets.UTF_8)
        } catch (e: Throwable) {
            // Roll back partial writes so half-saved entries don't pollute the
            // library and confuse the picker.
            File(dir, "$id.html").delete()
            File(dir, "$id.json").delete()
            throw GenerationException("Failed to save: ${e.message}")
        }
        return id
    }

    /** Re-iterate an existing user-generated wallpaper. Reads its current
     *  HTML, asks the AI to apply [instruction] to it, writes the modified
     *  HTML back over the same id. Returns the id (unchanged) on success.
     *  No new entry, no UUID change — re-binding the manage-tab card causes
     *  the WebView to reload the new HTML. */
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
        val dir = WallpaperLibrary.userDir(context)
        val htmlFile = File(dir, "$id.html")
        if (!htmlFile.exists()) throw GenerationException("Wallpaper file missing")

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val currentHtml = htmlFile.readText(Charsets.UTF_8)
        val systemPrompt = systemPromptWithPlugins(context) + "\n\n---\n\n" + loadIterateProtocol(context)
        // First-attempt iterate model (Haiku by default — fast diff-emit);
        // full_html retry falls back to the higher-quality create model.
        val newHtml = runIteration(
            apiKey = key,
            iterateModel = store.iterateModel,
            fallbackModel = store.anthropicModel,
            systemPrompt = systemPrompt, currentHtml = currentHtml,
            instruction = trimmed, allowFullHtmlOnly = false,
            onProgress = onProgress,
        )
        // Compare-against-old: only flag NEWLY-introduced forbidden APIs;
        // a legacy wallpaper that already has fetch passes through unless
        // the AI added a fresh forbidden call.
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

    /** Mirror of [com.iappyx.launcher.widget.WidgetGenerator.runIteration] —
     *  one round-trip of the iterate loop. Returns the new HTML on success.
     *  If the AI emits `edits` and they fail to apply, retries ONCE with
     *  full_html-only mode to nudge the model toward a complete rewrite. */
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
        val model = if (allowFullHtmlOnly) fallbackModel else iterateModel
        // Cacheable prefix = the embedded HTML (identical across consecutive
        // edits to the same wallpaper, hits Anthropic's 5-min prompt cache).
        // Fresh suffix = the instruction + protocol nudge.
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
                    val why = parsed.reason
                    val msg = if (why != null) "No changes — $why"
                              else "No changes — the AI didn't modify anything. Try being more specific."
                    throw GenerationException(msg)
                }
                val applied = try { HtmlIterator.applyEdits(currentHtml, parsed.edits) }
                catch (e: HtmlIterator.ApplyException) {
                    if (allowFullHtmlOnly) {
                        throw GenerationException(e.message ?: "Edit application failed")
                    }
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
                val cleaned = HtmlIterator.stripCodeFence(parsed.text)
                if (!cleaned.trimStart().startsWith("<")) {
                    throw GenerationException("AI returned malformed response")
                }
                validateOrThrow(cleaned, currentHtml.length)
                cleaned
            }
        }
    }

    private fun buildIterateContent(
        currentHtml: String, instruction: String, allowFullHtmlOnly: Boolean,
    ): Pair<String, String> {
        val prefix = buildString {
            append("Here is the current wallpaper HTML:\n```html\n")
            append(currentHtml)
            append("\n```\n")
        }
        val suffix = buildString {
            append("\nThe user wants this change:\n")
            append(instruction)
            append("\n\n")
            if (allowFullHtmlOnly) {
                append("Your previous response emitted edits that did not match. ")
                append("Use the `full_html` mode instead — emit the COMPLETE updated ")
                append("wallpaper HTML in a single JSON object: ")
                append("""{ "full_html": "<!DOCTYPE html>...<\/html>" }. """)
                append("Preserve <title>.")
            } else {
                append("Respond with JSON only — `edits` for small changes, ")
                append("`full_html` for restructures (see system prompt).")
            }
        }
        return prefix to suffix
    }

    /** Cached on first call; ~3 KB unchanged at runtime. */
    @Volatile private var cachedIterateProtocol: String? = null
    private fun loadIterateProtocol(context: Context): String {
        cachedIterateProtocol?.let { return it }
        val raw = try {
            context.assets.open("wallpaper_iterate_prompt.md").use {
                BufferedReader(InputStreamReader(it)).readText()
            }
        } catch (_: Exception) { ITERATE_FALLBACK_PROTOCOL }
        cachedIterateProtocol = raw
        return raw
    }

    private fun validateOrThrow(html: String, originalLength: Int) {
        try { HtmlIterator.validate(html, originalLength) }
        catch (e: HtmlIterator.ValidationException) {
            throw GenerationException(e.message ?: "Result HTML failed validation")
        }
    }

    private const val ITERATE_FALLBACK_PROTOCOL = """
You are editing an existing wallpaper. Respond with JSON only — no prose, no
markdown fences. Two shapes:
  { "edits": [ { "old_string": "...", "new_string": "..." }, ... ] }
  { "full_html": "<!doctype html>..." }
`old_string` must occur exactly ONCE in the current HTML, character for
character. Edits apply in order. Use `edits` for small changes, `full_html`
only for structural rewrites.
"""

    /** Strip surrounding markdown code fences if the model insists on them. */
    /** Build the full prompt (system rules + user description) that the
     *  manual-AI flow copies to the clipboard for the user to paste into
     *  ChatGPT / Claude / any external AI. Same content the automated
     *  flow sends to Anthropic, just emitted as one big string the user
     *  can ferry across themselves. */
    fun buildManualPrompt(context: android.content.Context, description: String): String =
        systemPromptWithPlugins(context).trim() + "\n\n---\n\nWallpaper description: " + description.trim()

    // PLUGINS: BEGIN — appends enabled-plugin context to the canonical
    // SYSTEM_PROMPT so the AI knows about iappyx.plugin('immich').recent(...)
    // and friends. Empty-string fast-path when no plugins are enabled
    // (no extra tokens spent in the common case).
    private fun systemPromptWithPlugins(context: android.content.Context): String {
        val section = com.iappyx.launcher.plugins.PluginsModule.aggregateAiPrompts(context)
        return if (section.isEmpty()) SYSTEM_PROMPT
        else SYSTEM_PROMPT + "\n\n---\n\n" + section
    }
    // PLUGINS: END

    /** Internal sanitiser exposed so the manual paste path can run the
     *  same markdown-fence stripping the automated path uses. */
    internal fun sanitizeHtml(raw: String): String = sanitize(raw)

    private fun sanitize(raw: String): String {
        val s = raw.trim()
        if (!s.startsWith("```")) return s
        val firstNl = s.indexOf('\n')
        if (firstNl < 0) return s
        val withoutOpener = s.substring(firstNl + 1)
        val closer = withoutOpener.lastIndexOf("```")
        return if (closer >= 0) withoutOpener.substring(0, closer).trim() else withoutOpener.trim()
    }

    /** Picker rows are short — first ~32 chars of the prompt makes for a
     *  recognisable label without bloating the UI. Used only when the
     *  smarter [smartTitle] / `<title>` paths aren't available. */
    private fun titleFrom(prompt: String): String {
        val one = prompt.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= 32) one else one.substring(0, 30) + "…"
    }

    /** Pull the inner text of `<title>...</title>` from the AI's HTML, if any.
     *  Returns null when missing, empty, or the AI left it as an obvious echo
     *  of the prompt. The system prompt now requires this tag, but older
     *  generated wallpapers lacked it — fall back through other paths. */
    internal fun extractHtmlTitle(html: String): String? {
        val m = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        var t = m.groupValues[1].replace(Regex("\\s+"), " ").trim()
        // Decode the only entities likely to appear in a 2-5 word title.
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        if (t.isEmpty()) return null
        if (t.length > 60) return null // AI wrote a sentence — better to fall back
        return t
    }

    /** Derive a clean, recognisable title from a free-form user prompt by
     *  stripping common AI filler ("A live wallpaper that…"), trimming at the
     *  first clause break, title-casing, and capping length. Used both as a
     *  save-time fallback when the HTML has no <title>, and to backfill old
     *  entries whose stored title is just a truncated prompt. */
    internal fun smartTitle(prompt: String): String {
        var s = prompt.replace(Regex("\\s+"), " ").trim()
        // Strip the AI's typical opening filler — case-insensitive.
        val prefixes = listOf(
            "A live wallpaper that displays",
            "A live wallpaper that shows",
            "A live wallpaper that",
            "A wallpaper that displays",
            "A wallpaper that shows",
            "A wallpaper that",
            "Live wallpaper:",
            "Live wallpaper that",
            "Live wallpaper",
            "A wallpaper",
            "A live wallpaper",
        )
        for (p in prefixes) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim().trimStart(':', '-', '—', ',', '.')
                break
            }
        }
        // Looser fallback for prompts the static prefixes didn't catch — e.g.
        // "A live map wallpaper that shows…", "A pulsing nebula wallpaper
        // that…". Find the first occurrence of "wallpaper that ?(shows|displays
        // |has)?" and chop everything up to and including it.
        Regex(
            "(?i)^(?:a |an )?[a-z ,-]*?wallpaper\\s+that\\s+(?:shows?|displays?|has\\s+|features?\\s+)?",
        ).find(s)?.let { m ->
            if (m.range.first == 0) s = s.substring(m.range.last + 1).trim()
        }
        // Also strip any remaining "Show me " / "I want " openers.
        for (p in listOf("Show me ", "I want ", "Make ", "Create ")) {
            if (s.startsWith(p, ignoreCase = true)) { s = s.substring(p.length).trim(); break }
        }
        // Cut at first clause break — first sentence/clause is usually the
        // most evocative chunk.
        val cutAt = s.indexOfFirst { it == ',' || it == '.' || it == ';' || it == '—' || it == ':' || it == '\n' }
        if (cutAt > 0) s = s.substring(0, cutAt).trim()
        // Strip trailing " live wallpaper" / "wallpaper" / "live" that the AI
        // tacks onto descriptive prompts ("lava lamp live wallpaper" → "lava
        // lamp", "magnetic particles wallpaper" → "magnetic particles").
        s = s.replace(Regex("(?i)\\s+(?:live\\s+)?wallpaper\\s*$"), "")
            .replace(Regex("(?i)\\s+live\\s*$"), "")
            .trim()
        // Title Case — capitalize each word's first letter.
        s = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word[0].uppercaseChar() + word.substring(1).lowercase()
        }
        // Cap length without breaking mid-word where possible.
        if (s.length > 36) {
            val cap = s.substring(0, 33).substringBeforeLast(' ', s.substring(0, 33))
            s = "$cap…"
        }
        return s.ifBlank { "Generated wallpaper" }
    }

    /** Wallpaper-specific system prompt. Deliberately compact — every example
     *  ships in the request and gets prompt-cached, so trim ruthlessly. */
    private val SYSTEM_PROMPT = """
You are designing a full-screen Android live wallpaper as ONE self-contained HTML5 document.

Hard constraints:
- ONE HTML file. NO external HTML-time resources: no <link>, no <script src>, no <img src>, no <link rel=stylesheet>, no @font-face URLs.
- For ANY runtime network request — RSS feeds, REST/JSON APIs, scraping, weather, news, both `http://` and `https://` — you MUST use `iappyxHttpClient.request(JSON.stringify({url}), 'cbName')`. NEVER use `fetch()` or `XMLHttpRequest`. The wallpaper WebView is sandboxed and cross-origin requests via fetch silently fail on most public servers due to browser CORS. The bridge is a native call that bypasses CORS and works for every URL regardless of scheme. Rule of thumb: any URL that is NOT a `data:` / `blob:` URL must go through the bridge.
- Do NOT use other Web standards as substitutes for the iappyx bridges either: no `navigator.geolocation` (use `iappyxLocation.getLocation`), no `Notification` API, no bare `WebSocket` (use `iappyxHttpClient` polling), no `await` on bridge calls (they're not Promises — use `iappyx.cb()` helper or the cbId / fnName patterns shown below).
- Full-bleed. <body> and any <canvas> fill the viewport with no margins or chrome.
- Use requestAnimationFrame. Pause work when window.iappyx.onVisibility(false) fires.
- Runs inside an Android WebView. Avoid APIs that require a real browser context.

The wallpaper sits BEHIND home-screen icons and labels — keep contrast moderate, avoid harsh white, and prefer slow, atmospheric motion. Users see this for hours.

You can also actively engage with the icons: `iappyxLayout` exposes their bounding boxes (privacy-safe — rectangles only, no app or widget identity) so motion can avoid them, orbit around them, trace their edges, fill the gaps between them, or use them as obstacles for particles. Reach for it whenever an effect would feel more alive on a populated home screen than on a blank canvas. Updates fire via `iappyx.onLayoutChanged` whenever the user adds / moves / removes anything, so the wallpaper adapts in real time. See the "Layout-aware animations" section below.

A bridge `window.iappyx` is available.

Pull-style (call from your code):
  iappyx.log(msg)
  iappyx.enableAccelerometer(true)   // request tilt events

Push-style (assign these to functions if you want them):
  iappyx.onPageOffset     = (x) => ...    // x in 0..1 as user swipes home pages
  iappyx.onAccelerometer  = (x,y,z) => ...// m/s²
  iappyx.onVisibility     = (v) => ...    // false while a foreground app is open
  iappyx.onLayoutChanged  = (layout) => ...// home grid + dock bounding boxes — see "Layout-aware animations" below

The full launcher toolkit is on these globals: iappyxStorage, iappyxDevice, iappyxSensor, iappyxLocation, iappyxCalendar, iappyxSqlite, iappyxHttpClient, iappyxMedia, iappyxCapabilities, iappyxLayout.

CRITICAL — these are NOT Web-standard APIs. Do NOT use navigator.geolocation, do NOT invent method names like getCurrentPosition / watchPosition / fetch on these globals. The methods below are EXACT.

Two callback patterns:

(1) **Async one-shot** — method takes a `cbId` string. Use the iappyx.cb() Promise helper:

  // returns {ok:true, lat, lon, accuracy, altitude, speed, bearing}  or  {ok:false, error}
  const r = await iappyx.cb(id => iappyxLocation.getLocation(id));
  if (r.ok) drawAt(r.lat, r.lon, r.accuracy);

(2) **Continuous events** — method takes a global function NAME (string). The bridge calls that named function repeatedly:

  window.onAccel = (data) => { /* {x, y, z, timestamp} */ };
  iappyxSensor.startAccelerometer('window.onAccel');
  // …later: iappyxSensor.stopAccelerometer();

For accelerometer specifically, prefer the wallpaper-native simple API (already shown above):
  iappyx.enableAccelerometer(true); iappyx.onAccelerometer = (x,y,z) => ...

Method signatures (these are EXACT — do not invent variants):

  // ── Location ────────────────────────────────────────────────
  iappyxLocation.getLocation(cbId)                 → cbId pattern. Result: {ok, lat, lon, accuracy, altitude, speed, bearing} or {ok:false, error}.
  iappyxLocation.hasBackgroundLocation()           → SYNC boolean.

  // ── HTTP ────────────────────────────────────────────────────
  iappyxHttpClient.request(optionsJsonString, cbId)→ cbId pattern. opts: {url, method, headers, body, timeoutMs}. Result: {ok, status, headers, body}.

  // ── Storage (key/value + files) ─────────────────────────────
  iappyxStorage.save(key, value)                   → SYNC void. value is a string.
  iappyxStorage.load(key)                          → SYNC string (or null).
  iappyxStorage.saveFile(filename, content)        → SYNC void. content is a string.
  iappyxStorage.loadFile(filename)                 → SYNC string (or null).

  // ── Calendar ────────────────────────────────────────────────
  iappyxCalendar.getEvents(cbId, startMs, endMs)   → cbId pattern. ms args are STRINGS of milliseconds. Result: {ok, events:[…]} or {ok:false, error}.

  // ── Sensors (fireEvent — register a global function name) ───
  iappyxSensor.startAccelerometer(fnName)          → fireEvent pattern. fnName receives {x,y,z,t}.
  iappyxSensor.startGyroscope(fnName)              → fireEvent pattern. fnName receives {x,y,z,t}.
  iappyxSensor.startMagnetometer(fnName)           → fireEvent pattern. fnName receives {x,y,z,t}.
  iappyxSensor.startCompass(fnName)                → fireEvent pattern. fnName receives {azimuth,pitch,roll}.
  iappyxSensor.stop()                              → SYNC void. Stops ALL active sensors. There is NO per-sensor stop — call this once when tearing down.

  // ── SQLite (BOTH SYNC — return JSON strings, no cbId) ───────
  iappyxSqlite.exec(sql, paramsJsonOrEmpty)        → SYNC string. Use for INSERT/UPDATE/DELETE/CREATE. Result: '{"ok":true}' or '{"ok":false,"error":...}'. Pass "" for paramsJsonOrEmpty if no params, otherwise a JSON-array string like '["v1", 42]'.
  iappyxSqlite.query(sql, paramsJsonOrEmpty)       → SYNC string. Use for SELECT. Result: '{"ok":true,"rows":[…]}'. JSON.parse it.

  // ── Device — SYNC, return JSON STRINGS, you must JSON.parse ─
  iappyxDevice.getDeviceInfo()                     → SYNC string. JSON.parse it. Fields: brand, model, sdk, battery (0-100, -1 if unknown), charging (bool), screenWidth, screenHeight, density, language. Battery state lives HERE — there is no top-level batteryLevel/isCharging getter.
  iappyxDevice.getConnectivity()                   → SYNC string. JSON.parse it. Fields: connected (bool), type ("wifi"|"cellular"|"ethernet"|"none"), metered (bool).
  iappyxDevice.getThemeColors()                    → SYNC string. JSON.parse it. Fields: isDark, primary, primaryLight, primaryDark, secondary, tertiary, neutral, background, surface, dynamic.
  iappyxDevice.getPackageName()                    → SYNC string.
  iappyxDevice.getAppName()                        → SYNC string.

  // ── Media (recent photos from the device gallery) ───────────
  // Requires READ_MEDIA_IMAGES (33+) or READ_EXTERNAL_STORAGE. The user
  // grants it via Launcher Settings → Wallpaper Toolkit → Photo library;
  // the wallpaper inherits that grant. Until granted, calls return
  // {ok:false, error:"permission denied"}. Use iappyxCapabilities to
  // check at startup and show a graceful fallback (e.g. solid colour
  // tile mosaic) if the perm isn't there yet.
  iappyxMedia.getImages(cbId, limit)               → cbId pattern. limit max 100. Result: {ok, images:[{id, name, date, size, width, height, mime}, …]} ordered newest first.
  iappyxMedia.loadThumbnail(cbId, id)              → cbId pattern. id is a NUMBER from getImages. Result: {ok, dataUrl:"data:image/jpeg;base64,…"} — 320×320 max, fast to load. Use this for collages; loadImage is overkill.
  iappyxMedia.loadImage(cbId, id)                  → cbId pattern. Full-res (max 1200px wide) base64 JPEG dataUrl. Use sparingly — each call returns ~50-200 KB; load via thumbnails first and only upgrade selected hero photos.

  // ── Capabilities ────────────────────────────────────────────
  iappyxCapabilities.get()                         → SYNC string. JSON.parse it. Fields: bridges (object of name→bool), perms (object of name→"granted"|"denied"|"unasked"). There is NO has(name) shortcut — read the parsed object.

  // ── Layout (bounding boxes of home grid + dock — for collision-aware animations) ─
  iappyxLayout.get()                               → SYNC string. JSON.parse it. Read the current layout snapshot. Always returns a parseable object — `cells` and `dock` are arrays even before the launcher has sent its first update.
  iappyx.onLayoutChanged = (layout) => {…}         → push event. Fires on commit (drag-drop release, page add/remove, app install) — NOT during a swipe. The `layout` argument is already parsed (no JSON.parse needed in the handler).

Layout-aware animations:

MANDATORY when the user's brief uses ANY of these words: icons, widgets, objects, cells, gaps, around, between, edges, dock, home screen, layout, blur (around), bounce, orbit, avoid, obstacles, particles, fluid, lava, magnetic, repel, attract, react. Reach for `iappyxLayout` — do not generate the wallpaper as if the home grid weren't there.

NEVER write placeholder comments like `// read current layout here`, `// get icon positions`, `// TODO: bounding boxes`. The bridge is REAL and SYNCHRONOUS — wire it up with the snippet below. Comments-instead-of-code is a hard failure.

Drop-in starter (paste verbatim, then add your effect to the `step()` body):

  let layout = JSON.parse(iappyxLayout.get());
  let pageOffset = 0;
  let visible = true;
  iappyx.onLayoutChanged = (next) => { layout = next; };
  iappyx.onPageOffset    = (x)    => { pageOffset = x; };
  iappyx.onVisibility    = (v)    => { visible = !!v; };

  // Returns the live screen-space rects (cells + dock) for THIS frame,
  // accounting for an in-progress swipe. Call this once per RAF tick.
  function liveRects() {
    const out = [];
    const pw = layout.pageWidth || 0;
    const fracHome = pageOffset * Math.max(1, (layout.pageCount || 1) - 1);
    for (const c of layout.cells || []) {
      out.push({ x: c.x + (c.page - fracHome) * pw, y: c.y, w: c.w, h: c.h });
    }
    for (const d of layout.dock || []) out.push(d);
    return out;
  }

  function step() {
    if (!visible) { requestAnimationFrame(step); return; }
    const rects = liveRects();
    // … your per-frame effect: iterate rects to avoid/orbit/blur/repel them …
    requestAnimationFrame(step);
  }
  requestAnimationFrame(step);

The wallpaper can read where icons / widgets / dock cells are sitting on the home screen and animate around them — RECTANGLES ONLY, no app or widget identity. The shape of `iappyxLayout.get()` parsed:

  {
    screen:     { width, height, density },          // CSS pixels (= deviceWidth / density)
    pageCount:  4,
    pageNames:  ["", "Work", "", "Reading"],          // OPTIONAL — user-given names parallel to pages; empty = unnamed
    pageWidth:  411,                                 // CSS pixels — matches window.innerWidth on a typical phone
    currentPage: 1,                                  // home page the user is currently on (post-swipe)
    systemBars: { top: 30, bottom: 23 },             // status / nav bar heights, CSS pixels
    cells: [{ page: 0, x: 9, y: 76, w: 76, h: 76 }, …],  // home-grid cells (icons + widgets), one entry per occupied placement
    dock:  [{ x: 9, y: 754, w: 76, h: 76 }, …]       // dock slots (no `page` — same on every home page)
  }

When labelling pages in a layout-aware wallpaper (mini-map, breadcrumb dots, page indicators), prefer `pageNames[i]` when non-empty; otherwise fall back to `"Page " + (i+1)`. `pageNames` may be missing entirely on older launcher builds — treat undefined as all-unnamed.

All values are CSS pixels matching the wallpaper's `<canvas>` / DOM coordinate space — i.e. the same units as `window.innerWidth` / `window.innerHeight` / `event.clientX`. The launcher computes positions in device pixels internally then divides by `density` before sending. Set your canvas the standard way (`canvas.width = window.innerWidth; canvas.height = window.innerHeight`) and don't apply any scale; layout values will line up exactly. Empty cells are simply absent — there is no "empty: true" marker — so anywhere not in `cells` or `dock` is free space.

`cells[].page` and `currentPage` are both in HOME-PAGE INDEX space (0..pageCount-1). The AI command panel at pager index 0 is NOT counted — the wallpaper is occluded behind it anyway.

Position-during-swipe formula. The layout snapshot describes cell positions when the pager is RESTING on `currentPage`. During an active swipe, combine with the existing `iappyx.onPageOffset(x)` push event.

CRITICAL: `pageOffset` is in [0, 1] ACROSS THE WHOLE PAGER, NOT per-page. With 4 home pages, the resting offsets are 0, 0.333, 0.666, 1.0 — so multiplying `pageOffset` by `pageWidth` directly is WRONG. Convert to a fractional home-page index first:

  let pageOffset = 0;
  iappyx.onPageOffset = (x) => { pageOffset = x; };  // [0, 1] across all home pages

  function liveX(cell, layout) {
    const fracHome = pageOffset * Math.max(1, layout.pageCount - 1);
    return cell.x + (cell.page - fracHome) * layout.pageWidth;
  }

Dock cells have no `page` field — they're fixed in screen space. Skip the swipe transform for them.

Privacy contract. The bridge exposes geometry only. Never assume you can read app names, widget contents, or icon types — that is intentionally not exposed. Treat every cell as an opaque rectangle.

Examples — copy these patterns exactly:

  // ── BATTERY + CHARGING — the most common gotcha. Read carefully. ──
  //
  // ❌ DO NOT use navigator.getBattery(). DO NOT use any Web Battery API,
  //    BatteryManager, navigator.battery.* — none of those work or are
  //    reliable in this WebView. Battery state lives ONLY in
  //    iappyxDevice.getDeviceInfo().
  //
  // ❌ DO NOT read it once at startup and never again. The bridge does not
  //    push battery events; you must POLL on a setInterval — every 5s is
  //    plenty (battery changes slowly).
  //
  // ❌ DO NOT poll inside the requestAnimationFrame loop. That's 60×/sec
  //    of bridge work for a value that changes once a minute.
  //
  // ❌ DO NOT draw a literal battery rectangle that fills up unless the
  //    user explicitly asked for that. Default to ambient metaphors:
  //    liquid filling, particle density, hue saturation, glow intensity,
  //    surface temperature, fog opacity. Anything but a tiny green
  //    rectangle in the corner — that's a status bar, not a wallpaper.
  //
  // ✅ Pattern (copy this skeleton):
  let battery = 50, charging = false, displayBattery = 50;
  function pullBattery() {
    if (!window.iappyxDevice) return;
    try {
      const info = JSON.parse(iappyxDevice.getDeviceInfo());
      if (typeof info.battery === 'number' && info.battery >= 0) battery = info.battery;
      charging = !!info.charging;
    } catch (_) { /* bridge not ready, retry next tick */ }
  }
  pullBattery();
  setInterval(pullBattery, 5000);
  // … then in your animation loop, smooth-interpolate so a 30→55 jump
  // looks like the visual is FILLING, not snapping:
  function step() {
    displayBattery += (battery - displayBattery) * 0.05;
    // … paint using displayBattery (the smoothed value) and `charging`.
    requestAnimationFrame(step);
  }
  requestAnimationFrame(step);

  // SELECT rows from sqlite:
  const r = JSON.parse(iappyxSqlite.query("SELECT * FROM notes WHERE pinned=?", '["1"]'));
  if (r.ok) r.rows.forEach(renderRow);

  // Capability check before using location:
  const caps = JSON.parse(iappyxCapabilities.get());
  if (caps.bridges.location && caps.perms.location === "granted") { … }

  // Async location:
  const loc = await iappyx.cb(id => iappyxLocation.getLocation(id));
  if (loc.ok) drawAt(loc.lat, loc.lon);

  // Bouncing ball that gently bounces off the home-grid cells + dock. Read
  // the layout once on start, refresh on commit, and integrate with the
  // current page-offset on each frame.
  let layout = JSON.parse(iappyxLayout.get());
  let pageOffset = 0;
  iappyx.onLayoutChanged = (next) => { layout = next; };
  iappyx.onPageOffset = (x) => { pageOffset = x; };
  let bx = 200, by = 200, vx = 4, vy = 3;
  function step() {
    bx += vx; by += vy;
    // Convert [0, 1]-across-pager offset to a fractional home-page index
    // BEFORE shifting cells. Skipping this is the most common mistake —
    // cells appear shifted by a full screen-width and balls slide right
    // through them.
    const fracHome = pageOffset * Math.max(1, layout.pageCount - 1);
    const rects = [];
    for (const c of layout.cells) {
      rects.push({ x: c.x + (c.page - fracHome) * layout.pageWidth,
                   y: c.y, w: c.w, h: c.h });
    }
    for (const d of layout.dock) rects.push(d);
    for (const r of rects) {
      if (bx + 20 > r.x && bx - 20 < r.x + r.w && by + 20 > r.y && by - 20 < r.y + r.h) {
        // Pick the shallower overlap axis to reflect on (cleaner-looking bounce).
        const dx = Math.min(bx + 20 - r.x, r.x + r.w - (bx - 20));
        const dy = Math.min(by + 20 - r.y, r.y + r.h - (by - 20));
        if (dx < dy) vx = -vx; else vy = -vy;
      }
    }
    // Window-edge bounce.
    if (bx < 20 || bx > layout.screen.width - 20)  vx = -vx;
    if (by < 20 || by > layout.screen.height - 20) vy = -vy;
    drawBall(bx, by);
    requestAnimationFrame(step);
  }
  requestAnimationFrame(step);

Permissions: iappyxLocation and iappyxCalendar return {ok:false, error:"…"} if the user hasn't granted the permission in Launcher Settings. ALWAYS handle the error path — fall back to a calmer non-data-driven mode rather than hanging.

External JS libraries via cache-then-load pattern: you MAY use libraries like Vanta.js, Three.js, p5.js, anime.js, GSAP, Tone.js etc. Fetch once via the bridge, cache to disk, run from cache thereafter. First boot needs internet; subsequent boots are fully offline. Use this exact pattern (NOT bare <script src=> tags — those work only online with no cache):

  function runScript(code){var s=document.createElement('script');s.textContent=code;document.head.appendChild(s);}
  async function loadLib(url, filename){
    let code = iappyxStorage.loadFile(filename);
    if (code && code.length > 100) { runScript(code); return; }
    const r = await iappyx.cb(id => iappyxHttpClient.request(JSON.stringify({url, method:'GET', timeoutMs:30000}), id));
    if (!r.ok || !r.body) throw new Error('CDN fetch failed: ' + (r.error || 'no body'));
    iappyxStorage.saveFile(filename, r.body);
    runScript(code = r.body);
  }
  // usage:
  await loadLib('https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js', 'three-0160.js');
  await loadLib('https://cdn.jsdelivr.net/npm/vanta@0.5.24/dist/vanta.net.min.js', 'vanta-net-0524.js');

Always pin CDN libraries to explicit versions (`@0.160.0`, NOT `@latest`). Use `cdn.jsdelivr.net/npm/` or `cdnjs.cloudflare.com/ajax/libs/` — those serve raw `.min.js` reliably; `unpkg.com` also works. Use `runScript()` (script tag injection), NOT `eval()` — libraries using `var` at top level won't register as globals with eval.

When the lib needs to attach to an element (Vanta, Three.js renderer): create the element first, then call into the library after `loadLib` resolves. Always have a calm fallback when the first-boot CDN fetch fails (no internet) — fall back to a static gradient instead of leaving a black screen.

A simple example to model your structure on:

<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;padding:0;background:#000;overflow:hidden;">
<canvas id="c" style="position:fixed;inset:0;width:100vw;height:100vh;display:block;"></canvas>
<script>
(function(){
  const c=document.getElementById('c'), x=c.getContext('2d');
  function fit(){c.width=innerWidth;c.height=innerHeight;} fit(); onresize=fit;
  let p=0,t=0,visible=true;
  if(window.iappyx){
    iappyx.onPageOffset=v=>p=v;
    iappyx.onAccelerometer=(ax,ay)=>{ t=t*0.85+ax*0.15; };
    iappyx.onVisibility=v=>visible=!!v;
    iappyx.enableAccelerometer(true);
  }
  function tick(){
    if(!visible){requestAnimationFrame(tick);return;}
    /* draw something slow + moody, react to p / t */
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
})();
</script></body></html>

Title: include a short, evocative <title> tag in <head> — 2 to 5 words, Title Case, no punctuation, NO ellipsis. Examples: "Magnetic Particles", "Tides at Dawn", "Hue Drift", "Lava Lamp", "Aurora Field". The launcher displays this as the wallpaper's name in the picker. Don't echo the user's prompt; pick a real name.

Output: ONLY the HTML document starting with <!doctype html>. No markdown fences. No commentary before or after.
""".trimIndent()
}
