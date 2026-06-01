/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: settings entry point. Lists all 5 slots with their
 * current bindings; tap a slot to launch the picker.
 */
package com.iappyx.launcher.quickwidget

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.SettingsScaffold
import com.iappyx.launcher.widget.WidgetLibrary

class QuickWidgetManagerActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        SettingsScaffold.attach(this, getString(R.string.settings_quickwidgets_label))
    }

    override fun onResume() {
        super.onResume()
        renderSlots()
    }

    private fun buildRoot(): View {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D14.toInt())
            fitsSystemWindows = true
        }
        // SettingsScaffold inflates its own toolbar; leave room for it.
        val toolbar = layoutInflater.inflate(R.layout.settings_toolbar, root, false)
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0,
        ).apply { weight = 1f })

        content.addView(TextView(this).apply {
            text = getString(R.string.quickwidget_manager_intro)
            setTextColor(0xFFB0B0B8.toInt())
            textSize = 13f
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        return root
    }

    private fun renderSlots() {
        val density = resources.displayMetrics.density
        list.removeAllViews()
        val bindings = QuickWidgetPrefs.allBindings(this)
        for (slot in 1..QuickWidgetPrefs.MAX_SLOTS) {
            val bound = bindings[slot]
            val title = bound?.let {
                try { WidgetLibrary.get(this, it)?.title ?: it } catch (_: Throwable) { it }
            } ?: getString(R.string.quickwidget_slot_empty)
            val subtitle = getString(R.string.quickwidget_slot_label, slot)
            list.addView(slotRow(slot, title, subtitle, bound != null))
            list.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (8 * density).toInt(),
                )
            })
        }
    }

    private fun slotRow(slot: Int, title: String, subtitle: String, isBound: Boolean): View {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(0xFF17171F.toInt())
                if (isBound) setStroke((1 * density).toInt(), com.iappyx.launcher.widget.Palette.accentAlpha(this@QuickWidgetManagerActivity, 0x55))
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@QuickWidgetManagerActivity, QuickWidgetPickerActivity::class.java)
                        .putExtra(QuickWidgetPickerActivity.EXTRA_SLOT, slot),
                )
            }
        }
        card.addView(TextView(this).apply {
            text = subtitle
            setTextColor(0xFF8E8E97.toInt())
            textSize = 11f
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        card.addView(TextView(this).apply {
            text = title
            setTextColor(if (isBound) Color.WHITE else 0xFF8E8E97.toInt())
            textSize = 15f
        })
        return card
    }
}
