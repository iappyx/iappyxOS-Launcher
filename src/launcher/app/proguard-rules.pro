# ─────────────────────────────────────────────────────────────────────
#  iappyxOS-Launcher — R8 rules
#  PHASE B (2026-05-15): shrinking-only. -dontoptimize + -dontobfuscate
#  keep all behaviour and readable names. The diagnostics widget
#  (assets/widgets/diagnostics.html) is the regression check — every
#  rule below exists because removing it makes a diagnostic row turn red.
# ─────────────────────────────────────────────────────────────────────

# Phase B: shrinking only — no inlining, no renaming. Adds back the
# safety net while we learn what else R8 catches.
-dontoptimize
-dontobfuscate

# Don't fail the build on missing optional classes in transitive deps
# (jcifs talks about Kerberos classes that aren't on Android, etc).
-ignorewarnings

# ─── WebView JavaScript bridges ───────────────────────────────────
# Every @JavascriptInterface method is called from injected JS shims
# by EXACT method name. R8 stripping or renaming a single method
# silently breaks that bridge. Blanket rule covers all 245+ methods
# across WidgetHost + PluginXxxBridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# The bridge OBJECTS themselves are registered by string name via
# `webView.addJavascriptInterface(obj, "iappyxFoo")`. Keep the class
# so the binding survives.
-keep class com.iappyx.launcher.WidgetHost { *; }
-keep class com.iappyx.launcher.WidgetHost$* { *; }
-keep class com.iappyx.launcher.plugins.Plugin*Bridge { *; }
-keep class com.iappyx.launcher.plugins.PluginsBridge { *; }

# ─── Reflective access points ─────────────────────────────────────
# PluginNotificationsBridge.getActiveNotifications() reaches
# NotificationBadgeListener's static `instance` field by name via
# reflection. R8 would otherwise rename or drop it.
-keepclassmembers class com.iappyx.launcher.notify.NotificationBadgeListener {
    static *** instance;
}
-keep class com.iappyx.launcher.notify.NotificationBadgeListener { *; }
-keep class com.iappyx.launcher.notify.NotificationBadgeListener$Companion { *; }

# ─── Eclipse Paho MQTT client ─────────────────────────────────────
# Paho uses reflection internally to load pluggable network module
# factories and persistence implementations from META-INF service
# files. Keep the whole package — it's ~150 KB so the lost shrinking
# is negligible.
-keep class org.eclipse.paho.client.mqttv3.** { *; }

# ─── OkHttp / Okio ────────────────────────────────────────────────
# Both ship consumer rules but warn about optional dependencies
# (BouncyCastle, Conscrypt) that aren't on every classpath. Suppress
# warnings so the build doesn't fail with -ignorewarnings off.
-dontwarn okhttp3.**
-dontwarn okio.**

# ─── Bouncycastle (transitive via jsch / okhttp) ──────────────────
# Mostly dead code that R8's tree-shaker drops — keep the warning
# suppression so the BouncyCastleProvider check doesn't trip.
-dontwarn org.bouncycastle.**

# ─── jsch SSH (transitive) ────────────────────────────────────────
# Uses reflection for connection types loaded by name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ─── JCIFS SMB (eu.agno3.jcifs:jcifs-ng) ──────────────────────────
# Network protocol classes loaded by SPI / Class.forName.
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# ─── Firebase (compiled-in, not registered at runtime) ────────────
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ─── ML Kit (consumer rules ship, but the native side wants the
#      Java glue intact) ────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ─── Kotlin metadata ──────────────────────────────────────────────
# kotlin.Metadata is consulted by anything that reflects over Kotlin
# code (we don't currently, but plugins might in future).
-keep class kotlin.Metadata { *; }

# ─── Activities / Services / Receivers declared in AndroidManifest ─
# These are kept automatically by the Android plugin's manifest
# parser, but list explicitly to be safe (R8 has been known to miss
# class strings in tricky manifest references).
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.work.Worker

# ─── WebView client / chrome client subclasses ───────────────────
# Method signatures match exact Android framework types — R8 with
# -dontoptimize wouldn't strip these anyway, but keep belt-and-braces.
-keep public class * extends android.webkit.WebViewClient
-keep public class * extends android.webkit.WebChromeClient
