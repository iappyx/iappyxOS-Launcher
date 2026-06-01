/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `storage` capability. Per-plugin key-value store, mirrors
 * the widget storage pattern (one SharedPreferences file per plugin
 * id, namespaced as `plugin_<id>_iappyx_store`). Plugin A cannot read
 * plugin B's storage; uninstall wipes the file.
 *
 * @JavascriptInterface signatures match the widget StorageBridge so
 * widget authors writing plugins can use the same calls they know.
 * iappyxStorage.save(k, v) writes synchronously to the per-plugin
 * file. load() is sync, returns null if missing.
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

public class PluginStorageBridge {

    private final SharedPreferences prefs;

    PluginStorageBridge(Context context, String pluginId) {
        // Same naming convention WidgetHost uses for widget storage
        // (`widget_<id>_iappyx_store`), substituting `plugin_` so the
        // two namespaces never collide on disk.
        this.prefs = context.getSharedPreferences(
            "plugin_" + pluginId + "_iappyx_store", Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void save(String key, String value) {
        if (key == null) return;
        prefs.edit().putString(key, value == null ? "" : value).apply();
    }

    @JavascriptInterface
    public String load(String key) {
        if (key == null) return null;
        return prefs.getString(key, null);
    }

    @JavascriptInterface
    public void remove(String key) {
        if (key == null) return;
        prefs.edit().remove(key).apply();
    }

    @JavascriptInterface
    public void clear() {
        prefs.edit().clear().apply();
    }

    /** Returns a JSON object of every key/value, useful for the
     *  Settings inspector and for the plugin's own self-export. */
    @JavascriptInterface
    public String snapshot() {
        org.json.JSONObject out = new org.json.JSONObject();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try { out.put(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
        }
        return out.toString();
    }
}
