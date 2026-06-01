/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Theme the contents of AlertDialogs. A dialog is a separate window, so the
 * app-wide tree walk installed by IappyxApp never reaches it — these helpers
 * apply the theme (font + accent + pill fills) to the dialog's own decor when
 * it's shown. Call site change is just `.show()` -> `.showThemed()`.
 *
 * Provided for both the platform (android.app) and AppCompat AlertDialog
 * builders since the codebase uses both.
 */
package com.iappyx.launcher.widget

/** Create + show a platform AlertDialog with its contents themed. */
fun android.app.AlertDialog.Builder.showThemed(): android.app.AlertDialog {
    val d = create()
    d.setOnShowListener { Palette.applyThemeToDialog(d) }
    d.show()
    return d
}

/** Create + show an AppCompat AlertDialog with its contents themed. */
fun androidx.appcompat.app.AlertDialog.Builder.showThemed(): androidx.appcompat.app.AlertDialog {
    val d = create()
    d.setOnShowListener { Palette.applyThemeToDialog(d) }
    d.show()
    return d
}

/** Apply the theme (font + accent + pill fills) to a bottom sheet's content.
 *  Call AFTER `show()` — the sheet's window/decor exists by then. Bottom
 *  sheets are separate windows the app-wide activity walk can't reach. Safe to
 *  call even if the sheet sets its own OnShowListener (this doesn't use one). */
fun com.google.android.material.bottomsheet.BottomSheetDialog.themeContent() {
    window?.decorView?.let { d -> d.post { Palette.applyThemeToDialog(this) } }
}

/** Apply the theme to a PopupWindow's content (long-press / context menus).
 *  Call after the content view is set (before or after show). */
fun android.widget.PopupWindow.themeContent() {
    contentView?.let { c -> c.post { Palette.applyThemeToTree(c) } }
}
