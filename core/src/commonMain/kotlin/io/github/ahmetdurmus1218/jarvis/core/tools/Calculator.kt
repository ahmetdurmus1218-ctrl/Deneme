package io.github.ahmetdurmus1218.jarvis.core.tools

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Safe arithmetic expression evaluator (the `hesapla` tool).
 *
 * Recursive-descent parser over a fixed grammar: numbers, + - * / % ^, parentheses, unary minus,
 * a whitelist of functions and the constants pi/e. Nothing is ever executed as code.
 * Accepts Turkish words (artı, eksi, çarpı, bölü, üssü, karekök) and decimal commas (3,5).
 */
object Calculator {

    class CalcException(message: String) : Exception(message)

    private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
        "sqrt" to { x: Double -> sqrt(x) },
        "karekok" to { x: Double -> sqrt(x) },
        "sin" to { x: Double -> sin(Math2.toRadians(x)) },
        "cos" to { x: Double -> cos(Math2.toRadians(x)) },
        "tan" to { x: Double -> tan(Math2.toRadians(x)) },
        "asin" to { x: Double -> Math2.toDegrees(asin(x)) },
        "acos" to { x: Double -> Math2.toDegrees(acos(x)) },
        "atan" to { x: Double -> Math2.toDegrees(atan(x)) },
        "ln" to { x: Double -> ln(x) },
        "log" to { x: Double -> log10(x) },
        "abs" to { x: Double -> abs(x) },
        "mutlak" to { x: Double -> abs(x) },
        "round" to { x: Double -> round(x) },
        "yuvarla" to { x: Double -> round(x) },
        "floor" to { x: Double -> floor(x) },
    )
    private val CONSTANTS = mapOf("pi" to PI, "e" to E)
    private const val MAX_LENGTH = 512
    private const val MAX_DEPTH = 64

    /** Rewrites Turkish spoken arithmetic into symbols. Returns null if text has no arithmetic shape. */
    fun fromSpeech(text: String): String {
        var s = " " + Turkish.normalize(text) + " "
        val words = listOf(
            " arti " to " + ", " eksi " to " - ", " carpi " to " * ", " kere " to " * ", " bolu " to " / ",
            " bolumu " to " / ", " ussu " to " ^ ", " uzeri " to " ^ ", " mod " to " % ",
" karekoku " to " sqrt ", " karekok " to " sqrt ",
            " x " to " * ",
        )
        for ((w, r) in words) {
            while (s.contains(w)) s = s.replace(w, r)
        }
        // Drop filler words around a pure expression ("... kaç eder", "... hesapla", "... nedir").
        s = s.replace(Regex("\\b(kac eder|kac|eder|hesapla|nedir|sonucu|sonuc|esittir|yapar|ne)\\b"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun evaluate(expression: String): Double {
        if (expression.length > MAX_LENGTH) throw CalcException("İfade çok uzun")
        val p = Parser(expression)
        val v = p.parseExpression(0)
        p.skipSpaces()
        if (!p.atEnd()) throw CalcException("Beklenmeyen karakter: '${p.peek()}'")
        if (v.isNaN() || v.isInfinite()) throw CalcException("Tanımsız sonuç")
        return v
    }

    /** Formats a result the Turkish way: 1234,5 (no exponent, trailing zeros trimmed). */
    fun format(value: Double): String {
        val rounded = round(value * 1e10) / 1e10
        if (rounded == floor(rounded) && abs(rounded) < 1e15) return rounded.toLong().toString()
        var s = rounded.toString()
        if (s.contains('E') || s.contains('e')) return s
        s = s.trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    private class Parser(private val src: String) {
        private var i = 0

        fun atEnd() = i >= src.length
        fun peek(): Char = src[i]
        fun skipSpaces() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        fun parseExpression(depth: Int): Double {
            if (depth > MAX_DEPTH) throw CalcException("İfade çok derin")
            var left = parseTerm(depth)
            while (true) {
                skipSpaces()
                if (atEnd()) return left
                when (peek()) {
                    '+' -> { i++; left += parseTerm(depth) }
                    '-', '−' -> { i++; left -= parseTerm(depth) }
                    else -> return left
                }
            }
        }

        private fun parseTerm(depth: Int): Double {
            var left = parseUnary(depth)
            while (true) {
                skipSpaces()
                if (atEnd()) return left
                when (peek()) {
                    '*', '×', '·' -> { i++; left *= parseUnary(depth) }
                    '/', '÷', ':' -> {
                        i++
                        val r = parseUnary(depth)
                        if (r == 0.0) throw CalcException("Sıfıra bölme")
                        left /= r
                    }
                    '%' -> {
                        i++
                        val r = parseUnary(depth)
                        if (r == 0.0) throw CalcException("Sıfıra bölme")
                        left %= r
                    }
                    else -> return left
                }
            }
        }

        private fun parseUnary(depth: Int): Double {
            skipSpaces()
            if (!atEnd() && (peek() == '-' || peek() == '−')) { i++; return -parseUnary(depth + 1) }
            if (!atEnd() && peek() == '+') { i++; return parseUnary(depth + 1) }
            return parsePower(depth)
        }

        private fun parsePower(depth: Int): Double {
            val base = parsePrimary(depth)
            skipSpaces()
            if (!atEnd() && peek() == '^') {
                i++
                val exp = parseUnary(depth + 1) // right-associative
                return base.pow(exp)
            }
            return base
        }

        private fun parsePrimary(depth: Int): Double {
            skipSpaces()
            if (atEnd()) throw CalcException("İfade eksik")
            val c = peek()
            if (c == '(') {
                i++
                val v = parseExpression(depth + 1)
                skipSpaces()
                if (atEnd() || peek() != ')') throw CalcException("Kapanmayan parantez")
                i++
                return v
            }
            if (c.isDigit() || c == '.' || c == ',') return parseNumber()
            if (c.isLetter()) {
                val start = i
                while (i < src.length && src[i].isLetter()) i++
                val name = src.substring(start, i).lowercase()
                CONSTANTS[name]?.let { return it }
                val fn = FUNCTIONS[name] ?: throw CalcException("Bilinmeyen ad: $name")
                return fn(parsePower(depth + 1))
            }
            throw CalcException("Beklenmeyen karakter: '$c'")
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == ',')) i++
            val raw = src.substring(start, i)
            return parseLocalizedNumber(raw) ?: throw CalcException("Geçersiz sayı: $raw")
        }
    }

    /**
     * "3,5" → 3.5, "1.234,5" → 1234.5, "1,234.5" → 1234.5, "1.000.000" → 1000000, "2.5" → 2.5.
     * A single separator followed by exactly three digits is read as a thousands separator only when
     * it is a dot ("1.000" → 1000), matching Turkish usage.
     */
    fun parseLocalizedNumber(raw: String): Double? {
        if (raw.isEmpty() || raw.none { it.isDigit() }) return null
        val dots = raw.count { it == '.' }
        val commas = raw.count { it == ',' }
        val normalized = when {
            dots > 0 && commas > 0 -> {
                val decimalIsComma = raw.lastIndexOf(',') > raw.lastIndexOf('.')
                if (decimalIsComma) raw.replace(".", "").replace(',', '.') else raw.replace(",", "")
            }
            dots > 1 -> raw.replace(".", "")
            commas > 1 -> raw.replace(",", "")
            commas == 1 -> raw.replace(',', '.')
            dots == 1 -> {
                val after = raw.substringAfter('.')
                if (after.length == 3 && raw.substringBefore('.').isNotEmpty()) raw.replace(".", "") else raw
            }
            else -> raw
        }
        return normalized.toDoubleOrNull()
    }
}

internal object Math2 {
    fun toRadians(deg: Double) = deg / 180.0 * PI
    fun toDegrees(rad: Double) = rad * 180.0 / PI
}
