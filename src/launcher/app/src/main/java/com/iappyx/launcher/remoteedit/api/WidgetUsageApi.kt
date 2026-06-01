/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — widget battery / usage view. Same data the
 * on-device WidgetUsageActivity surfaces: per-widget cumulative
 * milliseconds of GPS / tracking / sensors / audio / visibility plus
 * HTTP bytes, since the last reset.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.usage.UsageStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WidgetUsageApi(private val context: Context) {

    fun get(ex: MicroHttpServer.Exchange) {
        val windowStart = UsageStore.windowStartMs(context)
        val durMs = (System.currentTimeMillis() - windowStart).coerceAtLeast(0L)
        val snapshot = UsageStore.snapshot(context)

        // Same drain formula the device uses (WidgetUsageActivity.Row).
        // GPS / tracking weighted heaviest; everything else proportional.
        fun drainScore(gps: Long, track: Long, sensor: Long, audio: Long): Long =
            gps * 5 + track * 5 + sensor * 1 + audio / 2

        val rows = JSONArray()
        snapshot.entries
            .map { (id, o) ->
                val gps = o.optLong("gpsMs", 0L)
                val track = o.optLong("trackingMs", 0L)
                val sensor = o.optLong("sensorMs", 0L)
                val audio = o.optLong("audioMs", 0L)
                val visible = o.optLong("visibleMs", 0L)
                val bytes = o.optLong("bytesIn", 0L)
                val score = drainScore(gps, track, sensor, audio)
                Sextet(id, gps, track, sensor, audio, visible, bytes, score)
            }
            // Hide rows with zero of every metric — they add noise without
            // signalling anything. Matches the device's empty-state behaviour
            // for individual widgets.
            .filter { it.gps + it.track + it.sensor + it.audio + it.visible + it.bytes > 0 }
            .sortedByDescending { it.score }
            .forEach { r ->
                rows.put(JSONObject().apply {
                    put("id", r.id)
                    put("title", lookupTitle(r.id))
                    put("gpsMs", r.gps)
                    put("trackingMs", r.track)
                    put("sensorMs", r.sensor)
                    put("audioMs", r.audio)
                    put("visibleMs", r.visible)
                    put("bytesIn", r.bytes)
                    put("drainScore", r.score)
                })
            }
        JsonResponse.ok(ex, JSONObject().apply {
            put("windowStartMs", windowStart)
            put("durationMs", durMs)
            put("rows", rows)
        })
    }

    /** POST /api/widgets/usage/reset — mirrors the device "Reset counters"
     *  button. Wipes the SharedPreferences row and re-stamps windowStartMs
     *  to now. */
    fun reset(ex: MicroHttpServer.Exchange) {
        UsageStore.resetWindow(context)
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Resolve a widget title from its meta.json (`title` field). Falls
     *  back to the raw id — that way an orphaned widget still appears,
     *  which is precisely the diagnostic the user needs when chasing a
     *  drain leak. Same logic as WidgetUsageActivity.lookupTitle. */
    private fun lookupTitle(widgetId: String): String {
        val meta = File(context.filesDir, "widgets/$widgetId/meta.json")
        if (meta.exists()) {
            try {
                val o = JSONObject(meta.readText(Charsets.UTF_8))
                val t = o.optString("title").trim()
                if (t.isNotEmpty()) return t
            } catch (_: Throwable) { /* fall through */ }
        }
        return widgetId
    }

    private data class Sextet(
        val id: String,
        val gps: Long,
        val track: Long,
        val sensor: Long,
        val audio: Long,
        val visible: Long,
        val bytes: Long,
        val score: Long,
    )
}
