/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.QrChunker

/**
 * Camera-based receiver for the QR sharing flow. Wires CameraX's
 * [PreviewView] to ML Kit's barcode scanner and reassembles
 * [QrChunker] frames as they arrive — frames cycle continuously on the
 * sender, so the user just keeps the receiver pointed at the QR until the
 * progress bar fills.
 *
 * Lifecycle: hand-rolled [LifecycleRegistry] because [Activity] doesn't
 * implement [LifecycleOwner] directly. We forward STARTED/RESUMED/STOPPED
 * to it from the activity callbacks; CameraX uses that to release the
 * camera when we leave the screen.
 *
 * Permissions: CAMERA. We request at startup; user denies → toast + finish.
 */
class QRReceiveActivity : AppCompatActivity() {

    // Mutated from the camera analyzer thread (handleFrame) AND read /
    // iterated from the UI thread (updateProgress, finishAssembly,
    // rebuildGrid). HashMap was unsafe — we'd see ConcurrentModification
    // or lost updates under fast scans. ConcurrentHashMap covers all the
    // ops used here (put / containsKey / clear / keys / size / decode).
    private val received = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var totalChunks = 0
    // @Volatile so the camera analyzer thread sees the UI-thread write
    // immediately on the next loop iteration; otherwise the analyzer keeps
    // dispatching frames into received after assembly already finished.
    @Volatile private var complete = false

    private lateinit var previewView: PreviewView
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var grid: LinearLayout
    private lateinit var doneSection: View

    private val scanner by lazy { BarcodeScanning.getClient() }
    private val analyzerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildView())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA,
            )
        } else {
            startCamera()
        }
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        try { scanner.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.qr_camera_perm_required_toast,
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(analyzerExecutor) { proxy -> processImage(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer,
                )
            } catch (e: Throwable) {
                Toast.makeText(this,
                    getString(R.string.qr_camera_failed_toast_format, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || complete) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (b in barcodes) {
                    val raw = b.rawValue ?: continue
                    handleFrame(raw)
                    if (complete) break
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleFrame(raw: String) {
        val frame = QrChunker.parseFrame(raw) ?: return
        if (totalChunks == 0) {
            totalChunks = frame.total
            runOnUiThread { rebuildGrid() }
        } else if (totalChunks != frame.total) {
            // Different transfer started — reset.
            received.clear()
            totalChunks = frame.total
            runOnUiThread { rebuildGrid() }
        }
        if (received.containsKey(frame.seq)) return
        received[frame.seq] = frame.chunk
        runOnUiThread {
            updateProgress()
            haptic()
            if (received.size == totalChunks) finishAssembly()
        }
    }

    private fun updateProgress() {
        progress.max = totalChunks.coerceAtLeast(1)
        progress.progress = received.size
        statusText.text = "${received.size} / $totalChunks chunks"
        // Repaint the dot for every newly-received seq.
        for (seq in received.keys) {
            grid.findViewWithTag<View>("dot$seq")?.let {
                (it.background as? GradientDrawable)?.setColor(Color.parseColor("#FF69F0AE"))
            }
        }
    }

    private fun haptic() {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {}
    }

    private fun finishAssembly() {
        complete = true
        try {
            val zipBytes = QrChunker.decode(received, totalChunks)
            val bundle = ArtefactBundle.readBundle(zipBytes)
            ArtefactBundle.install(this, bundle)
            doneSection.visibility = View.VISIBLE
            statusText.text = getString(R.string.qr_saved_status_format, bundle.title)
            hintText.text = getString(R.string.bundle_open_tab_hint_format, bundle.kind.label)
        } catch (e: Throwable) {
            val msg = e.message?.take(80) ?: getString(R.string.unknown_error_short)
            statusText.text = getString(R.string.qr_decode_failed_status_format, msg)
            // Allow the user to try again — reset state and resume scanning.
            complete = false
            received.clear(); totalChunks = 0
            rebuildGrid()
        }
    }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val dp = resources.displayMetrics.density
        // Clamp to 100 dots — beyond that we just show a "+N" badge so the
        // grid stays a reasonable height on phones.
        val show = totalChunks.coerceAtMost(100)
        for (i in 0 until show) {
            grid.addView(View(this).apply {
                tag = "dot$i"
                background = GradientDrawable().apply {
                    cornerRadius = 2 * dp
                    setColor(Color.parseColor("#FF1A1A2E"))
                }
                val lp = LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt())
                lp.rightMargin = (4 * dp).toInt(); lp.topMargin = (4 * dp).toInt()
                layoutParams = lp
            })
        }
    }

    private fun buildView(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding(0, sys.top, 0, sys.bottom)
                    insets
                }
            }
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(),
                (12 * dp).toInt(), (8 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                null, null,
            )
            setPadding((10 * dp).toInt(), (8 * dp).toInt(),
                (10 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            setText(R.string.qr_receive_title)
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * dp).toInt()
            layoutParams = lp
        })
        root.addView(header)

        // Camera preview takes the full middle section.
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val previewWrap = FrameLayout(this).apply {
            setPadding((16 * dp).toInt(), (8 * dp).toInt(),
                (16 * dp).toInt(), (8 * dp).toInt())
        }
        val previewCard = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#1A1A2E"))
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 12 * dp)
                }
            }
            addView(previewView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        previewWrap.addView(previewCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(previewWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // Bottom info section.
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(),
                (20 * dp).toInt(), (24 * dp).toInt())
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressDrawable = ContextCompat.getDrawable(
                this@QRReceiveActivity, android.R.drawable.progress_horizontal)?.mutate()
            isIndeterminate = false
            max = 1
            progress = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt(),
            )
            layoutParams = lp
        }
        info.addView(progress)
        statusText = TextView(this).apply {
            setText(R.string.qr_receive_hint)
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@QRReceiveActivity)); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        info.addView(statusText)
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Wrap manually with FlexboxLayout-style by using a RecyclerView…
            // simpler: use a Wrap-style LinearLayout via line breaks. We'll
            // rely on multiple lines being uncommon (≤100 dots clamp).
        }
        // Use a horizontally-scrolling row for the dots so this works
        // without a flexbox dependency. Simpler than wrapping logic.
        val gridScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (10 * dp).toInt()
            layoutParams = lp
        }
        info.addView(gridScroll)
        hintText = TextView(this).apply {
            setText(R.string.qr_receive_steady_hint)
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        info.addView(hintText)

        doneSection = TextView(this).apply {
            text = "✓ Saved"
            setTextColor(Color.parseColor("#FF69F0AE")); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (12 * dp).toInt()
            layoutParams = lp
            visibility = View.GONE
        }
        info.addView(doneSection)

        root.addView(info)
        return root
    }

    companion object {
        private const val REQ_CAMERA = 31337
    }
}
