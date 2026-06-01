/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Per-instance sandbox: each placed widget gets its own directory and namespace.
 * Same HTML placed twice == two independent sandboxes with their own storage.
 */
class WidgetSandbox(context: Context, val widgetId: String) {
    val dir: File = File(context.filesDir, "widgets/$widgetId").also { it.mkdirs() }

    companion object {
        fun newId(): String = "w_" + UUID.randomUUID().toString().substring(0, 12)

        fun sandboxFor(context: Context, widgetId: String): WidgetSandbox =
            WidgetSandbox(context, widgetId)
    }
}
