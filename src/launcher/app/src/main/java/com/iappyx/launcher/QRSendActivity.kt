/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.QrChunker
import java.io.File

/**
 * Cycles a chunked artefact bundle as QR frames. The receiver
 * ([QRReceiveActivity]) deduplicates by sequence number and reassembles
 * once it's seen every chunk at least once — so the screen-on time scales
 * linearly with bundle size, but the user can stop staring at it the
 * moment the receiver flashes "complete".
 *
 * Started via:
 *   `Intent(QRSendActivity).putExtra(EXTRA_KIND, "widget"|"wallpaper"|"transition").putExtra(EXTRA_ID, …)`
 *
 * The activity does its own dark theming so the QR contrast is high
 * regardless of the system theme. Screen stays on while we're cycling.
 */
class QRSendActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var qrView: ImageView
    private lateinit var counter: TextView
    private var frames: List<String> = emptyList()
    private var index = 0
    private val tick = object : Runnable {
        override fun run() {
            if (frames.isNotEmpty()) {
                index = (index + 1) % frames.size
                renderFrame()
                main.postDelayed(this, QrChunker.FRAME_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Bump brightness so the camera on the receiving phone gets a
        // crisp image even in a dim room.
        window.attributes = window.attributes.also { it.screenBrightness = 1f }

        val kindStr = intent.getStringExtra(EXTRA_KIND) ?: ""
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val kind = ArtefactBundle.Kind.values().firstOrNull { it.label == kindStr }
        if (kind == null || id.isBlank()) {
            finish(); return
        }

        // Build the bundle into a temp file, read its bytes, then drop it.
        val tmp = try {
            val dir = File(cacheDir, "qr_send").also { it.mkdirs() }
            when (kind) {
                ArtefactBundle.Kind.WIDGET       -> ArtefactBundle.buildWidget(this, id, dir)
                ArtefactBundle.Kind.WALLPAPER    -> ArtefactBundle.buildWallpaper(this, id, dir)
                ArtefactBundle.Kind.TRANSITION   -> ArtefactBundle.buildTransition(this, id, dir)
                ArtefactBundle.Kind.ICON_FILTER  -> ArtefactBundle.buildIconFilter(this, id, dir)
                // PLUGINS: BEGIN — QR send for plugins not supported.
                ArtefactBundle.Kind.PLUGIN       -> throw IllegalArgumentException(
                    "Plugin sharing via QR isn't supported — install from a file or the showcase instead."
                )
                // PLUGINS: END
            }
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.qr_couldnt_build_bundle_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            finish(); return
        }
        val bytes = tmp.readBytes()
        try { tmp.delete() } catch (_: Throwable) {}

        frames = QrChunker.encode(bytes)
        setContentView(buildView(tmp.nameWithoutExtension))
        renderFrame()
        main.postDelayed(tick, QrChunker.FRAME_INTERVAL_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(tick)
    }

    private fun renderFrame() {
        if (frames.isEmpty()) return
        val raw = frames[index]
        val bmp = encodeQr(raw, qrSidePx())
        qrView.setImageBitmap(bmp)
        counter.text = getString(R.string.qr_frame_counter_format, index + 1, frames.size)
    }

    private fun qrSidePx(): Int {
        val dm = resources.displayMetrics
        return (minOf(dm.widthPixels, dm.heightPixels) * 0.78f).toInt().coerceAtLeast(256)
    }

    private fun encodeQr(text: String, sidePx: Int): Bitmap {
        val writer = QRCodeWriter()
        // L = 7% recovery — small, dense codes are easier to scan from a
        // moving phone; we already have parity via the chunk loop.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, sidePx, sidePx, hints)
        val bmp = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sidePx) {
            for (x in 0 until sidePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun buildView(title: String): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
            // Edge-to-edge — apply system insets manually so the toolbar row
            // sits below the status bar.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding((20 * dp).toInt(), sys.top + (24 * dp).toInt(),
                        (20 * dp).toInt(), sys.bottom + (24 * dp).toInt())
                    insets
                }
            }
        }

        // Header row — close button + title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
        }
        header.addView(makeIconButton("✕") { finish() })
        header.addView(TextView(this).apply {
            setText(R.string.qr_send_title)
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * dp).toInt()
            layoutParams = lp
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (24 * dp).toInt()
            layoutParams = lp
        })

        val cycleSec = (frames.size * QrChunker.FRAME_INTERVAL_MS / 1000.0).let {
            if (it < 1) "<1" else "${it.toInt()}"
        }
        root.addView(TextView(this).apply {
            text = "${frames.size} frames • $cycleSec s per cycle"
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (4 * dp).toInt()
            layoutParams = lp
        })

        // QR card.
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Color.WHITE)
            }
            setPadding((12 * dp).toInt(), (12 * dp).toInt(),
                (12 * dp).toInt(), (12 * dp).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (24 * dp).toInt()
            layoutParams = lp
        }
        qrView = ImageView(this).apply {
            adjustViewBounds = true
            val side = qrSidePx()
            layoutParams = FrameLayout.LayoutParams(side, side)
        }
        card.addView(qrView)
        root.addView(card)

        counter = TextView(this).apply {
            text = getString(R.string.qr_frame_counter_format, 1, frames.size)
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@QRSendActivity)); textSize = 14f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
        root.addView(counter)

        root.addView(TextView(this).apply {
            setText(R.string.qr_send_hold_hint)
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 13f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (24 * dp).toInt()
            layoutParams = lp
        })

        return root
    }

    private fun makeIconButton(label: String, onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label; textSize = 18f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                null, null,
            )
            setPadding((10 * dp).toInt(), (8 * dp).toInt(),
                (10 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
        }
    }
}
