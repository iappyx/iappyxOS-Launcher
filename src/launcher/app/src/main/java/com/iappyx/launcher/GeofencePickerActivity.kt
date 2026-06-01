/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.iappyx.launcher.widget.Palette
import java.util.Locale

/**
 * Map-based picker for the geofence trigger. Replaces the raw lat/long
 * dialog. Hosts a Leaflet map (loaded from an asset HTML, OSM tiles via
 * unpkg.com — no API key, no extra deps) backed by a small JS bridge so
 * tap/drag events update the native lat/lng/radius state.
 *
 * Caller passes the current trigger values via [EXTRA_LAT]/[EXTRA_LNG]/
 * [EXTRA_RADIUS]/[EXTRA_LABEL] if any; result extras follow the same
 * keys. RESULT_CANCELED on back/cancel.
 *
 * Internet is required (tiles + Leaflet CDN). On a fresh phone with no
 * network, the WebView shows the empty water-coloured map and the user
 * can still position the pin (just no labels). Acceptable trade-off vs.
 * bundling 50MB of vector tiles or pulling in the Maps SDK.
 */
class GeofencePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_LABEL = "label"
        const val DEFAULT_RADIUS_M = 150f
        const val MIN_RADIUS_M = 50
        const val MAX_RADIUS_M = 1000
        // Slider step in metres — keeps the slider usable but avoids
        // sub-50m fences (geofence accuracy on most phones is ~30-50m).
        private const val RADIUS_STEP_M = 25
        // Fallback centre when no initial fix and no current location is
        // granted. Amsterdam Centraal — the project lives in NL, so it's
        // a less-confusing zero point than null island.
        private const val FALLBACK_LAT = 52.3791
        private const val FALLBACK_LNG = 4.9003
    }

    private var currentLat: Double = Double.NaN
    private var currentLng: Double = Double.NaN
    private var currentRadius: Float = DEFAULT_RADIUS_M
    private var hasFix: Boolean = false
    private var pageReady: Boolean = false

    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var webView: WebView
    private lateinit var labelField: EditText
    private lateinit var radiusSlider: SeekBar
    private lateinit var radiusValue: TextView
    private lateinit var coordsValue: TextView
    private lateinit var saveBtn: TextView

    private val locationPermLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { granted ->
            val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) requestCurrentLocation()
            else Toast.makeText(this, R.string.geofence_picker_perm_denied_toast, Toast.LENGTH_SHORT).show()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read incoming extras. NaN sentinel means "no initial fix" so we
        // start zoomed-out and prompt the user to tap.
        currentLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        currentLng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        currentRadius = intent.getFloatExtra(EXTRA_RADIUS, DEFAULT_RADIUS_M)
            .coerceIn(MIN_RADIUS_M.toFloat(), MAX_RADIUS_M.toFloat())
        hasFix = !currentLat.isNaN() && !currentLng.isNaN()
        val initialLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        setContentView(buildView(initialLabel))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setResult(RESULT_CANCELED)
                finish()
            }
        })

        webView.loadUrl("file:///android_asset/geofence_picker.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView(initialLabel: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bgHome(this@GeofencePickerActivity))
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        // ── Header ──
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p(12), p(8), p(12), p(8))
            // Back chip
            addView(TextView(this@GeofencePickerActivity).apply {
                setText(R.string.action_back)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.accent(this@GeofencePickerActivity))
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.accent(this@GeofencePickerActivity), 0x1F))
                }
                setPadding(p(14), p(8), p(14), p(8))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            })
            addView(TextView(this@GeofencePickerActivity).apply {
                setText(R.string.geofence_picker_title)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Palette.textPrimary(this@GeofencePickerActivity))
                setPadding(p(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            })
            // Save chip — confirms the pick. Disabled until a pin is dropped.
            saveBtn = TextView(this@GeofencePickerActivity).apply {
                setText(R.string.action_save)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(p(16), p(8), p(16), p(8))
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Palette.accent(this@GeofencePickerActivity))
                }
                setTextColor(Palette.bgHome(this@GeofencePickerActivity))
                isClickable = true; isFocusable = true
                setOnClickListener { commitAndFinish() }
                alpha = if (hasFix) 1f else 0.4f
            }
            addView(saveBtn)
        })

        // ── Map ──
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setGeolocationEnabled(false)
            // API 30+ flipped allowFileAccess default to false, which
            // breaks <img src="file:///android_asset/...">. Re-enable so
            // bundled Leaflet marker PNGs resolve.
            settings.allowFileAccess = true
            // Leaflet uses CSS animations + transforms; hardware-accelerated
            // layer is the Android default but make sure we're not running
            // in a software-rendered fallback (some OEM WebViews disable
            // it for file:// content).
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(Palette.bgHome(this@GeofencePickerActivity))
            addJavascriptInterface(PickerBridge(), "Picker")
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "iappyxGeofencePicker",
                        "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}",
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    val lat = if (hasFix) currentLat else FALLBACK_LAT
                    val lng = if (hasFix) currentLng else FALLBACK_LNG
                    evaluateJavascript(
                        "iappyxInit($lat, $lng, ${currentRadius.toInt()}, $hasFix)",
                        null,
                    )
                }
            }
        }
        // Map fills remaining vertical space.
        root.addView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            // "Use my location" button overlaid on the map (top-right).
            addView(TextView(this@GeofencePickerActivity).apply {
                setText(R.string.geofence_picker_my_location)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(p(12), p(8), p(12), p(8))
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Palette.scrimStrong(this@GeofencePickerActivity))
                    setStroke(p(1), Palette.separatorStrong(this@GeofencePickerActivity))
                }
                isClickable = true; isFocusable = true
                setOnClickListener { ensureLocationAndCenter() }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(p(12), p(12), p(12), p(12))
                }
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f,
            )
        })

        // ── Bottom card: label + radius + coords ──
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p(20), p(16), p(20), p(20))
            background = GradientDrawable().apply {
                setColor(Palette.bgCell(this@GeofencePickerActivity))
                setStroke(p(1), Palette.separator(this@GeofencePickerActivity))
                cornerRadii = floatArrayOf(
                    p(20).toFloat(), p(20).toFloat(),
                    p(20).toFloat(), p(20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        }
        // Label field
        card.addView(sectionLabel(getString(R.string.geofence_picker_label_section)))
        labelField = EditText(this).apply {
            setHint(R.string.geofence_picker_label_hint)
            setText(initialLabel)
            setTextColor(Palette.textPrimary(this@GeofencePickerActivity))
            setHintTextColor(Palette.textDisabled(this@GeofencePickerActivity))
            setPadding(0, p(4), 0, p(4))
            background = null
        }
        card.addView(labelField)

        // Radius row — slider + label.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, p(12), 0, 0)
            addView(sectionLabel(getString(R.string.geofence_picker_radius_section)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            })
            radiusValue = TextView(this@GeofencePickerActivity).apply {
                text = formatRadius(currentRadius)
                setTextColor(Palette.textSecondary(this@GeofencePickerActivity))
                textSize = 13f
            }
            addView(radiusValue)
        })
        radiusSlider = SeekBar(this).apply {
            max = (MAX_RADIUS_M - MIN_RADIUS_M) / RADIUS_STEP_M
            progress = ((currentRadius.toInt() - MIN_RADIUS_M) / RADIUS_STEP_M).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val newRadius = (MIN_RADIUS_M + p * RADIUS_STEP_M).toFloat()
                    currentRadius = newRadius
                    radiusValue.text = formatRadius(newRadius)
                    if (pageReady) {
                        webView.evaluateJavascript(
                            "iappyxSetRadius(${newRadius.toInt()})", null,
                        )
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        card.addView(radiusSlider)

        // Coordinate readout — unobtrusive but reassuring for power users.
        coordsValue = TextView(this).apply {
            text = if (hasFix) formatCoords(currentLat, currentLng)
            else getString(R.string.geofence_picker_tap_hint)
            setTextColor(Palette.textSecondary(this@GeofencePickerActivity))
            textSize = 11f
            setPadding(0, p(8), 0, 0)
        }
        card.addView(coordsValue)

        root.addView(card)
        return root
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Palette.textSecondary(this@GeofencePickerActivity))
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
    }

    /** Locate the user via FusedLocationProviderClient and re-centre.
     *  Requests permission on the fly if needed. */
    private fun ensureLocationAndCenter() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        requestCurrentLocation()
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation() {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .lastLocation
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        Toast.makeText(
                            this, R.string.geofence_picker_loc_unavailable_toast,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@addOnSuccessListener
                    }
                    onLocationChosen(loc.latitude, loc.longitude, recenter = true)
                }
                .addOnFailureListener {
                    Toast.makeText(this, R.string.geofence_picker_loc_fetch_failed_toast,
                        Toast.LENGTH_SHORT).show()
                }
        } catch (_: SecurityException) {
            // Race between permission grant and call — re-request.
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    private fun onLocationChosen(lat: Double, lng: Double, recenter: Boolean) {
        currentLat = lat
        currentLng = lng
        if (!hasFix) {
            hasFix = true
            saveBtn.alpha = 1f
        }
        coordsValue.text = formatCoords(lat, lng)
        if (pageReady) {
            webView.evaluateJavascript(
                "iappyxSetLocation($lat, $lng, ${if (recenter) "true" else "false"})",
                null,
            )
        }
    }

    private fun commitAndFinish() {
        if (!hasFix) {
            Toast.makeText(this, R.string.geofence_picker_tap_first_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val data = android.content.Intent().apply {
            putExtra(EXTRA_LAT, currentLat)
            putExtra(EXTRA_LNG, currentLng)
            putExtra(EXTRA_RADIUS, currentRadius)
            putExtra(EXTRA_LABEL, labelField.text.toString().trim())
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun formatCoords(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f · ±${currentRadius.toInt()}m", lat, lng)

    private fun formatRadius(r: Float): String {
        val m = r.toInt()
        return if (m >= 1000) "${m / 1000.0} km" else "${m} m"
    }

    private fun p(v: Int) = (v * dp).toInt()

    override fun onDestroy() {
        if (::webView.isInitialized) {
            try {
                webView.stopLoading()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Throwable) {}
        }
        super.onDestroy()
    }

    /** JS → native bridge. The Leaflet HTML calls these on tap/drag so
     *  the activity's lat/lng state stays in sync with the pin. */
    inner class PickerBridge {
        @JavascriptInterface
        fun onLocationChanged(lat: Double, lng: Double) {
            runOnUiThread { onLocationChosen(lat, lng, recenter = false) }
        }
    }
}
