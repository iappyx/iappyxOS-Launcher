/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Single-stroke ($1-style) letter recognizer for the native Field. Ported 1:1
 * from the web Field's recognizer + the same single-stroke alphabet templates.
 * Orientation matters (no rotation normalization) so direction disambiguates
 * look-alikes (i vs l, etc.).
 */
package com.iappyx.launcher.fieldnative

import kotlin.math.sqrt

object Unistroke {
    private const val N = 32
    private const val THRESHOLD = 0.34f

    // Normalized 0..1, y-down. Same data as field.html's TPL.
    private val RAW: Map<Char, Array<FloatArray>> = mapOf(
        'a' to arrayOf(f(.72f,.32f),f(.45f,.12f),f(.16f,.42f),f(.45f,.74f),f(.74f,.58f),f(.74f,.95f)),
        'b' to arrayOf(f(.22f,.05f),f(.22f,.95f),f(.6f,.92f),f(.82f,.64f),f(.55f,.46f),f(.22f,.5f)),
        'c' to arrayOf(f(.8f,.26f),f(.46f,.06f),f(.13f,.46f),f(.46f,.92f),f(.82f,.78f)),
        'd' to arrayOf(f(.8f,.05f),f(.8f,.95f),f(.42f,.92f),f(.16f,.6f),f(.45f,.36f),f(.8f,.42f)),
        'e' to arrayOf(f(.2f,.52f),f(.82f,.46f),f(.62f,.16f),f(.26f,.2f),f(.12f,.56f),f(.42f,.92f),f(.82f,.84f)),
        'f' to arrayOf(f(.82f,.16f),f(.42f,.1f),f(.36f,.5f),f(.36f,.95f)),
        'g' to arrayOf(f(.72f,.32f),f(.45f,.12f),f(.16f,.42f),f(.45f,.7f),f(.72f,.56f),f(.72f,.96f),f(.42f,1.06f),f(.2f,.96f)),
        'h' to arrayOf(f(.22f,.05f),f(.22f,.95f),f(.22f,.5f),f(.56f,.44f),f(.82f,.64f),f(.82f,.95f)),
        'i' to arrayOf(f(.5f,.95f),f(.5f,.05f)),
        'j' to arrayOf(f(.62f,.05f),f(.62f,.88f),f(.42f,1.0f),f(.2f,.86f)),
        'k' to arrayOf(f(.22f,.05f),f(.22f,.95f),f(.22f,.55f),f(.78f,.12f),f(.32f,.55f),f(.82f,.95f)),
        'l' to arrayOf(f(.5f,.05f),f(.5f,.95f)),
        'm' to arrayOf(f(.16f,.95f),f(.2f,.1f),f(.5f,.7f),f(.8f,.1f),f(.84f,.95f)),
        'n' to arrayOf(f(.2f,.95f),f(.2f,.12f),f(.78f,.92f),f(.8f,.12f)),
        'o' to arrayOf(f(.5f,.05f),f(.13f,.5f),f(.5f,.95f),f(.87f,.5f),f(.5f,.05f)),
        'p' to arrayOf(f(.22f,1.0f),f(.22f,.1f),f(.62f,.1f),f(.82f,.36f),f(.52f,.56f),f(.22f,.5f)),
        'q' to arrayOf(f(.74f,.32f),f(.45f,.12f),f(.16f,.42f),f(.45f,.7f),f(.74f,.55f),f(.74f,1.0f),f(.9f,.84f)),
        'r' to arrayOf(f(.22f,.95f),f(.22f,.14f),f(.56f,.1f),f(.82f,.36f)),
        's' to arrayOf(f(.8f,.2f),f(.34f,.1f),f(.3f,.46f),f(.7f,.56f),f(.68f,.9f),f(.2f,.82f)),
        't' to arrayOf(f(.5f,.05f),f(.5f,.9f),f(.82f,.85f)),
        'u' to arrayOf(f(.2f,.1f),f(.2f,.7f),f(.5f,.95f),f(.8f,.7f),f(.8f,.1f)),
        'v' to arrayOf(f(.15f,.1f),f(.5f,.95f),f(.85f,.1f)),
        'w' to arrayOf(f(.1f,.1f),f(.3f,.95f),f(.5f,.4f),f(.7f,.95f),f(.9f,.1f)),
        'x' to arrayOf(f(.2f,.12f),f(.8f,.88f),f(.5f,.5f),f(.8f,.12f),f(.2f,.88f)),
        'y' to arrayOf(f(.2f,.1f),f(.5f,.55f),f(.8f,.1f),f(.5f,.55f),f(.4f,1.04f),f(.2f,.98f)),
        'z' to arrayOf(f(.2f,.14f),f(.82f,.14f),f(.2f,.86f),f(.82f,.86f)),
    )

