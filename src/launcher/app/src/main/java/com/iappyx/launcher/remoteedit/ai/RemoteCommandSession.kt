/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — adapter that routes the browser editor's chat
 * through the on-device [CommandSession] / [LauncherCommandRunner] so
 * the editor gets the FULL tool surface (widget generate + iterate,
 * wallpaper generate + iterate, transitions, icon filters, etc.) for
 * free, instead of maintaining a parallel narrower tool registry.
 *
 * Wire compatibility: emits the same StreamEvent types EditAiSession
 * used to, so the browser SPA needs no changes.
 *
 * Independence: this module only depends on classes in
 * com.iappyx.launcher.command (CommandSession, LauncherCommandRunner,
 * ChatDatabase). Those are part of the on-device launcher proper and
 * exist regardless of whether `remoteedit/` is present. Deleting the
 * `remoteedit/` directory still leaves the launcher building.
 */
package com.iappyx.launcher.remoteedit.ai

import android.app.Activity
import android.content.Intent
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.command.ChatDatabase
import com.iappyx.launcher.command.CommandSession
import com.iappyx.launcher.command.LauncherCommandRunner
import com.iappyx.launcher.model.HomeLayout
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class RemoteCommandSession(private val activity: Activity) {

    /** Browser-facing chat record. Mirrors EditAiSession.Message so the
     *  /api/chat history endpoint stays wire-compatible. */
    data class ToolCall(val name: String, val args: String, val result: String)
    data class Message(val role: String, val text: String, val toolCalls: List<ToolCall> = emptyList())
    data class StreamEvent(val type: String, val payload: String)

    private val store = PlacementStore(activity)
    private val chatDb = ChatDatabase(activity)
    private val subscribers = CopyOnWriteArrayList<(StreamEvent) -> Unit>()

    /** Runner listener. The editor doesn't have a "current home page"
     *  the way the on-device launcher does, so [currentHomePageIndex]
     *  returns 0 — the AI's placement defaults to page 0 unless the
     *  user specifies, which matches the editor's mental model where
     *  "the home screen" reads as "the first page." */
    private val runner = LauncherCommandRunner(
        activity = activity,
        store = store,
        listener = object : LauncherCommandRunner.Listener {
            override fun getLayout(): HomeLayout = store.load()
            override fun applyLayout(layout: HomeLayout) {
                store.save(layout)
                broadcastChanged()
            }
            override fun currentHomePageIndex(): Int = 0
        },
    )

    private val session = CommandSession(activity, runner).also { s ->
        s.listener = object : CommandSession.Listener {
            override fun onLine(line: CommandSession.Line) {
                when (line) {
                    is CommandSession.Line.User -> {
                        // Echo user-side messages back so reconnecting
                        // clients see them — CommandSession replays
                        // history through the same callback at attach
                        // time, so this also feeds the initial replay
                        // into the SSE stream.
                        emit("user-message", JSONObject().put("text", line.text).toString())
                    }
                    is CommandSession.Line.Assistant -> {
                        // CommandSession emits whole-message assistant
                        // lines (not chunks). The editor JS already
                        // accumulates ai-text-chunk events into the
                        // assistant bubble, so passing the whole text
                        // as one chunk works without any UI changes.
                        emit("ai-text-chunk", JSONObject().put("text", line.text).toString())
                    }
                    is CommandSession.Line.Tool -> {
                        // CommandSession exposes the human-readable
                        // summary (e.g. "Generating widget: weather…"),
                        // not the raw tool input JSON. We emit it as
                        // BOTH tool-call AND tool-result so the chat
                        // row renders consistently with the old
                        // EditAiSession events.
                        val payload = JSONObject().apply {
                            put("name", line.name)
                            put("summary", line.summary)
                        }.toString()
                        emit("tool-call", payload)
                        emit("tool-result", payload)
                    }
                    is CommandSession.Line.Error -> {
                        emit("ai-error", JSONObject().put("message", line.text).toString())
                    }
                    CommandSession.Line.Working -> { /* tracked via onWorking */ }
                }
            }
            override fun onWorking(working: Boolean) {
                if (!working) emit("done", "{}")
            }
            override fun onClear() {
                emit("cleared", "{}")
            }
            override fun onProgress(chars: Int) {
                emit("ai-progress", JSONObject().put("chars", chars).toString())
            }
        }
    }

    /** Recent chat history for the /api/chat GET endpoint. Reads from
     *  the shared ChatDatabase synchronously so it works even before
     *  CommandSession's async replay has drained to the listener.
     *  Includes both on-device-originated AND editor-originated rows —
     *  they share the same DB so the chat surfaces cross-pollinate. */
    fun messages(): List<Message> {
        val rows = chatDb.loadRecent()
        // Fold consecutive tool rows into the preceding assistant
        // message so the editor's existing chat UI (which knows how to
        // render an assistant message with toolCalls) keeps working.
        // Stand-alone tool rows (no preceding assistant) get rendered
        // as a tiny synthetic assistant row.
        val out = mutableListOf<Message>()
        for (r in rows) {
            when (r.role) {
                "user" -> out.add(Message("user", r.text))
                "assistant" -> out.add(Message("assistant", r.text))
                "tool" -> {
                    val tc = ToolCall(r.toolName ?: "", "", r.text)
                    val last = out.lastOrNull()
                    if (last != null && last.role == "assistant") {
                        out[out.size - 1] = last.copy(toolCalls = last.toolCalls + tc)
                    } else {
                        out.add(Message("assistant", "", listOf(tc)))
                    }
                }
                "error" -> out.add(Message("assistant", "⚠ " + r.text))
            }
        }
        return out
    }

    fun send(prompt: String, imageBase64: String? = null, imageMime: String? = null) {
        session.send(prompt, imageBase64, imageMime)
    }

    fun clear() {
        session.clearHistory()
    }

    fun subscribe(handler: (StreamEvent) -> Unit): () -> Unit {
        subscribers.add(handler)
        return { subscribers.remove(handler) }
    }

    fun setPendingPlacement(hint: CommandSession.PlacementHint?) {
        session.setPendingPlacement(hint)
    }

    private fun emit(type: String, payload: String) {
        val ev = StreamEvent(type, payload)
        for (s in subscribers) {
            try { s(ev) } catch (_: Throwable) { /* subscriber gone */ }
        }
    }

    private fun broadcastChanged() {
        try {
            activity.sendBroadcast(
                Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(activity.packageName),
            )
        } catch (_: Throwable) {}
    }
}
