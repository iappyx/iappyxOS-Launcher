// USAGE: BEGIN — Widget battery-usage tracking (Tier 2). Removable.
package com.iappyx.launcher.usage;

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-WidgetHost active-resource tracker. Records start timestamps when a
 * bridge call opens a battery-relevant resource (sensor, GPS, audio); on
 * stop it computes the delta and reports it to {@link UsageStore}.
 *
 * Designed to be called from WidgetHost bridge methods. All public methods
 * are no-ops if widgetId is null/empty, so callers don't need to gate on
 * "do we know our widget id yet?".
 *
 * Tracked resources:
 *  - sensors: keyed by type int (one widget can hold rotation-vector +
 *    accelerometer simultaneously — see compass)
 *  - gps: a single boolean (watchPosition has only one listener per host)
 *  - tracking: a single boolean (foreground LocationService)
 *  - audio: a single boolean (the host's single ExoPlayer)
 *  - visible: a single boolean (host visibility)
 *
 * Timestamps are {@link SystemClock#elapsedRealtime()} — monotonic, immune
 * to wall-clock jumps (NTP, manual time change, DST).
 */
public class UsageTracker {

    private final Context appContext;
    private final String widgetId;

    private final Map<Integer, Long> sensorStarts = new HashMap<>();
    private long gpsStart = 0L;
    private long trackingStart = 0L;
    private long audioStart = 0L;
    private long visibleStart = 0L;

    public UsageTracker(Context ctx, String widgetId) {
        this.appContext = ctx.getApplicationContext();
        this.widgetId = widgetId == null ? "" : widgetId;
    }

    private boolean alive() { return !widgetId.isEmpty(); }
    private long now() { return SystemClock.elapsedRealtime(); }

    public void onSensorStart(int type) {
        if (!alive()) return;
        // If already running, do nothing — same listener kept alive across
        // re-register (e.g. resumeBridges). The original start ts wins.
        sensorStarts.putIfAbsent(type, now());
    }

    public void onSensorStop(int type) {
        if (!alive()) return;
        Long s = sensorStarts.remove(type);
        if (s != null) UsageStore.INSTANCE.addMs(appContext, widgetId, "sensorMs", now() - s);
    }

    public void onAllSensorsStop() {
        if (!alive()) return;
        long t = now();
        for (Map.Entry<Integer, Long> e : sensorStarts.entrySet()) {
            UsageStore.INSTANCE.addMs(appContext, widgetId, "sensorMs", t - e.getValue());
        }
        sensorStarts.clear();
    }

    public void onGpsStart() {
        if (!alive()) return;
        if (gpsStart == 0L) gpsStart = now();
    }

    public void onGpsStop() {
        if (!alive() || gpsStart == 0L) return;
        UsageStore.INSTANCE.addMs(appContext, widgetId, "gpsMs", now() - gpsStart);
        gpsStart = 0L;
    }

    public void onTrackingStart() {
        if (!alive()) return;
        if (trackingStart == 0L) trackingStart = now();
    }

    public void onTrackingStop() {
        if (!alive() || trackingStart == 0L) return;
        UsageStore.INSTANCE.addMs(appContext, widgetId, "trackingMs", now() - trackingStart);
        trackingStart = 0L;
    }

    public void onAudioStart() {
        if (!alive()) return;
        if (audioStart == 0L) audioStart = now();
    }

    public void onAudioStop() {
        if (!alive() || audioStart == 0L) return;
        UsageStore.INSTANCE.addMs(appContext, widgetId, "audioMs", now() - audioStart);
        audioStart = 0L;
    }

    public void onVisible() {
        if (!alive()) return;
        if (visibleStart == 0L) visibleStart = now();
    }

    public void onHidden() {
        if (!alive() || visibleStart == 0L) return;
        UsageStore.INSTANCE.addMs(appContext, widgetId, "visibleMs", now() - visibleStart);
        visibleStart = 0L;
    }

    public void onHttpBytes(long bytes) {
        if (!alive() || bytes <= 0) return;
        UsageStore.INSTANCE.addBytes(appContext, widgetId, bytes);
    }

    /** Called from WidgetHost.destroy(). Flushes any still-active timers as
     *  if they had stopped now, so a widget destroyed mid-tracking still
     *  contributes its drain to the totals. */
    public void onDestroy() {
        onAllSensorsStop();
        onGpsStop();
        onTrackingStop();
        onAudioStop();
        onHidden();
    }

    /** Re-stamp every live start timestamp to {@code now()}. Pairs with
     *  [UsageStore.resetWindow]: without this, any resource that was already
     *  active when the user hit "Reset counters" would dump its full
     *  pre-reset duration into the cleared store the next time it stops
     *  — making the reset look ineffective. Active resources stay active;
     *  only the accounting clock for them is moved forward. */
    public void rebase() {
        if (!alive()) return;
        long t = now();
        if (gpsStart      != 0L) gpsStart      = t;
        if (trackingStart != 0L) trackingStart = t;
        if (audioStart    != 0L) audioStart    = t;
        if (visibleStart  != 0L) visibleStart  = t;
        for (Map.Entry<Integer, Long> e : sensorStarts.entrySet()) e.setValue(t);
    }
}
// USAGE: END
