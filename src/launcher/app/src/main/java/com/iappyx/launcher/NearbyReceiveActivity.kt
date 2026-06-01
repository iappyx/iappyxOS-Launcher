/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.sharing.ArtefactBundle
import com.iappyx.launcher.sharing.P2PService
import java.io.File

/**
 * Receiver side of the WiFi-Direct flow. Discovers nearby devices, lets
 * the user pick one, dials the P2P group host, downloads the bundle, then
 * installs it via [ArtefactBundle.install] — same install path the QR and
 * file flows use, so all three converge on the same library entries.
 *
 * Connection flow:
 *   1. discoverPeers → onPeersChanged populates the list
 *   2. user taps a peer → connectToPeer
 *   3. WIFI_P2P_CONNECTION_CHANGED arrives with the host's IP
 *   4. downloadBundle pulls /info.json + /bundle.zip
 *   5. ArtefactBundle.install writes it into the right library
 */
class NearbyReceiveActivity : AppCompatActivity() {

    private lateinit var p2p: P2PService
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var progressBar: ProgressBar
    private lateinit var peerList: LinearLayout
    private var hasConnected = false
    private var downloadStarted = false
    /** Set true in [onDestroy] so any in-flight P2P callback that schedules
     *  a runOnUiThread early-returns instead of touching torn-down views.
     *  Same pattern as [SettingsActivity] / [ShowcaseBrowserActivity]. */
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())

        p2p = P2PService(this).apply {
            onPeersChanged = { list ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    renderPeers(list)
                }
            }
            onConnectionChanged = { info ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    onConnection(info)
                }
            }
        }
        p2p.init()
        startDiscovery()
    }

    private fun startDiscovery() {
        statusText.setText(R.string.nearby_looking_for_devices)
        subText.setText(R.string.nearby_open_share_on_other)
        peerList.removeAllViews()
        p2p.discoverPeers { ok, err ->
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

    private fun renderPeers(list: List<P2PService.PeerInfo>) {
        peerList.removeAllViews()
        if (list.isEmpty()) return
        statusText.text = resources.getQuantityString(
            R.plurals.nearby_devices_count_format, list.size, list.size,
        )
        subText.setText(R.string.nearby_tap_to_connect)
        for (peer in list) {
            peerList.addView(peerRow(peer))
        }
    }

    private fun peerRow(peer: P2PService.PeerInfo): View {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#1FFFFFFF"))
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            setPadding((14 * dp).toInt(), (12 * dp).toInt(),
                (14 * dp).toInt(), (12 * dp).toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { connectTo(peer) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = (8 * dp).toInt()
            layoutParams = lp

            addView(TextView(context).apply {
                text = peer.name; textSize = 14f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                val lp2 = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp2
            })
            addView(TextView(context).apply {
                text = peer.status
                textSize = 12f
                setTextColor(Color.parseColor("#80FFFFFF"))
            })
        }
    }

    private fun connectTo(peer: P2PService.PeerInfo) {
        statusText.text = getString(R.string.nearby_connecting_format, peer.name)
        subText.text = ""
        peerList.removeAllViews()
        p2p.connectToPeer(peer.address) { ok, err ->
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (!ok) {
                    statusText.setText(R.string.nearby_couldnt_connect)
                    subText.text = err ?: getString(R.string.nearby_try_again)
                }
            }
        }
    }

    private fun onConnection(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed || hasConnected) return
        if (downloadStarted) return
        // We're the receiver if the OTHER side is the group owner.
        if (info.isGroupOwner) return
        hasConnected = true
        downloadStarted = true
        val hostIp = info.groupOwnerAddress?.hostAddress ?: return
        statusText.setText(R.string.nearby_downloading)
        subText.text = ""
        progressBar.visibility = View.VISIBLE

        val destPath = File(cacheDir, "nearby_recv/incoming.zip").also {
            it.parentFile?.mkdirs()
            try { it.delete() } catch (_: Throwable) {}
        }.absolutePath

        p2p.downloadBundle(
            hostIp = hostIp,
            destPath = destPath,
            onProgress = { pct, _, _ ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    progressBar.progress = pct
                    statusText.text = getString(R.string.nearby_downloading_pct_format, pct)
                }
            },
            onDone = { ok, err, _ ->
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    if (!ok) {
                        statusText.setText(R.string.nearby_download_failed)
                        subText.text = err ?: ""
                        progressBar.visibility = View.GONE
                        return@runOnUiThread
                    }
                    installFrom(destPath)
                }
            },
        )
    }

    private fun installFrom(path: String) {
        progressBar.visibility = View.GONE
        spinner.visibility = View.GONE
        try {
            val bytes = File(path).readBytes()
            val bundle = ArtefactBundle.readBundle(bytes)
            ArtefactBundle.install(this, bundle)
            statusText.text = getString(R.string.qr_saved_status_format, bundle.title)
            subText.text = getString(R.string.bundle_open_tab_hint_format, bundle.kind.label)
        } catch (e: Throwable) {
            statusText.setText(R.string.nearby_install_failed)
            subText.text = e.message ?: ""
        } finally {
            try { File(path).delete() } catch (_: Throwable) {}
            try { p2p.disconnect() } catch (_: Throwable) {}
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
        super.onDestroy()
    }

    private fun buildView(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
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
            setText(R.string.nearby_receive_title)
            setTextColor(Color.WHITE); textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * dp).toInt()
            layoutParams = lp
        })
        root.addView(header)

        // Status block at top.
        val statusBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (24 * dp).toInt(); lp.bottomMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt())
            layoutParams = lp
        }
        statusBlock.addView(spinner)
        val statusCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * dp).toInt()
            layoutParams = lp
        }
        statusText = TextView(this).apply {
            setText(R.string.nearby_looking_for_devices)
            setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        subText = TextView(this).apply {
            setText(R.string.nearby_open_share_on_other)
            setTextColor(Color.parseColor("#A0A0B8")); textSize = 12f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (2 * dp).toInt()
            layoutParams = lp
        }
        statusCol.addView(statusText)
        statusCol.addView(subText)
        statusBlock.addView(statusCol)
        root.addView(statusBlock)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (8 * dp).toInt(),
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        root.addView(progressBar)

        // Scrollable peer list (typically very short).
        val scroll = ScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
        peerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(peerList, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll)

        return root
    }
}
