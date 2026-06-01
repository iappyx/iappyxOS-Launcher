/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — capability table. Maps each capability string (declared in
 * a plugin's manifest.json) to:
 *   - the bridge factory that instantiates the @JavascriptInterface
 *   - the JS-side namespace name the shim should expose under iappyx.*
 *
 * Unknown capabilities are silently dropped. Adding a new capability
 * = add a row here + the corresponding bridge class. No other code in
 * the plugin system needs to change.
 */
package com.iappyx.launcher.plugins

import android.content.Context
import android.webkit.WebView

internal object PluginCapability {

    /** One row of the table. [attacher] adds the @JavascriptInterface
     *  to the plugin's WebView; [shimNamespace] is the property on
     *  `window.iappyx` the shim should populate (e.g. "httpClient" →
     *  `iappyx.httpClient = iappyxHttpClient`). */
    private data class Cap(
        val name: String,
        val shimNamespace: String,
        val attacher: (WebView, String, Context, PluginInvocationContext) -> Unit,
    )

    private val table: List<Cap> = listOf(
        Cap("http", "httpClient") { wv, _, _, ctx ->
            wv.addJavascriptInterface(PluginHttpBridge(ctx), "iappyxHttpClient")
        },
        Cap("storage", "storage") { wv, id, c, _ ->
            wv.addJavascriptInterface(PluginStorageBridge(c, id), "iappyxStorage")
        },
        Cap("secureStore", "secureStore") { wv, id, c, _ ->
            wv.addJavascriptInterface(PluginSecureStoreBridge(c, id), "iappyxSecureStore")
        },
        Cap("scheduler", "scheduler") { wv, id, c, ctx ->
            wv.addJavascriptInterface(PluginSchedulerBridge(c, id, ctx), "iappyxScheduler")
        },
        Cap("notification:read", "notifications") { wv, id, c, ctx ->
            wv.addJavascriptInterface(PluginNotificationsBridge(c, id, ctx), "iappyxNotifications")
        },
        Cap("push", "push") { wv, id, c, ctx ->
            wv.addJavascriptInterface(PluginPushBridge(c, id, ctx), "iappyxPush")
        },
        Cap("mqtt", "mqtt") { wv, id, c, ctx ->
            wv.addJavascriptInterface(PluginMqttBridge(c, id, ctx), "iappyxMqtt")
        },
    )

    /** Attach the bridges a plugin's manifest declared. Called once by
     *  PluginHost when the plugin's WebView is created. */
    fun attachFor(
        webView: WebView,
        pluginId: String,
        context: Context,
        invocationCtx: PluginInvocationContext,
        capabilities: List<String>,
    ): List<String> {
        val attachedNamespaces = mutableListOf<String>()
        for (cap in capabilities) {
            val match = table.firstOrNull { it.name == cap } ?: continue
            try {
                match.attacher(webView, pluginId, context, invocationCtx)
                attachedNamespaces.add(match.shimNamespace)
            } catch (t: Throwable) {
                android.util.Log.w("iappyx-plugin",
                    "Failed to attach capability '$cap' for $pluginId: ${t.message}")
            }
        }
        return attachedNamespaces
    }

    /** User-facing display names for the install-time consent dialog
     *  (P4 / P5). Kept here so adding a capability adds its label in
     *  the same place as its plumbing. */
    fun displayName(capability: String): String = when (capability) {
        "http" -> "Network access"
        "storage" -> "Local storage"
        "secureStore" -> "Encrypted credential storage"
        "scheduler" -> "Background scheduling (periodic / one-shot)"
        "notification:read" -> "Read other apps' notifications"
        "push" -> "Receive push notifications"
        "mqtt" -> "MQTT messaging (subscribe + publish to a broker)"
        else -> capability
    }

    /** Short labels for the per-plugin capability chips in Settings.
     *  The card row is horizontal and non-wrapping, so labels need to
     *  stay tight enough that 4+ chips fit one one line on a phone. The
     *  long [displayName] version stays in place for the install
     *  consent dialog where the user actually needs to understand what
     *  the capability is. */
    fun chipLabel(capability: String): String = when (capability) {
        "http" -> "Network"
        "storage" -> "Storage"
        "secureStore" -> "Credentials"
        "scheduler" -> "Background"
        "notification:read" -> "Notifications"
        "push" -> "Push"
        "mqtt" -> "MQTT"
        else -> capability
    }
}
