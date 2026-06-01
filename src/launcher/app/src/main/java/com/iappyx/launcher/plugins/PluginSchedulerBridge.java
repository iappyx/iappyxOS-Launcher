/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `scheduler` capability. AlarmManager-backed scheduling so a
 * plugin can run background work without keeping its WebView resident.
 *
 * JS-side surface (when capability granted):
 *   iappyx.scheduler.every({intervalMin, method, args})  → {ok, subId}
 *     Calls the plugin's exported `method(args)` on its declared cadence.
 *     intervalMin >= 15 (AlarmManager's effective minimum on modern Android).
 *
 *   iappyx.scheduler.at({timestampMs, method, args})      → {ok, subId}
 *     One-shot — fires once at the given wall-clock time.
 *
 *   iappyx.scheduler.cancel({subId})                      → {ok, cancelled:bool}
 *   iappyx.scheduler.list()                               → {ok, schedules:[{subId,kind,method,nextFireAtMs,intervalMin}]}
 *
 * Storage: schedules persist in `plugin_<id>_iappyx_scheduler` SharedPreferences
 * so an APK upgrade / device reboot doesn't drop them. PluginBootReceiver
 * re-arms every registered schedule on `ACTION_BOOT_COMPLETED`.
 *
 * Lifecycle on fire: PluginAlarmReceiver wakes (process resurrects if dead),
 * looks up the schedule, calls PluginHost.invoke which lazily spawns the
 * plugin's WebView and runs the registered method. The receiver finishes
 * synchronously; the launcher process stays alive long enough for the JS
 * to run (Android grants 10s of receiver-class runtime; widgets / wallpaper
 * service typically keep the process alive longer when present).
 */
package com.iappyx.launcher.plugins;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public class PluginSchedulerBridge {

    private final Context appContext;
    private final String pluginId;
    private final PluginInvocationContext invocationCtx;

    PluginSchedulerBridge(Context context, String pluginId, PluginInvocationContext invocationCtx) {
        this.appContext = context.getApplicationContext();
        this.pluginId = pluginId;
        this.invocationCtx = invocationCtx;
    }

    @JavascriptInterface
    public void every(String optionsJson, String cbId) {
        if (cbId == null) return;
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            int intervalMin = Math.max(15, opts.optInt("intervalMin", 60));
            String method = opts.optString("method");
            String argsJson = opts.optJSONObject("args") != null
                ? opts.optJSONObject("args").toString()
                : opts.optString("args", "{}");
            if (method == null || method.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("method required"));
                return;
            }
            String subId = "every_" + UUID.randomUUID().toString().substring(0, 12);
            long intervalMs = intervalMin * 60L * 1000L;
            long firstFireAt = System.currentTimeMillis() + intervalMs;

            JSONObject record = new JSONObject();
            record.put("subId", subId);
            record.put("kind", "every");
            record.put("method", method);
            record.put("argsJson", argsJson);
            record.put("intervalMs", intervalMs);
            record.put("nextFireAtMs", firstFireAt);
            saveSchedule(subId, record);
            armAlarm(appContext, pluginId, subId, firstFireAt);

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("subId", subId);
            out.put("nextFireAtMs", firstFireAt);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson("invalid options: " + t.getMessage()));
        }
    }

    @JavascriptInterface
    public void at(String optionsJson, String cbId) {
        if (cbId == null) return;
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            long fireAt = opts.optLong("timestampMs", 0L);
            String method = opts.optString("method");
            String argsJson = opts.optJSONObject("args") != null
                ? opts.optJSONObject("args").toString()
                : opts.optString("args", "{}");
            if (method == null || method.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("method required"));
                return;
            }
            if (fireAt <= System.currentTimeMillis()) {
                invocationCtx.deliverResult(cbId, errJson("timestampMs must be in the future"));
                return;
            }
            String subId = "at_" + UUID.randomUUID().toString().substring(0, 12);
            JSONObject record = new JSONObject();
            record.put("subId", subId);
            record.put("kind", "at");
            record.put("method", method);
            record.put("argsJson", argsJson);
            record.put("nextFireAtMs", fireAt);
            saveSchedule(subId, record);
            armAlarm(appContext, pluginId, subId, fireAt);

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("subId", subId);
            out.put("nextFireAtMs", fireAt);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson("invalid options: " + t.getMessage()));
        }
    }

    @JavascriptInterface
    public void cancel(String subId, String cbId) {
        if (cbId == null) return;
        if (subId == null || subId.isEmpty()) {
            invocationCtx.deliverResult(cbId, errJson("subId required"));
            return;
        }
        boolean existed = prefs().contains(subId);
        cancelAlarm(appContext, pluginId, subId);
        prefs().edit().remove(subId).apply();
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("cancelled", existed);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable ignored) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":false}");
        }
    }

    @JavascriptInterface
    public void list(String cbId) {
        if (cbId == null) return;
        JSONArray arr = new JSONArray();
        for (java.util.Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            try {
                JSONObject rec = new JSONObject(e.getValue().toString());
                JSONObject summary = new JSONObject();
                summary.put("subId", rec.optString("subId"));
                summary.put("kind", rec.optString("kind"));
                summary.put("method", rec.optString("method"));
                summary.put("nextFireAtMs", rec.optLong("nextFireAtMs"));
                if (rec.has("intervalMs")) {
                    summary.put("intervalMin", rec.optLong("intervalMs") / 60_000L);
                }
                arr.put(summary);
            } catch (Throwable ignored) {}
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("schedules", arr);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson(t.getMessage()));
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(prefsName(pluginId), Context.MODE_PRIVATE);
    }

    private void saveSchedule(String subId, JSONObject record) {
        prefs().edit().putString(subId, record.toString()).apply();
    }

    // ── statics — also used by PluginAlarmReceiver + PluginBootReceiver ──

    /** Pref file name for a plugin's scheduled work. Exposed so the boot
     *  receiver can iterate plugins via PluginRegistry and re-arm. */
    static String prefsName(String pluginId) { return "plugin_" + pluginId + "_iappyx_scheduler"; }

    /** Action used on PendingIntents — namespaced so receivers can
     *  filter unambiguously and we don't collide with the existing
     *  AlarmReceiver / TaskSchedulerReceiver. */
    static final String ACTION_FIRE = "com.iappyx.launcher.plugins.SCHEDULER_FIRE";
    static final String EXTRA_PLUGIN_ID = "pluginId";
    static final String EXTRA_SUB_ID = "subId";

    static void armAlarm(Context context, String pluginId, String subId, long fireAtMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pendingIntent(context, pluginId, subId);
        // Inexact alarms — plugin scheduling is for periodic background
        // work (sync, prefetch, polling); minute-level drift is fine and
        // we want Doze / app-standby to batch us with other wakeups.
        // setAndAllowWhileIdle/setExact would be wrong; the plugin
        // doesn't deserve to break Doze.
        try {
            am.set(AlarmManager.RTC_WAKEUP, fireAtMs, pi);
        } catch (SecurityException ignored) {
            // SCHEDULE_EXACT_ALARM not granted on Android 12+; we don't
            // need exact anyway. set() is inexact and always allowed.
        }
    }

    static void cancelAlarm(Context context, String pluginId, String subId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pendingIntent(context, pluginId, subId));
    }

    private static PendingIntent pendingIntent(Context context, String pluginId, String subId) {
        Intent intent = new Intent(context, PluginAlarmReceiver.class)
            .setAction(ACTION_FIRE)
            .setPackage(context.getPackageName())
            .putExtra(EXTRA_PLUGIN_ID, pluginId)
            .putExtra(EXTRA_SUB_ID, subId);
        // requestCode unique per (plugin, subId) so different schedules
        // don't overwrite each other's PendingIntents. hashCode is good
        // enough — schedules are short-lived strings under user control.
        int requestCode = (pluginId + ":" + subId).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static String errJson(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
