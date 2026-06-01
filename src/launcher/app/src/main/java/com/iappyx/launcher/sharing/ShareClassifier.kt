/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Classifies an `ACTION_SEND` intent into one of five widget kinds via
 * web-standards autodetection. Run from a non-main thread (the network
 * fetches block).
 *
 * The detection layer is deliberately host-list-free: every classification
 * decision comes from either the intent itself (MIME type) or from
 * standards-compliant metadata exposed by the shared URL (Open Graph,
 * Schema.org). New platforms work as soon as they expose the same metadata
 * the rest of the web does — no allowlist to update.
 */
object ShareClassifier {

    private const val TAG = "iappyxShareClsf"

    enum class Kind { Video, Music, Article, Image, Note }

    /** Result of classifying an intent. Fed straight into the widget meta. */
    data class Classification(
        val kind: Kind,
        val title: String,
        val sourceUrl: String? = null,
        val sourceHost: String? = null,
        val thumbnailUrl: String? = null,
        val subtitle: String? = null,
        val videoId: String? = null,
        /** Raw bytes to copy into the widget sandbox (e.g. shared image
         *  bitmap content from another app). The widget HTML reads them
         *  via [localAsset] which is also written to meta.json. */
        val bytesToCopy: ByteArray? = null,
        val bytesFilename: String? = null,
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Top-level dispatch. Reads the intent, walks the detection hierarchy. */
    fun classify(context: Context, intent: Intent): Classification {
        val mime = intent.type ?: ""

        // 1. Intent MIME — image / video / audio shared as a URI extra.
        if (mime.startsWith("image/")) return classifyLocalImage(context, intent, mime)
        if (mime.startsWith("video/")) return classifyLocalMedia(context, intent, mime, Kind.Video)
        if (mime.startsWith("audio/")) return classifyLocalMedia(context, intent, mime, Kind.Music)

        // 2. text/* — extract URL or fall through to plain note.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val url = extractFirstUrl(text)
        if (url == null) {
            // No URL → text-only note (can be empty if subject was also blank).
            val noteText = if (subject.isNotEmpty()) "$subject\n\n$text" else text
            return Classification(
                kind = Kind.Note,
                title = subject.ifEmpty { firstLine(text) }.ifEmpty { "Shared note" },
                subtitle = if (noteText.length > 200) noteText.take(200) + "…" else noteText,
                sourceHost = "device",
            )
        }

        // 3. URL present — try web-standards autodetect.
        return try {
            classifyUrl(url, fallbackTitle = subject.ifEmpty { firstLine(text) })
        } catch (t: Throwable) {
            Log.w(TAG, "URL classify failed for $url, falling back to article", t)
            Classification(
                kind = Kind.Article,
                title = subject.ifEmpty { firstLine(text) }.ifEmpty { hostOf(url) ?: url },
                sourceUrl = url,
                sourceHost = hostOf(url),
            )
        }
    }

    // ── URL classification ────────────────────────────────────────

    private fun classifyUrl(url: String, fallbackTitle: String): Classification {
        // 2a. HEAD request — if Content-Type is media, classify directly
        //     without fetching the body. Cheaper and works for direct
        //     media URLs (e.g. .mp4 / .mp3 links).
        try {
            val headResp = http.newCall(
                Request.Builder().url(url).head().build(),
            ).execute()
            headResp.use {
                val ct = it.header("Content-Type")?.lowercase()?.substringBefore(';') ?: ""
                if (ct.startsWith("video/")) {
                    return Classification(
                        kind = Kind.Video,
                        title = fallbackTitle.ifEmpty { hostOf(url) ?: url },
                        sourceUrl = url,
                        sourceHost = hostOf(url),
                    )
                }
                if (ct.startsWith("audio/")) {
                    return Classification(
                        kind = Kind.Music,
                        title = fallbackTitle.ifEmpty { hostOf(url) ?: url },
                        sourceUrl = url,
                        sourceHost = hostOf(url),
                    )
                }
                if (ct.startsWith("image/")) {
                    return Classification(
                        kind = Kind.Image,
                        title = fallbackTitle.ifEmpty { hostOf(url) ?: url },
                        sourceUrl = url,
                        sourceHost = hostOf(url),
                        thumbnailUrl = url,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "HEAD failed for $url, continuing with GET", t)
        }

        // 2b. GET → parse Open Graph / oEmbed / JSON-LD / <title> from body.
        val resp = try {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build(),
            ).execute()
        } catch (t: Throwable) {
            // No network or server error — best we can do is an article card
            // with the URL host as the source.
            return Classification(
                kind = Kind.Article,
                title = fallbackTitle.ifEmpty { hostOf(url) ?: url },
                sourceUrl = url,
                sourceHost = hostOf(url),
            )
        }
        val body = resp.use {
            if (!it.isSuccessful) return@use null
            try { it.body?.string()?.take(MAX_BODY_BYTES) } catch (_: Throwable) { null }
        } ?: return Classification(
            kind = Kind.Article,
            title = fallbackTitle.ifEmpty { hostOf(url) ?: url },
            sourceUrl = url,
            sourceHost = hostOf(url),
        )

        // Parse standard meta — first non-null wins for kind, title is a
        // fallback chain (og:title → JSON-LD name → <title>).
        val og = parseOpenGraph(body)
        val ld = parseJsonLdType(body)
        val titleFromMeta = og["og:title"]
            ?: og["twitter:title"]
            ?: parseHtmlTitle(body)
            ?: fallbackTitle.ifEmpty { hostOf(url) ?: url }
        val description = og["og:description"]
            ?: og["twitter:description"]
            ?: og["description"]
        // Image fallback chain: og:image → twitter:image → apple-touch-icon
         // → <link rel="icon"> → /favicon.ico. Most blogs / news sites that
        // miss og:image still expose at least a favicon.
        val thumb = absolutize(
            og["og:image"]
                ?: og["twitter:image"]
                ?: parseLinkHref(body, "apple-touch-icon")
                ?: parseLinkHref(body, "icon")
                ?: parseLinkHref(body, "shortcut icon"),
            url,
        ) ?: hostOf(url)?.let { "https://$it/favicon.ico" }

        // YouTube fast-path: a parsed video id is a definitive signal for
        // Kind.Video — runs BEFORE the regular kind decision so it can't be
        // overridden by a missing/wrong og:type. YouTube's HTML often hides
        // og:type behind consent walls / region blocks; relying on the URL
        // shape works regardless.
        val ytId = parseYouTubeId(url)
        val kind = if (ytId != null) {
            Kind.Video
        } else {
            pickKind(
                ldType = ld, ogType = og["og:type"],
                hasOgVideo = og["og:video"] != null,
                hasOgAudio = og["og:audio"] != null,
            )
        }
        val effectiveThumb = thumb ?: ytId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

        // Title fallback chain: if all parsing returned the bare URL, replace
        // with a kind-specific human label so the card doesn't read as raw
        // copy-paste. Most apps share "URL only" with no EXTRA_SUBJECT.
        val effectiveTitle = if (titleFromMeta == url || titleFromMeta == hostOf(url)) {
            when (kind) {
                Kind.Video -> if (ytId != null) "YouTube video" else "Video"
                Kind.Music -> "Track"
                Kind.Image -> "Image"
                Kind.Article -> hostOf(url) ?: "Link"
                Kind.Note -> "Note"
            }
        } else titleFromMeta

        return Classification(
            kind = kind,
            title = effectiveTitle.take(120),
            sourceUrl = url,
            sourceHost = hostOf(url),
            thumbnailUrl = effectiveThumb,
            subtitle = description?.take(200),
            videoId = ytId.takeIf { kind == Kind.Video },
        )
    }

    /** Pick a [Kind] from autodetect signals. JSON-LD is the most authoritative
     *  when present; falls through to og:type, then ogVideo/ogAudio booleans,
     *  then default Article. */
    private fun pickKind(ldType: String?, ogType: String?, hasOgVideo: Boolean, hasOgAudio: Boolean): Kind {
        // 1. JSON-LD @type maps directly.
        when (ldType?.lowercase()) {
            "videoobject", "movieclip", "movie", "tvepisode" -> return Kind.Video
            "musicrecording", "audioobject", "musicvideoobject", "podcastepisode" -> return Kind.Music
            "newsarticle", "blogposting", "article", "scholarlyarticle", "techarticle" -> return Kind.Article
            "imageobject", "photograph" -> return Kind.Image
            null -> {} // fall through
            else -> {} // unknown @type — fall through
        }
        // 2. og:type — common values: video / video.* / music / music.* /
        //    article / website / book / profile / etc.
        val ogt = ogType?.lowercase().orEmpty()
        if (ogt.startsWith("video")) return Kind.Video
        if (ogt.startsWith("music")) return Kind.Music
        if (ogt == "article") return Kind.Article
        // 3. og:video / og:audio presence is a good signal even without og:type.
        if (hasOgVideo) return Kind.Video
        if (hasOgAudio) return Kind.Music
        // 4. Default to article.
        return Kind.Article
    }

    // ── Local-media classification ─────────────────────────────────

    private fun classifyLocalImage(context: Context, intent: Intent, mime: String): Classification {
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val bytes = uri?.let { readBytes(context.contentResolver, it) }
        return Classification(
            kind = Kind.Image,
            title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifEmpty { null }
                ?: "Shared image",
            sourceHost = "device",
            bytesToCopy = bytes,
            bytesFilename = if (bytes != null) "shared.${guessExt(mime)}" else null,
        )
    }

    private fun classifyLocalMedia(context: Context, intent: Intent, mime: String, kind: Kind): Classification {
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val bytes = uri?.let { readBytes(context.contentResolver, it) }
        return Classification(
            kind = kind,
            title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifEmpty { null }
                ?: ("Shared " + kind.name.lowercase()),
            sourceHost = "device",
            bytesToCopy = bytes,
            bytesFilename = if (bytes != null) "shared.${guessExt(mime)}" else null,
        )
    }

    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        Log.w(TAG, "couldn't read shared bytes from $uri", t); null
    }

    private fun guessExt(mime: String): String = when {
        "/" !in mime -> "bin"
        mime.endsWith("/jpeg") || mime.endsWith("/jpg") -> "jpg"
        mime.endsWith("/png") -> "png"
        mime.endsWith("/webp") -> "webp"
        mime.endsWith("/gif") -> "gif"
        mime.endsWith("/heic") || mime.endsWith("/heif") -> "heic"
        mime.endsWith("/mp4") -> "mp4"
        mime.endsWith("/quicktime") -> "mov"
        mime.endsWith("/webm") -> "webm"
        mime.endsWith("/mpeg") -> "mp3"
        mime.endsWith("/mp3") -> "mp3"
        mime.endsWith("/aac") -> "aac"
        mime.endsWith("/ogg") -> "ogg"
        mime.endsWith("/wav") -> "wav"
        else -> mime.substringAfter("/").take(5).ifEmpty { "bin" }
    }

    // ── Parsing helpers ────────────────────────────────────────────

    private fun parseOpenGraph(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // Match <meta property="og:..." content="..."> AND <meta name="..." content="...">
        // Order-tolerant: property/name + content can appear in either order.
        val re = Regex(
            """<meta\s+(?:[^>]*?)(?:property|name)\s*=\s*["']([^"']+)["'](?:[^>]*?)content\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        val re2 = Regex(
            """<meta\s+(?:[^>]*?)content\s*=\s*["']([^"']*)["'](?:[^>]*?)(?:property|name)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        re.findAll(body).forEach { m -> out[m.groupValues[1].lowercase()] = unescapeHtml(m.groupValues[2]) }
        re2.findAll(body).forEach { m -> out.putIfAbsent(m.groupValues[2].lowercase(), unescapeHtml(m.groupValues[1])) }
        return out
    }

    /** Find the most specific `@type` in any inline JSON-LD block. Many sites
     *  emit multiple JSON-LD scripts (BreadcrumbList, Organization, ...) plus
     *  a content-specific one. We look for the FIRST type that maps to one
     *  of our kinds — JSON-LD @type lookup is intentionally lenient. */
    private fun parseJsonLdType(body: String): String? {
        val scriptRe = Regex(
            """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val typeRe = Regex(""""@type"\s*:\s*"([^"]+)"""")
        val matches = scriptRe.findAll(body).toList()
        for (m in matches) {
            val json = m.groupValues[1]
            for (t in typeRe.findAll(json).map { it.groupValues[1] }) {
                val lower = t.lowercase()
                if (lower in setOf(
                        "videoobject", "movieclip", "movie", "tvepisode",
                        "musicrecording", "audioobject", "musicvideoobject", "podcastepisode",
                        "newsarticle", "blogposting", "article", "scholarlyarticle", "techarticle",
                        "imageobject", "photograph",
                    )
                ) return t
            }
        }
        // No mappable type — return first @type if any (so the caller knows
        // there WAS structured data, just unmappable).
        for (m in matches) {
            typeRe.find(m.groupValues[1])?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Find the href of a `<link rel="...">` tag matching [rel] (case-insensitive,
     *  any rel-token order). Returns the first match. Used as a fallback for
     *  og:image-missing pages — favicons / apple-touch-icons are nearly
     *  universal and give us at least *something* visual to render. */
    private fun parseLinkHref(body: String, rel: String): String? {
        // <link rel="apple-touch-icon" href="..."> — order-tolerant
        val a = Regex(
            """<link\s+(?:[^>]*?)rel\s*=\s*["'][^"']*\b""" + Regex.escape(rel) + """\b[^"']*["'](?:[^>]*?)href\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val b = Regex(
            """<link\s+(?:[^>]*?)href\s*=\s*["']([^"']+)["'](?:[^>]*?)rel\s*=\s*["'][^"']*\b""" + Regex.escape(rel) + """\b[^"']*["']""",
            RegexOption.IGNORE_CASE,
        )
        return a.find(body)?.groupValues?.get(1) ?: b.find(body)?.groupValues?.get(1)
    }

    private fun parseHtmlTitle(body: String): String? {
        val m = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body) ?: return null
        return unescapeHtml(m.groupValues[1].replace(Regex("\\s+"), " ").trim()).ifEmpty { null }
    }

