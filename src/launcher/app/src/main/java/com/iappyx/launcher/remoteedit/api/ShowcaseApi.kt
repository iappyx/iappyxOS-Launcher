/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — showcase browser. Read-only proxy to
 * ShowcaseFetcher; install via ArtefactBundle.install() so re-installs
 * update in place. Network calls are made on the request thread (the
 * MicroHttpServer thread pool absorbs them).
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.ai.SecureStore
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.GithubClient
import com.iappyx.launcher.sharing.GithubException
import com.iappyx.launcher.sharing.ShowcaseFetcher
import com.iappyx.launcher.sharing.ShowcaseInstalledIndex
import com.iappyx.launcher.transitions.TransitionLibrary
import com.iappyx.launcher.wallpaper.WallpaperLibrary
import com.iappyx.launcher.widget.WidgetLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ShowcaseApi(private val context: Context) {

    fun list(ex: MicroHttpServer.Exchange) {
        val index = try {
            ShowcaseFetcher.loadIndex()
        } catch (e: Throwable) {
            return JsonResponse.error(ex, 502, "showcase fetch failed: ${e.message}")
        }
        val resp = JSONObject().apply {
            put("widgets", entriesJson(index.widgets, ShowcaseFetcher.Kind.WIDGET))
            put("wallpapers", entriesJson(index.wallpapers, ShowcaseFetcher.Kind.WALLPAPER))
            put("transitions", entriesJson(index.transitions, ShowcaseFetcher.Kind.TRANSITION))
            put("iconFilters", entriesJson(index.iconFilters, ShowcaseFetcher.Kind.ICON_FILTER))
            // PLUGINS: BEGIN
            put("plugins", entriesJson(index.plugins, ShowcaseFetcher.Kind.PLUGIN))
            // PLUGINS: END
        }
        JsonResponse.ok(ex, resp)
    }

    fun reload(ex: MicroHttpServer.Exchange) {
        ShowcaseFetcher.reload()
        JsonResponse.ok(ex, JSONObject().put("ok", true))
    }

    /** Install a single entry by `kind` + `slug`. The fetch + install
     *  runs synchronously — the editor shows a busy indicator while we
     *  download. Per-entry resources (large SQLite databases etc.)
     *  inflate the response time but stream straight into the install
     *  dir so heap stays bounded. */
    fun install(ex: MicroHttpServer.Exchange, kindStr: String, slug: String) {
        val kind = parseKind(kindStr)
            ?: return JsonResponse.error(ex, 400, "unknown kind '$kindStr'")
        val index = try {
            ShowcaseFetcher.loadIndex()
        } catch (e: Throwable) {
            return JsonResponse.error(ex, 502, "showcase fetch failed: ${e.message}")
        }
        val entry = index.byKind(kind).firstOrNull { it.slug == slug }
            ?: return JsonResponse.error(ex, 404, "no such entry")
        val bundle = try {
            ShowcaseFetcher.fetchEntry(entry)
        } catch (e: Throwable) {
            return JsonResponse.error(ex, 502, "fetch failed: ${e.message}")
        }
        val installedId = try {
            ArtefactBundle.install(context, bundle)
        } catch (e: Throwable) {
            return JsonResponse.error(ex, 500, "install failed: ${e.message}")
        }
        // Broadcast so the live launcher picks up new artefacts (an icon
        // filter spec, transition spec, etc.) without a relaunch.
        try {
            context.sendBroadcast(
                android.content.Intent(LauncherPrefs.CLIPPINGS_CHANGED_ACTION)
                    .setPackage(context.packageName),
            )
        } catch (_: Throwable) {}
        JsonResponse.ok(ex, JSONObject().apply {
            put("ok", true)
            put("id", installedId)
            put("kind", kindStr)
        })
    }

    /** Submit a user-generated artefact to the public showcase by opening
     *  a GitHub PR against the showcase repo. Mirrors the on-device
     *  ShowcaseSubmitDialog flow: pre-flight token / not-bundled, read
     *  source files, call GithubClient.submitArtefact synchronously
     *  (the HTTP thread can park — MicroHttpServer pools requests).
     *
     *  Body: { title, slug, description }. Returns { ok, prUrl }. */
    fun submit(ex: MicroHttpServer.Exchange, kindStr: String, artefactId: String) {
        val kind = parseBundleKind(kindStr)
            ?: return JsonResponse.error(ex, 400, "unknown kind '$kindStr'")
        if (isBundled(kind, artefactId)) {
            return JsonResponse.error(ex, 400, "this $kindStr is already in the showcase (bundled)")
        }
        val token = SecureStore(context).githubToken
        if (token.isNullOrBlank()) {
            return JsonResponse.error(ex, 400, "no GitHub token set — open Settings → Show­case integration on the phone first")
        }
        val obj = JsonResponse.readJsonObject(ex) ?: return JsonResponse.error(ex, 400, "no body")
        val source = readSource(kind, artefactId)
            ?: return JsonResponse.error(ex, 404, "artefact files missing on device")
        val title = obj.optString("title").trim().ifBlank { source.title }
        val slug = slugify(obj.optString("slug").trim()).ifBlank { slugify(source.title) }
        val description = obj.optString("description").trim().ifBlank { source.description }
        val meta = JSONObject().apply {
            put("title", title)
            put("description", description)
            put("author", "community")
            if (source.attribution.isNotEmpty()) {
                put("uses", JSONArray(source.attribution))
            }
            put("added", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date()))
        }.toString(2)
        try {
            val prUrl = GithubClient(token).submitArtefact(
                kindFolder = kindFolder(kind),
                slug = slug,
                contentFileName = source.contentFileName,
                contentText = source.content,
                metaJson = meta,
                title = title,
                description = description,
                attribution = source.attribution,
            )
            JsonResponse.ok(ex, JSONObject().apply {
                put("ok", true); put("prUrl", prUrl); put("slug", slug)
            })
        } catch (e: GithubException) {
            JsonResponse.error(ex, 502, "submit failed: ${e.message}")
        } catch (e: Throwable) {
            JsonResponse.error(ex, 500, "unexpected: ${e.message}")
        }
    }

    private data class Source(
        val title: String,
        val description: String,
        val content: String,
        val contentFileName: String,
        val attribution: List<String>,
    )

    private fun isBundled(kind: ArtefactBundle.Kind, id: String): Boolean = when (kind) {
        ArtefactBundle.Kind.WIDGET -> WidgetLibrary.isBundled(id)
        ArtefactBundle.Kind.WALLPAPER -> WallpaperLibrary.all(context)
            .firstOrNull { it.id == id }?.isUserGenerated == false
        ArtefactBundle.Kind.TRANSITION -> !TransitionLibrary.isUserGenerated(id)
        ArtefactBundle.Kind.ICON_FILTER -> !IconFilterRegistry.isUserGenerated(id)
        // PLUGINS: BEGIN — submitting a bundled plugin to the showcase
        // is a no-op (it's already in the showcase repo).
        ArtefactBundle.Kind.PLUGIN -> id in ShowcaseInstalledIndex.BUNDLED_PLUGINS
        // PLUGINS: END
    }

    private fun readSource(kind: ArtefactBundle.Kind, id: String): Source? = when (kind) {
        ArtefactBundle.Kind.WIDGET -> {
            val dir = File(context.filesDir, "widgets/$id")
            val htmlFile = File(dir, "widget.html")
            val metaFile = File(dir, "meta.json")
            if (!htmlFile.exists()) null else {
                val meta = if (metaFile.exists())
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Generated widget",
                    description = meta?.optString("prompt").orEmpty(),
                    content = htmlFile.readText(),
                    contentFileName = "widget.html",
                    attribution = emptyList(),
                )
            }
        }
        ArtefactBundle.Kind.WALLPAPER -> {
            val htmlFile = File(context.filesDir, "wallpapers/$id.html")
            val metaFile = File(context.filesDir, "wallpapers/$id.json")
            if (!htmlFile.exists()) null else {
                val meta = if (metaFile.exists())
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Generated wallpaper",
                    description = meta?.optString("prompt").orEmpty(),
                    content = htmlFile.readText(),
                    contentFileName = "wallpaper.html",
                    attribution = emptyList(),
                )
            }
        }
        ArtefactBundle.Kind.TRANSITION -> {
            val specFile = File(context.filesDir, "transitions/$id.json")
            val metaFile = File(context.filesDir, "transitions/$id.meta.json")
            if (!specFile.exists()) null else {
                val meta = if (metaFile.exists())
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Generated transition",
                    description = meta?.optString("prompt").orEmpty(),
                    content = specFile.readText(),
                    contentFileName = "spec.json",
                    attribution = emptyList(),
                )
            }
        }
        ArtefactBundle.Kind.ICON_FILTER -> {
            val dir = File(context.filesDir, "icon_filters/$id")
            val specFile = File(dir, "spec.json")
            val metaFile = File(dir, "meta.json")
            if (!specFile.exists()) null else {
                val meta = if (metaFile.exists())
                    runCatching { JSONObject(metaFile.readText()) }.getOrNull() else null
                Source(
                    title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: "Generated icon style",
                    description = meta?.optString("prompt").orEmpty(),
                    content = specFile.readText(),
                    contentFileName = "spec.json",
                    attribution = emptyList(),
                )
            }
        }
        // PLUGINS: BEGIN — for submit, read the plugin's primary file
        // (plugin.html) + use the manifest as the canonical title/
        // description source. The submit path uses the Source.content
        // field as the "primary" file; for plugins that's plugin.html.
        // Showcase repo submitting follows the same convention: a
        // plugins/<id>/ folder with manifest.json + plugin.html +
        // optional settings.html / icon.png. The GithubClient
        // submission flow currently only uploads a single contentFile;
        // a future P7.5 iteration may upload the full folder.
        ArtefactBundle.Kind.PLUGIN -> {
            val dir = File(context.filesDir, "plugins/$id")
            val plugin = File(dir, "plugin.html")
            val manifestFile = File(dir, "manifest.json")
            if (!plugin.exists() || !manifestFile.exists()) null else {
                val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
                Source(
                    title = manifest?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: "Plugin",
                    description = manifest?.optString("description").orEmpty(),
                    content = plugin.readText(),
                    contentFileName = "plugin.html",
                    attribution = emptyList(),
                )
            }
        }
        // PLUGINS: END
    }

    private fun kindFolder(kind: ArtefactBundle.Kind): String = when (kind) {
        ArtefactBundle.Kind.WIDGET -> "widgets"
        ArtefactBundle.Kind.WALLPAPER -> "wallpapers"
        ArtefactBundle.Kind.TRANSITION -> "transitions"
        ArtefactBundle.Kind.ICON_FILTER -> "icon_filters"
        // PLUGINS: BEGIN
        ArtefactBundle.Kind.PLUGIN -> "plugins"
        // PLUGINS: END
    }

    private fun parseBundleKind(s: String): ArtefactBundle.Kind? = when (s) {
        "widget", "widgets" -> ArtefactBundle.Kind.WIDGET
        "wallpaper", "wallpapers" -> ArtefactBundle.Kind.WALLPAPER
        "transition", "transitions" -> ArtefactBundle.Kind.TRANSITION
        "icon_filter", "icon_filters", "iconFilter" -> ArtefactBundle.Kind.ICON_FILTER
        // PLUGINS: BEGIN
        "plugin", "plugins" -> ArtefactBundle.Kind.PLUGIN
        // PLUGINS: END
        else -> null
    }

    private fun slugify(input: String): String {
        if (input.isBlank()) return ""
        return input.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
    }

    private fun entriesJson(entries: List<ShowcaseFetcher.Entry>, kind: ShowcaseFetcher.Kind): JSONArray {
        val installedSlugs = ShowcaseInstalledIndex.installedSlugs(context, kind)
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("slug", e.slug)
                put("title", e.title)
                put("description", e.description)
                put("author", e.author)
                put("attribution", JSONArray().apply { e.attribution.forEach { put(it) } })
                put("installed", e.slug in installedSlugs)
                if (e.resources.isNotEmpty()) {
                    put("resources", JSONArray().apply {
                        for (r in e.resources) put(JSONObject().apply {
                            put("name", r.name); put("size", r.size)
                        })
                    })
                }
            })
        }
        return arr
    }

    private fun parseKind(s: String): ShowcaseFetcher.Kind? = when (s) {
        "widget", "widgets" -> ShowcaseFetcher.Kind.WIDGET
        "wallpaper", "wallpapers" -> ShowcaseFetcher.Kind.WALLPAPER
        "transition", "transitions" -> ShowcaseFetcher.Kind.TRANSITION
        "icon_filter", "icon_filters", "iconFilter" -> ShowcaseFetcher.Kind.ICON_FILTER
        // PLUGINS: BEGIN
        "plugin", "plugins" -> ShowcaseFetcher.Kind.PLUGIN
        // PLUGINS: END
        else -> null
    }
}
