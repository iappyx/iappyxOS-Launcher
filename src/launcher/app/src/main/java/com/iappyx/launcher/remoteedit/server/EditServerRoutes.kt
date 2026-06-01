/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — route table.
 */
package com.iappyx.launcher.remoteedit.server

import android.app.Activity
import com.iappyx.launcher.remoteedit.api.AboutApi
import com.iappyx.launcher.remoteedit.api.AppsApi
import com.iappyx.launcher.remoteedit.api.AssetsApi
import com.iappyx.launcher.remoteedit.api.BackupApi
import com.iappyx.launcher.remoteedit.api.BridgeProxyApi
import com.iappyx.launcher.remoteedit.api.ChatApi
import com.iappyx.launcher.remoteedit.api.ClippingsApi
import com.iappyx.launcher.remoteedit.api.IconApi
import com.iappyx.launcher.remoteedit.api.IconFiltersApi
import com.iappyx.launcher.remoteedit.api.LayoutApi
import com.iappyx.launcher.remoteedit.api.PairApi
import com.iappyx.launcher.remoteedit.api.PluginsApi
import com.iappyx.launcher.remoteedit.api.PluginSettingsServeApi
import com.iappyx.launcher.remoteedit.api.ProfilesApi
import com.iappyx.launcher.remoteedit.api.SettingsApi
import com.iappyx.launcher.remoteedit.api.ShowcaseApi
import com.iappyx.launcher.remoteedit.api.StateApi
import com.iappyx.launcher.remoteedit.api.StateStreamApi
import com.iappyx.launcher.remoteedit.api.ThemeApi
import com.iappyx.launcher.remoteedit.api.ThumbnailApi
import com.iappyx.launcher.remoteedit.api.TransitionsApi
import com.iappyx.launcher.remoteedit.api.WallpapersApi
import com.iappyx.launcher.remoteedit.api.WidgetApi
import com.iappyx.launcher.remoteedit.api.WidgetPreviewApi
import com.iappyx.launcher.remoteedit.api.WidgetUsageApi
import org.json.JSONObject

