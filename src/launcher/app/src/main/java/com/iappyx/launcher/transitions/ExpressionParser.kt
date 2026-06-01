/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.transitions

/**
 * Recursive-descent parser for the math expression DSL used by transition
 * specs. Grammar (precedence climbing):
 *
 *   expr   = term (('+'|'-') term)*
 *   term   = factor (('*'|'/'|'%') factor)*
 *   factor = ['-'] primary
 *   primary = NUMBER | IDENT | IDENT '(' args ')' | '(' expr ')'
 *   args   = expr (',' expr)*
 *
 * Identifiers are either variables (looked up via [VariableScope]) or
 * function names (one of: abs, sin, cos, tan, sqrt, sign, min, max, pow,
 * clamp, lerp, mod). Constants like `pi` should be passed in as variables
 * by the caller.
 *
 * Errors throw [ExpressionException] with a helpful position. Compile once
 * at spec load; evaluate per frame.
 */
object ExpressionParser {

    class ExpressionException(msg: String) : Exception(msg)

    private val FUNCTIONS = setOf(
        "abs", "sin", "cos", "tan", "sqrt", "sign",
        "min", "max", "pow", "clamp", "lerp", "mod",
    )

    fun compile(src: String, scope: VariableScope): Expr {
        val parser = Parser(src, scope)
        val e = parser.expr()
        parser.expectEnd()
        return e
    }

    private class Parser(private val src: String, private val scope: VariableScope) {
        private var pos = 0

        fun expr(): Expr {
            var left = term()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '+' && c != '-') break
                pos++
                val right = term()
                left = BinOp(c, left, right)
            }
            return left
        }

        private fun term(): Expr {
            var left = factor()
            while (true) {
                skipWs()
                val c = peek() ?: break
                if (c != '*' && c != '/' && c != '%') break
                pos++
                val right = factor()
                left = BinOp(c, left, right)
            }
            return left
        }

        private fun factor(): Expr {
            skipWs()
            if (peek() == '-') { pos++; return Neg(primary()) }
            if (peek() == '+') { pos++ } // unary plus is a no-op
            return primary()
        }

        private fun primary(): Expr {
            skipWs()
            val c = peek() ?: throw ExpressionException("unexpected end of expression")
            if (c == '(') {
                pos++
                val e = expr()
                skipWs()
                if (peek() != ')') throw ExpressionException("expected ')' at $pos")
                pos++
                return e
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return identOrCall()
            throw ExpressionException("unexpected '$c' at $pos")
        }

        private fun number(): Expr {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            // Optional exponent e.g. 1e-3
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val token = src.substring(start, pos)
            val v = token.toDoubleOrNull()
                ?: throw ExpressionException("bad number '$token' at $start")
            return NumLit(v)
        }

        private fun identOrCall(): Expr {
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            skipWs()
            if (pos < src.length && src[pos] == '(') {
                if (name !in FUNCTIONS) {
                    throw ExpressionException("unknown function '$name' at $start")
                }
                pos++
                val args = mutableListOf<Expr>()
                skipWs()
                if (peek() != ')') {
                    args.add(expr())
                    skipWs()
                    while (peek() == ',') {
                        pos++
                        args.add(expr())
                        skipWs()
                    }
                }
                if (peek() != ')') throw ExpressionException("expected ')' at $pos")
                pos++
                // Validate arity so a malformed spec (e.g. "min(p)") is rejected
                // at parse time → the whole spec falls back to default, instead
                // of compiling an expression that throws IndexOutOfBounds in
                // Call.eval on every frame during a swipe.
                val expectedArgs = when (name) {
                    "min", "max", "pow", "mod" -> 2
                    "clamp", "lerp" -> 3
                    else -> 1 // abs, sin, cos, tan, sqrt, sign
                }
                if (args.size != expectedArgs) {
                    throw ExpressionException("'$name' expects $expectedArgs arg(s), got ${args.size} at $start")
                }
                return Call(name, args)
            }
            val idx = scope.indexOf(name)
                ?: throw ExpressionException("unknown variable '$name' at $start")
            return VarRef(idx)
        }

        fun expectEnd() {
            skipWs()
            if (pos != src.length) {
                throw ExpressionException("trailing input at $pos: '${src.substring(pos)}'")
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWs()
            return if (pos < src.length) src[pos] else null
        }
    }
}
