/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.iappyx.launcher.model.CellType
import com.iappyx.launcher.model.Placement

/**
 * Long-press context menu for a filled home-grid cell. Visual style matches
 * [AppDrawerPanel.showContextPopup] so users see one consistent menu chrome
 * across the launcher. Items vary by [CellType]:
 *   - ICON  → app shortcuts + Remove + App info + Uninstall + Customize home
 *   - FOLDER → Customize home + Remove (open is on tap, rename is in the
 *              folder overlay)
 *   - STOCK_WIDGET / APP_DRAWER → Customize home + Remove
 *   - GENERATED_WIDGET → Edit + Customize home + Remove
 *
 * "Customize home" enters edit mode with this placement selected — the legacy
 * long-press behaviour, kept reachable so users who prefer it still have it
 * (and we have a Settings toggle that flips this whole popup off).
 */
object HomeCellContextMenu {
    fun show(
        context: Context,
        anchor: View,
        placement: Placement,
        onCustomizeHome: () -> Unit,
        onRemove: () -> Unit,
        onEditGenerated: () -> Unit = {},
        onOpenAppInfo: (pkg: String) -> Unit = {},
        onUninstall: (pkg: String) -> Unit = {},
        onChangeIcon: (pkg: String) -> Unit = {},
        onRename: (pkg: String) -> Unit = {},
    ): PopupWindow {
        val density = context.resources.displayMetrics.density
        // Notifications for this app icon (empty for non-icon cells or when the
        // notification listener isn't bound) — rendered at the top of the menu.
        val iconPkg = placement.takeIf { it.type == CellType.ICON }?.packageName
        val notifItems = if (iconPkg != null) {
            com.iappyx.launcher.notify.NotificationBadgeListener.notificationsFor(context, iconPkg)
        } else {
            emptyList()
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Color.parseColor("#22FFFFFF"))
            }
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            elevation = 12 * density
        }
        val popup = PopupWindow(
            container,
            ((if (notifItems.isNotEmpty()) 300 else 240) * density).toInt(),
            LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 12 * density
        }

        fun row(
            label: String,
            iconDrawable: android.graphics.drawable.Drawable? = null,
            onClick: () -> Unit,
        ) {
            val outer = LinearLayout(context).apply {
                orientation = if (iconDrawable != null) LinearLayout.HORIZONTAL
                              else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
                background = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#22FFFFFF"),
                ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
                setOnClickListener { popup.dismiss(); onClick() }
            }
            if (iconDrawable != null) {
                outer.addView(ImageView(context).apply {
                    setImageDrawable(iconDrawable)
                    val s = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginEnd = (10 * density).toInt()
                    }
                })
            }
            outer.addView(TextView(context).apply {
                text = label
                setTextColor(Palette.textPrimary(context))
                textSize = 14f
                if (iconDrawable != null) {
                    layoutParams = LinearLayout.LayoutParams(
                        0, LayoutParams.WRAP_CONTENT, 1f,
                    )
                }
            })
            container.addView(outer)
        }

        fun divider() {
            container.addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#22FFFFFF"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt(),
                )
                lp.topMargin = (4 * density).toInt()
                lp.bottomMargin = (4 * density).toInt()
                layoutParams = lp
            })
        }

        fun firePending(pi: android.app.PendingIntent?) {
            if (pi == null) return
            try { pi.send() } catch (_: Throwable) {}
        }

        /** Render the app's live notifications at the top: tap to open (+
         *  dismiss), ✕ to dismiss, action buttons, and an inline reply field
         *  for RemoteInput (messaging) actions. */
        fun notifSection(items: List<com.iappyx.launcher.notify.NotifItem>) {
            val tp = android.graphics.Typeface.DEFAULT
            for (item in items.take(5)) {
                val itemBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val px = (10 * density).toInt(); val py = (8 * density).toInt()
                    setPadding(px, py, px, py)
                }
                val headRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true; isFocusable = true
                    background = android.content.res.ColorStateList.valueOf(
                        Color.parseColor("#22FFFFFF"),
                    ).let { android.graphics.drawable.RippleDrawable(it, null, null) }
                    setOnClickListener {
                        popup.dismiss()
                        firePending(item.contentIntent)
                        if (item.isClearable) {
                            com.iappyx.launcher.notify.NotificationBadgeListener.dismiss(item.key)
                        }
                    }
                }
                item.icon?.let { ic ->
                    headRow.addView(ImageView(context).apply {
                        setImageDrawable(ic)
                        val s = (22 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(s, s)
                            .apply { marginEnd = (10 * density).toInt() }
                    })
                }
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                }
                if (item.title.isNotBlank()) texts.addView(TextView(context).apply {
                    text = item.title
                    setTextColor(Palette.textPrimary(context))
                    textSize = 13f
                    setTypeface(tp, android.graphics.Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (item.text.isNotBlank()) texts.addView(TextView(context).apply {
                    text = item.text
                    setTextColor(Palette.textSecondary(context))
                    textSize = 11f
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                headRow.addView(texts)
                if (item.isClearable) headRow.addView(TextView(context).apply {
                    text = "✕"
                    setTextColor(Palette.textSecondary(context))
                    textSize = 15f
                    val p = (6 * density).toInt(); setPadding(p, p, p, p)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        com.iappyx.launcher.notify.NotificationBadgeListener.dismiss(item.key)
                        container.removeView(itemBox)
                    }
                })
                itemBox.addView(headRow)

                val acts = item.actions.filter { it.title.isNotBlank() }.take(3)
                if (acts.isNotEmpty()) {
                    val actionRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (4 * density).toInt(), 0, 0)
                    }
                    for (a in acts) actionRow.addView(TextView(context).apply {
                        text = a.title
                        setTextColor(Palette.accent(context))
                        textSize = 12f
                        setTypeface(tp, android.graphics.Typeface.BOLD)
                        val px = (8 * density).toInt(); val py = (4 * density).toInt()
                        setPadding(px, py, px, py)
                        isClickable = true; isFocusable = true
                        setOnClickListener {
                            if (a.remoteInput != null) {
                                // Reveal an inline reply field in place of the actions.
                                itemBox.removeView(actionRow)
                                val input = android.widget.EditText(context).apply {
                                    hint = a.title; textSize = 13f
                                    setTextColor(Palette.textPrimary(context))
                                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                                    maxLines = 4
                                }
                                val send = TextView(context).apply {
                                    text = context.getString(com.iappyx.launcher.R.string.notif_reply_send)
                                    setTextColor(Palette.accent(context)); textSize = 12f
                                    setTypeface(tp, android.graphics.Typeface.BOLD)
                                    val p2 = (8 * density).toInt()
                                    setPadding(p2, (6 * density).toInt(), p2, (6 * density).toInt())
                                    isClickable = true; isFocusable = true
                                    setOnClickListener {
                                        val msg = input.text?.toString()?.trim().orEmpty()
                                        if (msg.isNotEmpty()) {
                                            com.iappyx.launcher.notify.NotificationBadgeListener.reply(context, a, msg)
                                            popup.dismiss()
                                        }
                                    }
                                }
                                itemBox.addView(LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(0, (4 * density).toInt(), 0, 0)
                                    addView(input); addView(send)
                                })
                                input.requestFocus()
                            } else {
                                popup.dismiss()
                                firePending(a.actionIntent)
                                if (item.isClearable) {
                                    com.iappyx.launcher.notify.NotificationBadgeListener.dismiss(item.key)
                                }
                            }
                        }
                    })
                    itemBox.addView(actionRow)
                }
                container.addView(itemBox)
            }
            if (items.isNotEmpty()) divider()
        }

        when (placement.type) {
            CellType.ICON -> {
                val pkg = placement.packageName
                if (pkg != null) {
                    notifSection(notifItems)
                    val shortcuts = fetchAppShortcuts(context, pkg)
                    val launcherApps = if (shortcuts.isNotEmpty()) {
                        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    } else null
                    val iconDpi = context.resources.displayMetrics.densityDpi
                    for (sc in shortcuts) {
                        val label = (sc.shortLabel ?: sc.longLabel
                            ?: context.getString(com.iappyx.launcher.R.string.drawer_shortcut_fallback_label)).toString()
                        val icon = try {
                            launcherApps?.getShortcutIconDrawable(sc, iconDpi)
                        } catch (_: Throwable) { null }
                        row(label, iconDrawable = icon) {
                            startAppShortcut(context, sc, anchor)
                        }
                    }
                    if (shortcuts.isNotEmpty()) divider()
                    row(context.getString(com.iappyx.launcher.R.string.cell_action_remove_from_home)) { onRemove() }
                    row(context.getString(com.iappyx.launcher.R.string.drawer_action_app_info)) { onOpenAppInfo(pkg) }
                    row(context.getString(com.iappyx.launcher.R.string.drawer_action_uninstall)) { onUninstall(pkg) }
                    // Only offer the icon-pack override when a pack is active —
                    // there's nothing to pick from otherwise.
                    if (com.iappyx.launcher.LauncherPrefs(context).iconPack.isNotBlank()) {
                        row(context.getString(com.iappyx.launcher.R.string.cell_action_change_icon)) { onChangeIcon(pkg) }
                    }
                    row(context.getString(com.iappyx.launcher.R.string.cell_action_rename)) { onRename(pkg) }
                    row(context.getString(com.iappyx.launcher.R.string.cell_action_customize_home)) { onCustomizeHome() }
                } else {
                    row(context.getString(com.iappyx.launcher.R.string.cell_action_remove_from_home)) { onRemove() }
                    row(context.getString(com.iappyx.launcher.R.string.cell_action_customize_home)) { onCustomizeHome() }
                }
            }
            CellType.GENERATED_WIDGET -> {
                row(context.getString(com.iappyx.launcher.R.string.cell_action_edit_widget)) { onEditGenerated() }
                row(context.getString(com.iappyx.launcher.R.string.cell_action_customize_home)) { onCustomizeHome() }
                row(context.getString(com.iappyx.launcher.R.string.cell_action_remove_from_home)) { onRemove() }
            }
            CellType.FOLDER,
            CellType.STOCK_WIDGET,
            CellType.APP_DRAWER -> {
                row(context.getString(com.iappyx.launcher.R.string.cell_action_customize_home)) { onCustomizeHome() }
                row(context.getString(com.iappyx.launcher.R.string.cell_action_remove_from_home)) { onRemove() }
            }
        }

        // Pre-measure the container so we can decide whether the popup fits
        // below the anchor or has to flip above. showAsDropDown() on its own
        // truncates rather than flipping when the row sits low on the screen,
        // which is exactly what users see on the bottom-most home cells.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            (240 * density).toInt(), View.MeasureSpec.EXACTLY,
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(widthSpec, heightSpec)
        val popupHeight = container.measuredHeight
        val popupWidth = container.measuredWidth

        val anchorLoc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val display = anchor.resources.displayMetrics
        val screenH = display.heightPixels
        val screenW = display.widthPixels
        val margin = (12 * density).toInt()
        val spaceBelow = screenH - (anchorLoc[1] + anchor.height) - margin
        val spaceAbove = anchorLoc[1] - margin
        val showAbove = popupHeight > spaceBelow && spaceAbove > spaceBelow

        // Horizontal: prefer left-aligned with the anchor, but clamp to the
        // screen so the popup doesn't run off either edge.
        val rawX = anchorLoc[0]
        val x = rawX.coerceIn(margin, (screenW - popupWidth - margin).coerceAtLeast(margin))
        val y = if (showAbove) {
            (anchorLoc[1] - popupHeight + (anchor.height * 0.2f).toInt()).coerceAtLeast(margin)
        } else {
            anchorLoc[1] + anchor.height - (anchor.height * 0.2f).toInt()
        }

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        popup.themeContent()
        // Pop-in animation: scale + fade, pivot at whichever edge is closest
        // to the anchor so the popup feels like it grows out of the cell.
        container.alpha = 0f
        container.scaleX = 0.88f
        container.scaleY = 0.88f
        container.pivotX = popupWidth / 2f
        container.pivotY = if (showAbove) popupHeight.toFloat() else 0f
        container.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
        return popup
    }

    /** Static + dynamic app shortcuts for [packageName]. Empty when our
     *  launcher isn't the active home (LauncherApps refuses), API < 25, or
     *  the target app declares no shortcuts. */
    private fun fetchAppShortcuts(context: Context, packageName: String): List<ShortcutInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        return try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? LauncherApps ?: return emptyList()
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            val list = launcherApps.getShortcuts(query, Process.myUserHandle())
                ?: return emptyList()
            list.sortedBy { it.rank }.take(5)
        } catch (_: Throwable) { emptyList() }
    }

    private fun startAppShortcut(context: Context, shortcut: ShortcutInfo, anchor: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as? LauncherApps ?: return
            val rect = Rect()
            anchor.getGlobalVisibleRect(rect)
            launcherApps.startShortcut(shortcut, rect, null)
        } catch (_: Throwable) {
            Toast.makeText(context, com.iappyx.launcher.R.string.cell_shortcut_launch_failed_toast, Toast.LENGTH_SHORT).show()
        }
    }
}
