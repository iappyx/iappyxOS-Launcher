/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.PlacementStore
import com.iappyx.launcher.model.Clipping
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Share-to-Launcher entry point. Receives `ACTION_SEND` from any app, classifies
 * the payload (image / video / music / article / note) via web-standards
 * autodetection, and adds the result to the launcher's Clippings inbox (the
 * rightmost pager page) with a 24h–7d half-life.
 *
 * Detection hierarchy (no hardcoded host allowlist):
 *  1. Intent MIME → image/video/audio direct kinds
 *  2. URL HEAD → Content-Type media types
 *  3. Page fetch → og:type / og:image / og:title / og:video / og:audio
 *  4. Fallback → Article with the page <title> + favicon
 *
 * Behaviour: blocks briefly with a toast while the network classification
 * runs, then writes the widget files, prepends a [Clipping] to the layout,
 * and broadcasts a layout-change so a running launcher refreshes.
 */
class ShareReceiverActivity : Activity() {

    companion object {
        private const val TAG = "iappyxShare"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent activity — no setContentView. We only show a toast and
        // finish. The receiver task is intentionally fire-and-forget from
        // the user's perspective: they shared from their app, we add the
        // card, they go back to whatever they were doing.
        val src = intent
        if (src == null || src.action != Intent.ACTION_SEND) {
            finish(); return
        }
        Toast.makeText(this, "Adding to home…", Toast.LENGTH_SHORT).show()

        // Network/IO off the main thread. The activity stays alive for the
        // duration; finish() runs on completion or error.
        Thread {
            try {
                val result = ShareClassifier.classify(this, src)
                runOnUiThread { onClassified(result) }
            } catch (t: Throwable) {
                Log.w(TAG, "share classification failed", t)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Couldn't add: " + (t.message ?: "error"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            }
        }.start()
    }

    private fun onClassified(c: ShareClassifier.Classification) {
        val widgetId = "share_" + UUID.randomUUID().toString().substring(0, 12)
        val dir = File(filesDir, "widgets/$widgetId").also { it.mkdirs() }

        // 1. Save the share-card HTML template (single adaptive template
        //    that branches on `kind` field in meta).
        try {
            val tplBytes = assets.open("share_widgets/share_card.html").use { it.readBytes() }
            File(dir, "widget.html").writeBytes(tplBytes)
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't load share template", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // 2. If the classification produced bytes (e.g. shared image), persist
        //    them into the widget's sandbox so the template can read them
        //    even after the source URI expires.
        if (c.bytesToCopy != null && c.bytesFilename != null) {
            try {
                File(dir, c.bytesFilename).writeBytes(c.bytesToCopy)
            } catch (t: Throwable) {
                Log.w(TAG, "couldn't persist shared bytes", t)
            }
        }

        // 3. Write meta.json with the ambient flag + classification fields.
        //    TTL comes from per-kind user setting (Settings → Clippings),
        //    which falls back to the original 1d/7d defaults.
        val prefs = LauncherPrefs(this)
        val ttlMs = prefs.clippingTtlMs(c.kind.name.lowercase())
        val createdAt = System.currentTimeMillis()
        // TTL = 0 → "never expire". Persist expiresAt = 0L for that case so
        // the auto-sweep in WidgetLibrary recognises it as locked-by-default.
        val expiresAt = if (ttlMs <= 0L) 0L else createdAt + ttlMs
        val meta = JSONObject().apply {
            put("title", c.title.take(120))
            put("prompt", "Shared from " + (c.sourceHost ?: "device"))
            put("createdAt", createdAt)
            put("ambient", true)
            put("kind", c.kind.name.lowercase())
            put("ttlMs", ttlMs)
            put("expiresAt", expiresAt)
            c.sourceUrl?.let { put("sourceUrl", it) }
            c.sourceHost?.let { put("sourceHost", it) }
            c.thumbnailUrl?.let { put("thumbnailUrl", it) }
            c.subtitle?.let { put("subtitle", it) }
            c.videoId?.let { put("videoId", it) }
            c.bytesFilename?.let { put("localAsset", it) }
        }
        try {
            File(dir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't save share meta", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // 4. Add to the clippings inbox. Clippings live on a dedicated
        //    rightmost pager page — they intentionally do NOT mix with
        //    regular generated widgets on home pages.
        val store = PlacementStore(this)
        val layout = store.load()
        // Newest first. Insert at index 0 so the freshly-shared card lands
        // at the top of the list once the launcher refreshes.
        layout.clippings.add(0, Clipping(widgetId))
        store.save(layout)

        // 5. Broadcast clippings-change so a running launcher process refreshes
        //    in place. If the launcher isn't running, the next cold start
        //    will load the saved layout and pick up the new clipping.
        //    NOT [LAYOUT_CHANGED_ACTION] — that one fires per swipe for the
        //    wallpaper and would otherwise recycle the pager on every settle.
        val notify = Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION).setPackage(packageName)
        sendBroadcast(notify)

        // 6. Done. The launcher takes over rendering; we get out of the way.
        Toast.makeText(this, "Added — " + c.kind.name.lowercase(), Toast.LENGTH_SHORT).show()
        finish()
    }

}
