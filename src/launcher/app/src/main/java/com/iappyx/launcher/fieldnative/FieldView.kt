/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Native Field surface: a single bubble-pack "organism" of every app, sized by
 * relevance. Canvas-drawn, Choreographer-driven physics with a cool-down that
 * freezes once settled. Single-letter filter (set by drawing a letter or by the
 * AlphabetRail). Tap launches; long-press opens a context menu. Mirrors the web
 * Field's behaviour but uses the launcher's real Drawables + icon-pack pipeline.
 */
package com.iappyx.launcher.fieldnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.iappyx.launcher.LauncherPrefs
import com.iappyx.launcher.cells.IconFilterRegistry
import com.iappyx.launcher.cells.IconMask
import com.iappyx.launcher.widget.AppRegistry
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class FieldView(context: Context) : View(context) {

    interface Listener {
        fun onLaunch(pkg: String)
        fun onClose()
        fun onFilterChanged(letter: String)
        fun onContextRequested(pkg: String, label: String, sx: Int, sy: Int)
    }
    var listener: Listener? = null

    private inner class Bead(
        val pkg: String,
        var label: String,
        var tokens: List<String>,
        val mass: Float,
        val drawable: Drawable,
    ) {
        var x = 0f; var y = 0f; var r = 3f; var rt = 20f; var op = 0f; var opT = 1f
        var ax = 0f; var ay = 0f          // spread-fill anchor (gravity target)
        var ox = 0f; var oy = 0f
        var f = 1f
        var joinAt = 0f                   // seconds after open before this bead enters the sim
        var homeR = 0f                    // its on-screen home-icon radius (for the lift hold)
        var bmp: Bitmap? = null; var bmpReq = false
        var lbn = false
    }

    private val beads = ArrayList<Bead>()
    private val renderOrder = ArrayList<Bead>()   // by mass desc, for lazy icon render
    private var renderIdx = 0
    private var query = ""

    // bounds
    private var FX0 = 0f; private var FX1 = 0f; private var FY0 = 0f; private var FY1 = 0f
    private var FCX = 0f; private var FCY = 0f; private var FAREA = 1f; private var MAXR = 60f
    private var bmpSize = 120

    // sim cool-down
    private var simT0 = 0L; private var frozen = false; private var running = false

    // paints
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(70, 255, 255, 255) }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 255, 255, 255); textAlign = Paint.Align.CENTER }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconRect = Rect()
    private val density = context.resources.displayMetrics.density

    // orbs
    private var orb1: RadialGradient? = null; private var orb2: RadialGradient? = null; private var orb3: RadialGradient? = null

    // touch / stroke
    private val stroke = ArrayList<FloatArray>()
    private var drawing = false
    private var sx = 0f; private var sy = 0f; private var downT = 0L; private var moved = false
    private var contextShown = false
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Open morph: home page shows behind (translucent) while on-page icons lift
    // off and the dark backdrop fades in after HOLD.
    private val HOLD = 0.5f             // home icons sit (and lift) on the visible home page this long
    private val JOIN_DELAY = 0.62f      // off-page apps join just after the lift
    private val BG_FADE = 0.7           // dark backdrop fades in over this, starting after HOLD
    private var startRects: Map<String, FloatArray>? = null
    private var revealing = false       // true only during the open morph (hold + staged join)
    private var closing = false         // true during the reverse morph (icons fly back home)
    private var closeT0 = 0L
    private val CLOSE_DUR = 0.4
    private var dismissed = false       // close finished — never reveal again

    /** Reverse morph: on-page apps fly back to their home spots, others fade out,
     *  backdrop fades away, then the activity is dismissed. */
    fun startClose() {
        if (closing) return
        closing = true; closeT0 = System.nanoTime()
        frozen = false
        if (!running) { running = true; Choreographer.getInstance().postFrameCallback(frameCb) }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        rimPaint.strokeWidth = 1.4f * density
        lblPaint.textSize = 11f * density
        isFocusable = true
    }

    // ───── data ─────
    private fun nrm(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    private fun tokensOf(label: String): List<String> {
        val out = ArrayList<String>()
        val full = nrm(label); if (full.isNotEmpty()) out.add(full)
        for (w in label.lowercase().split(Regex("[^a-z0-9]+"))) { val n = nrm(w); if (n.isNotEmpty() && n !in out) out.add(n) }
        return out
    }

    private fun buildData() {
        beads.clear(); renderOrder.clear(); renderIdx = 0
        val prefs = LauncherPrefs(context)
        val apps = try { AppRegistry.apps(context) } catch (_: Throwable) { emptyList() }
        if (apps.isEmpty()) return
        val counts = try { prefs.launchCounts() } catch (_: Throwable) { emptyMap() }
        val recents = try { prefs.recentApps() } catch (_: Throwable) { emptyList() }
        val maxRecent = recents.size.coerceAtLeast(1)
        val tmp = ArrayList<Bead>()
        for (a in apps) {
            val freq = counts[a.packageName] ?: 0
            val fComp = ln(1.0 + freq).toFloat()
            val ri = recents.indexOf(a.packageName)
            val rComp = if (ri >= 0) 1f - ri.toFloat() / maxRecent else 0f
            val m = 0.18f + fComp * 0.9f + rComp * 1.3f
            val lbl = prefs.appLabel(a.packageName, a.label).toString()
            tmp.add(Bead(a.packageName, lbl, tokensOf(lbl), m, a.icon))
        }
        val mMax = (tmp.maxOfOrNull { it.mass } ?: 1f).coerceAtLeast(0.001f)
        for (b in tmp) {
            val bb = Bead(b.pkg, b.label, b.tokens, b.mass / mMax, b.drawable)
            beads.add(bb)
        }
        renderOrder.addAll(beads.sortedByDescending { it.mass })
        // Morph handoff: home-page icon positions to fly from.
        startRects = FieldHandoff.consume()
        startRects?.let { sr ->
            val prefs = LauncherPrefs(context)
            val spec = try { IconFilterRegistry.resolve(context, prefs.iconFilter) } catch (_: Throwable) { IconFilterRegistry.noneSpec }
            for (b in beads) if (sr.containsKey(b.pkg) && !b.bmpReq) {   // render their icons NOW so they appear instantly
                b.bmpReq = true
                b.bmp = try { IconMask.render(b.pkg, b.drawable, bmpSize, spec) } catch (_: Throwable) { null }
            }
        }
    }

    // ───── layout / weighting ─────
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (dismissed || closing) return    // window tearing down — don't re-reveal the field
        val fw = w.toFloat(); val fh = h.toFloat()
        FX0 = fw * 0.04f; FX1 = fw * 0.92f; FY0 = fh * 0.045f; FY1 = fh * 0.96f
        FCX = (FX0 + FX1) / 2f; FCY = (FY0 + FY1) / 2f; FAREA = (FX1 - FX0) * (FY1 - FY0)
        MAXR = fw * 0.12f
        bmpSize = ((2f * MAXR).toInt()).coerceIn(64, 144)
        bgPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, fh,
            intArrayOf(Color.parseColor("#1a1a24"), Color.parseColor("#0d0d13"), Color.parseColor("#050507")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        orb1 = RadialGradient(fw * 0.18f, fh * 0.14f, fh * 0.30f, Color.argb(70, 58, 47, 102), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        orb2 = RadialGradient(fw * 0.85f, fh * 0.42f, fh * 0.34f, Color.argb(70, 29, 74, 85), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        orb3 = RadialGradient(fw * 0.40f, fh * 0.92f, fh * 0.28f, Color.argb(70, 64, 42, 85), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        if (beads.isNotEmpty()) { setAnchors(); resetReveal(); recompute() }
    }

    /** Sunflower (phyllotaxis) anchors filling the field ellipse by relevance. */
    private fun setAnchors() {
        val n = beads.size; if (n == 0) return
        val rx = (FX1 - FX0) / 2f * 0.97f; val ry = (FY1 - FY0) / 2f * 0.97f
        for (i in 0 until n) {
            val b = beads[i]
            val rad = kotlin.math.sqrt((i + 0.5f) / n)
            val ang = i * 2.39996323f
            b.ax = FCX + kotlin.math.cos(ang) * rad * rx
            b.ay = FCY + kotlin.math.sin(ang) * rad * ry
        }
    }

    private fun resetReveal() {
        revealing = true            // this seeding is an OPEN — run the lift/join morph
        val sr = startRects
        val hasHome = !sr.isNullOrEmpty()
        for (b in beads) {
            val rect = sr?.get(b.pkg)
            if (rect != null) {                    // on-page icon: lift off from its real home spot + size, immediately
                b.x = rect[0]; b.y = rect[1]; b.r = (rect[2] / 2f).coerceIn(8f, MAXR); b.op = 1f; b.joinAt = 0f; b.homeR = b.r
            } else {                               // off-page app: JOIN after the home icons have lifted
                b.x = FCX + (Math.random().toFloat() - 0.5f) * 60f
                b.y = FCY + (Math.random().toFloat() - 0.5f) * 60f
                b.r = 3f; b.op = 0f; b.joinAt = if (hasHome) JOIN_DELAY else 0f
            }
        }
    }

    private fun isMatch(b: Bead): Boolean {
        if (query.isEmpty()) return true
        if (query == "#") { for (t in b.tokens) if (t.isNotEmpty() && t[0] in '0'..'9') return true; return false }
        for (t in b.tokens) if (t.startsWith(query)) return true
        return false
    }

    private fun recompute() {
        if (beads.isEmpty() || FAREA <= 1f) return
        wake()
        var sumf2 = 0f
        for (b in beads) {
            val m = isMatch(b)
            b.f = if (query.isNotEmpty()) { if (m) 1.25f + 0.85f * b.mass else 0.40f } else 0.52f + 0.95f * b.mass
            sumf2 += b.f * b.f
        }
        val cov = if (query.isNotEmpty()) 0.74f else 0.82f
        val s = sqrt(cov * FAREA / (Math.PI.toFloat() * sumf2.coerceAtLeast(0.001f)))
        for (b in beads) {
            val m = isMatch(b)
            b.rt = (s * b.f).coerceIn(7f, MAXR)
            b.opT = if (query.isNotEmpty()) (if (m) 1f else 0.34f) else 0.5f + 0.5f * b.mass
            b.lbn = query.isNotEmpty() && m && b.rt > 30f
        }
    }

    fun setFilter(letter: Char) { revealing = false; query = letter.lowercaseChar().toString(); recompute(); listener?.onFilterChanged(query) }
    fun clearFilter() { if (query.isEmpty()) return; revealing = false; query = ""; recompute(); listener?.onFilterChanged("") }

    /** First letters present across all apps, uppercased; digits map to '#'.
     *  Feeds AlphabetRail.activeLetters so dead letters dim like the std drawer. */
    fun availableFirstLetters(): Set<Char> {
        val s = HashSet<Char>()
        for (b in beads) for (t in b.tokens) if (t.isNotEmpty()) {
            val c = t[0]; s.add(if (c in '0'..'9') '#' else c.uppercaseChar())
        }
        return s
    }

    // ───── icon bitmaps (lazy, a few per frame) ─────
    private fun renderSome(n: Int) {
        val prefs = LauncherPrefs(context)
        val spec = try { IconFilterRegistry.resolve(context, prefs.iconFilter) } catch (_: Throwable) { IconFilterRegistry.noneSpec }
        var done = 0
        while (renderIdx < renderOrder.size && done < n) {
            val b = renderOrder[renderIdx++]
            if (!b.bmpReq) {
                b.bmpReq = true
                b.bmp = try { IconMask.render(b.pkg, b.drawable, bmpSize, spec) } catch (_: Throwable) { null }
            }
            done++
        }
    }

    // ───── physics loop ─────
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) { step(); if (running) Choreographer.getInstance().postFrameCallback(this) }
    }
    private fun wake() { if (dismissed) return; simT0 = System.nanoTime(); frozen = false; if (!running) { running = true; Choreographer.getInstance().postFrameCallback(frameCb) } }

    private fun step() {
        if (dismissed && !closing) { running = false; return }   // closed — never re-inflate the field
        if (closing) {
            val ce = (System.nanoTime() - closeT0) / 1e9
            val sr = startRects
            for (b in beads) {
                val rect = sr?.get(b.pkg)
                if (rect != null) {                       // fly back to its home spot + size
                    b.x += (rect[0] - b.x) * 0.22f; b.y += (rect[1] - b.y) * 0.22f
                    b.r += ((rect[2] / 2f).coerceIn(8f, MAXR) - b.r) * 0.22f
                    b.op += (1f - b.op) * 0.20f
                } else {                                  // off-page apps fade out
                    b.op += (0f - b.op) * 0.22f
                }
            }
            invalidate()
            if (ce > CLOSE_DUR) { closing = false; running = false; dismissed = true; listener?.onClose() }
            return
        }
        if (renderIdx < renderOrder.size) renderSome(5)
        if (frozen) { running = false; return }
        val n = beads.size
        if (n == 0 || FAREA <= 1f) { invalidate(); return }
        val elapsed = (System.nanoTime() - simT0) / 1e9
        // Open: gentle fading gravity. Filtering: ease the re-pack in over the first
        // ~150ms (no jarring burst) with brisk gravity so it settles quickly.
        val grav: Float; val rK: Float
        if (revealing) {
            grav = 0.02f * maxOf(0.10f, (1f - (elapsed / 1.6)).toFloat()); rK = 0.16f
        } else {
            // ease in over ~150ms, then FADE gravity out by ~0.7s so it calms and
            // settles instead of oscillating against collision forever.
            val ramp = (elapsed / 0.15).coerceIn(0.0, 1.0).toFloat()
            val fade = (1f - (elapsed / 0.7).toFloat()).coerceAtLeast(0f)   // fade fully to 0 → core stops
            grav = 0.02f * ramp * fade; rK = 0.05f + 0.20f * ramp
        }
        for (b in beads) {
            if (revealing && elapsed < b.joinAt) continue   // not joined yet — stays hidden at its seed
            b.ox = b.x; b.oy = b.y
            b.op += (b.opT - b.op) * 0.14f
            if (revealing && b.joinAt == 0f && elapsed < HOLD) {
                // home icon lifting off in place: hold position, gently pop bigger
                b.r += (b.homeR * 1.12f - b.r) * 0.2f
            } else {
                b.r += (b.rt - b.r) * rK
                b.x += (b.ax - b.x) * grav; b.y += (b.ay - b.y) * grav   // pull to its spread-fill anchor
            }
        }
        // collision relaxation — 2 passes on open, 1 otherwise
        for (it in 0 until (if (revealing) 2 else 1)) {
            for (i in 0 until n) {
                val b = beads[i]
                if (revealing && (elapsed < b.joinAt || (b.joinAt == 0f && elapsed < HOLD))) continue
                for (j in i + 1 until n) {
                    val b2 = beads[j]
                    if (revealing && (elapsed < b2.joinAt || (b2.joinAt == 0f && elapsed < HOLD))) continue
                    val dx = b2.x - b.x; val dy = b2.y - b.y
                    val md = b.r + b2.r + 2f; val d2 = dx * dx + dy * dy
                    if (d2 < md * md) {
                        val d = sqrt(d2).coerceAtLeast(0.01f); val ov = (md - d) * 0.5f
                        val ux = dx / d; val uy = dy / d
                        b.x -= ux * ov; b.y -= uy * ov; b2.x += ux * ov; b2.y += uy * ov
                    }
                }
            }
        }
        var maxMove = 0f
        for (b in beads) {
            if (revealing && elapsed < b.joinAt) continue
            if (revealing && b.joinAt == 0f && elapsed < HOLD) continue   // lifting in place — keep its true home spot, don't clamp to field bounds
            val r = b.r
            if (b.x < FX0 + r) b.x = FX0 + r else if (b.x > FX1 - r) b.x = FX1 - r
            if (b.y < FY0 + r) b.y = FY0 + r else if (b.y > FY1 - r) b.y = FY1 - r
            val mv = abs(b.x - b.ox) + abs(b.y - b.oy); if (mv > maxMove) maxMove = mv
        }
        val settleT = if (revealing) 1.6 else 0.6   // filtering settles much sooner
        if (elapsed > settleT && maxMove < 0.4f) { frozen = true; revealing = false }
        invalidate()
    }

    // ───── render ─────
    override fun onDraw(canvas: Canvas) {
        // During the open: keep the backdrop transparent (home page visible) for
        // HOLD seconds while the icons lift off it, then fade the dark in.
        val el = (System.nanoTime() - simT0) / 1e9
        val bgA = when {
            closing -> (1.0 - (System.nanoTime() - closeT0) / 1e9 / CLOSE_DUR).coerceIn(0.0, 1.0).toFloat()
            revealing -> (((el - HOLD) / BG_FADE).coerceIn(0.0, 1.0)).toFloat()
            else -> 1f
        }
        val bgAlpha = (255 * bgA).toInt()
        bgPaint.alpha = bgAlpha
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        orbPaint.alpha = bgAlpha
        orb1?.let { orbPaint.shader = it; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orbPaint) }
        orb2?.let { orbPaint.shader = it; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orbPaint) }
        orb3?.let { orbPaint.shader = it; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orbPaint) }
        orbPaint.shader = null

        for (b in beads) {
            if (b.op < 0.02f) continue
            val a = (b.op * 255f).toInt().coerceIn(0, 255)
            val r = b.r
            val bmp = b.bmp
            if (bmp != null) {
                val ir = (r * 0.96f)
                iconRect.set((b.x - ir).toInt(), (b.y - ir).toInt(), (b.x + ir).toInt(), (b.y + ir).toInt())
                hiPaint.alpha = a
                canvas.drawBitmap(bmp, null, iconRect, hiPaint)
            } else {
                // faint placeholder so the packing reads while the icon loads
                glassFill.alpha = (28 * b.op).toInt().coerceIn(0, 255)
                canvas.drawCircle(b.x, b.y, r, glassFill)
            }
            if (b.lbn) {
                lblPaint.alpha = a
                canvas.drawText(ellipsize(b.label, r * 2.2f), b.x, b.y + r + 14f * density, lblPaint)
            }
        }

        if (stroke.size > 1) {
            val path = Path(); path.moveTo(stroke[0][0], stroke[0][1])
            for (i in 1 until stroke.size) path.lineTo(stroke[i][0], stroke[i][1])
            inkPaint.color = Color.argb(100, 255, 255, 255); inkPaint.strokeWidth = 12f; canvas.drawPath(path, inkPaint)
            inkPaint.color = Color.argb(240, 255, 255, 255); inkPaint.strokeWidth = 3.5f; canvas.drawPath(path, inkPaint)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (lblPaint.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && lblPaint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    // ───── touch ─────
    private fun beadAt(x: Float, y: Float): Bead? {
        var best: Bead? = null; var bd = Float.MAX_VALUE
        for (b in beads) {
            if (b.op < 0.12f) continue
            val dx = b.x - x; val dy = b.y - y; val d = dx * dx + dy * dy; val rr = b.r + 8f * density
            if (d < rr * rr && d < bd) { bd = d; best = b }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; downT = System.currentTimeMillis(); moved = false; contextShown = false
                stroke.clear(); stroke.add(floatArrayOf(e.x, e.y))
                // NOTE: do NOT wake the sim on touch — that would re-pack the field
                // and drift bubbles out from under the finger. Only filter changes move it.
                longPressRunnable?.let { handler.removeCallbacks(it) }
                val lp = Runnable {
                    if (!moved && !contextShown) {
                        val b = beadAt(sx, sy)
                        if (b != null) {
                            contextShown = true
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val loc = IntArray(2); getLocationOnScreen(loc)
                            listener?.onContextRequested(b.pkg, b.label, (loc[0] + sx).toInt(), (loc[1] + sy).toInt())
                        }
                    }
                }
                longPressRunnable = lp; handler.postDelayed(lp, 430)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                stroke.add(floatArrayOf(e.x, e.y))
                if (abs(e.x - sx) + abs(e.y - sy) > 14f * density) {
                    moved = true; longPressRunnable?.let { handler.removeCallbacks(it) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { handler.removeCallbacks(it) }
                val wasContext = contextShown
                val dt = System.currentTimeMillis() - downT
                val dx = e.x - sx; val dy = e.y - sy
                val ptsCopy = ArrayList(stroke)
                stroke.clear(); invalidate()
                if (wasContext || e.actionMasked == MotionEvent.ACTION_CANCEL) return true
                val pl = Unistroke.pathLength(ptsCopy)
                val tapTh = minOf(width, height) * 0.045f
                if (pl < tapTh && dt < 320) {
                    beadAt(sx, sy)?.let { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); listener?.onLaunch(it.pkg) }
                    return true
                }
                if (dy > height * 0.2f && abs(dx) < dy * 0.8f && pl < height * 0.6f) {
                    if (query.isNotEmpty()) clearFilter() else startClose()   // reverse morph back home
                    return true
                }
                if (dx < -width * 0.22f && abs(dy) < abs(dx) * 0.55f && dt < 320) { clearFilter(); return true }
                if (pl < minOf(width, height) * 0.05f) return true
                val letter = Unistroke.recognize(ptsCopy)
                if (letter != null) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); setFilter(letter) }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Re-read a single app's label (after a rename) and repaint. */
    fun refreshLabel(pkg: String) {
        val prefs = LauncherPrefs(context)
        beads.firstOrNull { it.pkg == pkg }?.let { b ->
            val lbl = prefs.appLabel(pkg, b.label).toString(); b.label = lbl; b.tokens = tokensOf(lbl)
        }
        wake()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try { IconMask.attach(context.applicationContext) } catch (_: Throwable) {}
        if (beads.isEmpty()) buildData()
        if (width > 0 && height > 0) { onSizeChanged(width, height, width, height) }
        if (!dismissed) wake()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        longPressRunnable?.let { handler.removeCallbacks(it) }
        // bitmaps are owned by IconMask's cache — do not recycle here.
    }
}
