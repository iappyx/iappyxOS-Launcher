/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — main-process client that talks to PluginIsolatedService
 * (running in :plugin_isolated). Lazily binds on first invocation;
 * stays bound for the launcher's lifetime so subsequent invokes don't
 * pay the connection latency every call.
 */
package com.iappyx.launcher.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

internal object PluginIsolatedClient {

    @Volatile private var serviceMessenger: Messenger? = null
    @Volatile private var bound: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Replies routed by main-process callId. */
    private val pending = ConcurrentHashMap<String, PluginResultCallback>()
    private val callSeq = AtomicLong(0)
    /** Invocations issued while the bind is in flight — drained on connect. */
    private val pendingInvocations = ConcurrentLinkedQueue<Runnable>()

    /** Reply Messenger registered with the service so it can post
     *  results back to us. Static handler ↔ singleton instance. */
    private val replyMessenger by lazy {
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != PluginIsolatedService.MSG_INVOKE_RESULT) return
                val data = msg.data ?: return
                val callId = data.getString(PluginIsolatedService.KEY_CALL_ID) ?: return
                val resultJson = data.getString(PluginIsolatedService.KEY_RESULT_JSON) ?: return
                pending.remove(callId)?.onResult(resultJson)
            }
        })
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serviceMessenger = Messenger(service)
            bound = true
            // Drain anything queued while we were binding.
            while (true) {
                val r = pendingInvocations.poll() ?: break
                mainHandler.post(r)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceMessenger = null
            bound = false
            // Pending callbacks get error replies — the isolated
            // process died (likely a crash); plugins should re-issue
            // on the next call.
            for ((id, cb) in pending) {
                cb.onResult(errJson("isolated plugin process disconnected"))
                pending.remove(id)
            }
        }
    }

    fun invoke(
        context: Context,
        pluginId: String,
        method: String,
        argsJson: String,
        onResult: PluginResultCallback,
    ) {
        val callId = "iso_call_${callSeq.incrementAndGet()}"
        pending[callId] = onResult

        val send = Runnable {
            val m = serviceMessenger
            if (m == null) {
                pending.remove(callId)?.onResult(errJson("isolated service not bound"))
                return@Runnable
            }
            val msg = Message.obtain(null, PluginIsolatedService.MSG_INVOKE)
            msg.replyTo = replyMessenger
            val b = Bundle()
            b.putString(PluginIsolatedService.KEY_CALL_ID, callId)
            b.putString(PluginIsolatedService.KEY_PLUGIN_ID, pluginId)
            b.putString(PluginIsolatedService.KEY_METHOD, method)
            b.putString(PluginIsolatedService.KEY_ARGS_JSON, argsJson)
            msg.data = b
            try { m.send(msg) }
            catch (e: RemoteException) {
                pending.remove(callId)?.onResult(errJson("RemoteException: ${e.message}"))
            }
        }

        if (bound) {
            mainHandler.post(send)
        } else {
            pendingInvocations.add(send)
            ensureBound(context)
        }
    }

    private fun ensureBound(context: Context) {
        if (bound) return
        val intent = Intent(context.applicationContext, PluginIsolatedService::class.java)
        try {
            context.applicationContext.bindService(
                intent, connection, Context.BIND_AUTO_CREATE,
            )
        } catch (e: Throwable) {
            // Failed to bind — surface as an error to all pending
            // invocations so they don't hang forever.
            while (true) {
                val r = pendingInvocations.poll() ?: break
                mainHandler.post {
                    pending.entries.firstOrNull()?.let {
                        it.value.onResult(errJson("bind failed: ${e.message}"))
                        pending.remove(it.key)
                    }
                }
            }
        }
    }

    private fun errJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
