/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.palette.graphics.Palette

/**
 * Pre-Android-12 fallback for wallpaper-derived accent. On API 31+ Material
 * You already maps our `bg_home`/`accent` resources to system_* colours; this
 * helper only runs on older devices.
 *
 * Uses `androidx.palette` to extract a vibrant or dominant color from the
 * current wallpaper. Cached in-memory; call [refresh] on wallpaper change.
 */
object WallpaperAccent {

    @Volatile private var cached: Int? = null

    /** Returns a wallpaper-derived accent color, or null if unavailable
     *  (wallpaper not readable, permission missing, or running on API 31+ where
     *  Material You handles it). */
    fun get(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= 31) return null // Material You handles it
        cached?.let { return it }
        val wm = WallpaperManager.getInstance(context)
        val drawable = try {
            // peekDrawable avoids keeping a strong ref if not available.
            wm.peekDrawable() ?: if (hasWallpaperReadPerm(context)) wm.drawable else null
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val sampled = downsample(bitmap, 160)
        val palette = try { Palette.from(sampled).generate() } catch (_: Exception) { null } ?: return null
        val chosen = palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: return null
        cached = chosen
        return chosen
    }

    fun refresh() { cached = null }

    private fun hasWallpaperReadPerm(context: Context): Boolean {
        // READ_EXTERNAL_STORAGE gates wm.drawable on older Androids; we don't
        // request it, so peekDrawable() is the only path we rely on.
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun downsample(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width; val h = src.height
        val longest = kotlin.math.max(w, h)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
