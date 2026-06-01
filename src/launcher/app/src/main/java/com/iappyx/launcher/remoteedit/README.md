# Remote Edit Feature

Browser-based editor for the launcher. Self-contained module — can be deleted without touching the rest of the launcher.

## What it does

Opens an HTTP server on the LAN when the user opens Settings → "Edit on another device". Generates a 6-digit pairing code. User opens the URL on their laptop browser, enters the code, gets a two-pane editor: AI chat on the left, visual home-screen board on the right.

## Hard rule: independence

Every change made for this feature is annotated with `REMOTE EDIT FEATURE` markers. To remove the feature without touching launcher code:

1. Delete this entire directory: `app/src/main/java/com/iappyx/launcher/remoteedit/`
2. Delete `app/src/main/assets/remoteedit/`
3. Delete `app/src/main/res/layout/activity_remote_edit.xml`
4. In `app/src/main/AndroidManifest.xml` — delete the block between
   `<!-- REMOTE EDIT FEATURE: BEGIN -->` and `<!-- REMOTE EDIT FEATURE: END -->`
5. In `app/src/main/res/values/strings.xml` — delete the block between
   `<!-- REMOTE EDIT FEATURE: BEGIN -->` and `<!-- REMOTE EDIT FEATURE: END -->`
6. In `SettingsActivity.kt`:
   - Delete the line `setupRemoteEditRow()` near the top of `setupBackupRestore`-adjacent code
   - Delete the `setupRemoteEditRow()` function block between the `// REMOTE EDIT FEATURE: BEGIN` and `// REMOTE EDIT FEATURE: END` markers

   In `res/layout/activity_settings.xml`:
   - Delete the entire Companion-section LinearLayout block between the
     `<!-- REMOTE EDIT FEATURE: BEGIN -->` and `<!-- REMOTE EDIT FEATURE: END -->` markers
7. In `WidgetHost.java`:
   - Delete the small short-circuit block at the top of `deliverResult` between
     `// REMOTE EDIT FEATURE: BEGIN` and `// REMOTE EDIT FEATURE: END`
   - Delete the entire block at the end of the class between
     `// REMOTE EDIT FEATURE: BEGIN` and `// REMOTE EDIT FEATURE: END`
     (contains `invokeBridge`, `createBridgeInstance`, `coerceArg`, the proxy
     callback registry)
8. Run `./gradlew :app:assembleDebug` — should succeed cleanly.

## Architecture quick reference

```
remoteedit/
├── RemoteEditActivity.kt          Entry point. Owns server lifetime.
├── server/
│   ├── MicroHttp.kt               Hand-rolled HTTP/1.1 server.
│   ├── EditServer.kt              Wraps MicroHttp; ties auth + routes.
│   ├── EditServerAuth.kt          Pairing code + IP-pin auth.
│   ├── EditServerRoutes.kt        Route table.
│   ├── JsonResponse.kt            Tiny response helpers.
│   └── SseEmitter.kt              Server-Sent Events (for AI streaming).
├── api/
│   ├── PairApi.kt                 Pairing endpoint.
│   ├── AssetsApi.kt               Serves the web app from assets/.
│   ├── StateApi.kt                Initial state snapshot.
│   ├── LayoutApi.kt               Direct layout CRUD (drag-drop pushes).
│   ├── WidgetApi.kt               Widget library reads.
│   ├── AppsApi.kt                 Installed apps list.
│   ├── IconApi.kt                 App icon PNG bytes.
│   └── ChatApi.kt                 AI chat REST + SSE stream.
├── ai/
│   ├── EditAiSession.kt           AI conversation + tool dispatch loop.
│   └── EditTools.kt               Tools the AI can call to mutate layout.
└── extensions/
    └── HomeLayoutExt.kt           Read-only browser-shaped JSON helpers.

assets/remoteedit/
├── pair.html                      6-digit code entry page.
├── index.html                     Two-pane editor SPA.
└── app.js                         All editor JS (no framework, no bundle).
```

## Known limitations

**Camera widgets (e.g. QR & barcode scanner) don't work in the editor.**
The widget's `navigator.mediaDevices.getUserMedia()` call fails for two
reasons:

1. The phone serves the editor over plain HTTP on a LAN address. Modern
   browsers refuse to expose `navigator.mediaDevices` on non-secure non-
   localhost origins, so the API is `undefined` before the call can even
   request permission.
2. Even if HTTPS were enabled, the iframe would access the **laptop's**
   webcam — not the phone's — which isn't useful for scanning physical
   items the user is holding near their phone.

Workaround: test camera-using widgets on the phone, not in the editor.

**Streaming sensor widgets (compass, live-gps-map) only get one reading
in the current build.** The bridge proxy completes-and-removes the cbId
after the first `deliverResult`. Streaming methods that fire repeatedly
need a persistent SSE subscription channel — not yet implemented.

## Calls into existing launcher code

These are the public APIs we depend on:
- `PlacementStore` (load + save)
- `WidgetLibrary` (all + get)
- `LauncherPrefs.CLIPPINGS_CHANGED_ACTION` (used to broadcast layout changes)
- `AiService.generateWithTools` (the existing AI call infrastructure)
- `SecureStore.anthropicKey` + `anthropicModel`
- `HomeLayout`, `Page`, `Placement`, `CellType` (data model)

No modifications to any of these. We only read public APIs.
