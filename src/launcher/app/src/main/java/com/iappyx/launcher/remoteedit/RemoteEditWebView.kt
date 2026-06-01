/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — proxy WebView.
 *
 * Some launcher bridges (sensor.startCompass, sensor.startMagnetometer,
 * fireEvent, etc.) push results to widget JS by directly calling
 * `webView.evaluateJavascript("window.fooCallback({...})", null)` —
 * NOT via the `deliverResult` cbId machinery. Those bypass our proxy
 * short-circuit and would silently no-op against an empty proxy
 * WebView.
 *
 * This subclass intercepts every `evaluateJavascript` call and routes
 * the script string to a forwarder callback. The callback streams it
 * over SSE to the browser iframe, where the script actually runs and
 * the widget's real callback fires.
 */
package com.iappyx.launcher.remoteedit

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView

class RemoteEditWebView(
    context: Context,
    private val onEvalJs: (String) -> Unit,
) : WebView(context) {

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        android.util.Log.d("iappyxRemoteEdit", "evalJS intercepted: " + script.take(120))
        try { onEvalJs(script) } catch (t: Throwable) {
            android.util.Log.w("iappyxRemoteEdit", "onEvalJs threw: ${t.message}")
        }
        resultCallback?.onReceiveValue(null)
    }
}
