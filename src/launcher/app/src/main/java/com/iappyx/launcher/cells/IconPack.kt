/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser

/**
 * Loads third-party icon packs (the de-facto Nova / ADW / Apex format) and maps
 * installed apps to their themed drawables.
 *
 * A pack is an ordinary APK that advertises itself with one of the well-known
 * theme intents and ships an `assets/appfilter.xml` mapping launch components to
 * drawable resource names:
 *
 * ```xml
 * <item component="ComponentInfo{pkg/activity}" drawable="resource_name"/>
 * ```
 *
 * Phase 1 supports the explicit component → drawable substitution. The
 * `<iconback>` / `<iconmask>` / `<iconupon>` / `<scale>` directives are parsed
 * and retained for the Phase 2 "mask unthemed apps" treatment but not yet
 * applied.
 *
 * The active pack is held as a process-wide cached singleton ([active]); call
 * [setActive] when the user changes the pack (it also flushes [IconMask]'s
 * bitmap cache).
 */
object IconPack {

    /** Theme intent actions various launcher ecosystems register under. Any
     *  app declaring an activity/filter for one of these is an icon pack. */
    private val THEME_ACTIONS = listOf(
        "com.novalauncher.THEME",
        "org.adw.launcher.THEMES",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "com.gau.go.launcherex.theme",
        "app.lawnchair.icons.THEMED_ICON",
        "com.anddoes.launcher.THEME",
        "ch.deletescape.lawnchair.ICONPACK",
    )

    /** A discovered, installable icon pack — used to populate the picker. */
    data class PackInfo(val packageName: String, val label: String, val icon: Drawable?)

    /** A fully-parsed pack ready to serve themed icons. */
    private class Loaded(
        val packageName: String,
        val res: Resources,
        /** Normalised `"pkg/activity"` → drawable resource name. */
        val byComponent: Map<String, String>,
        /** `pkg` → drawable resource name (first themed activity per app). */
        val byPackage: Map<String, String>,
        // Phase-2 directives — parsed now, applied later.
        val iconBacks: List<String>,
        val iconMask: String?,
        val iconUpon: String?,
        val scale: Float,
    )

    @Volatile private var active: Loaded? = null
    @Volatile private var activePkg: String? = null

