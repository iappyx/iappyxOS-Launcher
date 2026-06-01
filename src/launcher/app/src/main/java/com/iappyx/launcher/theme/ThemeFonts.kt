/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Bundled theme fonts (open-source, OFL 1.1) usable by both widgets (WebView,
 * via injected @font-face served through GeneratedWidgetCell.shouldInterceptRequest)
 * and native UI (Typeface loaded from assets). The font files live in
 * assets/fonts/. Single source of truth for: the picker list, the CSS the
 * widget cssGuard injects, the request→asset mapping the WebView uses to serve
 * the file, and the native Typeface lookup.
 */
package com.iappyx.launcher.theme

import android.content.Context
import android.graphics.Typeface

object ThemeFonts {

    enum class Fallback(val stack: String) {
        SANS("-apple-system, \"Roboto\", \"Segoe UI\", system-ui, sans-serif"),
        SERIF("Georgia, \"Noto Serif\", \"Times New Roman\", serif"),
        MONO("ui-monospace, \"Roboto Mono\", monospace"),
    }

    /** A bundled font. [variable] = single variable-weight file covers all
     *  weights; otherwise [file] is the regular weight and [boldFile] the bold. */
    data class Font(
        val display: String,
        val family: String,
        val file: String,
        val fallback: Fallback,
        val variable: Boolean = true,
        val boldFile: String? = null,
    )

    val ALL = listOf(
        Font("Inter", "Inter", "inter.ttf", Fallback.SANS),
        Font("Poppins", "Poppins", "poppins.ttf", Fallback.SANS, variable = false, boldFile = "poppins-bold.ttf"),
        Font("Nunito", "Nunito", "nunito.ttf", Fallback.SANS),
        Font("Space Grotesk", "Space Grotesk", "spacegrotesk.ttf", Fallback.SANS),
        Font("Lora", "Lora", "lora.ttf", Fallback.SERIF),
        Font("JetBrains Mono", "JetBrains Mono", "jetbrainsmono.ttf", Fallback.MONO),
    )

    /** The CSS `--iappyx-font` value for a bundled font: its family first,
     *  then a sane system fallback so it degrades if the file fails to load. */
    fun cssStack(font: Font): String = "\"${font.family}\", ${font.fallback.stack}"

    /** The bundled font referenced (by family name) in a `--iappyx-font` stack,
     *  or null if the stack doesn't name one of ours. */
    fun fromStack(stack: String?): Font? {
        val s = stack ?: return null
        return ALL.firstOrNull { s.contains("\"${it.family}\"", ignoreCase = true) || s.contains(it.family, ignoreCase = true) }
    }

    /** @font-face block injected into every widget's <head>: the bundled
     *  families always + any DOWNLOADED catalog families. The .ttf only loads
     *  when a family is actually referenced. */
    fun fontFaceCss(context: Context): String = bundledFontFaceCss() + catalogFontFaceCss(context)

    private fun bundledFontFaceCss(): String = buildString {
        for (f in ALL) {
            if (f.variable) {
                append("@font-face{font-family:\"${f.family}\";font-weight:100 900;font-display:swap;src:url(\"https://widget.local/__themefont/${f.file}\")}")
            } else {
                append("@font-face{font-family:\"${f.family}\";font-weight:400;font-display:swap;src:url(\"https://widget.local/__themefont/${f.file}\")}")
                f.boldFile?.let { append("@font-face{font-family:\"${f.family}\";font-weight:600 700;font-display:swap;src:url(\"https://widget.local/__themefont/$it\")}") }
            }
        }
    }

    private fun catalogFontFaceCss(context: Context): String = buildString {
        for (e in FontCatalog.all(context)) {
            if (!FontCatalog.isDownloaded(context, e)) continue
            val w = if (e.variable) "font-weight:100 900;" else "font-weight:400;"
            append("@font-face{font-family:\"${e.family}\";${w}font-display:swap;src:url(\"https://widget.local/__themefont/${e.file}\")}")
        }
    }

    /** InputStream for a `/__themefont/<file>` request — bundled (assets) or a
     *  downloaded catalog font (filesDir). Null if the file isn't one of ours. */
    fun openFontStream(context: Context, file: String): java.io.InputStream? {
        val bundled = ALL.flatMap { listOfNotNull(it.file, it.boldFile) }
        if (file in bundled) {
            return try { context.applicationContext.assets.open("fonts/$file") } catch (_: Throwable) { null }
        }
        val catalogFiles = FontCatalog.all(context).map { it.file }
        if (file in catalogFiles) {
            val f = java.io.File(FontCatalog.dir(context), file)
            if (f.exists()) return try { f.inputStream() } catch (_: Throwable) { null }
        }
        return null
    }

    private val typefaceCache = HashMap<String, Typeface?>()

    /** Drop a downloaded catalog font's cached Typeface (after deletion). */
    fun evictCatalog(family: String) = synchronized(typefaceCache) { typefaceCache.remove("cat:$family") }

    /** Native Typeface for a bundled font (loaded from assets, cached). */
    fun typeface(context: Context, font: Font): Typeface? = synchronized(typefaceCache) {
        typefaceCache.getOrPut(font.family) {
            try { Typeface.createFromAsset(context.applicationContext.assets, "fonts/${font.file}") }
            catch (_: Throwable) { null }
        }
    }

    /** Resolve a `--iappyx-font` stack to a Typeface — bundled OR a downloaded
     *  catalog font — or null if it names neither (caller falls back). */
    fun resolveTypeface(context: Context, stack: String): Typeface? {
        fromStack(stack)?.let { return typeface(context, it) }
        FontCatalog.fromStack(context, stack)?.let { e ->
            if (FontCatalog.isDownloaded(context, e)) {
                return synchronized(typefaceCache) {
                    typefaceCache.getOrPut("cat:${e.family}") {
                        try { Typeface.createFromFile(FontCatalog.localFile(context, e)) } catch (_: Throwable) { null }
                    }
                }
            }
        }
        return null
    }
}
