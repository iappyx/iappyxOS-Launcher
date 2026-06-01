/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Read-only client for the public
 * [iappyxOS-Launcher-showcase](https://github.com/iappyx/iappyxOS-Launcher-showcase)
 * repo. Pulls the root `showcase.json` index and individual entry files
 * from raw.githubusercontent.com — no authentication required.
 *
 * Indices are cached in memory for the activity's lifetime so re-opening
 * the browser doesn't re-fetch. Force a refresh with [reload].
 *
 * All network calls run on the caller's thread.
 */
object ShowcaseFetcher {

    private const val OWNER = "iappyx"
    private const val REPO = "iappyxOS-Launcher-showcase"
    private const val BRANCH = "main"
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedIndex: ShowcaseIndex? = null

    enum class Kind(val folder: String, val contentFile: String) {
        WIDGET("widgets", "widget.html"),
        WALLPAPER("wallpapers", "wallpaper.html"),
        TRANSITION("transitions", "spec.json"),
        ICON_FILTER("icon_filters", "spec.json"),
        // PLUGINS: BEGIN — a plugin's primary content file. The full
        // plugin lives in a zip alongside (plugin.iappyxplugin), but
        // for showcase-entry shape consistency we point at plugin.html
        // since that's what survives the download path as the "main"
        // file. Install diverges from the other kinds: instead of
        // copying the single content file, the launcher pulls the
        // entire plugin folder into filesDir/plugins/<slug>/.
        PLUGIN("plugins", "plugin.html"),
        // PLUGINS: END
    }

    /** One bundled file entry declared by the showcase manifest. The launcher
     *  downloads each into the new widget's per-id `resources/` dir on
     *  install — same path the WidgetHost StorageBridge reads from. */
    data class ResourceRef(val name: String, val size: Long)

    data class Entry(
        val kind: Kind,
        val slug: String,
        val title: String,
        val description: String,
        val author: String,
        val attribution: List<String>,
        /** Per-entry bundled files (e.g. a SQLite DB). Empty for entries
         *  that don't declare a `resources` array in showcase.json. */
        val resources: List<ResourceRef> = emptyList(),
    )

    /** Progress callback signature for [fetchEntry]. Fires periodically
     *  during downloads of large resources so the install UI can show
     *  bytes-done / bytes-total. */
    fun interface FetchProgress {
        fun onProgress(bytesDone: Long, bytesTotal: Long, currentName: String)
    }

    data class ShowcaseIndex(
        val widgets: List<Entry>,
        val wallpapers: List<Entry>,
        val transitions: List<Entry>,
        val iconFilters: List<Entry>,
        // PLUGINS: BEGIN
        val plugins: List<Entry> = emptyList(),
        // PLUGINS: END
    ) {
        fun byKind(kind: Kind): List<Entry> = when (kind) {
            Kind.WIDGET -> widgets
            Kind.WALLPAPER -> wallpapers
            Kind.TRANSITION -> transitions
            Kind.ICON_FILTER -> iconFilters
            // PLUGINS: BEGIN
            Kind.PLUGIN -> plugins
            // PLUGINS: END
        }
    }

    @Throws(IOException::class)
    fun loadIndex(): ShowcaseIndex {
        cachedIndex?.let { return it }
        val req = Request.Builder().url("$RAW_BASE/showcase.json").get().build()
        val raw = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(
                "Couldn't reach the showcase (HTTP ${resp.code}).",
            )
            resp.body?.string() ?: throw IOException("Empty showcase response")
        }
        val obj = JSONObject(raw)
        val index = ShowcaseIndex(
            widgets = parseList(obj.optJSONArray("widgets"), Kind.WIDGET),
            wallpapers = parseList(obj.optJSONArray("wallpapers"), Kind.WALLPAPER),
            transitions = parseList(obj.optJSONArray("transitions"), Kind.TRANSITION),
            iconFilters = parseList(obj.optJSONArray("iconFilters"), Kind.ICON_FILTER),
            // PLUGINS: BEGIN — older showcase.json files don't have a
            // plugins array; optJSONArray returns null and parseList
            // returns an empty list, so this stays backward-compatible.
            plugins = parseList(obj.optJSONArray("plugins"), Kind.PLUGIN),
            // PLUGINS: END
        )
        cachedIndex = index
        return index
    }

    fun reload() { cachedIndex = null }

    /** Download an entry's content + meta and assemble an [ArtefactBundle.Imported]
     *  ready to feed straight into [ArtefactBundle.install].
     *
     *  When [entry.resources] is non-empty (e.g. the Road Trip NL widget's
     *  60 MB SQLite DB), each declared file is downloaded into the bundle
     *  under `resources/<name>` — the same path shape the install side
     *  expects. [progress] fires per-chunk so the calling UI can render
     *  a progress bar; pass `null` for silent installs.  */
    @Throws(IOException::class)
    fun fetchEntry(entry: Entry, progress: FetchProgress? = null): ArtefactBundle.Imported {
        val contentUrl = "$RAW_BASE/${entry.kind.folder}/${entry.slug}/${entry.kind.contentFile}"
        val metaUrl = "$RAW_BASE/${entry.kind.folder}/${entry.slug}/meta.json"

        // Pre-compute the total bytes the user is about to download so
        // progress callbacks can report a meaningful percentage. The
        // HTML + meta sizes aren't declared in showcase.json (small,
        // typically <50 KB combined) — accounted for as a flat 32 KB
        // overhead so the percentage never overshoots.
        val resourcesTotal = entry.resources.sumOf { it.size }
        val totalBytes = resourcesTotal + 32 * 1024  // tiny overhead for html + meta

        val files = mutableMapOf<String, ByteArray>()
        // HTML + meta: small enough to grab as a single response body.
        files[entry.kind.contentFile] = fetchBytes(contentUrl)
        files["meta.json"] = fetchBytes(metaUrl)
        var cumulativeDone = (32 * 1024).toLong()  // pretend the html+meta covered the overhead
        progress?.onProgress(cumulativeDone, totalBytes, entry.kind.contentFile)

        // PLUGINS: BEGIN — plugins ship a manifest.json (required) and
        // optionally a settings.html + icon.png alongside plugin.html.
        // Pull them in the same pass; settings.html / icon.png are
        // best-effort (the showcase repo may not include them for
        // every plugin).
        if (entry.kind == Kind.PLUGIN) {
            val manifestUrl = "$RAW_BASE/${entry.kind.folder}/${entry.slug}/manifest.json"
            files["manifest.json"] = fetchBytes(manifestUrl)
            // Optional files — swallow 404 so missing files don't fail
            // the whole install.
            for (opt in listOf("settings.html", "icon.png")) {
                val optUrl = "$RAW_BASE/${entry.kind.folder}/${entry.slug}/$opt"
                val bytes = try { fetchBytes(optUrl) } catch (_: Throwable) { null }
                if (bytes != null) files[opt] = bytes
            }
        }
        // PLUGINS: END

        // Resources: stream each so a 60 MB DB doesn't need a single
        // contiguous heap buffer at the OkHttp level (and so per-chunk
        // progress is visible to the user instead of a long stall).
        for (res in entry.resources) {
            val resUrl = "$RAW_BASE/${entry.kind.folder}/${entry.slug}/resources/${res.name}"
            val baos = java.io.ByteArrayOutputStream()
            val req = Request.Builder().url(resUrl).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException(
                    "Couldn't fetch ${res.name} (HTTP ${resp.code}).",
                )
                val body = resp.body ?: throw IOException("Empty body for ${res.name}")
                val expected = if (body.contentLength() > 0) body.contentLength() else res.size
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf); if (n == -1) break
                        baos.write(buf, 0, n)
                        cumulativeDone += n
                        // Throttle progress callbacks: at most once per
                        // 64 KB so the UI thread isn't flooded mid-stream.
                        if (cumulativeDone - lastReport >= 64 * 1024) {
                            lastReport = cumulativeDone
                            progress?.onProgress(cumulativeDone, totalBytes, res.name)
                        }
                    }
                    progress?.onProgress(cumulativeDone, totalBytes, res.name)
                }
                if (expected > 0 && baos.size().toLong() != expected) {
                    // Don't fail outright — Fastly content-length is
                    // sometimes off by one when chunked transfer kicks in
                    // — but log so a real truncation is debuggable.
                    android.util.Log.w(
                        "ShowcaseFetcher",
                        "Size mismatch for ${res.name}: got ${baos.size()} expected $expected",
                    )
                }
            }
            // Map showcase content-file names to ArtefactBundle's canonical
            // shape. Widget bundles expect "widget.html", wallpaper
            // "wallpaper.html", transition "spec.json"; resources are
            // namespaced under resources/ — same prefix the import side
            // (ArtefactBundle.install) extracts from.
            files["resources/${res.name}"] = baos.toByteArray()
        }

        val abKind = when (entry.kind) {
            Kind.WIDGET -> ArtefactBundle.Kind.WIDGET
            Kind.WALLPAPER -> ArtefactBundle.Kind.WALLPAPER
            Kind.TRANSITION -> ArtefactBundle.Kind.TRANSITION
            Kind.ICON_FILTER -> ArtefactBundle.Kind.ICON_FILTER
            // PLUGINS: BEGIN
            Kind.PLUGIN -> ArtefactBundle.Kind.PLUGIN
            // PLUGINS: END
        }
        return ArtefactBundle.Imported(
            kind = abKind,
            title = entry.title,
            prompt = entry.description,
            files = files,
            // Tag the install with the showcase slug so the browser can
            // mark this entry as "already installed" on next visit.
            showcaseSlug = entry.slug,
        )
    }

    private fun fetchBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(
                "Couldn't fetch $url (HTTP ${resp.code}).",
            )
            return resp.body?.bytes() ?: throw IOException("Empty body for $url")
        }
    }

    private fun parseList(arr: JSONArray?, kind: Kind): List<Entry> {
        if (arr == null) return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val attribution = o.optJSONArray("uses")?.let { uses ->
                (0 until uses.length()).map { uses.optString(it) }
                    .filter { it.isNotBlank() }
            } ?: emptyList()
            // Optional per-entry resources array. Each element: { name, size }.
            // Skips malformed entries (missing name) silently — better than
            // failing the entire showcase load on one bad row.
            val resources = o.optJSONArray("resources")?.let { rs ->
                (0 until rs.length()).mapNotNull { idx ->
                    val r = rs.optJSONObject(idx) ?: return@mapNotNull null
                    val name = r.optString("name").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ResourceRef(name = name, size = r.optLong("size", 0L))
                }
            } ?: emptyList()
            out.add(
                Entry(
                    kind = kind,
                    slug = o.optString("slug"),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    author = o.optString("author"),
                    attribution = attribution,
                    resources = resources,
                ),
            )
        }
        return out
    }
}