    /** Enumerate installed icon packs. Safe to call on a background thread. */
    fun discoverPacks(context: Context): List<PackInfo> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, PackInfo>()
        for (action in THEME_ACTIONS) {
            val intent = Intent(action)
            val matches = try {
                pm.queryIntentActivities(intent, 0)
            } catch (_: Throwable) {
                emptyList()
            }
            for (ri in matches) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (found.containsKey(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
                val icon = try { ri.loadIcon(pm) } catch (_: Throwable) { null }
                found[pkg] = PackInfo(pkg, label, icon)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Switch the active pack (empty / blank = none). Loads + parses on the
     *  calling thread, so prefer a background thread for first load. Always
     *  flushes the [IconMask] bitmap cache so icons re-render. */
    fun setActive(context: Context, packageName: String?) {
        val pkg = packageName?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            active = null
            activePkg = null
            IconMask.clearCache()
            return
        }
        if (pkg == activePkg && active != null) return
        active = try { parse(context, pkg) } catch (_: Throwable) { null }
        activePkg = if (active != null) pkg else null
        IconMask.clearCache()
    }

    /** Ensure [packageName] is the loaded pack (lazy load if a previous process
     *  set the pref but this process hasn't parsed it yet). */
    private fun ensureLoaded(context: Context, packageName: String) {
        if (packageName == activePkg && active != null) return
        synchronized(this) {
            if (packageName == activePkg && active != null) return
            active = try { parse(context, packageName) } catch (_: Throwable) { null }
            activePkg = if (active != null) packageName else null
        }
    }

    /** True when a pack is currently selected (regardless of whether [pkg] is
     *  themed by it). Used to gate filter-override + mask behaviour. */
    fun isActive(context: Context, packPkg: String?): Boolean {
        val p = packPkg?.takeIf { it.isNotBlank() } ?: return false
        ensureLoaded(context, p)
        return active != null
    }

    /**
     * Themed drawable for [appPackage] from the active pack, or null when the
     * app isn't themed (or no pack is active). Resolves the app's launch
     * component to match the component-keyed appfilter, then falls back to a
     * package-level match.
     */
    fun iconFor(context: Context, packPkg: String?, appPackage: String): Drawable? {
        val p = packPkg?.takeIf { it.isNotBlank() } ?: return null
        ensureLoaded(context, p)
        val loaded = active ?: return null
        // Manual per-app override wins over the appfilter auto-match.
        val override = com.iappyx.launcher.LauncherPrefs(context).iconOverrides[appPackage]
        if (override != null) {
            drawableNamed(loaded, override)?.let { return it }
        }
        val pm = context.packageManager
        val component = launchComponent(pm, appPackage)
        val name = (component?.let { loaded.byComponent[normalize(it)] })
            ?: loaded.byPackage[appPackage]
            ?: return null
        return drawableNamed(loaded, name)
    }

    /** True when the active pack ships iconback/iconmask/iconupon directives —
     *  i.e. there's a treatment to apply to unthemed apps. */
    fun hasMaskTreatment(): Boolean {
        val l = active ?: return false
        return l.iconBacks.isNotEmpty() || l.iconMask != null || l.iconUpon != null
    }

    /**
     * Compose the pack's iconback / iconmask / iconupon over an unthemed app's
     * own [base] icon (the canonical CM/Trebuchet algorithm): shrink the base by
     * the pack's `scale`, cut it with the mask (DST_OUT), drop the back behind
     * (DST_OVER), then lay the "upon" overlay on top. Returns null if the pack
     * has no treatment to apply.
     */
    fun maskTreatment(context: Context, packPkg: String, appPackage: String, base: Drawable, sizePx: Int): Bitmap? {
        ensureLoaded(context, packPkg)
        val loaded = active ?: return null
        if (loaded.iconBacks.isEmpty() && loaded.iconMask == null && loaded.iconUpon == null) {
            return null
        }
        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Scaled base icon, centred.
        val s = loaded.scale.coerceIn(0.1f, 1.5f)
        val inset = (sizePx * (1f - s) / 2f).toInt()
        base.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        base.draw(canvas)

        // Mask: opaque mask pixels carve away the icon (DST_OUT).
        loaded.iconMask?.let { name ->
            drawableNamed(loaded, name)?.let { m ->
                val mb = toBitmap(m, sizePx)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                }
                canvas.drawBitmap(mb, 0f, 0f, p)
                mb.recycle()
            }
        }

        // Back: drawn behind everything already composited (DST_OVER). Hashed
        // pick when the pack ships several so the grid varies pleasantly.
        if (loaded.iconBacks.isNotEmpty()) {
            val backName = loaded.iconBacks[Math.floorMod(appPackage.hashCode(), loaded.iconBacks.size)]
            drawableNamed(loaded, backName)?.let { b ->
                val bb = toBitmap(b, sizePx)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
                }
                canvas.drawBitmap(bb, 0f, 0f, p)
                bb.recycle()
            }
        }

        // Upon: foreground overlay (gloss/sticker) on top (SRC_OVER).
        loaded.iconUpon?.let { name ->
            drawableNamed(loaded, name)?.let { u ->
                val ub = toBitmap(u, sizePx)
                canvas.drawBitmap(ub, 0f, 0f, null)
                ub.recycle()
            }
        }
        return result
    }

    /** All drawable resource names the pack lists for its picker, deduped and
     *  sorted. Used by the manual per-app icon chooser. */
    fun allDrawables(context: Context, packPkg: String): List<String> {
        ensureLoaded(context, packPkg)
        val loaded = active ?: return emptyList()
        return loaded.byComponent.values.toSortedSet().toList()
    }

