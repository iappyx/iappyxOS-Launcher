/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.P2PService
import java.io.File

/**
 * Sender side of the WiFi-Direct flow. Builds an artefact bundle, fires
 * up [P2PService.startSharing] to host an HTTP server, and waits — the
 * receiver does the discovery → connect → download dance, our screen
 * just shows status and lets the user cancel.
 *
 * Started via:
 *   Intent(NearbySendActivity)
 *     .putExtra(EXTRA_KIND, "widget"|"wallpaper"|"transition")
 *     .putExtra(EXTRA_ID, …)
 */
class NearbySendActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
    }

    private lateinit var p2p: P2PService
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var spinner: ProgressBar
    private var bundleFile: File? = null
    /** Set true in [onDestroy] so any in-flight P2P callback that schedules
     *  a runOnUiThread early-returns instead of touching torn-down views.
     *  Same pattern as [SettingsActivity] / [ShowcaseBrowserActivity]. */
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())

        val kindStr = intent.getStringExtra(EXTRA_KIND) ?: ""
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val kind = ArtefactBundle.Kind.values().firstOrNull { it.label == kindStr }
        if (kind == null || id.isBlank()) { finish(); return }

        bundleFile = try {
            val dir = File(cacheDir, "nearby_send").also { it.mkdirs() }
            when (kind) {
                ArtefactBundle.Kind.WIDGET       -> ArtefactBundle.buildWidget(this, id, dir)
                ArtefactBundle.Kind.WALLPAPER    -> ArtefactBundle.buildWallpaper(this, id, dir)
                ArtefactBundle.Kind.TRANSITION   -> ArtefactBundle.buildTransition(this, id, dir)
                ArtefactBundle.Kind.ICON_FILTER  -> ArtefactBundle.buildIconFilter(this, id, dir)
                // PLUGINS: BEGIN — nearby send for plugins isn't
                // supported yet (no peer-to-peer flow); user installs
                // plugins via .iappyxplugin file or showcase.
                ArtefactBundle.Kind.PLUGIN       -> throw IllegalArgumentException(
                    "Plugin sharing via nearby isn't supported — install from a file or the showcase instead."
                )
                // PLUGINS: END
            }
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.nearby_couldnt_build_bundle_format, e.message ?: ""),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            finish(); return
        }

        p2p = P2PService(this).apply {
            onStatusChanged = { status ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    when (status) {
                        "waiting" -> {
                            statusText.setText(R.string.nearby_waiting_for_receiver)
                            subText.setText(R.string.nearby_open_receive_on_other)
                        }
                        "transferring" -> {
                            statusText.setText(R.string.nearby_sending)
                            subText.setText(R.string.nearby_sending_hint)
                        }
                        "done" -> {
                            statusText.setText(R.string.nearby_sent)
                            subText.text = getString(R.string.nearby_sent_hint_format, kind.label)
                            spinner.visibility = View.GONE
                        }
                    }
                }
            }
        }
        p2p.init()

        val bf = bundleFile ?: run { finish(); return }
        val title = bf.nameWithoutExtension
        p2p.startSharing(
            bundleFile = bf,
            title = title,
            kindLabel = kind.label,
            size = bf.length(),
        ) { ok, err ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (!ok) {
                    statusText.setText(R.string.nearby_couldnt_start)
                    subText.text = err ?: getString(R.string.nearby_try_again)
                    spinner.visibility = View.GONE
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        p2p.onPermissionsResult(requestCode)
    }

    override fun onDestroy() {
        destroyed = true
        try { p2p.destroy() } catch (_: Throwable) {}
        try { bundleFile?.delete() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun buildView(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    v.setPadding((20 * dp).toInt(), sys.top + (20 * dp).toInt(),
                        (20 * dp).toInt(), sys.bottom + (20 * dp).toInt())
                    insets
                }
            }
        }
        // Header with close button.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
        }
        header.addView(closeButton())
        header.addView(TextView(this).apply {
            setText(R.string.nearby_send_title)
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * dp).toInt()
            layoutParams = lp
        })
        root.addView(header)

        // Centred spinner + status block.
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            layoutParams = lp
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
            layoutParams = lp
        }
        center.addView(spinner)
        statusText = TextView(this).apply {
            setText(R.string.nearby_starting_wifi_direct)
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (24 * dp).toInt()
            layoutParams = lp
        }
        center.addView(statusText)
        subText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#80FFFFFF")); textSize = 13f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        center.addView(subText)
        root.addView(center)

        // Hint card at the bottom.
        root.addView(hintCard(
            getString(R.string.nearby_how_it_works_title),
            getString(R.string.nearby_how_it_works_body),
        ))

        return root
    }

    private fun closeButton(): View {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                null, null,
            )
            setPadding((10 * dp).toInt(), (8 * dp).toInt(),
                (10 * dp).toInt(), (8 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }
    }

    private fun hintCard(title: String, body: String): View {
        val dp = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(Color.parseColor("#1FFFFFFF"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            setPadding((16 * dp).toInt(), (12 * dp).toInt(),
                (16 * dp).toInt(), (12 * dp).toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = body; textSize = 12f
            setTextColor(Color.parseColor("#A0A0B8"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (4 * dp).toInt()
            layoutParams = lp
        })
        return card
    }
}
