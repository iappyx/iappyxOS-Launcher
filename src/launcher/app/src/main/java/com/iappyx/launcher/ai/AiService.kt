/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

import android.content.Context
import android.os.PowerManager
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Anthropic client. Mirrors iappyxOS's ai_service.dart:
 *  - system prompt sent as a cached content block (cache_control ephemeral)
 *  - beta header "prompt-caching-2024-07-31"
 *
 * Kept tiny on purpose; retries, streaming, and OpenRouter are future extensions.
 */
object AiService {
    /**
     * One message in a chat exchange.
     *
     * @param role "user" or "assistant"
     * @param content The message text. If [cacheablePrefix] is non-null, this
     *                field is the *suffix* that varies between calls (e.g. the
     *                user's instruction); the prefix is the bytes that should
     *                be cached.
     * @param cacheablePrefix Optional cacheable prefix. When set, the message
     *                content is sent as a two-block array: [prefix block with
     *                `cache_control: ephemeral`, suffix block]. Anthropic's
     *                prompt cache then matches the system+prefix prefix on
     *                subsequent calls, so iterating the same widget skips
     *                ~75-90% of input cost on follow-up edits.
     * @param imageBase64 Optional base64-encoded image bytes (ASCII, no
     *                data-URI prefix). When non-null, the message goes as a
     *                content-block array with the image FIRST (Anthropic's
     *                recommended ordering) followed by the text. Mutually
     *                exclusive with [cacheablePrefix] — image-bearing
     *                messages are user inputs that aren't cache-friendly
     *                anyway (each is unique).
     * @param imageMimeType MIME type of [imageBase64] — typically
     *                `image/jpeg` (resizer in CommandPanel always emits JPEG).
     *                Required when [imageBase64] is set.
     */
    data class Message(
        val role: String,
        val content: String,
        val cacheablePrefix: String? = null,
        val imageBase64: String? = null,
        val imageMimeType: String? = null,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Cooperative cancellation handle. Pass to [generate] / [listModels]
     *  / [generateWithTools]; the in-flight OkHttp call is bound here so
     *  [cancel] aborts the request mid-flight. Idempotent — a cancel
     *  before the call binds still propagates once it does. */
    class CancelToken {
        @Volatile var isCancelled: Boolean = false; private set
        private val callRef = AtomicReference<Call?>()
        internal fun bind(call: Call) {
            callRef.set(call)
            // If cancel() arrived before bind(), honour it now.
            if (isCancelled) try { call.cancel() } catch (_: Throwable) {}
        }
        /** User-side cancel: aborts the current Call (if any) and short-
         *  circuits the retry loop on the next iteration. The thread
         *  running the AI request sees an [AiException] with message
         *  "Cancelled" — callers that need a separate path can check
         *  [isCancelled] on the token. */
        fun cancel() {
            isCancelled = true
            try { callRef.get()?.cancel() } catch (_: Throwable) {}
        }
    }

    /** Per-chunk progress hook for [generate] streaming mode. Receives
     *  the text delta (NOT the accumulated string) on every
     *  `content_block_delta` SSE event. Fires on the OkHttp dispatcher
     *  thread — caller is responsible for thread-hopping if it needs to
     *  touch UI state. */
    fun interface StreamProgress {
        fun onChunk(delta: String)
    }

    /** Executes [request] with retry+backoff on transient failures: 429
     *  (rate limit), any 5xx, and IOException (network blip). Auth errors
     *  (401/403) and other 4xx are NOT retried — they're terminal. The
     *  loop sleeps 1s, then 2s after the first/second failure; if all 3
     *  attempts fail, the final exception is rethrown so the caller's
     *  normal error path runs.
     *
     *  Returns the response body string; the caller does its own JSON
     *  parsing. We close the Response immediately after reading the body
     *  (the body string is the only thing the caller cares about). */
    private fun executeWithRetry(
        request: Request, cancelToken: CancelToken? = null,
    ): String {
        val maxAttempts = 3
        var attempt = 0
        var lastException: Exception? = null
        while (attempt < maxAttempts) {
            if (cancelToken?.isCancelled == true) throw AiException("Cancelled")
            attempt++
            try {
                val call = http.newCall(request)
                cancelToken?.bind(call)
                val resp = call.execute()
                val respBody = resp.body?.string().orEmpty()
                resp.close()
                if (resp.isSuccessful) return respBody
                val isTransient = resp.code == 429 || resp.code in 500..599
                if (!isTransient || attempt >= maxAttempts) {
                    val err = runCatching {
                        JSONObject(respBody).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    throw AiException(err ?: "API error ${resp.code}")
                }
                // Transient — back off, then retry.
                Thread.sleep(1000L * (1L shl (attempt - 1))) // 1s after attempt 1, 2s after 2
            } catch (e: AiException) {
                throw e
            } catch (e: java.io.IOException) {
                // OkHttp throws IOException on Call.cancel() — translate to
                // a typed cancel error so callers can distinguish "user
                // aborted" from "network blip" (the latter triggers retry,
                // the former does not).
                if (cancelToken?.isCancelled == true) throw AiException("Cancelled")
                lastException = e
                if (attempt >= maxAttempts) break
                Thread.sleep(1000L * (1L shl (attempt - 1)))
            }
        }
        throw AiException(lastException?.message ?: "Network error after $maxAttempts attempts")
    }

    /**
     * Calls Anthropic, returns assistant text, throws [AiException] with a user-safe
     * message on any failure.
     */
    @Throws(AiException::class)
    fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Message>,
        cancelToken: CancelToken? = null,
        onProgress: StreamProgress? = null,
    ): String {
        if (apiKey.isBlank()) throw AiException("API key not set. Open Settings to add one.")
        // When [onProgress] is set, switch to SSE streaming so the caller
        // sees text as the model emits it. Same wire shape otherwise —
        // we collect deltas into a single string and return it. Lets us
        // drive a "live typing" UI in the chat without changing callers
        // that don't pass a progress hook.
        val streaming = onProgress != null

        val body = JSONObject().apply {
            put("model", model)
            if (streaming) put("stream", true)
            // Sonnet 4.x supports 64 K output tokens natively. The previous
            // 16 K cap routinely truncated full-widget rewrites (sticky
            // notes, road-trip-nl, anything > ~4 K visible chars) and
            // surfaced "Response was cut off" to the user even though the
            // model could have completed. Anthropic returns stop_reason =
            // "end_turn" when the model finishes naturally, so a generous
            // cap costs nothing on short generations and unblocks long ones.
            put("max_tokens", 64000)
            put("system", JSONArray().put(JSONObject().apply {
                put("type", "text"); put("text", systemPrompt)
                put("cache_control", JSONObject().apply { put("type", "ephemeral") })
            }))
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        // Three content shapes:
                        //  1. cacheablePrefix set → two-block text array with
                        //     a cache_control breakpoint between prefix +
                        //     suffix (used by widget iterate for prompt-cache
                        //     savings).
                        //  2. imageBase64 set → [image, text] block array.
                        //     Anthropic recommends image-before-text for
                        //     better grounding. Mutually exclusive with #1
                        //     (image messages are user inputs and unique).
                        //  3. plain content → string (default — keeps the
                        //     wire format identical for callers that don't
                        //     use either feature).
                        val prefix = m.cacheablePrefix
                        val img = m.imageBase64
                        val imgMime = m.imageMimeType
                        if (img != null && imgMime != null) {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", imgMime)
                                        put("data", img)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", m.content)
                                })
                            })
                        } else if (prefix != null) {
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prefix)
                                    put("cache_control", JSONObject().apply { put("type", "ephemeral") })
                                })
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", m.content)
                                })
                            })
                        } else {
                            put("content", m.content)
                        }
                    })
                }
            })
        }.toString()

        val requestBuilder = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (streaming) requestBuilder.header("Accept", "text/event-stream")
        val request = requestBuilder.build()

        if (streaming) {
            return streamGenerate(request, cancelToken, onProgress!!)
        }
        val respBody = executeWithRetry(request, cancelToken)
        val json = try { JSONObject(respBody) }
            catch (e: Exception) { throw AiException("Malformed response: ${e.message}") }
        // Anthropic signals truncation via stop_reason="max_tokens". If we
        // hand off the partial content to a generator, it'll save invalid
        // HTML / a malformed JSON spec / a half-written widget and the user
        // sees nothing or a crash on next render. Surface a typed error
        // instead so the UI can suggest the fix (smaller change, simpler
        // prompt).
        if (json.optString("stop_reason") == "max_tokens") {
            throw AiException("Response was cut off — try a simpler prompt or a smaller change.")
        }
        val content = json.optJSONArray("content")
            ?: throw AiException("Empty response from API")
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") {
                return block.optString("text").ifBlank {
                    throw AiException("Empty response from API")
                }
            }
        }
        throw AiException("No text block in response")
    }

    /** Streaming variant of [generate]. Reads Anthropic's SSE event stream
     *  and accumulates `content_block_delta` text chunks into the returned
     *  string. Each chunk is also relayed to [onProgress] for live UI.
     *
     *  We don't retry streaming requests — Anthropic's stream is one-shot,
     *  and if we did retry mid-way the user would see duplicate text.
     *  Network failures during the stream surface as an [AiException].
     *  Cancellation works the same way — bind the Call to the token, the
     *  user's cancel aborts the socket, we translate the IOException. */
    private fun streamGenerate(
        request: Request,
        cancelToken: CancelToken?,
        onProgress: StreamProgress,
    ): String {
        if (cancelToken?.isCancelled == true) throw AiException("Cancelled")
        val call = http.newCall(request)
        cancelToken?.bind(call)
        val resp = try { call.execute() }
            catch (e: java.io.IOException) {
                if (cancelToken?.isCancelled == true) throw AiException("Cancelled")
                throw AiException(e.message ?: "Network error")
            }
        try {
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                val msg = runCatching {
                    JSONObject(errBody).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw AiException(msg ?: "API error ${resp.code}")
            }
            val src = resp.body?.byteStream()
                ?: throw AiException("Empty response stream")
            val reader = BufferedReader(InputStreamReader(src, Charsets.UTF_8))
            val accumulated = StringBuilder()
            var stopReason: String? = null
            // SSE format: each event is a sequence of "field: value\n"
            // lines, terminated by a blank line. We only need the `data:`
            // payload — Anthropic always emits one JSON object per event.
            while (true) {
                if (cancelToken?.isCancelled == true) {
                    try { call.cancel() } catch (_: Throwable) {}
                    throw AiException("Cancelled")
                }
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                val ev = try { JSONObject(payload) } catch (_: Throwable) { continue }
                when (ev.optString("type")) {
                    "content_block_delta" -> {
                        val delta = ev.optJSONObject("delta") ?: continue
                        if (delta.optString("type") != "text_delta") continue
                        val chunk = delta.optString("text")
                        if (chunk.isNotEmpty()) {
                            accumulated.append(chunk)
                            try { onProgress.onChunk(chunk) } catch (_: Throwable) {}
                        }
                    }
                    "message_delta" -> {
                        val delta = ev.optJSONObject("delta")
                        delta?.optString("stop_reason")?.takeIf { it.isNotBlank() }
                            ?.let { stopReason = it }
                    }
                    "error" -> {
                        val msg = ev.optJSONObject("error")?.optString("message")
                        throw AiException(msg ?: "Stream error")
                    }
                    "message_stop" -> break
                }
            }
            if (stopReason == "max_tokens") {
                throw AiException("Response was cut off — try a simpler prompt or a smaller change.")
            }
            if (accumulated.isEmpty()) throw AiException("Empty response from API")
            return accumulated.toString()
        } finally {
            try { resp.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Tool-use variant. [messages] is the raw conversation array (role +
     * content blocks). [tools] is the Anthropic tool-schema array. Returns
     * the **assistant's** content array unchanged — caller inspects it for
     * `text` and `tool_use` blocks, executes any tool calls, then sends a
     * follow-up `user` message containing `tool_result` blocks.
     */
    @Throws(AiException::class)
    fun generateWithTools(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: JSONArray,
        tools: JSONArray,
        cancelToken: CancelToken? = null,
    ): JSONArray {
        if (apiKey.isBlank()) throw AiException("API key not set. Open Settings to add one.")
        val body = JSONObject().apply {
            put("model", model)
            // Tool-use responses are usually short (a tool_use block + a few
            // sentences of reasoning) but multi-step Command Bar plans can
            // exceed 4 K. Bumped to 16 K so a "create three widgets, move
            // the dock, then open the first one" plan doesn't truncate.
            // Sonnet 4.6 supports 64 K but tool calls almost never need it.
            put("max_tokens", 16000)
            put("system", JSONArray().put(JSONObject().apply {
                put("type", "text"); put("text", systemPrompt)
                put("cache_control", JSONObject().apply { put("type", "ephemeral") })
            }))
            put("tools", tools)
            put("messages", messages)
        }.toString()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val respBody = executeWithRetry(request, cancelToken)
        val json = try { JSONObject(respBody) }
            catch (e: Exception) { throw AiException("Malformed response: ${e.message}") }
        // Same truncation guard the non-tool generate() path has: a tool_use
        // block cut off at max_tokens feeds a malformed action back into the
        // loop (or triggers an Anthropic 400 on the follow-up call because the
        // tool_use ids don't line up). Surface it as a typed error instead.
        if (json.optString("stop_reason") == "max_tokens") {
            throw AiException("Response was cut off — try a simpler request or break it into steps.")
        }
        return json.optJSONArray("content")
            ?: throw AiException("Empty response from API")
    }

    /**
     * One model entry from the Anthropic [Models endpoint](https://docs.anthropic.com/en/api/models-list).
     * Stored verbatim from the API response — `id` is the string we send in
     * future `model` request fields; `displayName` is what we show in UI.
     */
    data class ModelInfo(val id: String, val displayName: String)

    /**
     * Fetch the list of models the user's API key has access to. Used by
     * the Settings screen to populate model-picker dropdowns. Cheap call
     * (~50 ms when fresh, ~10 ms cached at GitHub edge), no charge,
     * supported by Anthropic.
     *
     * Reuses [executeWithRetry] so transient 429/5xx self-heal. 401/403
     * (invalid / missing key) surfaces as [AiException] so the caller can
     * fall back to manual model entry.
     *
     * @param apiKey same key used for [generate] / [generateWithTools].
     * @return list of [ModelInfo], in the order Anthropic returned them
     *         (typically newest-first).
     */
    @Throws(AiException::class)
    fun listModels(apiKey: String): List<ModelInfo> {
        if (apiKey.isBlank()) throw AiException("API key not set")
        // limit=100 covers every model Anthropic has ever shipped at once
        // (current count is ~15) without paginating. If they ever exceed 100
        // we can revisit, but reading multiple pages just to populate a
        // settings dropdown isn't worth the round-trips.
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models?limit=100")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .get()
            .build()
        val respBody = executeWithRetry(request)
        val json = try { JSONObject(respBody) }
            catch (e: Exception) { throw AiException("Malformed model list response") }
        val data = json.optJSONArray("data")
            ?: throw AiException(
                json.optJSONObject("error")?.optString("message")
                    ?: "Empty model list",
            )
        val out = mutableListOf<ModelInfo>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("display_name").ifBlank { id }
            out.add(ModelInfo(id = id, displayName = name))
        }
        return out
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK while [block] runs. The CPU stays up even if
     * the user locks the screen mid-request, so the open TCP socket to
     * Anthropic doesn't get torn down by Doze.
     *
     * Uses a 10-minute timeout (matches worst-case readTimeout of 300s + buffer)
     * so a forgotten release can't drain the battery. Safe to call off the
     * main thread; the block runs on the caller's thread.
     */
    fun <T> withWakeLock(context: Context, tag: String = "iappyx:ai", block: () -> T): T {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return block()
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        wl.setReferenceCounted(false)
        wl.acquire(10 * 60 * 1000L)
        try {
            return block()
        } finally {
            if (wl.isHeld) {
                try { wl.release() } catch (_: Exception) {}
            }
        }
    }
}

class AiException(message: String) : Exception(message)
