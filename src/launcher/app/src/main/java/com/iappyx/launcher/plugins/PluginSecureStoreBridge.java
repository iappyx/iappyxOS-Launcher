/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `secureStore` capability. Same crypto as SecureStore.kt
 * (EncryptedSharedPreferences with a hardware-backed master key from
 * the Android Keystore), but namespaced per-plugin so plugin A can't
 * read plugin B's credentials. File: `plugin_<id>_iappyx_secure`.
 *
 * Used for things widget authors should NEVER paste into their HTML:
 * Immich API tokens, SMTP passwords, OAuth refresh tokens. The plugin
 * UI (settings.html, P5) puts these via iappyx.secureStore.set; the
 * plugin's runtime reads them via .get. Values are AES-256-GCM
 * encrypted at rest; keys are AES-256-SIV (deterministic, for lookup).
 *
 * Falls back to a no-op (returns null on get, ignores set) if the
 * device's Keystore is unavailable (same failure mode as the existing
 * launcher SecureStore — surface a "secure storage unavailable" error
 * to the user instead of crashing).
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class PluginSecureStoreBridge {

    private final SharedPreferences prefs;

    PluginSecureStoreBridge(Context context, String pluginId) {
        SharedPreferences p = null;
        try {
            MasterKey key = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
            p = EncryptedSharedPreferences.create(
                context,
                "plugin_" + pluginId + "_iappyx_secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            android.util.Log.e("iappyx-plugin",
                "PluginSecureStore init failed for " + pluginId + ": " + e.getMessage());
        }
        this.prefs = p;
    }

    @JavascriptInterface
    public void set(String key, String value) {
        if (prefs == null || key == null) return;
        if (value == null) prefs.edit().remove(key).apply();
        else prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String get(String key) {
        if (prefs == null || key == null) return null;
        return prefs.getString(key, null);
    }

    @JavascriptInterface
    public void remove(String key) {
        if (prefs == null || key == null) return;
        prefs.edit().remove(key).apply();
    }

    /** Surface availability so a plugin's settings.html can show
     *  "secure storage is unavailable on this device" instead of
     *  silently no-op'ing. */
    @JavascriptInterface
    public boolean isAvailable() {
        return prefs != null;
    }
}
