/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

/**
 * Parses the <meta name="iappyx-widget" content="..."> tag out of widget HTML
 * before it's loaded into the WebView. Reads declarative policy so the launcher
 * knows how to treat lifecycle before any JS runs.
 *
 * Grammar: content is a comma-separated list of tokens, e.g.:
 *   "keepAlive"
 *   "pause"
 *   "keepAlive, refresh=30s"
 *
 * Unknown tokens are ignored. Missing meta tag == default policy (pause).
 */
data class WidgetPolicy(
    val keepAlive: Boolean = false,
    val refreshSeconds: Int? = null,
) {
    companion object { val DEFAULT = WidgetPolicy() }
}

object MetaParser {
    private val metaRegex = Regex(
        """<meta\s+name\s*=\s*["']iappyx-widget["']\s+content\s*=\s*["']([^"']*)["']\s*/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val refreshRegex = Regex("""refresh\s*=\s*(\d+)\s*s""", RegexOption.IGNORE_CASE)

    fun parse(html: String?): WidgetPolicy {
        if (html.isNullOrEmpty()) return WidgetPolicy.DEFAULT
        val match = metaRegex.find(html) ?: return WidgetPolicy.DEFAULT
        val content = match.groupValues[1].lowercase()
        val keepAlive = content.contains("keepalive")
        val refresh = refreshRegex.find(content)?.groupValues?.get(1)?.toIntOrNull()
        return WidgetPolicy(keepAlive = keepAlive, refreshSeconds = refresh)
    }
}
