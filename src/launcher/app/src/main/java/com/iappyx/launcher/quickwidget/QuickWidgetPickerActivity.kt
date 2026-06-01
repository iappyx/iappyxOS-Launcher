/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: picker. Lists every widget in the library (bundled +
 * user-generated); tap one to bind it to the slot passed via
 * EXTRA_SLOT. Launched either from a tile tap when no widget is bound,
 * or from the Settings → Quick Widgets manager.
 */
package com.iappyx.launcher.quickwidget

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.iappyx.launcher.widget.WidgetLibrary

class QuickWidgetPickerActivity : Activity() {

    companion object {
        const val EXTRA_SLOT = "slot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(EXTRA_SLOT, 1)
        if (slot !in 1..QuickWidgetPrefs.MAX_SLOTS) { finish(); return }

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D14.toInt())
            fitsSystemWindows = true
        }

        // Sticky header — makes it unmistakable this is a picker, not a
        // preview. Tinted band at the top with a clear instruction.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF17171F.toInt())
            setPadding(pad, pad, pad, (16 * density).toInt())
        }
        header.addView(TextView(this).apply {
            text = "Pick a widget"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Quick Settings tile · Slot $slot"
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@QuickWidgetPickerActivity))
            textSize = 12f
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        })
        header.addView(TextView(this).apply {
            text = "Tap a widget below to bind it. The Quick Settings tile " +
                "will launch this widget as a panel when tapped from any app."
            setTextColor(0xFFB0B0B8.toInt())
            textSize = 13f
            setLineSpacing(0f, 1.3f)
        })
        root.addView(header)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, pad)
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0,
        ).apply { weight = 1f })

        val current = QuickWidgetPrefs.getWidgetForSlot(this, slot)

        // Unbind row — only shown when something IS bound, so the
        // empty-slot case isn't cluttered with a destructive option.
        if (current != null) {
            list.addView(unbindRow {
                QuickWidgetPrefs.setWidgetForSlot(this, slot, null)
                finish()
            })
        }

        for (entry in WidgetLibrary.all(this)) {
            list.addView(widgetRow(entry, isSelected = entry.id == current) {
                QuickWidgetPrefs.setWidgetForSlot(this, slot, entry.id)
                finish()
            })
        }

        setContentView(root)
    }

    private fun widgetRow(
        entry: WidgetLibrary.Entry,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(0xFF17171F.toInt())
                if (isSelected) setStroke((2 * density).toInt(), com.iappyx.launcher.widget.Palette.accent(this@QuickWidgetPickerActivity))
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        // Square thumb placeholder. Phase 5 polish: render the widget's
        // actual thumbnail here via the existing iframe preview pipeline.
        val thumb = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xFF0A0A10.toInt())
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                (56 * density).toInt(), (56 * density).toInt(),
            )
        }
        val initials = TextView(this).apply {
            text = entry.title.take(2).uppercase()
            setTextColor(com.iappyx.launcher.widget.Palette.accent(this@QuickWidgetPickerActivity))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        thumb.addView(initials)
        row.addView(thumb)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                weight = 1f
                leftMargin = (14 * density).toInt()
            }
        }
        textCol.addView(TextView(this).apply {
            text = entry.title
            setTextColor(if (isSelected) com.iappyx.launcher.widget.Palette.accent(this@QuickWidgetPickerActivity) else Color.WHITE)
            textSize = 15f
            setTypeface(typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        })
        if (entry.subtitle.isNotBlank()) {
            textCol.addView(TextView(this).apply {
                text = entry.subtitle
                setTextColor(0xFF8E8E97.toInt())
                textSize = 12f
                setPadding(0, (3 * density).toInt(), 0, 0)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(textCol)

        if (isSelected) {
            row.addView(TextView(this).apply {
                text = "✓"
                setTextColor(com.iappyx.launcher.widget.Palette.accent(this@QuickWidgetPickerActivity))
                textSize = 22f
                setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            })
        }
        return row
    }

    private fun unbindRow(onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = "Unbind this slot"
            setTextColor(0xFFFF5252.toInt())
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setStroke((1 * density).toInt(), 0x55FF5252)
            }
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (16 * density).toInt() }
        }
    }
}
