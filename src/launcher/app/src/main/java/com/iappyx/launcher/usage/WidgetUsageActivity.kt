// USAGE: BEGIN — Widget battery-usage tracking (Tier 2). Removable.
package com.iappyx.launcher.usage

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.widget.Palette
import com.iappyx.launcher.widget.showThemed
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings → Battery usage by widget. Lists every widget that has accumulated
 * any battery-relevant activity since the last reset, sorted by a composite
 * drain estimate (GPS-seconds weighted heaviest, then sensors, then audio).
 *
 * The numbers are proportional, not Joules. Their job is to surface "this
 * widget held GPS for 1 h 51 m while you weren't looking at it" — that's
 * enough to identify a leak without needing the system battery-attribution
 * API (which is signature-only and unavailable to third-party apps).
 */
class WidgetUsageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        com.iappyx.launcher.SettingsScaffold.attach(
            this, getString(com.iappyx.launcher.R.string.settings_battery_usage_label),
        )
    }

    private fun build(): View {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val bgHome = Palette.bgHome(this)
        val bgCell = Palette.bgCell(this)
        val text = Palette.textPrimary(this)
        val hint = Palette.textSecondary(this)
        val accent = Palette.accent(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgHome)
            fitsSystemWindows = true
        }
        // Shared toolbar — back arrow + title at the top, matching other
        // settings detail screens.
        val toolbar = android.view.LayoutInflater.from(this)
            .inflate(com.iappyx.launcher.R.layout.settings_toolbar, root, false)
        root.addView(toolbar)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
            ).apply { weight = 1f }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content)
        root.addView(scroll)

        // ── Window-duration subtitle ─────────────────────────────
        val windowStart = UsageStore.windowStartMs(this)
        val durMs = (System.currentTimeMillis() - windowStart).coerceAtLeast(0L)
        content.addView(TextView(this).apply {
            this.text = "Since last reset · ${formatDuration(durMs)}"
            setTextColor(hint)
            textSize = 13f
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        // ── Reset button ─────────────────────────────────────────
        val resetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        resetRow.addView(com.iappyx.launcher.SettingsButtons.build(
            this,
            com.iappyx.launcher.SettingsButtonKind.SECONDARY,
            "Reset counters",
        ) {
            AlertDialog.Builder(this)
                .setMessage("Reset all widget battery counters?")
                .setPositiveButton("Reset") { _, _ ->
                    UsageStore.resetWindow(this)
                    // Re-stamp the live UsageTracker timestamps so any
                    // resource that's currently active (sensor / GPS / audio /
                    // visible) doesn't dump its pre-reset duration back into
                    // the cleared store on next stop.
                    for (host in com.iappyx.launcher.WidgetHost.hostsByWidgetId.values) {
                        try { host.usage().rebase() } catch (_: Throwable) {}
                    }
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .showThemed()
        })
        content.addView(resetRow)

        // ── Build rows sorted by drain score ─────────────────────
        val rows = UsageStore.snapshot(this).map { (id, o) ->
            Row(
                id = id,
                title = lookupTitle(id),
                sensorMs = o.optLong("sensorMs"),
                gpsMs = o.optLong("gpsMs"),
                trackingMs = o.optLong("trackingMs"),
                audioMs = o.optLong("audioMs"),
                visibleMs = o.optLong("visibleMs"),
                bytesIn = o.optLong("bytesIn"),
            )
        }.sortedByDescending { it.drainScore() }

        if (rows.isEmpty()) {
            content.addView(TextView(this).apply {
                this.text = "No widgets have used a battery-relevant resource yet. " +
                    "Place a widget that uses GPS, sensors, or audio and come back."
                setTextColor(hint)
                textSize = 14f
                setPadding(0, (16 * dp).toInt(), 0, 0)
            })
            return root
        }

        for (r in rows) {
            content.addView(buildRow(r, dp, bgCell, text, hint))
            content.addView(Space(this, (12 * dp).toInt()))
        }

        return root
    }

    private fun buildRow(
        r: Row,
        dp: Float,
        bgCell: Int,
        textColor: Int,
        hintColor: Int,
    ): View {
        val cardPad = (16 * dp).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgCell)
                cornerRadius = 16 * dp
            }
            background = bg
        }
        card.addView(TextView(this).apply {
            text = r.title
            setTextColor(textColor)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        addMetric(card, "GPS (watchPosition)", formatDuration(r.gpsMs), textColor, hintColor, dp,
            primary = r.gpsMs > 0)
        addMetric(card, "GPS tracking (foreground)", formatDuration(r.trackingMs),
            textColor, hintColor, dp, primary = r.trackingMs > 0)
        addMetric(card, "Sensors (compass / accel / etc.)", formatDuration(r.sensorMs),
            textColor, hintColor, dp, primary = r.sensorMs > 0)
        addMetric(card, "Audio playback", formatDuration(r.audioMs),
            textColor, hintColor, dp, primary = r.audioMs > 0)
        addMetric(card, "Visible on screen", formatDuration(r.visibleMs),
            textColor, hintColor, dp, primary = false)

        return card
    }

    private fun addMetric(
        parent: LinearLayout,
        label: String,
        value: String,
        textColor: Int,
        hintColor: Int,
        dp: Float,
        primary: Boolean,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(hintColor)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(if (primary) textColor else hintColor)
            textSize = 13f
            if (primary) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        })
        parent.addView(row)
    }

    private data class Row(
        val id: String,
        val title: String,
        val sensorMs: Long,
        val gpsMs: Long,
        val trackingMs: Long,
        val audioMs: Long,
        val visibleMs: Long,
        val bytesIn: Long,
    ) {
        /** Weighted drain estimate. GPS/tracking weighted heaviest (the proven
         *  worst battery drain on this device), then sensors, then audio. */
        fun drainScore(): Long =
            gpsMs * 5 + trackingMs * 5 + sensorMs * 1 + audioMs / 2
    }

    /** Resolve a widget title from its meta.json (`title` field). Fallback
     *  to the raw id if meta.json is missing or doesn't have a title — that
     *  way even orphaned/uninstalled widgets still appear, which is exactly
     *  what helps diagnose "what was that widget that drained 1h of GPS". */
    private fun lookupTitle(widgetId: String): String {
        val meta = File(filesDir, "widgets/$widgetId/meta.json")
        if (meta.exists()) {
            try {
                val o = JSONObject(meta.readText(Charsets.UTF_8))
                val t = o.optString("title").trim()
                if (t.isNotEmpty()) return t
            } catch (_: Throwable) { /* fall through */ }
        }
        return widgetId
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    private class Space(ctx: android.content.Context, h: Int) : View(ctx) {
        init {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h
            )
        }
    }
}
// USAGE: END
