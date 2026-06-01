/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.iappyx.launcher.R
import com.iappyx.launcher.theme.ThemeFonts
import com.iappyx.launcher.theme.ThemeOverrides

/**
 * Thin accessor over the launcher's semantic colour tokens defined in
 * `res/values/colors.xml` (and its night-mode counterpart, when added).
 *
 * Centralizes the white-on-dark alpha ladder + accent shades so screens
 * can write `Palette.separator(ctx)` instead of
 * `Color.parseColor("#22FFFFFF")` — this is what makes a future light-
 * theme override possible without touching every callsite.
 *
 * On Android 12+ `values-v31/colors.xml` may map [accent] to
 * `system_accent1_200` for Material You. The fixed-brand-accent decision
 * means we don't sample from the wallpaper here; theme switching ships
 * with Plan 3b/3c.
 */
object Palette {
    // ── Surfaces ───────────────────────────────────────────────────────
    fun bgHome(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.bg_home)
    fun bgCell(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.bg_cell)
    fun surfaceHigh(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.surface_high)

    // ── Text ───────────────────────────────────────────────────────────
    fun textPrimary(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.text_primary)
    fun textSecondary(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.text_secondary)
    fun textDisabled(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.text_disabled)

    // ── Accent ─────────────────────────────────────────────────────────
    /** Accent. Defaults to the fixed brand cyan, but when the user has set a
     *  custom accent in the widget theme editor (`--iappyx-primary` override)
     *  the whole launcher follows it. The chip/light shades are derived from
     *  the same base so they stay in harmony. Reads at call time (view-bind),
     *  so transient surfaces (drawer, search, command bar, settings) pick up a
     *  changed accent next time they open; the persistent home updates on the
     *  next launcher start. */
    fun accent(ctx: Context): Int =
        overrideAccent(ctx) ?: ContextCompat.getColor(ctx, R.color.accent)
    fun accentLight(ctx: Context): Int =
        overrideAccent(ctx)?.let { blendToWhite(it, 0.5f) }
            ?: ContextCompat.getColor(ctx, R.color.accent_light)
    fun accentChipBg(ctx: Context): Int =
        overrideAccent(ctx)?.let { withAlpha(it, 0x1F) }
            ?: ContextCompat.getColor(ctx, R.color.accent_chip_bg)
    fun accentChipBgStrong(ctx: Context): Int =
        overrideAccent(ctx)?.let { withAlpha(it, 0x33) }
            ?: ContextCompat.getColor(ctx, R.color.accent_chip_bg_strong)
    fun accentChipStroke(ctx: Context): Int =
        overrideAccent(ctx)?.let { withAlpha(it, 0x66) }
            ?: ContextCompat.getColor(ctx, R.color.accent_chip_stroke)

    /** The user's custom accent (`--iappyx-primary`) as an ARGB int, or null
     *  when unset / unparseable → callers fall back to the brand resource.
     *  Cached per [ThemeOverrides.generation] so per-draw callers (the home
     *  indicator / grid overlays read this every frame) don't re-parse JSON. */
    @Volatile private var cachedGen = -1
    @Volatile private var cachedAccent: Int? = null
    private fun overrideAccent(ctx: Context): Int? {
        val g = ThemeOverrides.generation
        if (g != cachedGen) {
            cachedGen = g
            cachedAccent = ThemeOverrides.get(ctx)["--iappyx-primary"]?.let {
                try { Color.parseColor(it) } catch (_: Throwable) { null }
            }
        }
        return cachedAccent
    }

    /** The accent at a custom alpha (0x00–0xFF), override-aware. Use for the
     *  translucent accent fills/strokes that were previously hardcoded as
     *  `#AA4FC3F7`. */
    fun accentAlpha(ctx: Context, alpha: Int): Int = withAlpha(accent(ctx), alpha)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun blendToWhite(c: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt(),
    )

    /** Apply the user's theme to a native view tree: (1) recolor views whose
     *  accent came from the static `@color/accent` resource to the custom
     *  accent, and (2) set the theme font on every text view. Gated by a
     *  per-tree signature tag so it only does work when the theme (or the tree)
     *  actually changed — safe + cheap to call on every resume. Installed
     *  app-wide by [com.iappyx.launcher.IappyxApp]. Programmatically-created
     *  text that's added after this runs (e.g. recycled list rows) should also
     *  set its own typeface via [themeTypeface] — see IconCell. */
    fun applyThemeToTree(root: View?) {
        root ?: return
        // This runs on EVERY activity resume (IappyxApp). The view ops are
        // safe, but never let a stray throw take down an unrelated screen.
        try {
            val ctx = root.context
            val brand = ContextCompat.getColor(ctx, R.color.accent)
            val accent = accent(ctx)
            val fontKey = ThemeOverrides.get(ctx)["--iappyx-font"]
            val sig = "$accent|${fontKey ?: ""}"
            val oldSig = root.getTag(R.id.theme_sig_tag) as? String
            if (oldSig == sig) return
            root.setTag(R.id.theme_sig_tag, sig)
            // Apply font when one is set now, OR to RESET to default if one was
            // previously applied (old signature carried a font key).
            val oldHadFont = oldSig != null && oldSig.substringAfter('|', "").isNotEmpty()
            val doFont = fontKey != null || oldHadFont
            val doAccent = accent != brand
            if (!doAccent && !doFont) return
            walk(root, brand, accent, doAccent, doFont, fontKey?.let { typefaceFor(ctx, it) })
        } catch (_: Throwable) { /* theming must never crash a resume */ }
    }

