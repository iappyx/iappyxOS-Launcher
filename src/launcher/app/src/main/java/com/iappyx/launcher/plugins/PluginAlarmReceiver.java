/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — receives `scheduler` capability alarm fires. Loads the
 * persisted record, invokes the plugin's exported method, re-arms for
 * the next tick (periodic) or removes the schedule (one-shot).
 *
 * Lifecycle: AlarmManager wakes the launcher process if it's dead.
 * The receiver returns synchronously after `PluginHost.invoke`, which
 * fires-and-forgets — the plugin work continues asynchronously in
 * whatever PluginHost spawns. For "every", we re-register the next
 * fire BEFORE invoking, so a crash mid-invoke doesn't drop the
 * schedule.
 */
package com.iappyx.launcher.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class PluginAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!PluginSchedulerBridge.ACTION_FIRE.equals(intent.getAction())) return;
        String pluginId = intent.getStringExtra(PluginSchedulerBridge.EXTRA_PLUGIN_ID);
        String subId = intent.getStringExtra(PluginSchedulerBridge.EXTRA_SUB_ID);
        if (pluginId == null || subId == null) return;

        Context appCtx = context.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(
            PluginSchedulerBridge.prefsName(pluginId), Context.MODE_PRIVATE);
        String raw = prefs.getString(subId, null);
        if (raw == null) return;  // stale alarm — schedule was cancelled
        JSONObject record;
        try { record = new JSONObject(raw); }
        catch (Throwable t) { prefs.edit().remove(subId).apply(); return; }

        String kind = record.optString("kind");
        String method = record.optString("method");
        String argsJson = record.optString("argsJson", "{}");
        if (method.isEmpty()) return;

        // Re-arm BEFORE invoking the plugin so a crash inside the
        // plugin's JS doesn't drop the schedule.
        if ("every".equals(kind)) {
            long intervalMs = record.optLong("intervalMs", 0L);
            if (intervalMs >= 60_000L) {
                long next = System.currentTimeMillis() + intervalMs;
                try { record.put("nextFireAtMs", next); } catch (Throwable ignored) {}
                prefs.edit().putString(subId, record.toString()).apply();
                PluginSchedulerBridge.armAlarm(appCtx, pluginId, subId, next);
            }
        } else {
            // One-shot ("at"): remove from prefs after we fire.
            prefs.edit().remove(subId).apply();
        }

        // Invoke the plugin's method via the normal PluginHost path.
        // The callback is fire-and-forget: we don't have anywhere to
        // deliver the result (the original cbId belonged to whatever
        // widget WebView registered the schedule, which has long
        // since been torn down). Errors get logged; success is silent.
        PluginHost.invoke(appCtx, pluginId, method, argsJson, json -> {
            android.util.Log.d(
                "iappyx-plugin",
                "[" + pluginId + "] scheduler fire " + method + " -> " + truncate(json));
        });
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
