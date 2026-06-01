/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iappyx.launcher.R
import com.iappyx.launcher.cells.IconPack

/**
 * Manual per-app icon chooser. Shows every drawable the active pack ships in a
 * scrollable grid (lazily bound via a [RecyclerView], so large packs stay
 * responsive) plus a "Reset to automatic" action.
 *
 * [onPick] receives the chosen drawable name; [onReset] clears the override.
 */
class IconOverrideSheet(
    private val activity: Activity,
    private val packPkg: String,
    private val onPick: (String) -> Unit,
    private val onReset: () -> Unit,
) {

    fun show() {
        val dp = activity.resources.displayMetrics.density
        val dialog = BottomSheetDialog(activity, R.style.Theme_iappyxLauncher_BottomSheet)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D1A"))
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        // Drag-handle pill
        root.addView(View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * dp
                setColor(Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (4 * dp).toInt())
                .apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (10 * dp).toInt() }
        })
        // Header row: title + reset
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            addView(TextView(activity).apply {
                text = "Choose an icon"
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = "Reset to auto"
                setTextColor(Palette.accent(activity))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                isClickable = true; isFocusable = true
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { onReset(); dialog.dismiss() }
            })
        })

        val names = try { IconPack.allDrawables(activity, packPkg) } catch (_: Throwable) { emptyList() }
        if (names.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "This pack doesn't expose a drawable list."
                setTextColor(Color.parseColor("#A0A0B8"))
                textSize = 13f
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            })
        } else {
            val cols = 5
            val recycler = RecyclerView(activity).apply {
                layoutManager = GridLayoutManager(activity, cols)
                adapter = IconAdapter(names, dp) { name -> onPick(name); dialog.dismiss() }
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                clipToPadding = false
                // Cap the sheet height so it doesn't cover the whole screen.
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.6f).toInt(),
                )
            }
            root.addView(recycler)
        }

        dialog.setContentView(root)
        dialog.dismissOnActivityDestroy(activity)
        dialog.expandFully()
        dialog.show()
        dialog.themeContent()
    }

    private inner class IconAdapter(
        private val names: List<String>,
        private val dp: Float,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<IconAdapter.VH>() {

        inner class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = (56 * dp).toInt()
            val pad = (8 * dp).toInt()
            val iv = ImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, size,
                )
                setPadding(pad, pad, pad, pad)
                isClickable = true; isFocusable = true
                background = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#22FFFFFF"),
                ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
            }
            return VH(iv)
        }

        override fun getItemCount() = names.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val name = names[position]
            holder.image.setImageDrawable(
                try { IconPack.drawableByName(activity, packPkg, name) } catch (_: Throwable) { null },
            )
            holder.image.setOnClickListener { onClick(name) }
        }
    }
}
