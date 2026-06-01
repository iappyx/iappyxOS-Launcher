/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.app.Activity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Auto-dismiss a [BottomSheetDialog] when the host activity is destroyed.
 * Without this, rotating the device or having the activity killed by the
 * system while a sheet is open triggers Android's `WindowLeaked` warning
 * because the dialog's window is still attached when the activity tears
 * down. The dialog itself isn't explicitly destroyed in those paths
 * because each sheet's lifecycle is tied to the user's tap-outside gesture
 * rather than the activity.
 *
 * Idempotent: removes its own observer when the dialog dismisses normally
 * (so it doesn't double-dismiss on rotation if the user closed it earlier).
 */
fun BottomSheetDialog.dismissOnActivityDestroy(activity: Activity) {
    val owner = activity as? LifecycleOwner ?: return
    // No-op if the activity is already destroyed — `addObserver` on a
    // destroyed lifecycle no longer fires events, so we'd never tear down.
    if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
        try { dismiss() } catch (_: Throwable) {}
        return
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(o: LifecycleOwner) {
            try { if (isShowing) dismiss() } catch (_: Throwable) {}
            o.lifecycle.removeObserver(this)
        }
    }
    owner.lifecycle.addObserver(observer)
    setOnDismissListener {
        // Drop the observer when the dialog is dismissed by tap-outside /
        // tap-row so it isn't kept around for the rest of the activity's
        // life (and so onDestroy doesn't try to dismiss an already-gone
        // dialog).
        owner.lifecycle.removeObserver(observer)
    }
}

/**
 * Open the sheet at full content height — skipping the half-height "peek"
 * state Material defaults to. The peek state is especially bad in landscape
 * (50% of a short screen barely shows the title) and is rarely the desired
 * UX for picker-style sheets where the user is choosing one of N rows.
 *
 * Apply by calling at the very end of `show()` — must be after the dialog's
 * window has been created so we can reach the inner sheet view's behavior.
 */
fun BottomSheetDialog.expandFully() {
    setOnShowListener { dlg ->
        val sheet = (dlg as BottomSheetDialog).findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet,
        ) ?: return@setOnShowListener
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // Some Material themes apply a max-width / side-rail on tablets in
        // landscape; force the sheet to full screen width so picker rows
        // don't read as a thin column.
        sheet.layoutParams = sheet.layoutParams.apply {
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        }
    }
}