    /** Typeface for the user's theme font, override-aware. Null = system
     *  default (also for "System", which has no distinct Android face). Use as
     *  `Typeface.create(themeTypeface(ctx) ?: DEFAULT, style)`. Cached per
     *  [ThemeOverrides.generation] — called per-frame (WormIndicator) and
     *  per-bind (IconCell/FolderCell), so it must not re-parse prefs each call. */
    @Volatile private var fontGen = -1
    @Volatile private var cachedFont: Typeface? = null
    fun themeTypeface(ctx: Context): Typeface? {
        val g = ThemeOverrides.generation
        if (g != fontGen) {
            fontGen = g
            cachedFont = ThemeOverrides.get(ctx)["--iappyx-font"]?.let { typefaceFor(ctx, it) }
        }
        return cachedFont
    }

    private fun typefaceFor(ctx: Context, stack: String): Typeface? {
        // Bundled OFL fonts (assets) + downloaded catalog fonts (filesDir).
        ThemeFonts.resolveTypeface(ctx, stack)?.let { return it }
        val s = stack.lowercase()
        return when {
            s.contains("condensed") -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            s.contains("monospace") || s.contains("ui-monospace") || s.contains("roboto mono") -> Typeface.MONOSPACE
            s.contains("georgia") || s.contains("serif") -> Typeface.SERIF
            else -> null // System → system default
        }
    }

    private fun walk(v: View, brand: Int, accent: Int, doAccent: Boolean, doFont: Boolean, tf: Typeface?) {
        if (doAccent) {
            when (v) {
                is Switch -> {
                    if (v.thumbTintList?.defaultColor == brand) v.thumbTintList = ColorStateList.valueOf(accent)
                    if (v.trackTintList?.defaultColor == brand) v.trackTintList = ColorStateList.valueOf(accent)
                    if (v.currentTextColor == brand) v.setTextColor(accent)
                }
                is ImageView -> if (v.imageTintList?.defaultColor == brand) v.imageTintList = ColorStateList.valueOf(accent)
                is TextView -> if (v.currentTextColor == brand) v.setTextColor(accent)
            }
            if (v.backgroundTintList?.defaultColor == brand) v.backgroundTintList = ColorStateList.valueOf(accent)
            recolorBrandFill(v.background, brand, accent)
        }
        if (doFont && v is TextView) {
            val style = v.typeface?.style ?: Typeface.NORMAL
            v.setTypeface(Typeface.create(tf ?: Typeface.DEFAULT, style))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), brand, accent, doAccent, doFont, tf)
    }

    /** Recolor a shape-drawable "pill"/chip fill whose RGB matches the brand
     *  accent — at ANY alpha — to the custom accent, preserving the original
     *  alpha (so translucent tonal pills stay translucent). Catches static XML
     *  chips (e.g. edit_bar_chip_secondary's #1F4FC3F7) and programmatic ones
     *  alike. Mutates first so we don't recolor the shared constant state. */
    private fun recolorBrandFill(bg: android.graphics.drawable.Drawable?, brand: Int, accent: Int) {
        val gd = bg as? android.graphics.drawable.GradientDrawable ?: return
        val c = gd.color?.defaultColor ?: return
        if ((c and 0x00FFFFFF) != (brand and 0x00FFFFFF)) return
        val alpha = c and 0xFF000000.toInt()
        val want = (accent and 0x00FFFFFF) or alpha
        if (c == want) return
        gd.mutate()
        gd.setColor(want)
    }

    /** Theme a dialog's content (font + accent + pill fills). An AlertDialog
     *  lives in its OWN window, not the activity decor the app-wide walk
     *  traverses — call this on show. See AlertDialog.Builder.showThemed(). */
    fun applyThemeToDialog(dialog: android.app.Dialog?) {
        applyThemeToTree(dialog?.window?.decorView)
    }

    // ── Separators / strokes ───────────────────────────────────────────
    /** Default separator/stroke alpha — 13% white. */
    fun separator(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.separator)
    /** Subtler separator — 12% white. */
    fun separatorSubtle(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.separator_subtle)
    /** Stronger separator — 20% white. */
    fun separatorStrong(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.separator_strong)

    // ── Overlays ───────────────────────────────────────────────────────
    fun overlayWhiteMedium(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.overlay_white_medium)
    fun overlayWhiteStrong(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.overlay_white_strong)
    /** Scrim behind modal sheets / overlays. */
    fun scrimStrong(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.scrim_strong)

    // ── Status ─────────────────────────────────────────────────────────
    fun danger(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.danger)
    fun dangerStrong(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.danger_strong)
    fun dangerLink(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.danger_link)
}