    /** Convert a possibly-relative og:image URL to absolute. */
    private fun absolutize(maybeUrl: String?, base: String): String? {
        if (maybeUrl.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(maybeUrl)
            if (u.scheme != null) return maybeUrl
            val baseUri = Uri.parse(base)
            if (maybeUrl.startsWith("//")) "${baseUri.scheme}:$maybeUrl"
            else if (maybeUrl.startsWith("/")) "${baseUri.scheme}://${baseUri.host}$maybeUrl"
            else "${baseUri.scheme}://${baseUri.host}/${maybeUrl.trimStart('/')}"
        } catch (_: Throwable) { maybeUrl }
    }

    private val URL_RE = Regex("""\bhttps?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    private fun extractFirstUrl(text: String): String? = URL_RE.find(text)?.value

    private fun hostOf(url: String): String? = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null }

    /** Match common YouTube URL shapes. Returns the 11-char video id or null. */
    private fun parseYouTubeId(url: String): String? {
        val u = try { Uri.parse(url) } catch (_: Throwable) { return null }
        val host = u.host?.lowercase() ?: return null
        if (host == "youtu.be") {
            val id = u.pathSegments.firstOrNull() ?: return null
            return id.takeIf { it.length == 11 }
        }
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
            val v = u.getQueryParameter("v")
            if (v != null && v.length == 11) return v
            // shorts/<id>, embed/<id>, live/<id>
            val segs = u.pathSegments
            if (segs.size >= 2 && segs.first() in setOf("shorts", "embed", "live")) {
                val id = segs[1]
                return id.takeIf { it.length == 11 }
            }
        }
        return null
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()

    private fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ")

    private const val MAX_BODY_BYTES = 256_000  // 256KB cap; OG meta sits in <head>
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 iappyxOS-Launcher/1.0"
}
