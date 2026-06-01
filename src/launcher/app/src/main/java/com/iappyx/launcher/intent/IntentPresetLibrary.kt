/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.intent

import android.content.Context
import android.util.Log
import com.iappyx.launcher.model.IntentAction
import com.iappyx.launcher.model.IntentExtra
import org.json.JSONObject

/**
 * Curated catalogue of starter [IntentAction]s for popular cooperative
 * apps (WireGuard, Tasker, Termux, MacroDroid, Obsidian, …). The user
 * picks a preset in the editor, the form is prefilled, the user fills
 * in just the placeholder values (tunnel name, task name, etc.).
 *
 * The catalogue lives in `assets/intent_presets.json`. Hot-swappable
 * by editing the JSON; no Kotlin changes needed to add new entries.
 *
 * Each preset carries a [tips] string surfaced after pick so the user
 * knows which fields to fill and what the target app needs (e.g. a
 * "Allow remote control" toggle in the target's own settings).
 */
object IntentPresetLibrary {

    private const val TAG = "iappyxIntentPresets"
    private const val ASSET_PATH = "intent_presets.json"

    data class Preset(
        val id: String,
        val label: String,
        val category: String,
        val summary: String,
        val needsToggle: String,
        val fillHints: String,
        /** Pre-filled action template — the user adjusts placeholder
         *  values per [fillHints] and saves. */
        val template: IntentAction,
    ) {
        /** Combined display string for the picker dialog. Two-line. */
        fun displayLabel(): String = "$label\n$summary"
    }

    /** Lazy-loaded — the JSON is only parsed on first request. The
     *  catalogue rarely changes per session, so caching is fine. */
    @Volatile private var cached: List<Preset>? = null

    fun all(context: Context): List<Preset> {
        cached?.let { return it }
        val list = try {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            Log.e(TAG, "preset asset load failed: ${t.message}", t)
            emptyList()
        }
        cached = list
        return list
    }

    private fun parse(json: String): List<Preset> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("presets") ?: return emptyList()
        val out = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                out += Preset(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    category = o.optString("category"),
                    summary = o.optString("summary"),
                    needsToggle = o.optString("needsToggle"),
                    fillHints = o.optString("fillHints"),
                    template = parseTemplate(o.getJSONObject("action")),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "skipping malformed preset id=${o.optString("id")}: ${t.message}")
            }
        }
        return out
    }

    private fun parseTemplate(o: JSONObject): IntentAction {
        val verb = runCatching {
            IntentAction.Verb.valueOf(o.optString("verb", "BROADCAST"))
        }.getOrDefault(IntentAction.Verb.BROADCAST)
        return IntentAction(
            label = o.optString("label").ifBlank { "Action" },
            verb = verb,
            packageName = o.optString("packageName").ifBlank { null },
            className = o.optString("className").ifBlank { null },
            action = o.optString("action").ifBlank { null },
            dataUri = o.optString("dataUri").ifBlank { null },
            mimeType = o.optString("mimeType").ifBlank { null },
            categories = emptyList(),
            flags = 0,
            extras = o.optJSONArray("extras")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val type = runCatching {
                        IntentExtra.ExtraType.valueOf(e.optString("type", "STRING"))
                    }.getOrDefault(IntentExtra.ExtraType.STRING)
                    IntentExtra(
                        key = e.optString("key"),
                        type = type,
                        value = e.optString("value"),
                    )
                }
            } ?: emptyList(),
            warmupTargetFirst = false,
        )
    }
}
