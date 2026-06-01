# Plugin system

Self-contained module that lets the launcher load JavaScript plugins —
small artefacts (HTML + manifest.json + assets) that extend widgets and
wallpapers with new capabilities (remote photo libraries, calendar
mirrors, push routing, etc.). Patterned after the Remote Edit feature:
delete this folder + the fenced hook blocks in other files and the
launcher still builds.

## Public surface

The rest of the launcher only ever imports `PluginsModule`:

- `PluginsModule.attachCallerBridge(webView, host)` — call from
  `WidgetHost.registerBridges()` / `registerWallpaperBridges()` to give
  widgets and wallpapers the `iappyxPlugins.invoke(...)` JS bridge.
- `PluginsModule.shutdown(context)` — optional, call from
  `LauncherActivity.onDestroy` if memory pressure shows up.
- `PluginsModule.{listInstalledAsJson, setEnabledExternal, exists,
  sourceOf, uninstall}` — surfaced for the dedicated `PluginsActivity`
  + remote-edit `PluginsApi`. No inline section anymore — Settings
  links to `PluginsActivity` directly.

Everything else (manifest parsing, registry, host, JS shim, capability
gating, settings UI, install pipeline) is **internal** to this package.
No other launcher class should ever import `com.iappyx.launcher.plugins.*`
beyond `PluginsModule`.

## File layout

```
java/com/iappyx/launcher/plugins/
  README.md                   # this file
  PluginsModule.kt            # public facade — ONLY external entry
  PluginManifest.kt           # manifest.json parser
  PluginRegistry.kt           # list / enable / disable
  PluginHost.kt               # owns the per-plugin hidden WebViews
  PluginsBridge.java          # caller-side @JavascriptInterface
  PluginPrefs.kt              # internal: persisted plugin state

assets/plugins-system/        # runtime assets
  iappyx-plugin-shim.js       # injected into every plugin WebView

assets/plugins/<id>/          # bundled plugins (ship in the APK)
  manifest.json
  plugin.html
  settings.html               # optional, P5
  icon.png                    # optional
```

User-installed plugins (P4) live under `filesDir/plugins/<id>/`, exact
same shape.

## Removal procedure

To remove the plugin system entirely:

1. Delete `app/src/main/java/com/iappyx/launcher/plugins/`.
2. Delete `app/src/main/assets/plugins-system/` and
   `app/src/main/assets/plugins/`.
3. Search the rest of the codebase for `// PLUGINS: BEGIN` and delete
   each fenced block (currently one in `WidgetHost.java`).
4. Delete `plugins_*` strings from `res/values/strings.xml` (only added
   from P5 onwards — at P1 there are no string resources yet).
5. Rebuild.

User-installed plugin data at `filesDir/plugins/` will remain orphaned
on existing installs — clean up with `adb shell pm clear` if needed.

## Install path

Plugins arrive ONLY via the curated showcase repo at
[iappyxOS-Launcher-showcase/plugins/](https://github.com/iappyx/iappyxOS-Launcher-showcase).
File-sideload (`.iappyxplugin` IntentFilter) and URL-install were
deliberately removed — the showcase is the single, audited entry
point. Bundled plugins ship in `assets/plugins/<id>/` and appear
pre-installed.

User flow:
- **On-device**: Settings → Plugins → "Browse showcase" → Plugins tab → Install
- **From the editor**: Showcase tab → Plugins → Install

Programmatic install (used internally by showcase install flows):
`PluginsModule.installFromBytes(context, zipBytes)`.

## Scope per phase

- **P1:** Plugin package skeleton, caller bridge wired into
  `WidgetHost`. No Settings UI, no capability gating, no install flow,
  no AI integration.
- **P2:** Promise sugar (`await iappyx.plugin('id').method({args})`)
  via shim injection on caller WebViews.
- **P3:** Capability gating — manifests declare what they need; bridges
  only attach when granted.
- **P4:** Install pipeline (`PluginInstaller` engine). File-/URL-
  sideload was removed in favour of showcase-only distribution; the
  engine still runs under showcase install.
- **P5:** Settings UI section (plugin list, on/off, configure,
  uninstall, browse showcase).
- **P6:** Immich plugin shipped as the first real-world consumer.
- **P7:** Showcase integration (browse + install + submit).
- **P8:** AI awareness — each plugin's `aiPrompt` aggregates into the
  widget/wallpaper generator system prompts; `get_plugins` tool for the
  Command Bar.
- **P9:** Scheduler / notification-read / push-routing bridges +
  `isolatedProcess: true` manifest flag.
