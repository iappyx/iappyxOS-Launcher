/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.sharing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * WiFi Direct P2P service for sharing artefact bundles between phones.
 * Lifted from iappyxOS' container app and slimmed: the payload is now an
 * artefact bundle zip (widget / wallpaper / transition) and the HTTP
 * server's `/info.json` carries bundle metadata instead of APK info.
 *
 * Sender flow:  `init()` → `startSharing(...)` → wait → `stopSharing()`
 * Receiver flow: `init()` → `discoverPeers()` → `connectToPeer()` →
 *                `downloadBundle()` (after WIFI_P2P_CONNECTION_CHANGED tells
 *                us the host's IP).
 *
 * The same instance can act as either side — the activity drives flow
 * by which methods it calls. Both sides MUST call [destroy] in onDestroy.
 */
class P2PService(private val activity: Activity) {

    companion object {
        private const val TAG = "iappyxLauncher-P2P"
        private const val PORT = 8888
        private const val REQ_P2P_PERMS = 9001
    }

    private val context: Context get() = activity

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var isSharing = false

    var onPeersChanged: ((List<PeerInfo>) -> Unit)? = null
    var onConnectionChanged: ((WifiP2pInfo?) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    data class PeerInfo(val name: String, val address: String, val status: String)

    fun init() {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(context, Looper.getMainLooper(), null)
        registerReceiver()
    }

    fun destroy() {
        stopSharing()
        unregisterReceiver()
        channel = null
        manager = null
    }

    private var pendingAction: (() -> Unit)? = null

    /** Returns true when permissions are already granted and the caller can
     *  proceed; false when we kicked off a request — the caller should
     *  expect [onPermissionsResult] to retry the action once granted. */
    private fun ensurePermissions(onGranted: () -> Unit): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (needed.isNotEmpty()) {
            pendingAction = onGranted
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQ_P2P_PERMS)
            return false
        }
        return true
    }

    fun onPermissionsResult(requestCode: Int) {
        if (requestCode == REQ_P2P_PERMS) {
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    // ── Sender ──

    /** Create a P2P group and serve [bundleFile] over HTTP on [PORT]. The
     *  receiving phone hits `/info.json` for the manifest, then `/bundle.zip`
     *  to grab the actual bytes. */
    @SuppressLint("MissingPermission")
    fun startSharing(
        bundleFile: File,
        title: String,
        kindLabel: String,
        size: Long,
        onReady: (Boolean, String?) -> Unit,
    ) {
        if (!ensurePermissions {
                startSharing(bundleFile, title, kindLabel, size, onReady)
            }) return
        val mgr = manager ?: run { onReady(false, "WiFi Direct not available"); return }
        val ch = channel ?: run { onReady(false, "WiFi Direct not initialized"); return }

        isSharing = true
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group created, starting HTTP server")
                startHttpServer(bundleFile, title, kindLabel, size)
                onReady(true, null)
            }
            override fun onFailure(reason: Int) {
                isSharing = false
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported"
                    WifiP2pManager.BUSY -> "WiFi Direct busy"
                    WifiP2pManager.ERROR -> "WiFi Direct error"
                    else -> "WiFi Direct failed (code $reason)"
                }
                Log.e(TAG, "createGroup failed: $msg")
                onReady(false, msg)
            }
        })
    }

    fun stopSharing() {
        isSharing = false
        stopHttpServer()
        removeGroup()
    }

    private fun startHttpServer(
        bundleFile: File, title: String, kindLabel: String, size: Long,
    ) {
        stopHttpServer()
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT)
                serverSocket?.soTimeout = 0
                Log.i(TAG, "HTTP server started on port $PORT")
                onStatusChanged?.invoke("waiting")
                // Snapshot serverSocket into a local val per iteration so
                // stopHttpServer() (which may null it from another thread)
                // can't NPE the !!.accept() / .isClosed access between the
                // check and the use.
                while (isSharing) {
                    val sock = serverSocket ?: break
                    if (sock.isClosed) break
                    try {
                        val client = sock.accept()
                        handleClient(client, bundleFile, title, kindLabel, size)
                    } catch (e: java.net.SocketException) {
                        if (isSharing) Log.e(TAG, "Server socket error: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP server error: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handleClient(
        client: Socket, bundleFile: File, title: String, kindLabel: String, size: Long,
    ) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                Log.i(TAG, "HTTP request: $requestLine")
                val output = c.getOutputStream()

                when {
                    requestLine.contains("GET /info.json") -> {
                        val safeTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
                        val json = """{"title":"$safeTitle","kind":"$kindLabel","size":$size}"""
                        val body = json.toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                    requestLine.contains("GET /bundle.zip") -> {
                        if (!bundleFile.exists()) {
                            output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                            return
                        }
                        onStatusChanged?.invoke("transferring")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${bundleFile.length()}\r\nConnection: close\r\n\r\n".toByteArray())
                        FileInputStream(bundleFile).use { fis ->
                            val buf = ByteArray(65536)
                            var read: Int
                            while (fis.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                            }
                        }
                        output.flush()
                        Log.i(TAG, "Bundle sent: ${bundleFile.length()} bytes")
                        onStatusChanged?.invoke("done")
                    }
                    else -> {
                        output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error: ${e.message}")
        }
    }

    private fun stopHttpServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    @SuppressLint("MissingPermission")
    private fun removeGroup() {
        try {
            val ch = channel ?: return
            manager?.removeGroup(ch, null)
        } catch (_: Exception) {}
    }

    // ── Receiver ──

    @SuppressLint("MissingPermission")
    fun discoverPeers(onResult: (Boolean, String?) -> Unit) {
        if (!ensurePermissions { discoverPeers(onResult) }) return
        val mgr = manager ?: run { onResult(false, "WiFi Direct not available"); return }
        val ch = channel ?: run { onResult(false, "WiFi Direct not initialized"); return }
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Peer discovery started"); onResult(true, null)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "discoverPeers failed: $reason")
                onResult(false, "Discovery failed (code $reason)")
            }
        })
    }

    fun stopDiscovery() {
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onResult: (Boolean, String?) -> Unit) {
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        val ch = channel ?: run { onResult(false, "P2P channel unavailable"); return }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connection initiated to $deviceAddress")
                onResult(true, null)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "connect failed: $reason")
                onResult(false, "Connection failed (code $reason)")
            }
        })
    }

    /** Pull `/info.json` then `/bundle.zip` from [hostIp]. The downloaded
     *  zip is written to [destPath] in chunks; [onProgress] fires on every
     *  percent change so the receiver UI gets a smooth progress bar. */
    fun downloadBundle(
        hostIp: String,
        destPath: String,
        onProgress: (Int, Long, Long) -> Unit,
        onDone: (Boolean, String?, String?) -> Unit,
    ) {
        Thread {
            try {
                val infoResponse: String
                val infoSocket = Socket()
                try {
                    infoSocket.connect(InetSocketAddress(hostIp, PORT), 5000)
                    infoSocket.getOutputStream().write(
                        "GET /info.json HTTP/1.1\r\nHost: $hostIp\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    infoResponse = BufferedReader(
                        InputStreamReader(infoSocket.getInputStream()),
                    ).readText()
                } finally { try { infoSocket.close() } catch (_: Exception) {} }
                val infoJson = infoResponse.substringAfter("\r\n\r\n", "{}").trim()
                Log.i(TAG, "Info: $infoJson")

                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(hostIp, PORT), 10000)
                    socket.getOutputStream().write(
                        "GET /bundle.zip HTTP/1.1\r\nHost: $hostIp\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    val input = socket.getInputStream()
                    val headerBuf = StringBuilder()
                    var crlfCount = 0
                    while (true) {
                        val b = input.read(); if (b == -1) break
                        headerBuf.append(b.toChar())
                        if (b == '\r'.code || b == '\n'.code) crlfCount++ else crlfCount = 0
                        if (crlfCount >= 4) break
                    }
                    val headers = headerBuf.toString()
                    val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(headers)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                    val outFile = File(destPath)
                    var totalRead = 0L
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(65536)
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf); if (read == -1) break
                            fos.write(buf, 0, read)
                            totalRead += read
                            if (contentLength > 0) {
                                val pct = (totalRead * 100 / contentLength).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct, totalRead, contentLength)
                                }
                            }
                        }
                    }
                    // Verify completeness: a mid-transfer WiFi-Direct drop ends
                    // the stream early, leaving a truncated file. Don't report
                    // that as success — delete it and fail (H10-3).
                    if (contentLength > 0 && totalRead != contentLength) {
                        try { outFile.delete() } catch (_: Exception) {}
                        Log.w(TAG, "Truncated download: $totalRead/$contentLength bytes")
                        onDone(false, "Transfer incomplete ($totalRead/$contentLength bytes)", null)
                    } else {
                        Log.i(TAG, "Bundle downloaded: ${outFile.length()} bytes")
                        onDone(true, null, infoJson)
                    }
                } finally { try { socket.close() } catch (_: Exception) {} }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                onDone(false, e.message ?: "Download failed", null)
            }
        }.start()
    }

    fun disconnect() {
        stopDiscovery()
        removeGroup()
    }

    // ── Broadcast Receiver ──

    @SuppressLint("MissingPermission")
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        manager?.requestPeers(ch) { peers ->
                            val list = peers.deviceList.map { d ->
                                PeerInfo(
                                    name = d.deviceName ?: "Unknown",
                                    address = d.deviceAddress,
                                    status = when (d.status) {
                                        WifiP2pDevice.CONNECTED -> "connected"
                                        WifiP2pDevice.INVITED -> "invited"
                                        WifiP2pDevice.AVAILABLE -> "available"
                                        else -> "unavailable"
                                    },
                                )
                            }
                            onPeersChanged?.invoke(list)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        manager?.requestConnectionInfo(ch) { info ->
                            onConnectionChanged?.invoke(info)
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiver() {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
    }
}
