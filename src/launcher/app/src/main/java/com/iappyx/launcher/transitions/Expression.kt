/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.transitions

/**
 * AST + evaluator for the page-transition math expression DSL.
 *
 * A transition spec is a JSON object whose values are math expressions in
 * variables like `p`, `w`, `h`, `cellCol`, `cellRow`, `cellIndex`. We compile
 * each expression once at spec-load time into an [Expr] tree; per-frame
 * evaluation is then a few function calls and array reads — sub-microsecond
 * for typical specs.
 *
 * Variables are resolved by INDEX (not by name) at runtime — the parser
 * binds names to indices using a fixed [VariableScope] passed in at compile
 * time, and the evaluator just reads `env[index]`. Avoids a hash-map lookup
 * per node.
 */
sealed class Expr {
    abstract fun eval(env: DoubleArray): Double
}

class NumLit(private val value: Double) : Expr() {
    override fun eval(env: DoubleArray): Double = value
}

class VarRef(private val index: Int) : Expr() {
    override fun eval(env: DoubleArray): Double = env[index]
}

class Neg(private val inner: Expr) : Expr() {
    override fun eval(env: DoubleArray): Double = -inner.eval(env)
}

class BinOp(
    private val op: Char,
    private val left: Expr,
    private val right: Expr,
) : Expr() {
    override fun eval(env: DoubleArray): Double {
        val a = left.eval(env)
        val b = right.eval(env)
        return when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> if (b == 0.0) 0.0 else a / b
            '%' -> if (b == 0.0) 0.0 else a % b
            else -> 0.0
        }
    }
}

class Call(
    private val name: String,
    private val args: List<Expr>,
) : Expr() {
    override fun eval(env: DoubleArray): Double = when (name) {
        "abs" -> kotlin.math.abs(args[0].eval(env))
        "sin" -> kotlin.math.sin(args[0].eval(env))
        "cos" -> kotlin.math.cos(args[0].eval(env))
        "tan" -> kotlin.math.tan(args[0].eval(env))
        "sqrt" -> kotlin.math.sqrt(args[0].eval(env).coerceAtLeast(0.0))
        "sign" -> kotlin.math.sign(args[0].eval(env))
        "min" -> kotlin.math.min(args[0].eval(env), args[1].eval(env))
        "max" -> kotlin.math.max(args[0].eval(env), args[1].eval(env))
        "pow" -> Math.pow(args[0].eval(env), args[1].eval(env))
        "clamp" -> {
            val x = args[0].eval(env); val lo = args[1].eval(env); val hi = args[2].eval(env)
            if (x < lo) lo else if (x > hi) hi else x
        }
        "lerp" -> {
            val a = args[0].eval(env); val b = args[1].eval(env); val t = args[2].eval(env)
            a + (b - a) * t
        }
        "mod" -> {
            // Always-positive modulo (unlike Kotlin's %, which can be negative).
            val a = args[0].eval(env); val b = args[1].eval(env)
            if (b == 0.0) 0.0 else ((a % b) + b) % b
        }
        else -> 0.0
    }
}

/** Names → indices in the eval-time env array. Pass to [ExpressionParser.compile]. */
class VariableScope(names: List<String>) {
    private val index: Map<String, Int> = names.withIndex().associate { (i, n) -> n to i }
    val size: Int = names.size
    fun indexOf(name: String): Int? = index[name]
}