    private val templates: Map<Char, Array<FloatArray>> =
        RAW.mapValues { normalize(it.value.map { p -> floatArrayOf(p[0], p[1]) }) }

    private fun f(x: Float, y: Float) = floatArrayOf(x, y)

    private fun pathLen(p: List<FloatArray>): Float {
        var l = 0f
        for (i in 1 until p.size) { val dx = p[i][0]-p[i-1][0]; val dy = p[i][1]-p[i-1][1]; l += sqrt(dx*dx+dy*dy) }
        return l
    }

    private fun resample(input: List<FloatArray>): Array<FloatArray>? {
        val total = pathLen(input)
        if (total <= 0f) return null
        val interval = total / (N - 1)
        val pts = input.map { floatArrayOf(it[0], it[1]) }.toMutableList()
        val out = ArrayList<FloatArray>(N)
        out.add(floatArrayOf(pts[0][0], pts[0][1]))
        var d = 0f
        var i = 1
        while (i < pts.size) {
            val a = pts[i-1]; val b = pts[i]
            val dx = b[0]-a[0]; val dy = b[1]-a[1]
            val dist = sqrt(dx*dx+dy*dy)
            if (d + dist >= interval) {
                val t = (interval - d) / dist
                val nx = a[0] + t*dx; val ny = a[1] + t*dy
                out.add(floatArrayOf(nx, ny))
                pts.add(i, floatArrayOf(nx, ny))
                d = 0f
            } else d += dist
            i++
        }
        while (out.size < N) out.add(floatArrayOf(pts.last()[0], pts.last()[1]))
        return Array(N) { out[it] }
    }

    private fun normalize(input: List<FloatArray>): Array<FloatArray> {
        val r = resample(input) ?: return Array(N) { floatArrayOf(0f, 0f) }
        var cx = 0f; var cy = 0f
        for (p in r) { cx += p[0]; cy += p[1] }
        cx /= N; cy /= N
        var minx = 1e9f; var miny = 1e9f; var maxx = -1e9f; var maxy = -1e9f
        for (p in r) { if (p[0]<minx) minx=p[0]; if (p[0]>maxx) maxx=p[0]; if (p[1]<miny) miny=p[1]; if (p[1]>maxy) maxy=p[1] }
        val s = maxOf(maxx-minx, maxy-miny).coerceAtLeast(1e-3f)
        for (p in r) { p[0] = (p[0]-cx)/s; p[1] = (p[1]-cy)/s }
        return r
    }

    private fun dist(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        var d = 0f
        for (i in 0 until N) { val dx = a[i][0]-b[i][0]; val dy = a[i][1]-b[i][1]; d += sqrt(dx*dx+dy*dy) }
        return d / N
    }

    /** Returns the recognized letter, or null if the stroke is too ambiguous. */
    fun recognize(points: List<FloatArray>): Char? {
        if (points.size < 2) return null
        val c = normalize(points)
        var best: Char? = null
        var bd = 1e9f
        for ((letter, tpl) in templates) {
            val d = dist(c, tpl)
            if (d < bd) { bd = d; best = letter }
        }
        return if (bd < THRESHOLD) best else null
    }

    fun pathLength(points: List<FloatArray>): Float = pathLen(points)

    /** Raw 0..1 single-stroke template for [c], for drawing the how-to guide. */
    fun template(c: Char): Array<FloatArray>? = RAW[c]
}
