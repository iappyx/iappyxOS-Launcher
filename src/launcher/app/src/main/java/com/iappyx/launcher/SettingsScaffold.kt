/*
 * MIT License - Copyright (c) 2026 iappyx
 * Plan A Phase 5 — one-call helper to attach the shared settings toolbar
 * to any settings activity. Standardises back arrow + title position
 * across every detail screen so opening a row feels like the row
 * "expanded into" the next screen.
 */
package com.iappyx.launcher

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

object SettingsScaffold {

    /** Wire up the shared toolbar. Call from onCreate AFTER
     *  setContentView. Layout must include
     *  `<include layout="@layout/settings_toolbar"/>` at the top of
     *  the root view (R.id.settings_toolbar). Back arrow finishes the
     *  activity — Android's standard up-navigation contract. */
    fun attach(activity: AppCompatActivity, title: CharSequence, showBack: Boolean = true) {
        val toolbar = activity.findViewById<Toolbar>(R.id.settings_toolbar) ?: return
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(showBack)
        activity.supportActionBar?.title = title
        if (showBack) {
            toolbar.setNavigationOnClickListener { activity.finish() }
        } else {
            toolbar.navigationIcon = null  // top-level Settings has no parent
        }
    }
}
