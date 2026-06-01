/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — minimal SSE emitter.
 */
package com.iappyx.launcher.remoteedit.server

import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SseEmitter(private val ex: MicroHttpServer.Exchange) {

    private var out: OutputStream? = null
    private val closed = CountDownLatch(1)

    /** Single-threaded executor — Android forbids socket writes on the main
     *  thread (NetworkOnMainThreadException). Sensor / location callbacks
     *  arrive on the main thread, so every send() gets dispatched here. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "iax-sse-writer").apply { isDaemon = true }
    }

    fun start() {
        ex.status = 200
        ex.setHeader("Content-Type", "text/event-stream")
        ex.setHeader("Cache-Control", "no-cache")
        // Connection: close is REQUIRED for unbounded streams. Without
        // it the browser doesn't know how long the body is (we never
        // set Content-Length or Transfer-Encoding) and may buffer or
        // never deliver events.
        ex.setHeader("Connection", "close")
        // Some intermediary buffers SSE; X-Accel-Buffering disables nginx
        // buffering. Cheap to set, harmless if no proxy is involved.
        ex.setHeader("X-Accel-Buffering", "no")
        out = ex.startStreaming()
        try {
            out!!.write(": stream open\n\n".toByteArray(Charsets.UTF_8))
            out!!.flush()
        } catch (_: Throwable) { close() }
        startHeartbeat()
    }

    /** Periodic comment frames keep the SSE connection alive. Browsers
     *  will reset an SSE connection that's silent for "too long" (the
     *  exact timeout is implementation-specific; 15s is comfortably
     *  under all known limits). Without these, a widget that doesn't
     *  immediately receive bridge events sees the connection flap. */
    private val heartbeatThread: Thread = Thread({
        while (out != null) {
            try { Thread.sleep(15_000) } catch (_: InterruptedException) { return@Thread }
            val o = out ?: return@Thread
            try {
                synchronized(this) {
                    o.write(": heartbeat\n\n".toByteArray(Charsets.UTF_8))
                    o.flush()
                }
            } catch (_: Throwable) {
                close()
                return@Thread
            }
        }
    }, "iax-sse-heartbeat").apply { isDaemon = true }

    private fun startHeartbeat() {
        try { heartbeatThread.start() } catch (_: IllegalThreadStateException) {}
    }

    fun send(event: String, dataJson: String) {
        if (out == null) return
        // Pre-build the frame on the caller's thread (cheap, no I/O).
        val frame = buildString {
            append("event: ").append(event).append('\n')
            for (line in dataJson.split('\n')) {
                append("data: ").append(line).append('\n')
            }
            append('\n')
        }.toByteArray(Charsets.UTF_8)
        // Socket write must NOT happen on the main thread — Android throws
        // NetworkOnMainThreadException. Dispatch to our private writer.
        try {
            writer.submit {
                val o = out ?: return@submit
                try {
                    synchronized(this) {
                        o.write(frame)
                        o.flush()
                    }
                } catch (t: Throwable) {
                    android.util.Log.d("iappyxRemoteEdit", "SSE send failed event=$event err=${t.javaClass.simpleName}: ${t.message}")
                    close()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // writer already shut down
        }
    }

    fun awaitClose() {
        closed.await()
    }

    fun close() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
        try { heartbeatThread.interrupt() } catch (_: Throwable) {}
        try { writer.shutdown() } catch (_: Throwable) {}
        closed.countDown()
    }
}
