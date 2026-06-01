/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Replacement for the system widget picker (ACTION_APPWIDGET_PICK) which fails
 * to bind on non-system launchers with "Cannot add widget". Here we enumerate
 * providers ourselves, show a rich list with preview + app icon + size chip,
 * and on selection return the provider ComponentName to the caller. The caller
 * then allocates a host id, calls bindAppWidgetIdIfAllowed, and falls back to
 * ACTION_APPWIDGET_BIND if allocation requires user consent.
 */
class StockWidgetPickerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PROVIDER = "provider_component"
        const val RESULT_MIN_WIDTH = "min_width"
        const val RESULT_MIN_HEIGHT = "min_height"
    }

    private data class Entry(
        val info: AppWidgetProviderInfo,
        val appLabel: String,
        val appIcon: Drawable?,
        val widgetLabel: String,
        val preview: Drawable?,
        val minWidthDp: Int,
        val minHeightDp: Int,
    )

    private var all: List<Entry> = emptyList()
    private val filtered: MutableList<Entry> = mutableListOf()
    private lateinit var adapter: Adapter
    private lateinit var spinner: ProgressBar
    private lateinit var searchField: EditText
    @Volatile private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            fitsSystemWindows = true
        }

        val header = TextView(this).apply {
            setText(R.string.stock_widget_picker_title)
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        val search = EditText(this).apply {
            setHint(R.string.stock_widget_picker_search_hint)
            setHintTextColor(Color.parseColor("#A0A0B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#1A1A2E"))
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
        // Wrap the RecyclerView in a FrameLayout so a centered spinner can
        // overlay it while loadEntries() runs on a background thread.
        val listContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@StockWidgetPickerActivity)
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (24 * dp).toInt())
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER }
        }
        listContainer.addView(list)
        listContainer.addView(spinner)
        searchField = search
        root.addView(header)
        root.addView(search)
        root.addView(listContainer)
        setContentView(root)

        adapter = Adapter(filtered) { chosen ->
            val data = Intent().apply {
                putExtra(RESULT_PROVIDER, chosen.info.provider)
                putExtra(RESULT_MIN_WIDTH, chosen.minWidthDp)
                putExtra(RESULT_MIN_HEIGHT, chosen.minHeightDp)
            }
            setResult(RESULT_OK, data)
            finish()
        }
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, s1: Int, s2: Int, s3: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, s1: Int, s2: Int, s3: Int) {
                applyFilter(s?.toString().orEmpty())
            }
        })

        // installedProviders + per-widget appIcon/preview load is ~50-200ms
        // on busy devices — push it off the main thread and reveal results
        // when ready. User sees a spinner; search continues to work once
        // populated, and any text typed before load completes is re-applied
        // post-load.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val loaded = try { loadEntries() } catch (_: Throwable) { emptyList() }
            main.post {
                if (destroyed) return@post
                all = loaded
                spinner.visibility = View.GONE
                applyFilter(searchField.text?.toString().orEmpty())
            }
        }.apply { isDaemon = true; name = "StockWidgetPicker-load"; start() }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        filtered.clear()
        if (q.isEmpty()) filtered.addAll(all)
        else filtered.addAll(all.filter {
            it.widgetLabel.lowercase().contains(q) || it.appLabel.lowercase().contains(q)
        })
        adapter.notifyDataSetChanged()
    }

    private fun loadEntries(): List<Entry> {
        val pm = packageManager
        val mgr = AppWidgetManager.getInstance(this)
        val dp = resources.displayMetrics.density
        return mgr.installedProviders.mapNotNull { info ->
            try {
                val appLabel = pm.getApplicationLabel(
                    pm.getApplicationInfo(info.provider.packageName, 0)
                ).toString()
                val appIcon = pm.getApplicationIcon(info.provider.packageName)
                val widgetLabel = info.loadLabel(pm).orEmpty().ifBlank { appLabel }
                val preview = try { info.loadPreviewImage(this, 0) } catch (_: Exception) { null }
                    ?: appIcon
                Entry(
                    info = info,
                    appLabel = appLabel,
                    appIcon = appIcon,
                    widgetLabel = widgetLabel,
                    preview = preview,
                    minWidthDp = (info.minWidth / dp).toInt(),
                    minHeightDp = (info.minHeight / dp).toInt(),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.widgetLabel.lowercase() }))
    }

    private class Adapter(
        private val items: List<Entry>,
        private val onPick: (Entry) -> Unit,
    ) : RecyclerView.Adapter<Adapter.H>() {

        class H(
            val card: LinearLayout,
            val preview: ImageView,
            val title: TextView,
            val subtitle: TextView,
            val sizeChip: TextView,
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = 14 * dp
                    setColor(Color.parseColor("#1A1A2E"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#22FFFFFF"))
                }
                val p = (12 * dp).toInt()
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (10 * dp).toInt() }
                isClickable = true; isFocusable = true
            }
            val preview = ImageView(ctx).apply {
                val size = (64 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (12 * dp).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(Color.parseColor("#0D0D1A"))
                }
                val pad = (6 * dp).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subtitle = TextView(ctx).apply {
                setTextColor(Color.parseColor("#A0A0B8"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val chip = TextView(ctx).apply {
                setTextColor(Color.parseColor("#0D0D1A"))
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(com.iappyx.launcher.widget.Palette.accent(ctx))
                }
                val hp = (8 * dp).toInt(); val vp = (3 * dp).toInt()
                setPadding(hp, vp, hp, vp)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = (6 * dp).toInt()
                layoutParams = lp
            }
            textCol.addView(title)
            textCol.addView(subtitle)
            textCol.addView(chip)
            card.addView(preview)
            card.addView(textCol)
            return H(card, preview, title, subtitle, chip)
        }

        override fun onBindViewHolder(h: H, position: Int) {
            val e = items[position]
            h.preview.setImageDrawable(e.preview ?: e.appIcon)
            h.title.text = e.widgetLabel
            h.subtitle.text = e.appLabel
            h.sizeChip.text = "${e.minWidthDp}×${e.minHeightDp} dp"
            h.card.setOnClickListener { onPick(e) }
        }

        override fun getItemCount() = items.size
    }
}
