/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — AI chat surface that drives layout via tools.
 *
 * Wire shape:
 *   POST /api/chat { prompt }            → 200 + { ok, sessionMessageId }
 *                                          AI work happens async; browser
 *                                          subscribes to GET /api/chat/stream
 *                                          (SSE) for live updates
 *   GET  /api/chat/stream                → SSE stream of events
 *                                          (text, tool-call, tool-result,
 *                                          done, error)
 *   GET  /api/chat                       → recent messages (history)
 *   POST /api/chat/clear                 → wipe history
 */
package com.iappyx.launcher.remoteedit.api

import android.app.Activity
import com.iappyx.launcher.remoteedit.ai.RemoteCommandSession
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.SseEmitter
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject

class ChatApi(activity: Activity) {

    // Routed through the on-device CommandSession / LauncherCommandRunner
    // so the editor's chat has the same full tool surface (generate +
    // iterate + layout + folder + dock + transitions + icon filters +
    // wallpapers + undo) as the AI Command Bar on the phone, and shares
    // the persistent chat history through ChatDatabase.
    private val session = RemoteCommandSession(activity)

    fun history(ex: MicroHttpServer.Exchange) {
        val arr = JSONArray()
        for (m in session.messages()) {
            arr.put(JSONObject().apply {
                put("role", m.role)
                put("text", m.text)
                if (m.toolCalls.isNotEmpty()) {
                    put("toolCalls", JSONArray().apply {
                        for (tc in m.toolCalls) put(JSONObject().apply {
                            put("name", tc.name)
                            put("args", tc.args)
                            put("result", tc.result)
                        })
                    })
                }
            })
        }
        JsonResponse.ok(ex, arr)
    }

    fun send(ex: MicroHttpServer.Exchange) {
        val obj = JsonResponse.readJsonObject(ex) ?: run {
            JsonResponse.error(ex, 400, "no body"); return
        }
        val prompt = obj.optString("prompt").trim()
        val imageBase64 = obj.optString("imageBase64").takeIf { it.isNotEmpty() }
        val imageMime = obj.optString("imageMime").takeIf { it.isNotEmpty() }
        if (prompt.isEmpty() && imageBase64 == null) {
            JsonResponse.error(ex, 400, "empty prompt"); return
        }
        session.send(prompt, imageBase64, imageMime)
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun clear(ex: MicroHttpServer.Exchange) {
        session.clear()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    fun stream(ex: MicroHttpServer.Exchange) {
        val emitter = SseEmitter(ex)
        emitter.start()
        val unsub = session.subscribe { event ->
            try { emitter.send(event.type, event.payload) } catch (_: Throwable) { /* client gone */ }
        }
        // Block until the client disconnects. The HTTP thread stays in
        // this method; httpserver runs each handler in its own pool
        // thread so this is fine for a single-client server.
        try {
            emitter.awaitClose()
        } finally {
            unsub()
        }
    }
}
