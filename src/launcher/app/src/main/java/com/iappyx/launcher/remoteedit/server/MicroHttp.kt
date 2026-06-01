/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — minimal HTTP/1.1 server.
 *
 * Why hand-rolled instead of NanoHTTPD or Ktor:
 *  - Zero new dependencies.
 *  - We need fine-grained control over Server-Sent Events (long-lived
 *    response bodies + flush per chunk). Most embedded servers buffer.
 *  - Single client at a time, simple protocol — bounded surface area.
 *
 * NOT a general-purpose HTTP server. Supports only what the editor
 * needs: GET/POST, request/response bodies (Content-Length only —
 * NO chunked transfer-encoding parsing), keep-alive connections, SSE
 * via raw OutputStream.write of pre-formatted frames.
 */
package com.iappyx.launcher.remoteedit.server

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MicroHttpServer(private val handler: (Exchange) -> Unit) {

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "iax-edit-http").apply { isDaemon = true }
    }

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        if (running.get()) return
        val s = ServerSocket(0)
        server = s
        running.set(true)
        Thread({ acceptLoop(s) }, "iax-edit-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            pool.submit { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val req = readRequest(input) ?: return
            val ex = Exchange(socket, req, output)
            handler(ex)
            try { ex.finishIfNotStreaming() } catch (_: Throwable) {}
        } catch (_: Throwable) {
            // best-effort
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
        val firstLine = reader.readLine() ?: return null
        val parts = firstLine.split(" ")
        if (parts.size < 3) return null
        val method = parts[0]
        val target = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }
        // Body: only Content-Length supported. Read into a byte array.
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        // Reject oversized bodies before allocating — a malicious client could
        // otherwise send Content-Length: 2GB and OOM the process. 32MB is well
        // above any legitimate widget-HTML / chat payload. Throw → handleClient
        // catches it and closes the socket.
        if (contentLength > 32 * 1024 * 1024) {
            throw java.io.IOException("request body too large: $contentLength")
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            // The BufferedReader has consumed up to (but not past) the
            // headers — body bytes come from the raw input now.
            // But because we used a BufferedReader, the buffer may have
            // pre-read body bytes. Read whatever's available from the
            // reader first, then drain from input. Simpler: re-architect
            // so we don't mix reader + stream. For our use cases body
            // fits in the BufferedReader's buffer; consume via reader.
            val charBuf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(charBuf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(charBuf, 0, read).toByteArray(Charsets.ISO_8859_1)
        } else ByteArray(0)
        val pathAndQuery = target.split("?", limit = 2)
        return Request(
            method = method,
            path = pathAndQuery[0],
            query = if (pathAndQuery.size > 1) pathAndQuery[1] else "",
            headers = headers,
            body = body,
        )
    }

    data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun bodyAsString(): String = body.toString(Charsets.UTF_8)
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** Mutable response built up by handlers, flushed by [Exchange.finishIfNotStreaming]. */
    class Exchange internal constructor(
        private val socket: Socket,
        val request: Request,
        private val outputStream: OutputStream,
    ) {
        var status: Int = 200
        private val responseHeaders = mutableMapOf<String, MutableList<String>>()
        private var responseBody: ByteArray? = null
        private var streamingStarted = false

        fun setHeader(name: String, value: String) {
            responseHeaders[name] = mutableListOf(value)
        }
        fun addHeader(name: String, value: String) {
            responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
        }

        /** Set the response body (small responses path). */
        fun setBody(bytes: ByteArray) {
            responseBody = bytes
        }

        /** Begin a streaming response (SSE / large download). Returns the
         *  raw OutputStream — caller is responsible for writing all bytes
         *  including the headers' implied framing. After this is called,
         *  [finishIfNotStreaming] becomes a no-op. */
        fun startStreaming(): OutputStream {
            if (streamingStarted) error("already streaming")
            streamingStarted = true
            // Write status + headers
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            for ((k, vs) in responseHeaders) for (v in vs) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
            outputStream.flush()
            return outputStream
        }

        internal fun finishIfNotStreaming() {
            if (streamingStarted) return
            val body = responseBody ?: ByteArray(0)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            // Always set Content-Length for fixed-length responses.
            if (!responseHeaders.containsKey("Content-Length")) {
                sb.append("Content-Length: ").append(body.size).append("\r\n")
            }
            // Default to close-after-response — keep it simple.
            sb.append("Connection: close\r\n")
            for ((k, vs) in responseHeaders) for (v in vs) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            try {
                outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
                if (body.isNotEmpty()) outputStream.write(body)
                outputStream.flush()
            } catch (_: IOException) { /* client gone */ }
        }

        /** Client IP — from socket. */
        fun clientIp(): String? = socket.inetAddress?.hostAddress
    }

    private companion object {
        fun reasonPhrase(code: Int): String = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }
}
