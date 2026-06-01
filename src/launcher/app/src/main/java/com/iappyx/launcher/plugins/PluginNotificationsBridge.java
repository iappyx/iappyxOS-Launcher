/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — `notification:read` capability. Read other apps'
 * notifications via the launcher's existing NotificationListenerService.
 *
 * JS-side surface (when capability granted):
 *   iappyx.notifications.subscribe({packages?, categories?, ongoing?, method})
 *     → {ok, subId}
 *     Plugin's exported `method` fires with the notification payload
 *     whenever a matching notification posts. Subscriptions persist
 *     across plugin / launcher restarts (re-arms automatically).
 *
 *   iappyx.notifications.unsubscribe({subId}) → {ok, cancelled}
 *
 *   iappyx.notifications.recent({count?:20, packages?, categories?})
 *     → {ok, notifications:[{packageName,title,text,subText,postedAt,
 *                            category,ongoing,id,key,group}]}
 *     Snapshot of currently-active notifications matching the filter.
 *     Returns empty list if the launcher doesn't have notification
 *     access enabled (Settings → Apps → iappyxOS → Notifications).
 *
 * Payload shape (delivered to method + recent()):
 *   { packageName, title, text, subText, postedAt, category,
 *     ongoing, id, key, group }
 */
package com.iappyx.launcher.plugins;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.webkit.JavascriptInterface;

import com.iappyx.launcher.notify.NotificationBadgeListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PluginNotificationsBridge {

    private final Context appContext;
    private final String pluginId;
    private final PluginInvocationContext invocationCtx;

    PluginNotificationsBridge(Context context, String pluginId, PluginInvocationContext invocationCtx) {
        this.appContext = context.getApplicationContext();
        this.pluginId = pluginId;
        this.invocationCtx = invocationCtx;
    }

    @JavascriptInterface
    public void subscribe(String optionsJson, String cbId) {
        if (cbId == null) return;
        try {
            JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
            String method = opts.optString("method");
            if (method == null || method.isEmpty()) {
                invocationCtx.deliverResult(cbId, errJson("method required"));
                return;
            }
            List<String> packages = parseStringList(opts.optJSONArray("packages"));
            List<String> categories = parseStringList(opts.optJSONArray("categories"));
            Boolean ongoing = opts.has("ongoing") ? opts.optBoolean("ongoing") : null;
            String subId = "ntf_" + UUID.randomUUID().toString().substring(0, 12);
            PluginNotificationsBus.Subscription sub =
                new PluginNotificationsBus.Subscription(
                    pluginId, subId, method, packages, categories, ongoing);
            PluginNotificationsBus.INSTANCE.add(appContext, sub);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("subId", subId);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable t) {
            invocationCtx.deliverResult(cbId, errJson("invalid options: " + t.getMessage()));
        }
    }

    @JavascriptInterface
    public void unsubscribe(String subId, String cbId) {
        if (cbId == null) return;
        if (subId == null || subId.isEmpty()) {
            invocationCtx.deliverResult(cbId, errJson("subId required"));
            return;
        }
        boolean removed = PluginNotificationsBus.INSTANCE.remove(appContext, pluginId, subId);
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("cancelled", removed);
            invocationCtx.deliverResult(cbId, out.toString());
        } catch (Throwable ignored) {
            invocationCtx.deliverResult(cbId, "{\"ok\":true,\"cancelled\":false}");
        }
    }

    /** Snapshot recent (currently-active) notifications matching the
     *  filter. Returns an empty list if notification access isn't
     *  granted — bridge doesn't try to deduce the reason or surface a
     *  setup prompt; that's the plugin's UX call. */
    @JavascriptInterface
    public void recent(String optionsJson, String cbId) {
        if (cbId == null) return;
        new Thread(() -> {
            try {
                JSONObject opts = new JSONObject(optionsJson == null ? "{}" : optionsJson);
                int max = Math.max(1, Math.min(100, opts.optInt("count", 20)));
                List<String> packages = parseStringList(opts.optJSONArray("packages"));
                List<String> categories = parseStringList(opts.optJSONArray("categories"));
                Boolean ongoing = opts.has("ongoing") ? opts.optBoolean("ongoing") : null;

                // Pull current active notifications from the listener
                // service if it's bound. If the user hasn't granted
                // notification access yet, this comes back empty.
                StatusBarNotification[] active = getActiveNotifications();
                JSONArray arr = new JSONArray();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (!filterMatches(sbn, packages, categories, ongoing)) continue;
                        arr.put(PluginNotificationsBus.INSTANCE.payloadJson(sbn));
                        if (arr.length() >= max) break;
                    }
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("notifications", arr);
                out.put("accessGranted",
                    NotificationBadgeListener.Companion.isEnabled(appContext));
                invocationCtx.deliverResult(cbId, out.toString());
            } catch (Throwable t) {
                invocationCtx.deliverResult(cbId, errJson(t.getMessage() != null ? t.getMessage() : t.toString()));
            }
        }).start();
    }

    /** Reach the bound NotificationBadgeListener instance (via its
     *  static companion-object field) and grab `activeNotifications`.
     *  Returns null when no listener is bound (= user hasn't granted
     *  notification access). Reflection because the field is private
     *  to the listener's companion object — exposing it as a public
     *  API would tangle the notify package with the plugins package. */
    private StatusBarNotification[] getActiveNotifications() {
        try {
            // Companion object access via Kotlin's generated INSTANCE.
            // NotificationBadgeListener has private `instance` static.
            // Best-effort reflective read; null on any failure.
            java.lang.reflect.Field f = NotificationBadgeListener.class
                .getDeclaredField("instance");
            f.setAccessible(true);
            Object listener = f.get(null);
            if (listener == null) return null;
            return ((android.service.notification.NotificationListenerService) listener)
                .getActiveNotifications();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean filterMatches(
        StatusBarNotification sbn,
        List<String> packages, List<String> categories, Boolean ongoing
    ) {
        if (!packages.isEmpty() && !packages.contains(sbn.getPackageName())) return false;
        android.app.Notification n = sbn.getNotification();
        if (!categories.isEmpty()) {
            String cat = n != null ? n.category : null;
            if (cat == null || !categories.contains(cat)) return false;
        }
        if (ongoing != null) {
            int flags = n != null ? n.flags : 0;
            boolean isOngoing = (flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
            if (isOngoing != ongoing) return false;
        }
        return true;
    }

    private static List<String> parseStringList(JSONArray a) {
        if (a == null) return new ArrayList<>();
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String errJson(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