class EditServerRoutes(
    private val activity: Activity,
    private val auth: EditServerAuth,
) {
    private val pair = PairApi(auth)
    private val assets = AssetsApi(activity)
    private val state = StateApi(activity)
    private val layout = LayoutApi(activity)
    private val widgets = WidgetApi(activity)
    private val apps = AppsApi(activity)
    private val icons = IconApi(activity)
    private val thumbs = ThumbnailApi(activity)
    private val widgetPreview = WidgetPreviewApi(activity)
    private val bridgeProxy = BridgeProxyApi(activity)
    private val chat = ChatApi(activity)
    private val profiles = ProfilesApi(activity)
    private val clippings = ClippingsApi(activity)
    private val settings = SettingsApi(activity)
    private val wallpapers = WallpapersApi(activity)
    private val backup = BackupApi(activity)
    private val showcase = ShowcaseApi(activity)
    private val transitions = TransitionsApi(activity)
    private val iconFilters = IconFiltersApi(activity)
    private val stateStream = StateStreamApi(activity)
    private val about = AboutApi(activity)
    private val widgetUsage = WidgetUsageApi(activity)
    private val theme = ThemeApi(activity)
    // PLUGINS: BEGIN
    private val plugins = PluginsApi(activity)
    private val pluginSettings = PluginSettingsServeApi(activity)
    // PLUGINS: END

    fun handle(ex: MicroHttpServer.Exchange) {
        val path = ex.request.path
        val method = ex.request.method

        // Public (no auth) routes
        when {
            path == "/" || path == "/index.html" -> {
                // Auto-redirect unauthenticated to /pair.
                if (auth.checkRequest(ex, requireAuth = true) != null) {
                    assets.servePair(ex); return
                }
                assets.serveIndex(ex); return
            }
            path == "/pair" || path == "/pair.html" -> {
                if (method == "GET") { assets.servePair(ex); return }
                if (method == "POST") { pair.tryPair(ex); return }
            }
            path == "/static/app.js" -> { assets.serveStatic(ex, "app.js"); return }
            path.startsWith("/static/") -> { assets.serveStatic(ex, path.removePrefix("/static/")); return }
            // PLUGINS: BEGIN — CORS preflight for the sandboxed settings iframe.
            // The iframe runs with an opaque ("null") origin, so its fetches
            // to /api/plugins/* are cross-origin and trigger preflights.
            // Preflights MUST NOT require auth (browsers omit credentials on
            // them by spec). The actual request is then authed by Bearer
            // token in PluginSettingsServeApi.verifyAuthFor.
            method == "OPTIONS" && path.startsWith("/api/plugins/") -> {
                ex.setHeader("Access-Control-Allow-Origin", "*")
                ex.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                ex.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
                ex.setHeader("Access-Control-Max-Age", "600")
                JsonResponse.empty(ex, 204)
                return
            }
            // Bridge/fetch calls from the SANDBOXED settings iframe run with an
            // opaque origin and carry NO cookie — only a plugin-scoped Bearer
            // token. They must skip the shared cookie gate (which would 401
            // them) and let PluginSettingsServeApi.verifyAuthFor validate the
            // token. Gated on the Bearer header being present so a tokenless
            // request still falls through to the cookie gate (legacy path) —
            // no auth bypass: verifyAuthFor rejects an absent/invalid token.
            method == "POST" && path.startsWith("/api/plugins/") &&
                (path.endsWith("/bridge") || path.endsWith("/fetch")) &&
                ex.request.header("Authorization")?.startsWith("Bearer ") == true -> {
                if (path.endsWith("/bridge")) {
                    pluginSettings.bridge(ex, path.removePrefix("/api/plugins/").removeSuffix("/bridge"))
                } else {
                    pluginSettings.fetch(ex, path.removePrefix("/api/plugins/").removeSuffix("/fetch"))
                }
                return
            }
            // PLUGINS: END
        }

        val rejected = auth.checkRequest(ex, requireAuth = true)
        if (rejected != null) {
            JsonResponse.error(ex, if (rejected == "locked") 429 else 401, rejected)
            return
        }

        when {
            path == "/api/state" && method == "GET" -> state.getState(ex)
            path == "/api/theme" && method == "GET" -> theme.get(ex)
            path == "/api/theme" && method == "POST" -> theme.set(ex)
            path == "/api/state/stream" && method == "GET" -> stateStream.subscribe(ex)
            path == "/api/layout" && method == "GET" -> layout.getLayout(ex)
            path == "/api/layout/move" && method == "POST" -> layout.move(ex)
            path == "/api/layout/resize" && method == "POST" -> layout.resize(ex)
            path == "/api/layout/delete" && method == "POST" -> layout.delete(ex)
            path == "/api/layout/delete_many" && method == "POST" -> layout.deleteMany(ex)
            path == "/api/layout/place_app" && method == "POST" -> layout.placeApp(ex)
            path == "/api/layout/place_widget" && method == "POST" -> layout.placeWidget(ex)
            path == "/api/layout/swap" && method == "POST" -> layout.swap(ex)
            path == "/api/layout/page_add" && method == "POST" -> layout.pageAdd(ex)
            path == "/api/layout/page_delete" && method == "POST" -> layout.pageDelete(ex)
            path == "/api/layout/page_rename" && method == "POST" -> layout.pageRename(ex)
            path == "/api/layout/page_reorder" && method == "POST" -> layout.pageReorder(ex)
            path == "/api/layout/move_to_dock" && method == "POST" -> layout.moveToDock(ex)
            path == "/api/layout/dock_page_add" && method == "POST" -> layout.dockPageAdd(ex)
            path == "/api/layout/dock_page_delete" && method == "POST" -> layout.dockPageDelete(ex)
            path == "/api/layout/folder_create" && method == "POST" -> layout.folderCreate(ex)
            path == "/api/layout/folder_add" && method == "POST" -> layout.folderAdd(ex)
            path == "/api/layout/folder_remove" && method == "POST" -> layout.folderRemove(ex)
            path == "/api/layout/folder_rename" && method == "POST" -> layout.folderRename(ex)
            path == "/api/layout/undo" && method == "POST" -> layout.undo(ex)
            path == "/api/apps" && method == "GET" -> apps.list(ex)
            path.startsWith("/api/icons/") && method == "GET" ->
                icons.iconForPackage(ex, path.removePrefix("/api/icons/"))
            path == "/api/widgets" && method == "GET" -> widgets.list(ex)
            // Usage routes — MUST sit above the generic /api/widgets/<id>
            // matcher below, otherwise they'd be routed as a widget whose
            // id is the string "usage".
            path == "/api/widgets/usage" && method == "GET" -> widgetUsage.get(ex)
            path == "/api/widgets/usage/reset" && method == "POST" -> widgetUsage.reset(ex)
            path.startsWith("/api/widgets/") && path.endsWith("/preview.html") && method == "GET" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/preview.html")
                widgetPreview.preview(ex, id)
            }
            path.startsWith("/api/widgets/") && path.endsWith("/thumb") && method == "GET" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/thumb")
                thumbs.widgetThumb(ex, id)
            }
            path.startsWith("/api/widgets/") && path.endsWith("/storage") && method == "GET" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/storage")
                widgets.listStorage(ex, id)
            }
            path.startsWith("/api/widgets/") && path.endsWith("/storage") && method == "DELETE" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/storage")
                widgets.clearStorage(ex, id)
            }
            path.startsWith("/api/widgets/") && method == "GET" -> {
                val id = path.removePrefix("/api/widgets/")
                widgets.get(ex, id)
            }
            path.startsWith("/api/widgets/") && path.endsWith("/rename") && method == "POST" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/rename")
                widgets.rename(ex, id)
            }
            path.startsWith("/api/widgets/") && path.endsWith("/description") && method == "POST" -> {
                val id = path.removePrefix("/api/widgets/").removeSuffix("/description")
                widgets.updateDescription(ex, id)
            }
            path.startsWith("/api/widgets/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/widgets/")
                widgets.delete(ex, id)
            }
            path == "/api/chat" && method == "GET" -> chat.history(ex)
            path == "/api/chat" && method == "POST" -> chat.send(ex)
            path == "/api/chat/clear" && method == "POST" -> chat.clear(ex)
            path == "/api/chat/stream" && method == "GET" -> chat.stream(ex)
            path == "/api/bridge/call" && method == "POST" -> bridgeProxy.call(ex)
            path == "/api/bridge/events" && method == "GET" -> bridgeProxy.events(ex)
            path == "/api/bridge/unsubscribe" && method == "POST" -> bridgeProxy.unsubscribe(ex)
            path == "/api/bridge/unsubscribe_all" && method == "POST" -> bridgeProxy.unsubscribeAll(ex)
            path == "/api/disconnect" && method == "POST" -> {
                auth.unpair()
                JsonResponse.ok(ex, JSONObject().put("ok", true))
            }
            // Profiles
            path == "/api/profiles" && method == "GET" -> profiles.list(ex)
            path == "/api/profiles/save_current" && method == "POST" -> profiles.saveCurrent(ex)
            path == "/api/profiles/create_blank" && method == "POST" -> profiles.createBlank(ex)
            path == "/api/profiles/autoswitch" && method == "POST" -> profiles.setAutoswitchPaused(ex)
            path.startsWith("/api/profiles/") && path.endsWith("/activate") && method == "POST" -> {
                val slug = path.removePrefix("/api/profiles/").removeSuffix("/activate")
                profiles.activate(ex, slug)
            }
            path.startsWith("/api/profiles/") && path.endsWith("/duplicate") && method == "POST" -> {
                val slug = path.removePrefix("/api/profiles/").removeSuffix("/duplicate")
                profiles.duplicate(ex, slug)
            }
            path.startsWith("/api/profiles/") && method == "PUT" -> {
                val slug = path.removePrefix("/api/profiles/")
                profiles.update(ex, slug)
            }
            path.startsWith("/api/profiles/") && method == "DELETE" -> {
                val slug = path.removePrefix("/api/profiles/")
                profiles.delete(ex, slug)
            }
            // Clippings
            path == "/api/clippings" && method == "GET" -> clippings.list(ex)
            path.startsWith("/api/clippings/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/clippings/")
                clippings.delete(ex, id)
            }
            path.startsWith("/api/clippings/") && method == "PATCH" -> {
                val id = path.removePrefix("/api/clippings/")
                clippings.patch(ex, id)
            }
            // Settings
            path == "/api/settings" && method == "GET" -> settings.get(ex)
            path == "/api/settings" && method == "PATCH" -> settings.patch(ex)
            path == "/api/settings/credentials" && method == "POST" -> settings.setCredential(ex)
            path == "/api/settings/refresh_models" && method == "POST" -> settings.refreshModels(ex)
            // Wallpapers
            path == "/api/wallpapers" && method == "GET" -> wallpapers.list(ex)
            path == "/api/wallpapers/active" && method == "POST" -> wallpapers.setActive(ex)
            path.startsWith("/api/wallpapers/") && path.endsWith("/preview.html") && method == "GET" -> {
                val id = path.removePrefix("/api/wallpapers/").removeSuffix("/preview.html")
                wallpapers.preview(ex, id)
            }
            path.startsWith("/api/wallpapers/") && path.endsWith("/rename") && method == "POST" -> {
                val id = path.removePrefix("/api/wallpapers/").removeSuffix("/rename")
                wallpapers.rename(ex, id)
            }
            path.startsWith("/api/wallpapers/") && path.endsWith("/description") && method == "POST" -> {
                val id = path.removePrefix("/api/wallpapers/").removeSuffix("/description")
                wallpapers.updateDescription(ex, id)
            }
            path.startsWith("/api/wallpapers/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/wallpapers/")
                wallpapers.delete(ex, id)
            }
            // Transitions
            path == "/api/transitions" && method == "GET" -> transitions.list(ex)
            path == "/api/transitions/active" && method == "POST" -> transitions.setActive(ex)
            path.startsWith("/api/transitions/") && path.endsWith("/rename") && method == "POST" -> {
                val id = path.removePrefix("/api/transitions/").removeSuffix("/rename")
                transitions.rename(ex, id)
            }
            path.startsWith("/api/transitions/") && path.endsWith("/description") && method == "POST" -> {
                val id = path.removePrefix("/api/transitions/").removeSuffix("/description")
                transitions.updateDescription(ex, id)
            }
            path.startsWith("/api/transitions/") && method == "DELETE" -> {
                val id = path.removePrefix("/api/transitions/")
                transitions.delete(ex, id)
            }
            // Icon filters
            path == "/api/icon_filters" && method == "GET" -> iconFilters.list(ex)
            path == "/api/icon_filters/active" && method == "POST" -> iconFilters.setActive(ex)
            path.startsWith("/api/icon_filters/") && path.endsWith("/rename") && method == "POST" -> {
                val slug = path.removePrefix("/api/icon_filters/").removeSuffix("/rename")
                iconFilters.rename(ex, slug)
            }
            path.startsWith("/api/icon_filters/") && path.endsWith("/description") && method == "POST" -> {
                val slug = path.removePrefix("/api/icon_filters/").removeSuffix("/description")
                iconFilters.updateDescription(ex, slug)
            }
            path.startsWith("/api/icon_filters/") && method == "DELETE" -> {
                val slug = path.removePrefix("/api/icon_filters/")
                iconFilters.delete(ex, slug)
            }
            // Backup
            path == "/api/backup/export" && method == "GET" -> backup.export(ex)
            path == "/api/backup/import" && method == "POST" -> backup.import(ex)
            path == "/api/backup/orphans" && method == "GET" -> backup.orphans(ex)
            path == "/api/backup/cleanup" && method == "POST" -> backup.cleanup(ex)
            path == "/api/about" && method == "GET" -> about.get(ex)
            path.startsWith("/api/about/license/") && method == "GET" -> {
                val kind = path.removePrefix("/api/about/license/")
                about.license(ex, kind)
            }
            // Showcase
            path == "/api/showcase" && method == "GET" -> showcase.list(ex)
            path == "/api/showcase/reload" && method == "POST" -> showcase.reload(ex)
            path.startsWith("/api/showcase/install/") && method == "POST" -> {
                val tail = path.removePrefix("/api/showcase/install/")
                val slash = tail.indexOf('/')
                if (slash <= 0) {
                    JsonResponse.error(ex, 400, "expected /api/showcase/install/{kind}/{slug}")
                } else {
                    val kindStr = tail.substring(0, slash)
                    val slug = tail.substring(slash + 1)
                    showcase.install(ex, kindStr, slug)
                }
            }
            path.startsWith("/api/showcase/submit/") && method == "POST" -> {
                val tail = path.removePrefix("/api/showcase/submit/")
                val slash = tail.indexOf('/')
                if (slash <= 0) {
                    JsonResponse.error(ex, 400, "expected /api/showcase/submit/{kind}/{id}")
                } else {
                    val kindStr = tail.substring(0, slash)
                    val artefactId = tail.substring(slash + 1)
                    showcase.submit(ex, kindStr, artefactId)
                }
            }
            // PLUGINS: BEGIN — manage + (phase 2) settings.html proxy.
            path == "/api/plugins/installed" && method == "GET" -> plugins.listInstalled(ex)
            path.matches(Regex("^/api/plugins/[^/]+/enable$")) && method == "POST" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/enable")
                plugins.setEnabled(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+$")) && method == "DELETE" -> {
                val id = path.removePrefix("/api/plugins/")
                plugins.uninstall(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+/network$")) && method == "GET" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/network")
                plugins.getNetwork(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+/network$")) && method == "POST" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/network")
                plugins.setNetwork(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+/search_exposed$")) && method == "POST" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/search_exposed")
                plugins.setSearchExposed(ex, id)
            }
            // Phase 2: settings.html + bridge proxy for in-browser configure.
            path.matches(Regex("^/api/plugins/[^/]+/settings\\.html$")) && method == "GET" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/settings.html")
                pluginSettings.serveSettings(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+/bridge$")) && method == "POST" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/bridge")
                pluginSettings.bridge(ex, id)
            }
            path.matches(Regex("^/api/plugins/[^/]+/fetch$")) && method == "POST" -> {
                val id = path.removePrefix("/api/plugins/").removeSuffix("/fetch")
                pluginSettings.fetch(ex, id)
            }
            // PLUGINS: END
            else -> JsonResponse.error(ex, 404, "no route: $method $path")
        }
    }
}
