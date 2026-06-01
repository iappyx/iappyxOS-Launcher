/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.search

/**
 * Tiny recursive-descent expression evaluator for the universal search bar.
 * Supports `+ - * / % ^ ( )`, unary minus/plus, the constants `pi` and `e`,
 * and the functions `sqrt`, `sin`, `cos`, `tan`, `log` (base 10), `ln`,
 * and `abs`. All numbers are doubles; trig is in radians.
 *
 * Returns null when the input either fails to parse OR doesn't look like
 * a math expression (no operators, no functions, no parens) so that plain
 * numeric or single-word app searches don't surface a useless math row.
 */
object MathEvaluator {
    data class Result(val display: String, val raw: Double)

    fun evaluate(input: String): Result? {
        val expr = input.trim()
        if (expr.isEmpty()) return null
        // Skip queries that don't smell like math — bare numbers, identifiers,
        // single words. We require at least one operator / parenthesis /
        // recognised function name before bothering to parse.
        if (!looksLikeMath(expr)) return null
        return try {
            val parser = Parser(expr)
            val v = parser.parseExpression()
            parser.expectEnd()
            if (v.isNaN() || v.isInfinite()) null
            else Result(format(v), v)
        } catch (_: Exception) { null }
    }

    private fun looksLikeMath(s: String): Boolean {
        if (s.any { it in "+*/%^()" }) return true
        // A bare "-5" alone isn't math, but "-5+1" is — handled above. A
        // function call like "sqrt(2)" includes parens, so it's caught above
        // too. Multi-token expressions like "5 - 3" need the operator check.
        // Final guard: require a recognised function token surrounded by
        // word boundaries (so "sin" inside "Singapore" doesn't trigger).
        val lower = s.lowercase()
        for (fn in functions) {
            val idx = lower.indexOf(fn)
            if (idx < 0) continue
            val before = if (idx == 0) ' ' else lower[idx - 1]
            val after = if (idx + fn.length >= lower.length) ' '
                        else lower[idx + fn.length]
            if (!before.isLetterOrDigit() && (after == '(' || after == ' ')) return true
        }
        return false
    }

    private val functions = listOf("sqrt", "sin", "cos", "tan", "log", "ln", "abs")

    private fun format(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
            return v.toLong().toString()
        }
        // Trim trailing zeros after up to 6 fractional digits.
        val s = "%.6f".format(v)
        return s.trimEnd('0').trimEnd('.')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun expectEnd() {
            skipWs()
            if (pos != src.length) throw IllegalStateException("trailing input at $pos")
        }

        // expression : term (('+' | '-') term)*
        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '+' && c != '-') break
                pos++
                val rhs = parseTerm()
                v = if (c == '+') v + rhs else v - rhs
            }
            return v
        }

        // term : factor (('*' | '/' | '%') factor)*
        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '*' && c != '/' && c != '%') break
                pos++
                val rhs = parseFactor()
                v = when (c) {
                    '*' -> v * rhs
                    '/' -> v / rhs
                    else -> v % rhs
                }
            }
            return v
        }

        // factor : unary ('^' factor)?   right-associative
        private fun parseFactor(): Double {
            val base = parseUnary()
            skipWs()
            if (peek() == '^') {
                pos++
                val exp = parseFactor()
                return Math.pow(base, exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWs()
            return when (peek()) {
                '-' -> { pos++; -parsePrimary() }
                '+' -> { pos++; parsePrimary() }
                else -> parsePrimary()
            }
        }

        // primary : number | ident ('(' expression ')')? | '(' expression ')'
        private fun parsePrimary(): Double {
            skipWs()
            val c = peek() ?: throw IllegalStateException("unexpected end")
            if (c == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (peek() != ')') throw IllegalStateException("missing ')'")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) return parseIdentifier()
            throw IllegalStateException("unexpected '$c' at $pos")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // Optional exponent: 1e3, 2.5e-2
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            return src.substring(start, pos).toDouble()
        }

        private fun parseIdentifier(): Double {
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos).lowercase()
            skipWs()
            // Function call?
            if (peek() == '(') {
                pos++
                val arg = parseExpression()
                skipWs()
                if (peek() != ')') throw IllegalStateException("missing ')' after $name")
                pos++
                return when (name) {
                    "sqrt" -> Math.sqrt(arg)
                    "sin" -> Math.sin(arg)
                    "cos" -> Math.cos(arg)
                    "tan" -> Math.tan(arg)
                    "log" -> Math.log10(arg)
                    "ln" -> Math.log(arg)
                    "abs" -> Math.abs(arg)
                    else -> throw IllegalStateException("unknown function $name")
                }
            }
            return when (name) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> throw IllegalStateException("unknown identifier $name")
            }
        }

        private fun peek(): Char? {
            skipWs()
            return if (pos < src.length) src[pos] else null
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
