/*
 * MIT License - Copyright (c) 2026 iappyx
 * Plan A Phase 5 — single canonical button language. All three styles
 * already exist in themes.xml as Button.Primary / Button.Secondary /
 * Button.Danger; this helper exposes them to programmatic UI so the
 * Kotlin-built activities (PluginDetail, WidgetUsage, etc.) look
 * identical to the XML-defined ones.
 */
package com.iappyx.launcher

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

enum class SettingsButtonKind { PRIMARY, SECONDARY, DANGER }

object SettingsButtons {

    fun build(
        context: Context,
        kind: SettingsButtonKind,
        text: CharSequence,
        onClick: () -> Unit,
    ): Button {
        val styleRes = when (kind) {
            SettingsButtonKind.PRIMARY -> R.style.Button_Primary
            SettingsButtonKind.SECONDARY -> R.style.Button_Secondary
            SettingsButtonKind.DANGER -> R.style.Button_Danger
        }
        // ContextThemeWrapper lets the inflated Button pick up the
        // style's attributes (background, textColor, etc.) without
        // hardcoding them in code. Identical visual to XML usage.
        val themed = ContextThemeWrapper(context, styleRes)
        return Button(themed, null, 0).apply {
            this.text = text
            isAllCaps = false
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // Default to WRAP_CONTENT so the caller can wrap in a row
            // without the button stretching the entire width.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}
