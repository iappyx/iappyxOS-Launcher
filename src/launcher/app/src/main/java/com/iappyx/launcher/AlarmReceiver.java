/*
 * MIT License - Copyright (c) 2026 iappyx
 * Ported from iappyxOS — trimmed for launcher use.
 *
 * Fires when an AlarmManager PendingIntent triggers, or on device boot
 * (re-registers any stored alarms so they survive reboot).
 */
package com.iappyx.launcher;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class AlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_LAUNCHER_ALARM_FIRED = "com.iappyx.launcher.ALARM_FIRED";
    public static final String EXTRA_CALLBACK = "callbackFn";
    public static final String EXTRA_ALARM_ID = "alarmId";
    public static final String EXTRA_WIDGET_ID = "widgetId";
    private static final String CH = "iappyx_launcher_alarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Context appCtx = context.getApplicationContext();
            reRegisterAlarms(appCtx);
            // Android drops dynamic trigger receivers and Play Services
            // geofences across a reboot. Nothing re-armed them on boot, so
            // persistent triggers + profile geofence auto-switching were dead
            // until the user next opened the launcher. Re-arm them here.
            try {
                if (TriggerStore.hasAnyPersistent(appCtx)) {
                    // The keepalive service's onCreate re-registers the dynamic
                    // TriggerReceiver against a long-lived (service) context —
                    // which is required, a BroadcastReceiver context is too
                    // short-lived to host a dynamic registration.
                    TriggerKeepaliveService.start(appCtx);
                }
            } catch (Throwable ignored) {}
            try {
                com.iappyx.launcher.profile.ProfileGeofenceManager.INSTANCE.reRegisterAll(appCtx);
            } catch (Throwable ignored) {}
            return;
        }

        String callbackFn = intent.getStringExtra(EXTRA_CALLBACK);
        String alarmId = intent.getStringExtra(EXTRA_ALARM_ID);
        String widgetId = intent.getStringExtra(EXTRA_WIDGET_ID);
        String id = alarmId != null ? alarmId : "default";

        // Broadcast to the running launcher activity (if any). LauncherActivity registers
        // a receiver for this action and routes to the right widget.
        Intent ping = new Intent(ACTION_LAUNCHER_ALARM_FIRED);
        ping.setPackage(context.getPackageName());
        ping.putExtra(EXTRA_CALLBACK, callbackFn);
        ping.putExtra(EXTRA_ALARM_ID, id);
        ping.putExtra(EXTRA_WIDGET_ID, widgetId);
        context.sendBroadcast(ping);

        // Also post a notification in case the launcher isn't in the foreground.
        createChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            int rc = id.hashCode() & 0x7FFFFFFF;
            Intent openLauncher = new Intent(context, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(context, rc, openLauncher, piFlags);
            try {
                nm.notify(rc % 100000, new NotificationCompat.Builder(context, CH)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Alarm")
                    .setContentText("Tap to open")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build());
            } catch (Exception ignored) {}
        }

        SharedPreferences prefs = context.getSharedPreferences("iappyx_launcher_alarm", Context.MODE_PRIVATE);
        long interval = prefs.getLong("interval_" + id, 0);
        if (interval > 0) {
            // Repeating — reschedule
            prefs.edit().remove("ts_" + id).apply();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                Intent next = new Intent(context, AlarmReceiver.class);
                next.putExtra(EXTRA_CALLBACK, callbackFn);
                next.putExtra(EXTRA_ALARM_ID, id);
                next.putExtra(EXTRA_WIDGET_ID, widgetId);
                int rc = id.hashCode() & 0x7FFFFFFF;
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi2 = PendingIntent.getBroadcast(context, rc, next, flags);
                scheduleExact(am, System.currentTimeMillis() + interval, pi2);
            }
        } else {
            prefs.edit().remove("ts_" + id).remove("callbackFn_" + id)
                .remove("interval_" + id).remove("widgetId_" + id).apply();
        }
    }

    private void reRegisterAlarms(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("iappyx_launcher_alarm", Context.MODE_PRIVATE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            java.util.Set<String> alarmIds = new java.util.HashSet<>();
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("ts_")) alarmIds.add(key.substring(3));
                else if (key.startsWith("interval_")) alarmIds.add(key.substring(9));
            }
            for (String id : alarmIds) {
                long ts = prefs.getLong("ts_" + id, 0);
                String fn = prefs.getString("callbackFn_" + id, null);
                String widgetId = prefs.getString("widgetId_" + id, null);
                long interval = prefs.getLong("interval_" + id, 0);
                if (fn == null) continue;
                Intent alarmIntent = new Intent(ctx, AlarmReceiver.class);
                alarmIntent.putExtra(EXTRA_CALLBACK, fn);
                alarmIntent.putExtra(EXTRA_ALARM_ID, id);
                alarmIntent.putExtra(EXTRA_WIDGET_ID, widgetId);
                int rc = id.hashCode() & 0x7FFFFFFF;
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getBroadcast(ctx, rc, alarmIntent, flags);
                if (interval > 0) scheduleExact(am, System.currentTimeMillis() + interval, pi);
                else if (ts > System.currentTimeMillis()) scheduleExact(am, ts, pi);
                else prefs.edit().remove("ts_" + id).remove("callbackFn_" + id)
                    .remove("interval_" + id).remove("widgetId_" + id).apply();
            }
        } catch (Exception ignored) {}
    }

    private static void scheduleExact(AlarmManager am, long triggerAt, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms())
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        else
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, "Launcher alarms",
                NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
