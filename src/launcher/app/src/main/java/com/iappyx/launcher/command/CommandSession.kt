/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.command

import android.content.Context
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.AiException
import com.iappyx.launcher.ai.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Conversation state + tool-use loop driver for the AI Command Bar.
 *
 * Owns the running list of Anthropic-format messages. Each user message
 * triggers an AI call; if the model emits tool_use blocks they're executed
 * via [LauncherCommandRunner], the results sent back, and the loop continues
 * until the model returns plain text (or [MAX_TURNS] is reached).
 *
 * The whole loop runs on a single-threaded background executor; UI callbacks
 * are dispatched on the main thread.
 */
class CommandSession(
    private val context: Context,
    private val runner: LauncherCommandRunner,
) {
    /** A line for the on-screen chat. */
    sealed class Line {
        /** [imageBase64] non-null iff the user attached an image. The chat row
         *  decodes it for an inline thumbnail; when persisted, the same bytes
         *  go to SQLite so they survive process kill. */
        data class User(
            val text: String,
            val imageBase64: String? = null,
            val imageMimeType: String? = null,
        ) : Line()
        data class Assistant(val text: String) : Line()
        data class Tool(val name: String, val summary: String) : Line()
        data class Error(val text: String) : Line()
        object Working : Line()
    }

    interface Listener {
        /** New line appended (or "Working" placeholder added/removed). Called on main thread. */
        fun onLine(line: Line)
        fun onWorking(working: Boolean)
        /** Drop every visible row from the UI. Triggered by [clearHistory]
         *  when the user invokes "Clear chat history" from Settings. Default
         *  is a no-op so existing implementations don't break. */
        fun onClear() {}
        /** Cumulative character count streamed by the model for the
         *  currently-running tool call (typically a slow widget/wallpaper
         *  iterate). Fired ~10-20×/sec while a stream is active; the UI
         *  should debounce / coalesce as appropriate. Resets implicitly
         *  via [onWorking] (false) at end-of-loop. Default no-op. */
        fun onProgress(chars: Int) {}
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "iappyx-cmd").apply { isDaemon = true }
    }
    /** Separate single-thread executor for SQLite writes so chat persistence
     *  doesn't queue behind a multi-second AI request. Without this, the
     *  user's most-recent message wouldn't hit disk until the loop returns;
     *  if the launcher process is killed mid-call, that message is lost. */
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "iappyx-cmd-db").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Anthropic messages format. ONLY mutated/read on [executor]'s thread —
     *  callers from main (send / reset) post their work in. JSONArray is not
     *  thread-safe; without this, a concurrent reset+send corrupts history. */
    private val messages = JSONArray()
    /** SQLite-backed visible-chat persistence. Loaded on init; appended to
     *  on every [emitLine]; cleared via [clearHistory]. */
    private val chatDb = ChatDatabase(context)
    /** Lines pulled from [chatDb] at construction time, queued until a
     *  [listener] is attached. Replayed once on listener-set so the UI shows
     *  the resumed conversation as soon as the panel binds. */
    private val pendingHistoryReplay: MutableList<Line> = mutableListOf()
    var listener: Listener? = null
        set(value) {
            field = value
            if (value != null && pendingHistoryReplay.isNotEmpty()) {
                val toReplay = pendingHistoryReplay.toList()
                pendingHistoryReplay.clear()
                main.post {
                    for (line in toReplay) value.onLine(line)
                }
            }
        }

    init {
        // Synchronous DB read at construction. Typical 200-row load is
        // ~30-60 ms even with image rows; the call site is on main during
        // lazy-init of the session, which happens once per launcher boot.
        // If profiling ever flags this, move behind the executor and gate
        // the listener replay on a CountDownLatch.
        val records = chatDb.loadRecent()
        for (r in records) {
            val line = recordToLine(r) ?: continue
            pendingHistoryReplay.add(line)
            // Reconstruct the Anthropic messages array so the AI keeps
            // multi-session memory. Tool/error rows are UI-only and don't
            // contribute to API state — the AI sees the assistant's natural-
            // language summary of what tools did, which is enough context
            // for follow-up edits in the common case.
            when (r.role) {
                "user" -> messages.put(buildApiUserMessage(r))
                "assistant" -> messages.put(JSONObject().apply {
                    put("role", "assistant"); put("content", r.text)
                })
            }
        }
    }

    /** One-shot placement hint set by the launcher when the user opens chat from
     *  an empty cell ("Ask iappyxOS" in the AddToHomeSheet). On the next [send],
     *  we silently prepend the coordinates to the user's message so the model
     *  can pass them straight to `create_generated_widget` /
     *  `place_app_icon` instead of having to call `find_empty_spot`. Cleared
     *  after one use. The user-visible chat line still shows only what they
     *  typed — no synthetic prefix. */
    @Volatile private var pendingPlacement: PlacementHint? = null

    /** Cancel handle for the in-flight tool-loop. Set when [runLoop] starts,
     *  cleared when it finishes. [cancel] aborts the current Anthropic
     *  request AND tells inner generators (WidgetGenerator.iterate etc.) to
     *  stop on their next request. */
    @Volatile private var currentCancelToken: AiService.CancelToken? = null

    /** True iff the loop is currently running. UI uses this to enable/
     *  disable the Cancel chip. */
    val isWorking: Boolean get() = currentCancelToken != null

    /** User-side cancel: aborts the current Anthropic call. The loop
     *  thread exits via an [AiException] with message "Cancelled" and the
     *  chat sees a regular Error line. Safe to call from any thread,
     *  no-op if no loop is running. */
    fun cancel() { currentCancelToken?.cancel() }

    data class PlacementHint(val pageIndex: Int, val row: Int, val col: Int)

    fun setPendingPlacement(hint: PlacementHint?) { pendingPlacement = hint }

    /** Submit a new user message — non-blocking, runs on background executor.
     *
     *  @param userText The user's typed text. Empty allowed iff [imageBase64]
     *                  is non-null (image-only message — the AI is expected
     *                  to describe / act on the image with no further prompt).
     *  @param imageBase64 Optional attached image, base64-encoded JPEG. The
     *                  CommandPanel resizes to ~1568px long-edge before
     *                  encoding so the payload stays manageable.
     *  @param imageMime MIME type for [imageBase64] — `image/jpeg` from the
     *                  panel, but kept generic in case future call sites pass
     *                  PNG/WebP. */
    fun send(userText: String, imageBase64: String? = null, imageMime: String? = null) {
        val trimmed = userText.trim()
        // Allow image-only messages (empty text) — the model can act on an
        // image alone ("recreate this widget", "what colour is this?"). Drop
        // only fully-empty calls.
        if (trimmed.isEmpty() && imageBase64 == null) return
        // Pull and clear the one-shot placement hint, if any. Append it as a
        // separate context line on the user message that goes to the API; the
        // chat list shows only the user's actual text.
        val hint = pendingPlacement
        pendingPlacement = null
        val forApi = if (hint != null) {
            "[Context: the user opened chat from an empty cell at " +
                "page_index=${hint.pageIndex}, row=${hint.row}, col=${hint.col}. " +
                "If they ask for a widget or app icon, place it at exactly these " +
                "coordinates by passing page_index/row/col to the relevant tool.]\n\n" +
                trimmed
        } else trimmed
        post(Line.User(trimmed, imageBase64, imageMime))
        executor.execute {
            messages.put(JSONObject().apply {
                put("role", "user")
                // When an image is attached, the wire format is a content-block
                // array with image FIRST, then text. Anthropic's docs recommend
                // image-before-text for better grounding; the text block also
                // carries the placement-hint prefix when one was set.
                if (imageBase64 != null && imageMime != null) {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", imageMime)
                                put("data", imageBase64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            // The text can be empty when the user sent an
                            // image with no caption; pass a single space so
                            // Anthropic's content-validator doesn't reject
                            // the block.
                            put("text", forApi.ifBlank { " " })
                        })
                    })
                } else {
                    put("content", forApi)
                }
            })
            runLoop()
        }
    }

    fun reset() {
        pendingPlacement = null
        executor.execute {
            while (messages.length() > 0) messages.remove(0)
        }
    }

    private fun runLoop() {
        val store = SecureStore(context)
        val key = store.anthropicKey
        if (key.isNullOrBlank()) {
            post(Line.Error("No API key. Open Settings → AI to add one."))
            return
        }
        val model = store.anthropicModel
        val sys = systemPrompt()
        val tools = Tools.definitions()
        var iter = 0
        // One token per loop, threaded into every API call so the user's
        // Cancel chip aborts whichever request is currently in flight.
        val token = AiService.CancelToken()
        currentCancelToken = token
        // One pending snapshot per user-action. Tool handlers add file
        // backups as they run; the snapshot is committed in the finally
        // block below, or discarded if the loop didn't make any visible
        // change (no tools called → AI replied with text only).
        runner.snapshotStore.begin("Before chat turn")
        var anyToolFired = false
        postWorking(true)
        // Hold the CPU for the duration of the tool-use loop — if the user
        // locks the screen mid-generation, Doze would otherwise tear down the
        // TCP socket to Anthropic and the request would fail. Covers every
        // nested AiService.generate call too (create_generated_widget runs
        // inside this same thread).
        try { AiService.withWakeLock(context) {
            while (iter < MAX_TURNS) {
                if (token.isCancelled) {
                    post(Line.Error("Cancelled"))
                    return@withWakeLock
                }
                iter++
                val content = AiService.generateWithTools(
                    apiKey = key, model = model, systemPrompt = sys,
                    messages = messages, tools = tools,
                    cancelToken = token,
                )
                // Append the assistant's full content array to history.
                messages.put(JSONObject().apply { put("role", "assistant"); put("content", content) })

                val toolUses = mutableListOf<JSONObject>()
                val texts = mutableListOf<String>()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    when (block.optString("type")) {
                        "text" -> {
                            val t = block.optString("text").trim()
                            if (t.isNotEmpty()) texts.add(t)
                        }
                        "tool_use" -> toolUses.add(block)
                    }
                }
                texts.forEach { post(Line.Assistant(it)) }

                if (toolUses.isEmpty()) break // model is done

                // Execute each tool, build a single user message with tool_result blocks.
                val resultsArr = JSONArray()
                for (tu in toolUses) {
                    val toolName = tu.optString("name")
                    // The model SHOULD send `input` as a JSON object. If it
                    // sends a string, number, or null, optJSONObject returns
                    // null and we'd silently substitute an empty object —
                    // tools then fail with misleading "field required"
                    // errors. Surface the actual issue so the model can
                    // self-correct on the next turn instead of looping.
                    val rawInput: Any? = tu.opt("input")
                    val input = when (rawInput) {
                        is JSONObject -> rawInput
                        null, JSONObject.NULL -> JSONObject()
                        else -> {
                            val msg = "tool '$toolName' got non-object input " +
                                "(${rawInput.javaClass.simpleName}); send a JSON object"
                            post(Line.Error(msg))
                            // Echo the error back to the model as a tool_result
                            // so it can correct itself rather than retry blindly.
                            resultsArr.put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", tu.optString("id"))
                                put("content", "{\"error\":\"$msg\"}")
                            })
                            continue
                        }
                    }
                    post(Line.Tool(toolName, briefInputSummary(toolName, input)))
                    anyToolFired = true
                    // Plumb a progress sink for the slow iterate paths so
                    // the UI can render a "Generating… N chars" pulse while
                    // the stream is open. Set per-tool, cleared after. Tools
                    // that don't stream (most of them) simply never invoke
                    // the sink — no extra cost.
                    runner.progressSink = { chars ->
                        main.post { listener?.onProgress(chars) }
                    }
                    val resultStr = try { runner.run(toolName, input) }
                        finally { runner.progressSink = null }
                    // Surface tool-level errors as a chat line so the user
                    // can see WHY a tool failed instead of just "Exceeded
                    // max iterations" several turns later.
                    try {
                        val obj = JSONObject(resultStr)
                        if (obj.has("error")) {
                            post(Line.Error("$toolName: ${obj.optString("error", "failed")}"))
                        }
                    } catch (_: Exception) { /* not a JSON object — fine */ }
                    resultsArr.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", tu.optString("id"))
                        put("content", resultStr)
                    })
                }
                messages.put(JSONObject().apply { put("role", "user"); put("content", resultsArr) })
            }
            if (iter >= MAX_TURNS) post(Line.Error("Exceeded max iterations"))
        } } catch (e: AiException) {
            // AiException("Cancelled") is the user-cancelled path —
            // we already posted "Cancelled" above, no extra error line.
            if (e.message == "Cancelled" || token.isCancelled) {
                post(Line.Error("Cancelled"))
            } else {
                post(Line.Error(e.message ?: "AI error"))
            }
        } catch (t: Throwable) {
            post(Line.Error(t.message ?: t.javaClass.simpleName))
        } finally {
            // Commit the snapshot only if the AI actually fired a tool —
            // pure-text replies don't change launcher state, so a snapshot
            // for them would just clutter the undo stack and make undo
            // require multiple invocations to reach the last real change.
            if (anyToolFired) {
                runner.snapshotStore.commit()
            } else {
                runner.snapshotStore.discardPending()
            }
            currentCancelToken = null
            postWorking(false)
        }
    }

    private fun briefInputSummary(name: String, input: JSONObject): String {
        // One-line readable description for the chat.
        return when (name) {
            "create_generated_widget" -> "Generating widget: ${input.optString("description").take(60)}"
            "edit_generated_widget" -> "Editing widget: ${input.optString("instruction").take(60)}"
            "place_app_icon" -> "Placing app: ${input.optString("package_name")}"
            "create_folder" -> {
                val n = input.optJSONArray("package_names")?.length() ?: 0
                val label = input.optString("folder_name").ifBlank { "folder" }
                "Creating $label with $n apps"
            }
            "open_app" -> "Opening: ${input.optString("package_name")}"
            "find_empty_spot" -> "Finding free space"
            "list_installed_apps" -> "Listing apps"
            "get_layout" -> "Reading current layout"
            "remove_cell" -> "Removing cell ${input.optString("placement_id")}"
            "move_cell" -> "Moving cell ${input.optString("placement_id")}"
            "add_to_folder" -> {
                val folder = input.optString("folder_name").ifBlank { input.optString("folder_id") }
                "Adding ${input.optString("package_name")} → $folder"
            }
            "remove_from_folder" -> {
                val folder = input.optString("folder_name").ifBlank { input.optString("folder_id") }
                "Removing ${input.optString("package_name")} from $folder"
            }
            "rename_folder" -> "Renaming folder → ${input.optString("new_name")}"
            "add_to_dock" -> "Adding ${input.optString("package_name")} to dock"
            "remove_from_dock" -> "Removing from dock: ${input.optString("package_name").ifBlank { input.optString("placement_id") }}"
            "swap_cells" -> "Swapping ${input.optString("placement_id_a")} and ${input.optString("placement_id_b")}"
            "reorganize_into_folders" -> {
                val n = input.optJSONArray("folders")?.length() ?: 0
                "Reorganising into $n folders"
            }
            "generate_wallpaper" -> "Generating wallpaper: ${input.optString("prompt").take(60)}"
            "iterate_wallpaper" -> "Editing wallpaper: ${input.optString("instruction").take(60)}"
            "iterate_transition" -> "Editing transition: ${input.optString("instruction").take(60)}"
            "undo_last_action" -> "Undoing last change"
            "set_iappyx_wallpaper" -> "Opening live-wallpaper picker"
            "generate_transition" -> "Generating transition: ${input.optString("prompt").take(60)}"
            "generate_icon_filter" -> "Generating icon style: ${input.optString("prompt").take(60)}"
            else -> name
        }
    }

    private fun systemPrompt(): String = """
        You are the iappyxOS-Launcher command engine. The user is talking to their Android home screen.
        Use the supplied tools to fulfil their requests — generate widgets, place icons, query layout, open apps.

        Pages: only the user's HOME pages exist in your tools — page_index is 0-based and refers to layout.pages[index].
        Pages may have user-given names. get_layout returns each page's optional "name" field (e.g. "Work", "Reading", "Travel"). Some pages have no name (the field is absent) — those are unnamed.
        When the user refers to a page by name ("move my clock to the Work page", "put this on Reading"), look up the matching page from get_layout and use its "index" in the tool call. Match case-insensitively and tolerate partial matches ("work" → "Work"). If multiple pages match, ask one clarifying question.
        When you talk to the user, refer to a page by its name when set (e.g. "Added the clock to your Work page"), and otherwise by 1-based ordinal ("page 1" = layout.pages[0], "page 2" = layout.pages[1], etc.).
        There is no page_index = -1 or special command page in your model; ignore any mental notion of "the screen the user is talking to me on".

        Be brief. Confirm what you did in ONE short sentence after tools succeed, mentioning the user-facing page number.
        Never invent tool names or arguments outside the schema. If unsure, ask one clarifying question.
        For widget creation: pick a sensible default size (2×2 for most, 4×2 for content-rich, 1×1 for a single-glyph display).
        Always rely on find_empty_spot or omit page/row/col so placement is auto-decided.
        Auto-placement considers EVERY placement (including stock Android widgets) when looking for a free rectangle, and APPENDS a new empty page if existing ones are full — you don't need to ask the user "where should I put it" first.

        IMPORTANT — DO NOT pre-judge what a widget can do: the widget generator behind create_generated_widget has access to the FULL iappyxOS bridge catalog (camera + torch/flashlight, microphone + speech, GPS + geofencing, BLE, NFC, HTTP/TCP/UDP/SSH/SMB servers + clients, accelerometer + gyroscope + compass + barometer, calendar read/write, contacts, SMS, biometrics, SQLite, clipboard, notifications, alarms, audio recording + playback + effects, vibration, print, wallpaper, DND, etc.). When the user asks for a "torch widget", "compass", "decibel meter", "BLE scanner", "geofence reminder", or anything hardware-ish, ALWAYS delegate by calling create_generated_widget with a clear description — never reply "I can't do that" based on your own guess. The generator will decide feasibility from its own capability list.

        MOVING vs CREATING: when the user wants to relocate something that already exists ("move the clock to a new page", "put my Gmail icon on page 3", "shift this widget down"), use move_cell with the placement's id from get_layout — DO NOT call create_generated_widget or place_app_icon (those create new content and would either duplicate or regenerate the widget). move_cell preserves the widget's existing HTML and saved state.

        EDITING vs CREATING (generated widgets): when the user wants to MODIFY a widget that already exists on the grid ("make my clock darker", "add seconds to the timer", "use a bigger font", "change the colour to red", "fix the alignment", "tweak X"), use edit_generated_widget with the widget's placement_id from get_layout — DO NOT call create_generated_widget. The edit tool reads the widget's current HTML, applies the change, and writes it back; this preserves all customizations and saved state. create_generated_widget would discard the existing widget and produce a fresh one. Only use create_generated_widget when the user is asking for an entirely NEW widget that doesn't exist yet.

        EDITING vs CREATING (wallpapers): wallpapers are NOT widgets and do NOT have placement_ids — they live in a separate library, only one is active at a time. When the user wants to MODIFY their current wallpaper ("make my wallpaper darker", "speed up the rain", "change the particles to gold", "add more stars"), call iterate_wallpaper with the instruction — wallpaper_id defaults to the active wallpaper. DO NOT call edit_generated_widget for wallpapers (it'll fail with "placement_id not found"). DO NOT call generate_wallpaper for tweaks — that creates a brand-new wallpaper entry and discards the existing one. Only use generate_wallpaper when the user explicitly asks for an entirely NEW look ("replace my wallpaper with X", "I want something completely different").

        UNDO: when the user says "undo", "revert", "take that back", "go back to before", "I changed my mind", or expresses regret about your most recent change, call undo_last_action with no arguments. Each turn that mutates state (placement, edit, generate, iterate, …) is undoable for several turns back. Don't pre-emptively warn the user about undo limits — only mention it if the tool returns "Nothing to undo". After a successful undo, reply with one short sentence describing what was reverted (e.g. "Reverted — your clock is back to its previous look."). Don't chain another tool call.

        EDITING vs CREATING (transitions): transitions are NOT widgets and have NO placement_ids — they live in a separate library, one is active at a time, and they animate between home pages. When the user wants to MODIFY the current transition ("slow my cube transition down", "make the fade longer", "add more wobble to the slide"), call iterate_transition with the instruction — transition_id defaults to the active transition. DO NOT call edit_generated_widget for transitions (it'll fail with "placement_id not found"). DO NOT call generate_transition for tweaks — that creates a brand-new transition entry and discards the customisations. Built-in transitions auto-fork on first edit (the result includes forked: true) — when you see that, mention it briefly: "Customised the built-in 3D-cube transition — the original stays untouched, your tweak is on a personal copy." Only use generate_transition when the user wants an entirely NEW effect from scratch.

        EDIT NO-OP HANDLING: edit_generated_widget can return `{"ok":true, "noop":true, "reason":"..."}`. This means the AI looked at the widget and decided no change was needed (e.g. the requested change is already in place, or the instruction was ambiguous). It is a SUCCESSFUL outcome — DO NOT retry, DO NOT call edit_generated_widget again. Reply to the user with a single short sentence relaying the reason (e.g. "Looks like that's already done — `iappyx.location.*` is used everywhere; no `navigator.geolocation` calls remain.") and end the turn.

        EDITING BUILT-IN WIDGETS: if the user asks you to modify a widget that ships with the launcher (clock, compass, weather, qr_barcode_scanner — placements pointing at an asset path rather than a user-owned id), call edit_generated_widget normally. The launcher auto-forks the bundled widget into a writable user copy on the first edit and proceeds to apply the change. The tool result will include `"forked": true` — when you see that, mention it briefly in your reply ("Customised the built-in clock — your tweak is on a personal copy now, the original is untouched."). Subsequent edits to the same placement edit the user copy directly and `"forked"` will not be set.

        FOLDER EDITS: for changes to an existing folder, use the dedicated tools rather than rebuilding it. add_to_folder pulls an installed app into a folder (and removes any existing home-grid icon for that app by default, avoiding duplicates). remove_from_folder pulls an app out (auto-collapses 1-item folders into icons). rename_folder changes the display name. swap_cells swaps two same-size placements' positions. add_to_dock / remove_from_dock manage the dock at the bottom.

        BATCH REORG: when the user asks for a categorisation ("group my chat apps", "organise my home into Work / Social / Banking", "tidy up my apps"), use reorganize_into_folders — it builds multiple folders atomically and removes the old direct-icon placements so apps end up only in their new folder. Don't loop create_folder N times.

        WALLPAPERS: when the user asks for a wallpaper / live wallpaper / "background" ("make me a foggy ocean wallpaper", "give me a lava lamp background", "a wallpaper that looks like the matrix"), call generate_wallpaper with a clear prompt — the wallpaper generator has the same iappyx bridge surface as widgets (storage, sensors, location, calendar, http, etc.) plus push events for page-scroll and accelerometer, so it can react to the user's environment. After generation, the tool returns wallpaper_active: true|false: when false, briefly tell the user to set "iappyxOS Live" via Launcher Settings → Live wallpaper, or — only if they explicitly want to do it now — call set_iappyx_wallpaper to deep-link the system picker. Don't auto-call set_iappyx_wallpaper without consent — it interrupts their flow with a system screen.

        ICON STYLES: when the user asks to restyle their app icons ("make my icons look cyberpunk", "give me a pastel kawaii icon style", "70s film grain on every icon", "minimal black-and-white icons", "rainbow icons by position"), call generate_icon_filter EXACTLY ONCE with a clear prompt. After the tool returns ok, REPLY to the user with one short sentence confirming what you applied (e.g. "Done — your icons now have a cyberpunk neon glow. Swipe to home to see them."). Do NOT call generate_icon_filter again, do NOT call any other tool — just the confirmation reply ends the turn. If the tool returns an error, tell the user briefly and ask if they want to retry with a tweaked description; do NOT auto-retry. The filter is applied uniformly to every installed app's icon — Spotify still looks like Spotify, just restyled. Don't confuse this with generate_wallpaper (which paints the screen background) or with per-icon icon packs (we don't support per-app overrides — just a global look).

        Identifying placements: get_layout returns a "label" field on each placement when one is available — for icons it's the app label ("Gmail", "Slack"), for folders it's the folder name, for generated widgets it's the <title> from the widget's HTML ("World Clock", "Water Tracker"). When the user refers to a widget by name ("move the world clock"), match against label.

        PLUGINS: extend the launcher with installable JS modules (remote photo libraries, smart-home bridges, etc.). Call get_plugins to list what's enabled on this device — surfaces each plugin's id, name, version, and exposed methods. When the user mentions a service the launcher has a plugin for ("show me my Immich photos"), suggest a widget that uses that plugin via `await iappyx.plugin('<id>').<method>(args)` from inside the widget's HTML — pass that hint to create_generated_widget in the description so the widget generator can wire it up directly.

        Tool errors come back as JSON {"error": "..."} — read them and adapt; don't claim success when a tool returned an error.
    """.trimIndent()

    /** Surface a chat line: deliver to the bound UI listener AND persist
     *  to SQLite. Replays during history-load bypass this (they go straight
     *  to listener.onLine) so we don't double-write rows we just read. The
     *  transient [Line.Working] sentinel is filtered out — it's a UI pill,
     *  not a chat row. */
    private fun post(line: Line) {
        main.post { listener?.onLine(line) }
        if (line is Line.Working) return
        dbExecutor.execute {
            try {
                when (line) {
                    is Line.User -> chatDb.append(
                        role = "user", text = line.text,
                        imageBase64 = line.imageBase64,
                        imageMime = line.imageMimeType,
                    )
                    is Line.Assistant -> chatDb.append(role = "assistant", text = line.text)
                    is Line.Tool -> chatDb.append(
                        role = "tool", text = line.summary, toolName = line.name,
                    )
                    is Line.Error -> chatDb.append(role = "error", text = line.text)
                    Line.Working -> {} // already filtered above
                }
            } catch (_: Throwable) {
                // Best-effort persistence — losing one row to a disk-full
                // event shouldn't break the conversation.
            }
        }
    }
    private fun postWorking(w: Boolean) { main.post { listener?.onWorking(w) } }

    /** Wipe persisted history + reset the in-memory Anthropic messages array.
     *  Surfaces a confirmation Line.Assistant so the chat scrollback shows
     *  what happened. Caller (Settings) should also clear the visible
     *  RecyclerView via [Listener.onClear] — this method on its own only
     *  deletes the data, the UI renders whatever the listener does next. */
    fun clearHistory(onComplete: (() -> Unit)? = null) {
        dbExecutor.execute {
            chatDb.clearAll()
        }
        executor.execute {
            while (messages.length() > 0) messages.remove(0)
        }
        pendingHistoryReplay.clear()
        main.post {
            listener?.onClear()
            onComplete?.invoke()
        }
    }

    /** Convert a stored [ChatDatabase.Record] back into a UI [Line]. Returns
     *  null for unrecognised roles (defensive — schema is closed but a
     *  future migration that adds a role wouldn't crash older builds). */
    private fun recordToLine(r: ChatDatabase.Record): Line? = when (r.role) {
        "user" -> Line.User(r.text, r.imageBase64, r.imageMime)
        "assistant" -> Line.Assistant(r.text)
        "tool" -> Line.Tool(r.toolName ?: "", r.text)
        "error" -> Line.Error(r.text)
        else -> null
    }

    /** Build an Anthropic-format user message from a stored record, mirroring
     *  the wire format [send] uses. Image rows go as a content-block array
     *  (image first then text); pure-text rows go as a plain string. */
    private fun buildApiUserMessage(r: ChatDatabase.Record): JSONObject {
        return JSONObject().apply {
            put("role", "user")
            val img = r.imageBase64
            val mime = r.imageMime
            if (img != null && mime != null) {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", mime)
                            put("data", img)
                        })
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", r.text.ifBlank { " " })
                    })
                })
            } else {
                put("content", r.text)
            }
        }
    }

    /** Stats the Settings "Clear chat history" row reads for its subtitle.
     *  Both calls hit SQLite — wrapped in [dbExecutor] won't help here
     *  because the caller awaits the return; a Settings render is rare so
     *  the brief read is fine on the calling thread. */
    fun historyCount(): Int = chatDb.count()
    fun historySizeBytes(): Long = chatDb.sizeBytes()

    fun shutdown() {
        executor.shutdown()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    companion object { const val MAX_TURNS = 6 }
}