    /** Load a pack drawable by resource name (for the manual picker preview). */
    fun drawableByName(context: Context, packPkg: String, name: String): Drawable? {
        ensureLoaded(context, packPkg)
        val loaded = active ?: return null
        return drawableNamed(loaded, name)
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        // Always render into a FRESH bitmap. Do NOT use createScaledBitmap on a
        // BitmapDrawable's bitmap: when the source is already size×size it
        // returns the SAME instance, and the callers here recycle() the result
        // — which would corrupt the pack drawable's shared cached bitmap and
        // crash the next render with "Cannot draw a recycled Bitmap".
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val saved = d.copyBounds()
        d.setBounds(0, 0, size, size)
        d.draw(c)
        d.bounds = saved
        return b
    }

    private fun launchComponent(pm: PackageManager, appPackage: String): ComponentName? =
        try { pm.getLaunchIntentForPackage(appPackage)?.component } catch (_: Throwable) { null }

    private fun normalize(c: ComponentName): String = "${c.packageName}/${c.className}"

    private fun drawableNamed(loaded: Loaded, name: String): Drawable? {
        return try {
            val id = loaded.res.getIdentifier(name, "drawable", loaded.packageName)
            if (id == 0) null else ResourcesCompat.getDrawable(loaded.res, id, null)
        } catch (_: Throwable) {
            null
        }
    }

    /** Parse a pack's appfilter into the lookup maps + Phase-2 directives. */
    private fun parse(context: Context, packPkg: String): Loaded? {
        val pm = context.packageManager
        val res = pm.getResourcesForApplication(packPkg)
        val parser = openAppfilter(res, packPkg) ?: return null

        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        val iconBacks = ArrayList<String>()
        var iconMask: String? = null
        var iconUpon: String? = null
        var scale = 1f

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val comp = parser.getAttributeValue(null, "component")
                            val drawable = parser.getAttributeValue(null, "drawable")
                            if (comp != null && drawable != null) {
                                val norm = parseComponent(comp)
                                if (norm != null) {
                                    byComponent[norm] = drawable
                                    val appPkg = norm.substringBefore('/')
                                    byPackage.putIfAbsent(appPkg, drawable)
                                }
                            }
                        }
                        "iconback" -> {
                            // Packs may list one or several (img1, img2, …);
                            // pick by hash later. Collect all img* attributes.
                            for (i in 0 until parser.attributeCount) {
                                parser.getAttributeValue(i)?.let { iconBacks.add(it) }
                            }
                        }
                        "iconmask" -> {
                            iconMask = parser.getAttributeValue(0)
                        }
                        "iconupon" -> {
                            iconUpon = parser.getAttributeValue(0)
                        }
                        "scale" -> {
                            parser.getAttributeValue(null, "factor")?.toFloatOrNull()
                                ?.let { scale = it }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // Tolerate malformed packs — keep whatever parsed so far.
        }

        if (byComponent.isEmpty() && byPackage.isEmpty()) return null
        return Loaded(packPkg, res, byComponent, byPackage, iconBacks, iconMask, iconUpon, scale)
    }

    /** Open the pack's appfilter as a pull-parser. Tries `assets/appfilter.xml`
     *  (the common case), then a compiled `res/xml/appfilter` resource. */
    private fun openAppfilter(res: Resources, packPkg: String): XmlPullParser? {
        try {
            val stream = res.assets.open("appfilter.xml")
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")
            return parser
        } catch (_: Throwable) { /* fall through to res/xml */ }
        return try {
            val id = res.getIdentifier("appfilter", "xml", packPkg)
            if (id == 0) null else res.getXml(id)
        } catch (_: Throwable) {
            null
        }
    }

    /** Convert an appfilter `component` attribute to `"pkg/activity"`.
     *  Formats seen in the wild:
     *   - `ComponentInfo{pkg/activity}`
     *   - `pkg/activity`
     *  Returns null for non-component entries (e.g. `:DEFAULT`). */
    private fun parseComponent(raw: String): String? {
        val inner = if (raw.startsWith("ComponentInfo{") && raw.endsWith("}")) {
            raw.substring("ComponentInfo{".length, raw.length - 1)
        } else {
            raw
        }
        if (!inner.contains('/')) return null
        return inner
    }
}
