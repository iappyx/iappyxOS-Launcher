# iappyxOS Launcher — Frequently Asked Questions

## General

### What is iappyxOS Launcher?
A full Android home-screen launcher that you can talk to. Most launchers let you arrange icons; this one also lets you *describe* widgets ("show me hourly weather as colored bars") and wallpapers ("a slow lava-lamp field that reacts to my battery") in plain language, and it generates them as real on-device HTML/JS that runs in its own sandbox. Underneath that, it's a perfectly conventional launcher — app icons, folders, stock Android widgets, an app drawer, universal search, profiles, and a swipeable dock.

### Who is it for?
Anyone who wants their home screen to do more than hold icons. If you can describe a glanceable widget — a countdown, a server status panel, a transit board, a habit tracker — you can have it on your home screen in seconds, without writing code or finding an app that does exactly that.

### Is it free?
Yes. MIT-licensed, no in-app purchases, no ads, no tiers. The AI features need your own Anthropic API key (or you can use the manual HTML-paste flow with any AI chat). Everything else is free forever.

### Does it need an internet connection?
Only for AI generation (sending your request to the AI) and the Showcase browser (fetching the community index). Everything else — every widget render, every wallpaper, the whole launcher — works completely offline. The launcher never phones home at render time.

### Do I need iappyxOS to run this?
No. [iappyxOS](https://github.com/iappyx/iappyxOS) (the on-device APK generator) and iappyxOS Launcher are sibling projects. Either one works on its own. They share the same widget HTML format, so a generated widget runs in both contexts.

---

## Widgets & wallpapers

### How do I create a widget?
Three ways:
1. **AI command bar** (pager position 0 — the page to the left of home page 1): describe what you want. The AI picks a sensible placement, or tap an empty cell first to pin it there.
2. **Manual paste**: edit mode → tap an empty cell → "+ Generated widget" → "Paste HTML manually." For users without an API key.
3. **Showcase**: Settings → Showcase → tap a widget → install. Curated community widgets.

### How do I create a wallpaper?
1. **AI**: ask the command bar ("make me a wallpaper that …"). The launcher generates a complete HTML5 document running in the `:wallpaper` process and points you at the system picker.
2. **Bundled**: Settings → Live wallpaper → pick from the bundled set (rotating gradient, falling snow, **battery jelly**, and more).
3. **Showcase**: Settings → Showcase → Wallpapers → install.

After install you may need to re-set "iappyxOS Live" via the Android system wallpaper picker — Android drops the live-wallpaper selection on every package replace.

### What's the difference between a "widget" and a "generated widget"?
- A **stock widget** is the standard Android widget you'd add from any launcher (weather, calendar, music). The launcher hosts them via `AppWidgetHost`.
- A **generated widget** is iappyxOS-specific: a complete HTML5 document running in a WebView with a broad native bridge surface (sensors, network, storage, and more — see the README table). Each placement is a sandboxed instance with its own files + prefs.

You can place either kind in any cell — they share the grid.

### How are AI-generated widgets rendered if I'm offline?
Generation is a one-shot at create-time. The resulting HTML is stored on-device, and every render thereafter is offline. The widget itself can choose to make HTTP calls (to a weather API, etc.) — that's its own design. The launcher doesn't re-contact the AI to render.

### What are Quick Widgets?
Five Quick Settings tiles (the toggles in your notification shade) that each pop a widget as a translucent panel over whatever app you're currently in. Same widget runtime as the home grid — handy for a "scan a code," "log a habit," or "toggle the lights" panel you can reach without leaving your current app.

### What's the Clippings inbox?
The rightmost home page is a **share target**. Share a YouTube link, an article, a song, an image, or a note from any app's share sheet and it lands in Clippings as a capture you can revisit later. Each kind has its own auto-expiry (Settings → Clippings TTL — e.g. videos expire after a day, notes never) so the inbox cleans itself up instead of becoming a junk drawer.

### Can I see which widget is draining my battery?
Yes — **Settings → Battery usage by widget**. Generated widgets that use power-relevant bridges (GPS, sensors, audio) accumulate proportional usage counters, and this screen ranks them by estimated drain (GPS weighted heaviest). The numbers aren't Joules-accurate, but they make "this one widget held GPS for an hour" obvious so you can fix or remove it.

### Can I lock individual apps?
Yes. **Settings → App locks** lets you hide chosen apps behind biometric authentication — launching them from the home screen, dock, drawer, or search prompts for fingerprint/face first.

---

## Icons, packs & shapes

### Why do some app icons look "boxed" inside the shape?
Apps ship icons in two formats. Modern apps ship *adaptive* icons that fill the cell. Older or third-party apps ship a flat PNG — the launcher shapes those by detecting the opaque artwork and scaling it to fill, so a self-contained icon (e.g. a logo on a white rounded square) reads edge-to-edge instead of floating in a small box.

### Can I use a third-party icon pack?
Yes. Install any Nova/ADW-compatible icon pack from the Play Store, then **Settings → Display & Appearance → Icon pack** → pick it. Themed apps swap to the pack's art. For apps the pack doesn't cover, toggle **Mask unthemed icons** to apply the pack's background/mask so the grid stays cohesive. You can also long-press any app → **Change icon** to override a single app's icon by hand.

### How do I customize my icon shape / icon filter?
The shape (squircle, circle, rounded square, hexagon, heart, or arbitrary SVG path) is part of the icon-filter spec:
- **AI**: ask "make my icons look [whatever]" — you get a complete `IconFilterSpec` including a shape.
- **Manual**: Settings → Icons → pick a bundled filter or paste a JSON `IconFilterSpec`.

When an icon pack is active it takes precedence over color filters — the pack's curated look wins so they don't clash.

---

## Profiles, triggers & search

### How do I make the launcher switch profiles automatically?
**Settings → Profiles**: save your current state as a named profile ("Work", "Home"), then set conditions — WiFi SSID, geofence, Android Auto, system dark/light. The trigger watcher runs out-of-process (`:trigger`), so the launcher doesn't need to be foreground.

### What does universal search cover?
Swipe down for a Spotlight-style sheet that live-filters across installed apps, contacts (with permission), the device's Settings activities (works on Pixel/Samsung/OnePlus with localized labels), and any plugins that opt into search. Recent searches show as chips; Enter launches the top result.

### Wait — search results can be interactive?
Yes. Plugins that participate in search return **interactive inline mini-widgets**, not just links. Search "living room" and a Hue result can include a brightness slider you dim right there; a Home Assistant result can have a toggle; a Paperless or Immich result can preview and open the document/photo. You act on the result inside the search sheet without launching an app. Each plugin decides what its search results render (via its `universalSearch(query)` method), and you can turn participation off per-plugin if you'd rather keep search to apps only.

### What are plugins?
Capability-gated integrations with external services — Home Assistant, Spotify, Immich, Paperless-ngx, GitHub, Microsoft 365, Google Workspace, Philips Hue, Unraid, MQTT, AdGuard Home. Installed from the Showcase, configured in a sandboxed settings page, and reachable by widgets through a gated bridge proxy. Credentials live in the plugin's own scope.

---

## Settings & data

### Do I need an API key?
Only for AI features (command bar, widget/wallpaper/transition/icon-filter generation). Drop an Anthropic key into **Settings → AI** — stored in Android's hardware-backed `EncryptedSharedPreferences`. No key ⇒ AI features show a "no key" message; icons, folders, stock widgets, drawer, search, plugins, and manual HTML paste all still work.

### Where is my data stored? Is anything sent to the cloud?
Everything is on-device:
- Layout, profiles, wallpaper choice, icon filter/pack, settings → local files in private storage.
- AI keys → hardware-backed `EncryptedSharedPreferences`.
- Per-widget data (storage, SQLite, cached files) → per-widget sandbox dirs, isolated from each other.

Network requests go only to: your configured AI provider (when you ask the AI to do something), `raw.githubusercontent.com` (when you open the Showcase), and Firebase Cloud Messaging (only when a widget uses the push bridge). No analytics, tracking, telemetry, crash reporting, or ad SDK.

### Can I import my layout from Nova / Niagara / etc.?
No — formats differ. The launcher has its own backup/restore (Settings → Backup) via a `.iappyxbackup` zip. An inter-launcher translator isn't on the roadmap unless someone wants to build one.

### How do I edit my home screen from a computer?
**Settings → Edit on another device** starts a foreground service that serves a local web editor on your LAN. Open the shown URL on a laptop on the same WiFi and rearrange from a big screen, with live two-way sync. The service keeps running (with a persistent notification) so the laptop session survives the phone screen locking.

---

## Troubleshooting

### Why is the APK around 50 MB?
Most of it is ML Kit — image labeling and selfie segmentation are bundled (~25 MB combined) because no thin Play Services variant exists for either. Barcode and text recognition use the thin variants (~150 KB each, download models on first use). R8 minification + resource shrinking trims another ~18 MB of unused code. Older builds were ~86 MB before the size pass.

### Do I need Google Play Services?
Mostly no — but two bridges go dark without it: `camera.scanQR` and `camera.scanText` (and their `scanFrame*` siblings) call into Play Services ML Kit modules and short-circuit with `{ok: false, error: "requires Google Play Services"}` on no-GMS devices (GrapheneOS without microG, CalyxOS, /e/OS, Huawei, AOSP without GApps). Everything else works the same. To get those two bridges on a no-GMS device: install microG, or rebuild with bundled ML Kit (swap the `play-services-mlkit-*` deps back to `com.google.mlkit:*` in `app/build.gradle`, +~16 MB).

### Why ARM64 only? Why won't this run on my emulator?
ML Kit ships 64-bit-only native libraries, and generated widgets that use ML features inherit that constraint. Including 32-bit/x86 variants would bloat the APK with unusable code. The fix is "test on a real ARM64 phone" — practically every Android phone shipped after 2018.

### Why does it ask for so many permissions?
It only asks for what a bridge needs, when a widget actually uses it. The launcher itself needs just: default-home assignment, `QUERY_ALL_PACKAGES` (to list apps for the drawer), `POST_NOTIFICATIONS` on Android 13+ (badges), and `BIND_NOTIFICATION_LISTENER_SERVICE` (granted explicitly, only if you turn on badges). Camera, location, contacts, mic, BLE, NFC, sensors, calendar are requested by widgets that need them — you can deny any request.

### I changed something but the wallpaper isn't reacting
Some wallpaper changes (layout JSON, wallpaper ID) are pushed via broadcasts. If it looks stale, force-stop the launcher and re-set the wallpaper via the Android system picker. This is mostly an issue right after `adb install -r` — Android drops the live-wallpaper selection on every package replace.

### How do I uninstall?
Set a different home app first (Settings → System → Default home app), then **Settings → Apps → iappyxOS Launcher → Uninstall**. If you used one of its live wallpapers, set a different wallpaper in the system picker first — tidier, though Android handles it gracefully.

---

## Security

### Can a widget I install secretly do bad things?
A widget runs inside the launcher process with the launcher's permissions. It **can** read its own sandboxed storage/SQLite, use bridges you've granted (Android prompts the first time), and make HTTP requests. It **cannot** read other widgets' storage (the `WidgetHost extends ContextWrapper` scoping), read your AI key (different prefs scope), or launch arbitrary intents (the intent bridge is gated).

One opt-in to know about: a widget can pass `trustAllCerts: true` to the HTTP bridge to skip TLS verification — when that happens the launcher shows a one-time toast. If you see it and didn't expect it, that widget is doing something unusual; uninstall it from Settings → Manage widgets. Showcase widgets are curated; AI-generated widgets are inspectable in their HTML before install.

### Why a separate `:wallpaper` process?
So a misbehaving wallpaper can't take the launcher down with it — WebView crashes are scoped to their process. The wallpaper process also has a deliberately small bridge subset (8 bridges, no intent launching, no clipboard, no audio): wallpapers should be ambient, not application-grade.

### Why a separate `:trigger` process?
Triggers need to fire even when the launcher activity isn't foreground. Out-of-process means the trigger watcher survives activity teardown and starts on boot independently.

---

## Technical

### Will my widgets keep working if I uninstall the launcher?
No — the bridge surface lives in the launcher. Reinstall and your backup (`.iappyxbackup`) brings widgets back; without a backup they're gone. The same widget HTML can also run as an iappyxOS-generated standalone APK if you use that project too — same bridge surface, different host.

### What Android versions are supported?
**API 29 (Android 10) or newer.** Built against SDK 35 (Android 15). ARM64 only.

### Is the source code available?
Yes — MIT-licensed. Build it with `./build.sh` (JDK 17, Android SDK, an ARM64 device). See the README for details.

---

## The hard questions

### Why is it called a "launcher"? It feels more like a platform.
It is a launcher in the strict Android sense — it's your home app, it hosts your icons and stock widgets and the app drawer. But the interesting part isn't the icon grid; it's that your home screen becomes a surface you can *program by description*. A launcher that generates and sandboxes its own HTML widgets, runs a separate wallpaper process, talks to home automation through plugins, and can be edited from a laptop is doing rather more than launching apps. "Launcher" is the honest label; the experience is closer to a programmable home screen.

### Did you write all the code yourself?
No. iappyxOS Launcher was built with heavy use of AI — primarily Claude — for code generation, architecture, and bug hunting. The idea, direction, and every design choice are mine; the code is heavily AI-generated. That's the whole thesis: if AI can generate apps and widgets for end users, it can generate the tools that generate them too. I believe almost all software will be prompts.

### Who is behind iappyx?
An independent maker from the Netherlands with a non-developer day job, who likes building useful tools and open-sourcing them. Previously built [Instrumenta](https://github.com/iappyx/Instrumenta), a free consulting-style PowerPoint toolbar that started as a COVID side project.

### Isn't this just a WebView in a launcher?
Yes — and that's the point. A WebView that runs in a per-placement sandbox with overridden storage, gets a broad native bridge surface (sensors, BLE, SSH, SMB, HTTP servers, ML Kit, push), is generated from a plain-language prompt, survives in its own crash-isolated wallpaper process, and can be rearranged from a laptop over your LAN. The "just a WebView" does a lot of heavy lifting. The WebView is an implementation detail; the experience is the product.

### Why would I use this instead of Nova / Niagara / KISS?
If all you want is a fast, clean icon grid, those are excellent and you should use them. This launcher is for people who want their home screen to *do* things that don't exist as an app yet — a glanceable panel for their self-hosted server, a custom transit board, a battery-reactive wallpaper, a one-tap automation tile — without hunting for a widget app that happens to match, and without writing code. The conventional-launcher features are all here; the generation is the reason to switch.

### Why is it free?
Because it costs nothing to run — no servers, no cloud, no accounts, no backend. Everything happens on your device. The only external cost is the AI API call, and you bring your own key. There's nothing to monetize without making the product worse. If you find it useful, there's a donate link.

### Are you going to start charging?
No plans to. The architecture is deliberately serverless — no infrastructure means no running costs means no pressure to monetize. If the project grows and needs funding for development time, a paid version with extra features is more likely than paywalling the free one.

### Why not build a marketplace around the widgets?
Because that needs servers, moderation, accounts, payments, and a full-time job maintaining it. Instead there's the [Showcase](https://github.com/iappyx/iappyxOS-showcase) — a curated, GitHub-backed collection of community widgets, wallpapers, transitions, icon filters, and plugins. No accounts, no moderation overhead. Anyone submits via pull request; everyone browses and installs in-app. Open source all the way down.
