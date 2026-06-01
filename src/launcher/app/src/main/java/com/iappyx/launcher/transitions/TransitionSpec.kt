/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.transitions

import android.view.View
import android.view.ViewGroup
import com.iappyx.launcher.widget.HomeGrid
import org.json.JSONObject

/**
 * Compiled, ready-to-apply page transition. Constructed from a JSON spec
 * once at load time; [apply] runs per-frame as the user swipes.
 *
 * The JSON has two optional blocks:
 *
 *   { "page": { ...properties... },
 *     "cell": { ...properties... } }
 *
 * Each property is a math expression in the variable set documented on
 * [PAGE_VARS] / [CELL_VARS]. Allowed properties: [ALLOWED_PROPS].
 *
 * Per-frame cost for a typical spec (~5 page props + ~5 cell props on a
 * 5×6 grid): a few microseconds. AST evaluation is constant-time per node.
 */
class TransitionSpec private constructor(
    private val pageEvaluators: List<Pair<String, Expr>>,
    private val cellEvaluators: List<Pair<String, Expr>>,
) {

    /** True iff this spec transforms individual cells (not just the page).
     *  Used by the launcher's transformer to decide whether to invoke
     *  per-cell recovery work after settle: cell-transforming specs can
     *  pressure Chromium's tile-memory budget so widgets need a forced
     *  re-layout on settle, while page-only specs (fade, blur, zoom etc.)
     *  don't touch cells and so don't need it (and would just see a
     *  cosmetic blip from the recovery's redraw).  */
    val hasCellEvaluators: Boolean get() = cellEvaluators.isNotEmpty()

    fun apply(page: View, position: Float) {
        // Defensive: at p≈0 (page fully settled), reset everything we might
        // have touched. AI specs sometimes evaluate to non-neutral values at
        // p=0 (e.g. `sin(cellCol)` is non-zero) which leaves icons stuck in
        // weird positions when the swipe ends. Forcing reset at rest means
        // a sloppy spec only misbehaves DURING swipes, never at rest.
        if (kotlin.math.abs(position) < 0.001f) {
            reset(page); return
        }
        val grid = findGrid(page)
        val density = page.resources.displayMetrics.density.toDouble()
        val pageEnv = DoubleArray(PAGE_VARS.size)
        pageEnv[0] = position.toDouble()
        pageEnv[1] = page.width.toDouble()
        pageEnv[2] = page.height.toDouble()
        pageEnv[3] = Math.PI
        pageEnv[4] = density
        applyToView(page, pageEvaluators, pageEnv)

        if (cellEvaluators.isEmpty() || grid == null) return
        val cellW = if (grid.cols > 0) grid.measuredWidth.toDouble() / grid.cols else 1.0
        val cellH = if (grid.rows > 0) grid.measuredHeight.toDouble() / grid.rows else 1.0
        val total = grid.childCount
        // Reuse one env array per page — cell loop only mutates cell-specific
        // slots, so allocation per page-frame stays at 1.
        val cellEnv = DoubleArray(CELL_VARS.size)
        cellEnv[0] = position.toDouble()
        cellEnv[1] = page.width.toDouble()
        cellEnv[2] = page.height.toDouble()
        cellEnv[3] = Math.PI
        cellEnv[4] = density
        cellEnv[9] = grid.cols.toDouble()
        cellEnv[10] = grid.rows.toDouble()
        for (i in 0 until total) {
            val cell = grid.getChildAt(i)
            val col = if (cellW > 0) (cell.left / cellW).toInt() else 0
            val row = if (cellH > 0) (cell.top / cellH).toInt() else 0
            cellEnv[5] = col.toDouble()
            cellEnv[6] = row.toDouble()
            cellEnv[7] = i.toDouble()
            cellEnv[8] = total.toDouble()
            applyToView(cell, cellEvaluators, cellEnv)
        }
    }

    private fun applyToView(view: View, evaluators: List<Pair<String, Expr>>, env: DoubleArray) {
        // Clamp budgets — guard against AI specs that emit wildly large
        // values (e.g. `cellCol * 1000`) that translate cells thousands of
        // pixels off-screen, blow up the cameraDistance, or scale to
        // millions. NaN/Inf are also rejected. Limits are loose enough that
        // legitimate full-screen sweeps (1.5× pageWidth) and pop-out scales
        // (3×) still work. Rotation is unclamped — Android handles modulo
        // internally and a spinning effect is a legitimate use case.
        val maxTranslate = 10f * kotlin.math.max(view.width, view.height).toFloat()
            .coerceAtLeast(2000f) // fallback when view isn't laid out yet
        for ((prop, expr) in evaluators) {
            // Never let an eval error (e.g. a malformed expression that slipped
            // past parse) crash the per-frame swipe callback — skip the property.
            val v = try { expr.eval(env).toFloat() } catch (_: Throwable) { continue }
            if (v.isNaN() || v.isInfinite()) continue
            when (prop) {
                "alpha" -> view.alpha = v.coerceIn(0f, 1f)
                "scaleX" -> view.scaleX = v.coerceIn(0.01f, 10f)
                "scaleY" -> view.scaleY = v.coerceIn(0.01f, 10f)
                "translationX" -> view.translationX = v.coerceIn(-maxTranslate, maxTranslate)
                "translationY" -> view.translationY = v.coerceIn(-maxTranslate, maxTranslate)
                "translationZ" -> view.translationZ = v.coerceIn(-1000f, 1000f)
                "rotation" -> view.rotation = v
                "rotationX" -> view.rotationX = v
                "rotationY" -> view.rotationY = v
                "pivotX" -> view.pivotX = v.coerceIn(-maxTranslate, maxTranslate)
                "pivotY" -> view.pivotY = v.coerceIn(-maxTranslate, maxTranslate)
                "cameraDistance" -> view.cameraDistance = v.coerceIn(100f, 100_000f)
                "blur" -> applyBlur(view, v.coerceIn(0f, 100f))
            }
        }
    }

    private fun applyBlur(view: View, radius: Float) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (radius <= 0.1f) {
            view.setRenderEffect(null)
        } else {
            view.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    private fun resetView(v: View) {
        v.translationX = 0f; v.translationY = 0f; v.translationZ = 0f
        v.alpha = 1f
        v.scaleX = 1f; v.scaleY = 1f
        v.rotation = 0f; v.rotationX = 0f; v.rotationY = 0f
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
        v.cameraDistance = 1280f * v.resources.displayMetrics.density
        if (android.os.Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null)
    }

    /** Find the HomeGrid inside the page wrapper. Pages are usually wrapped
     *  in a FrameLayout (with bottom padding for the dock); the grid is one
     *  of its children. Returns null for the command page. */
    private fun findGrid(page: View): HomeGrid? {
        if (page is HomeGrid) return page
        val vg = page as? ViewGroup ?: return null
        for (i in 0 until vg.childCount) {
            val c = vg.getChildAt(i)
            if (c is HomeGrid) return c
        }
        return null
    }

    /** Preview-only apply path: takes an explicit list of (cell view, col,
     *  row) instead of looking for a HomeGrid. Lets the manage-tab carousel
     *  preview transitions on a fake mini-grid without instantiating the
     *  real HomeGrid (which has a lot of edit-mode / drag baggage). The
     *  launcher's [apply] path is untouched. */
    fun applyPreview(
        page: View,
        position: Float,
        cells: List<PreviewCell>,
        cols: Int,
        rows: Int,
    ) {
        if (kotlin.math.abs(position) < 0.001f) {
            resetView(page)
            for (c in cells) resetView(c.view)
            return
        }
        val density = page.resources.displayMetrics.density.toDouble()
        val pageEnv = DoubleArray(PAGE_VARS.size)
        pageEnv[0] = position.toDouble()
        pageEnv[1] = page.width.toDouble()
        pageEnv[2] = page.height.toDouble()
        pageEnv[3] = Math.PI
        pageEnv[4] = density
        applyToView(page, pageEvaluators, pageEnv)

        if (cellEvaluators.isEmpty()) return
        val total = cells.size
        val cellEnv = DoubleArray(CELL_VARS.size)
        cellEnv[0] = position.toDouble()
        cellEnv[1] = page.width.toDouble()
        cellEnv[2] = page.height.toDouble()
        cellEnv[3] = Math.PI
        cellEnv[4] = density
        cellEnv[9] = cols.toDouble()
        cellEnv[10] = rows.toDouble()
        for ((i, c) in cells.withIndex()) {
            cellEnv[5] = c.col.toDouble()
            cellEnv[6] = c.row.toDouble()
            cellEnv[7] = i.toDouble()
            cellEnv[8] = total.toDouble()
            applyToView(c.view, cellEvaluators, cellEnv)
        }
    }

    /** Reset every property this spec might touch back to a neutral state.
     *  Called when the page is fully off-screen or when switching transition
     *  styles, so a stale partial transform doesn't bleed into the next swipe. */
    fun reset(page: View) {
        resetView(page)
        val grid = findGrid(page) ?: return
        for (i in 0 until grid.childCount) resetView(grid.getChildAt(i))
    }

    /** Single fake-grid cell in [applyPreview]. */
    data class PreviewCell(val view: View, val col: Int, val row: Int)

    companion object {
        /** Variable order MUST match the env array indices used in [apply]. */
        val PAGE_VARS = listOf("p", "w", "h", "pi", "density")
        val CELL_VARS = listOf(
            "p", "w", "h", "pi", "density",
            "cellCol", "cellRow", "cellIndex", "cellTotal",
            "cols", "rows",
        )
        val ALLOWED_PROPS = setOf(
            "alpha", "scaleX", "scaleY",
            "translationX", "translationY", "translationZ",
            "rotation", "rotationX", "rotationY",
            "pivotX", "pivotY",
            "cameraDistance", "blur",
        )

        /** Parse a JSON string into a compiled spec. Returns null if the
         *  string is malformed or every expression fails to compile (i.e. a
         *  spec that would be a no-op). Bad expressions inside a valid block
         *  are silently dropped — better to render a partial transition than
         *  no transition at all. */
        fun parse(jsonStr: String): TransitionSpec? = try {
            val root = JSONObject(jsonStr)
            val pageScope = VariableScope(PAGE_VARS)
            val cellScope = VariableScope(CELL_VARS)
            val pageList = root.optJSONObject("page")?.let { compile(it, pageScope) } ?: emptyList()
            val cellList = root.optJSONObject("cell")?.let { compile(it, cellScope) } ?: emptyList()
            if (pageList.isEmpty() && cellList.isEmpty()) null
            else TransitionSpec(pageList, cellList)
        } catch (_: Throwable) { null }

        private fun compile(json: JSONObject, scope: VariableScope): List<Pair<String, Expr>> {
            val out = mutableListOf<Pair<String, Expr>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in ALLOWED_PROPS) continue
                val src = json.optString(key)
                if (src.isBlank()) continue
                try {
                    out.add(key to ExpressionParser.compile(src, scope))
                } catch (_: Throwable) { /* skip — partial spec is fine */ }
            }
            return out
        }
    }
}
