/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * On-demand fetch of catalog fonts (FontCatalog) from the Google Fonts GitHub
 * repo into filesDir/fonts/. https-only, host-allowlisted, magic-byte +
 * size-validated, atomic write. Cached forever once downloaded. Callbacks land
 * on the main thread.
 */
package com.iappyx.launcher.theme

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object FontDownloader {

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Ensure [entry] is cached locally, downloading if needed. [onResult] is
     *  posted to the main thread with (success, errorMessageOrNull). */
    fun ensure(context: Context, entry: FontCatalog.Entry, onResult: (Boolean, String?) -> Unit) {
        if (FontCatalog.isDownloaded(context, entry)) {
            android.util.Log.i("FontDownloader", "cached: ${entry.family}")
            onResult(true, null); return
        }
        val app = context.applicationContext
        android.util.Log.i("FontDownloader", "fetch ${entry.family} <- ${entry.url}")
        io.execute {
            val err = try { download(app, entry); null } catch (e: Throwable) { e.message ?: "download failed" }
            if (err == null) {
                FontCatalog.invalidateDownloaded()
                ThemeOverrides.bumpGeneration()
                android.util.Log.i("FontDownloader", "OK ${entry.family} (${FontCatalog.localFile(app, entry).length()} bytes)")
            } else android.util.Log.w("FontDownloader", "FAIL ${entry.family}: $err")
            main.post { onResult(err == null, err) }
        }
    }

    /** Delete a downloaded font + drop its cached Typeface. Returns true if a
     *  file was removed. Bundled fonts are unaffected (not in filesDir). */
    fun delete(context: Context, entry: FontCatalog.Entry): Boolean {
        ThemeFonts.evictCatalog(entry.family)
        val f = FontCatalog.localFile(context.applicationContext, entry)
        val removed = f.exists() && f.delete()
        FontCatalog.invalidateDownloaded()
        ThemeOverrides.bumpGeneration() // refresh font-resolving caches (e.g. if the deleted font was active)
        return removed
    }

    private fun download(context: Context, entry: FontCatalog.Entry) {
        if (!entry.url.startsWith("https://")) throw SecurityException("not https")
        val req = Request.Builder().url(entry.url)
            .header("User-Agent", "iappyxOS-Launcher")
            .build()
        client.newCall(req).execute().use { resp ->
            val host = resp.request.url.host
            if (host != "github.com" && !host.endsWith(".githubusercontent.com")) {
                throw SecurityException("blocked host: $host")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val bytes = (resp.body ?: throw IOException("empty body")).bytes()
            if (bytes.size < 1024 || bytes.size > MAX_BYTES) throw IOException("bad size ${bytes.size}")
            if (!looksLikeFont(bytes)) throw IOException("not a font file")
            val dir = FontCatalog.dir(context)
            // Unique tmp per download so two concurrent fetches of the same
            // font don't write the same scratch file and corrupt it.
            val tmp = File(dir, "${entry.file}.${System.nanoTime()}.tmp")
            tmp.writeBytes(bytes)
            val dest = File(dir, entry.file)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** sfnt / WOFF magic at the start of the file. */
    private fun looksLikeFont(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val tag = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        return tag == 0x00010000 || // TrueType
            tag == 0x4F54544F ||     // 'OTTO' (CFF/OpenType)
            tag == 0x74727565 ||     // 'true'
            tag == 0x74746366 ||     // 'ttcf'
            tag == 0x774F4646 ||     // 'wOFF'
            tag == 0x774F4632        // 'wOF2'
    }
}
