/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Loads the widget system prompt from bundled assets (`assets/widget_prompt.md`).
 *
 * Structure adapted from iappyxOS's `system_prompt.md` — same anti-hallucination
 * preamble, bridge-init/async-callback patterns, CSS defaults, and bridge
 * reference — with widget-specific adjustments (lifecycle meta tag, transparent
 * background, glanceable layout, Promise wrappers) and the push/trigger/task/
 * widget bridges removed since they aren't registered in iappyxOS-Launcher.
 */
object WidgetPromptBuilder {

    /** Cached fully-composed prompt (base + plugin section). Invalidated
     *  via [invalidate] when plugin state changes. */
    @Volatile private var cached: String? = null
    /** Base prompt (asset + version stamp), without plugin context.
     *  Plugins change at runtime; the base doesn't. Cache it separately
     *  so toggling plugins on/off doesn't pay the asset read cost. */
    @Volatile private var cachedBase: String? = null
    /** Iteration protocol appendix (asset content only). The combined
     *  iterate prompt is re-concatenated each call because the base
     *  changes with plugin state. */
    @Volatile private var cachedIterateProtocol: String? = null

    fun load(context: Context): String {
        // Plugins can be enabled/disabled at runtime, so we can't cache
        // the prompt across calls — recompute the plugin section each
        // time. The base prompt (asset + version stamp) IS cached.
        val base = cachedBase ?: run {
            val raw = try {
                context.assets.open("widget_prompt.md").use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
            } catch (e: Exception) {
                FALLBACK_SHORT_PROMPT
            }
            val stamped = raw.replace("{{VERSION_TAG}}", versionTag())
            cachedBase = stamped
            stamped
        }
        // PLUGINS: BEGIN
        val pluginsSection = com.iappyx.launcher.plugins.PluginsModule
            .aggregateAiPrompts(context)
        val withPlugins = if (pluginsSection.isEmpty()) base
            else base + "\n\n---\n\n" + pluginsSection
        // PLUGINS: END
        cached = withPlugins
        return withPlugins
    }

    /** Invalidate the cached prompt — call after a plugin is enabled,
     *  disabled, installed, or uninstalled so the next AI request picks
     *  up the new plugin surface. */
    @Suppress("unused")
    fun invalidate() {
        cached = null
    }

    /** System prompt for iteration. Reuses the full create-prompt (so the
     *  AI has all the bridge / lifecycle / CSS rules in scope) and appends
     *  the iteration-output protocol that documents the `edits` / `full_html`
     *  JSON shapes. The two-file split keeps create-flow concerns out of the
     *  iterate-protocol section and vice versa. */
    fun loadIterate(context: Context): String {
        // Don't cache the combined string — `load()` recomputes the
        // plugin section on every call, so cachedIterate would stale
        // out the moment a plugin is enabled/disabled. The two
        // components (base prompt + iterate protocol) are each
        // cached individually so this concat is essentially free.
        val base = load(context)
        val protocol = cachedIterateProtocol ?: run {
            val read = try {
                context.assets.open("widget_iterate_prompt.md").use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
            } catch (_: Exception) { ITERATE_FALLBACK_PROTOCOL }
            cachedIterateProtocol = read
            read
        }
        return base + "\n\n---\n\n" + protocol
    }

    private fun versionTag(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date())
    }

    /** Minimal fallback if the asset is missing (should never happen in a real build). */
    private const val FALLBACK_SHORT_PROMPT = """
You generate small HTML widgets for the iappyxOS-Launcher home screen. Output
ONLY a complete HTML document starting with <!DOCTYPE html>, nothing else.
Include <meta name="iappyx-widget" content="pause"> in <head>. Transparent body
background, white text, content centered, no scrolling. Bridges available as
iappyx.storage.*, iappyx.httpClient.*, iappyx.location.*, iappyx.sensor.*,
iappyx.audio.*, iappyx.device.*, iappyx.vibration.*, iappyx.tts.*. Many async
methods (location, httpClient, camera, calendar, biometric) also have Promise
wrappers you can await.
"""

    /** Iteration-protocol fallback if the asset is missing — describes the
     *  JSON output shape the launcher's parser expects. */
    private const val ITERATE_FALLBACK_PROTOCOL = """
You are editing an existing widget. Respond with JSON only — no prose, no
markdown fences. Two shapes:

  { "edits": [ { "old_string": "...", "new_string": "..." }, ... ] }
  { "full_html": "<!doctype html>..." }

Use `edits` for small/localised changes. Each `old_string` must appear EXACTLY
ONCE in the current HTML, character for character (whitespace included). Edits
apply in order. To insert, set old_string to a unique anchor and new_string to
that anchor + your insertion. To delete, set new_string to "".

Use `full_html` only for structural rewrites (>10 edits or interlocking
cross-region changes). Preserve the existing <title> and the <meta
name="iappyx-widget" ...> tag.
"""

    /** Backwards-compatible accessor — callers that don't have a Context use this. */
    val SYSTEM_PROMPT: String
        get() = cached ?: FALLBACK_SHORT_PROMPT
}
