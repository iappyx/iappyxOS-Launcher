/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — re-arms every plugin's persisted scheduler entries after a
 * device reboot. AlarmManager loses pending intents on reboot; we
 * iterate each plugin's `plugin_<id>_iappyx_scheduler` SharedPreferences
 * and re-register the alarms from saved `nextFireAtMs`. Past-due
 * schedules fire ~immediately (treated as "we should have fired during
 * the downtime").
 */
package com.iappyx.launcher.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

public class PluginBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
            && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)
            && !"android.intent.action.PACKAGE_REPLACED".equals(action)) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        java.util.List<PluginRegistry.Entry> entries =
            PluginRegistry.INSTANCE.all(appCtx);
        long now = System.currentTimeMillis();
        for (PluginRegistry.Entry entry : entries) {
            if (!entry.getEnabled()) continue;
            String pluginId = entry.getManifest().getId();
            SharedPreferences prefs = appCtx.getSharedPreferences(
                PluginSchedulerBridge.prefsName(pluginId), Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> kv : prefs.getAll().entrySet()) {
                String subId = kv.getKey();
                JSONObject record;
                try { record = new JSONObject(kv.getValue().toString()); }
                catch (Throwable t) { continue; }
                long fireAt = record.optLong("nextFireAtMs", 0L);
                // Past-due alarms fire as soon as possible — but not
                // literally `now` because AlarmManager would just race
                // the activity. Five-second nudge gives the launcher
                // time to finish booting before we fire.
                if (fireAt < now + 5_000L) fireAt = now + 5_000L;
                PluginSchedulerBridge.armAlarm(appCtx, pluginId, subId, fireAt);
            }
        }
    }
}
