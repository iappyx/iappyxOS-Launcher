/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.wallpaper

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Minimal bridge surface available to wallpaper HTML payloads.
 *
 * Two sides:
 *
 *  - **Pull (JS calls native).** `@JavascriptInterface` methods that JS can
 *    invoke synchronously. Deliberately small: the more API we expose, the
 *    more the wallpaper turns into a general app, and the more failure modes
 *    we sign up for. Phase 2 ships:
 *      - [log] for debugging (forwarded to logcat under the wallpaper process)
 *      - [enableAccelerometer] for tilt-parallax effects
 *
 *  - **Push (native calls JS).** The engine tells the wallpaper about events
 *    by evaluating `iappyx.onSomething(...)` — the JS author registers those
 *    handlers as plain functions (see the bootstrap shim in
 *    [IappyxWallpaperService] which makes `window.iappyx` a regular JS object,
 *    so user code can assign to it). Phase 2 emits:
 *      - `iappyx.onPageOffset(x)`     — when the home pager scrolls
 *      - `iappyx.onAccelerometer(x,y,z)` — while accelerometer is enabled
 *      - `iappyx.onVisibility(bool)`  — pause/resume hint
 *
 * Sensor callbacks fire on the SensorManager thread; everything that touches
 * the WebView gets bounced back to the main looper.
 */
class WallpaperBridge(
    context: Context,
    private val webView: WebView,
) {
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val main = Handler(Looper.getMainLooper())
    private var accelListener: SensorEventListener? = null
    /** What the JS most recently asked for. Separate from [accelListener]
     *  (which represents the live OS registration) so we can keep the JS-
     *  intended state across visibility cycles — register on visible,
     *  unregister on invisible — without the wallpaper losing its tilt
     *  parallax just because the user briefly opened another app. */
    private var accelWantedByJs: Boolean = false
    /** Mirrors the engine's last `onVisibilityChanged` value. Updated by
     *  [setEngineVisible]; gates whether we actually register the sensor. */
    private var engineVisible: Boolean = true

    @JavascriptInterface
    fun log(msg: String) {
        Log.d(TAG, "[js] $msg")
    }

    @JavascriptInterface
    fun enableAccelerometer(enabled: Boolean) {
        main.post {
            accelWantedByJs = enabled
            reconcileAccelerometer()
        }
    }

    /** Called from [IappyxWallpaperService.IappyxEngine.onVisibilityChanged].
     *  When the wallpaper goes invisible (user is in another app), we tear
     *  down the sensor registration so it doesn't keep firing
     *  evaluateJavascript at a paused WebView every 60ms — the dominant
     *  background drain on tilt-aware wallpapers. Re-registers on resume
     *  if the JS still wants the accelerometer. Must be called on the
     *  main thread; the engine already does. */
    fun setEngineVisible(visible: Boolean) {
        if (engineVisible == visible) return
        engineVisible = visible
        reconcileAccelerometer()
    }

    /** Single source of truth for whether the OS-level accelerometer
     *  registration is alive: `wanted by JS && engine visible`. */
    private fun reconcileAccelerometer() {
        val shouldBeOn = accelWantedByJs && engineVisible
        if (shouldBeOn && accelListener == null) {
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor == null) {
                Log.w(TAG, "no accelerometer on this device")
                return
            }
            accelListener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    push("onAccelerometer", "${e.values[0]},${e.values[1]},${e.values[2]}")
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            // SENSOR_DELAY_UI (~16Hz) instead of SENSOR_DELAY_GAME (~50Hz) —
            // wallpapers do atmospheric tilt-parallax, they don't need
            // game-physics-grade sample rates. ~3× fewer wakeups.
            sensorManager?.registerListener(
                accelListener, sensor, SensorManager.SENSOR_DELAY_UI,
            )
        } else if (!shouldBeOn && accelListener != null) {
            accelListener?.let { sensorManager?.unregisterListener(it) }
            accelListener = null
        }
    }

    // ── Engine-side push helpers ────────────────────────────────

    fun pushPageOffset(x: Float) = push("onPageOffset", "$x")
    fun pushVisibility(visible: Boolean) = push("onVisibility", visible.toString())

    /** [json] is the full layout-snapshot JSON STRING. Passed to JS as a
     *  parsed object so user code can use it directly. The wallpaper has
     *  trusted access to this payload — it's our own broadcast, not user
     *  input — so embedding it inline is safe. */
    fun pushLayoutChanged(json: String) {
        main.post {
            // We can't string-interpolate the JSON straight into the call
            // (would break on apostrophes, newlines, etc.), so pass it as a
            // string literal that JS parses inside the guard.
            val escaped = json
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                // U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR are
                // valid in JSON strings but JavaScript treats them as line
                // terminators that break out of even single-quoted string
                // literals. Anything containing these characters (rare, but
                // possible for future label fields or app names) would cause
                // JSON.parse to fail with a SyntaxError. Cheap to guard.
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029")
            webView.evaluateJavascript(
                "if(window.iappyx && typeof iappyx.onLayoutChanged === 'function')" +
                    " iappyx.onLayoutChanged(JSON.parse('$escaped'))",
                null,
            )
        }
    }

    private fun push(method: String, args: String) {
        main.post {
            // Guard the call so payloads that don't define a handler don't
            // throw a noisy ReferenceError on every event.
            webView.evaluateJavascript(
                "if(window.iappyx && typeof iappyx.$method === 'function') iappyx.$method($args)",
                null,
            )
        }
    }

    fun teardown() {
        accelListener?.let { sensorManager?.unregisterListener(it) }
        accelListener = null
    }

    companion object { private const val TAG = "IappyxWallpaper" }
}
