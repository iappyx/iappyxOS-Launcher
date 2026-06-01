/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Wire format for the QR sharing flow — keeps the same envelope the
 * iappyxOS app uses so a phone running the container app and a phone
 * running the launcher can transfer artefacts in either direction.
 *
 *   `IXYQR|seq|total|chunk`
 *
 * - **prefix** `IXYQR` lets the receiver reject random barcodes before
 *   trying to decode.
 * - **seq** 0-based chunk index.
 * - **total** chunk count (every frame carries it so the receiver can pick
 *   any frame as its first read).
 * - **chunk** base64 fragment of the gzip-compressed payload, capped at
 *   [CHUNK_SIZE] bytes — small enough to keep error correction comfortable
 *   and the QR low-density (easier to scan from across a desk).
 *
 * The cycler in [QRSendActivity] paints frames at 250 ms each, matching the
 * receiver in [QRReceiveActivity] which scans continuously and dedupes by
 * seq.
 */
object QrChunker {
    const val PREFIX = "IXYQR"
    const val CHUNK_SIZE = 900
    const val FRAME_INTERVAL_MS = 250L

    /** Compress [payload] with gzip, base64-encode it, and split it into the
     *  envelope frames the receiver will reassemble. Always emits at least
     *  one frame (a zero-length payload becomes a single frame with an
     *  empty chunk). */
    fun encode(payload: ByteArray): List<String> {
        val gz = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gzip -> gzip.write(payload) }
            bos.toByteArray()
        }
        val b64 = Base64.encodeToString(gz, Base64.NO_WRAP)
        val total = ((b64.length + CHUNK_SIZE - 1) / CHUNK_SIZE).coerceAtLeast(1)
        val out = ArrayList<String>(total)
        for (i in 0 until total) {
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, b64.length)
            val chunk = if (start < b64.length) b64.substring(start, end) else ""
            out.add("$PREFIX|$i|$total|$chunk")
        }
        return out
    }

    /** Parse a single frame. Null if the frame doesn't match the envelope or
     *  fails sanity checks. The receiver dedupes by [Frame.seq] and waits
     *  for [Frame.total] unique seqs before assembling. */
    data class Frame(val seq: Int, val total: Int, val chunk: String)

    fun parseFrame(raw: String?): Frame? {
        if (raw == null || !raw.startsWith("$PREFIX|")) return null
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return null
        val seq = parts[1].toIntOrNull() ?: return null
        val total = parts[2].toIntOrNull() ?: return null
        if (total <= 0 || seq < 0 || seq >= total) return null
        return Frame(seq, total, parts[3])
    }

    /** Reassemble the gzip+base64 payload back into raw bytes. Throws if a
     *  chunk is missing or if base64/gzip decoding fails. */
    fun decode(chunks: Map<Int, String>, total: Int): ByteArray {
        val sb = StringBuilder()
        for (i in 0 until total) {
            sb.append(chunks[i] ?: error("Missing chunk $i"))
        }
        val gz = Base64.decode(sb.toString(), Base64.DEFAULT)
        return ByteArrayInputStream(gz).use { bis ->
            GZIPInputStream(bis).use { it.readBytes() }
        }
    }
}
