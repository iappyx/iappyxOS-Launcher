/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Reusable app chooser — icons, search, grouped by first letter with subtle
 * dividers. Returns the chosen package + activity name via startActivityForResult.
 */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PACKAGE = "result_package"
        const val RESULT_ACTIVITY = "result_activity"
        const val EXTRA_TITLE = "extra_title"
    }

    private data class AppEntry(val label: String, val packageName: String, val activityName: String, val icon: Drawable)
    private sealed class Row {
        data class Header(val letter: String) : Row()
        data class App(val entry: AppEntry) : Row()
    }

    private lateinit var all: List<AppEntry>
    private lateinit var rows: MutableList<Row>
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_picker_default_title)
        val self = this
        val palBgCell = com.iappyx.launcher.widget.Palette.bgCell(self)
        val palBgHome = com.iappyx.launcher.widget.Palette.bgHome(self)
        val palSecondary = com.iappyx.launcher.widget.Palette.textSecondary(self)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palBgHome)
            fitsSystemWindows = true
        }
        val header = TextView(this).apply {
            text = title
            setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(self))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        val search = EditText(this).apply {
            setHint(R.string.app_picker_search_hint)
            setHintTextColor(palSecondary)
            setTextColor(com.iappyx.launcher.widget.Palette.textPrimary(self))
            textSize = 14f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(palBgCell)
                setStroke((1 * dp).toInt(), Color.parseColor("#22FFFFFF"))
            }
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins((20 * dp).toInt(), 0, (20 * dp).toInt(), (12 * dp).toInt())
            layoutParams = lp
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (24 * dp).toInt())
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(header)
        root.addView(search)
        root.addView(list)
        setContentView(root)

        all = loadApps()
        rows = toRows(all).toMutableList()
        adapter = Adapter(rows) { entry ->
            val data = Intent().apply {
                putExtra(RESULT_PACKAGE, entry.packageName)
                putExtra(RESULT_ACTIVITY, entry.activityName)
            }
            setResult(RESULT_OK, data)
            finish()
        }
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, s1: Int, s2: Int, s3: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, s1: Int, s2: Int, s3: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                rows.clear()
                val filtered = if (q.isEmpty()) all
                else all.filter { it.label.lowercase().contains(q) }
                rows.addAll(toRows(filtered))
                adapter.notifyDataSetChanged()
            }
        })
    }

    private fun loadApps(): List<AppEntry> {
        // AppRegistry prewarms the installed-apps list on a background
        // thread at activity start — pulling from there skips the
        // synchronous queryIntentActivities + loadIcon-per-app cost
        // (~500ms-1s on a busy device with 200+ apps).
        return com.iappyx.launcher.widget.AppRegistry.apps(this)
            .mapNotNull { app ->
                val activityName = app.activityName ?: return@mapNotNull null
                AppEntry(
                    label = app.label,
                    packageName = app.packageName,
                    activityName = activityName,
                    icon = app.icon,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun toRows(apps: List<AppEntry>): List<Row> {
        val out = mutableListOf<Row>()
        var currentLetter = ""
        for (e in apps) {
            val l = firstLetter(e.label)
            if (l != currentLetter) {
                out.add(Row.Header(l))
                currentLetter = l
            }
            out.add(Row.App(e))
        }
        return out
    }

    private fun firstLetter(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    private class Adapter(
        private val items: List<Row>,
        private val onPick: (AppEntry) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.App -> TYPE_APP
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            return if (viewType == TYPE_HEADER) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
                }
                val letter = TextView(ctx).apply {
                    setTextColor(com.iappyx.launcher.widget.Palette.accent(ctx))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding((4 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                }
                val line = View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#22FFFFFF"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, (1 * dp).toInt(), 1f,
                    )
                }
                row.addView(letter)
                row.addView(line)
                HeaderHolder(row, letter)
            } else {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * dp
                        setColor(com.iappyx.launcher.widget.Palette.bgCell(ctx))
                    }
                    val p = (10 * dp).toInt()
                    setPadding(p, p, p, p)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (6 * dp).toInt() }
                    isClickable = true; isFocusable = true
                }
                val icon = ImageView(ctx).apply {
                    val size = (40 * dp).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (12 * dp).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    )
                }
                val label = TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val pkg = TextView(ctx).apply {
                    setTextColor(Color.parseColor("#A0A0B8"))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                textCol.addView(label)
                textCol.addView(pkg)
                card.addView(icon)
                card.addView(textCol)
                AppHolder(card, icon, label, pkg)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = items[position]) {
                is Row.Header -> (holder as HeaderHolder).letter.text = r.letter
                is Row.App -> (holder as AppHolder).apply {
                    icon.setImageDrawable(r.entry.icon)
                    label.text = r.entry.label
                    pkg.text = r.entry.packageName
                    root.setOnClickListener { onPick(r.entry) }
                }
            }
        }

        class HeaderHolder(val root: View, val letter: TextView) : RecyclerView.ViewHolder(root)
        class AppHolder(
            val root: View,
            val icon: ImageView,
            val label: TextView,
            val pkg: TextView,
        ) : RecyclerView.ViewHolder(root)
    }
}
