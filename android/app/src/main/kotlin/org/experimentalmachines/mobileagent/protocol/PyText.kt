package org.experimentalmachines.mobileagent.protocol

import java.math.BigDecimal

/** Python `str` behaviour the protocol depends on: strip, splitlines, slicing by code point, float(). */
object PyText {
    /** `str.isspace()` for one code point (CPython's _PyUnicode_IsWhitespace table). */
    fun isSpace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    /** `str.strip()`. */
    fun strip(s: String): String {
        var start = 0
        var end = s.length
        while (start < end) {
            val cp = s.codePointAt(start)
            if (!isSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!isSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    /** `str.splitlines()[0]` for a string with at least one line (the text before the first line boundary). */
    fun firstLine(s: String): String {
        for (i in s.indices) {
            when (s[i]) {
                '\n', '\r', '\u000b', '\u000c', '\u001c', '\u001d', '\u001e', '\u0085', ' ', ' ' -> return s.substring(0, i)
            }
        }
        return s
    }

    /** `s[:n]`: the first [n] code points (not UTF-16 units). */
    fun take(s: String, n: Int): String {
        if (n <= 0) return ""
        var i = 0
        var count = 0
        while (i < s.length && count < n) {
            i += Character.charCount(s.codePointAt(i))
            count++
        }
        return s.substring(0, i)
    }

    private val FLOAT_DEC = Regex("""[+-]?(?:[0-9](?:_?[0-9])*(?:\.(?:[0-9](?:_?[0-9])*)?)?|\.[0-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)?""")
    private val FLOAT_SPECIAL = Regex("""[+-]?(?:nan|inf|infinity)""", RegexOption.IGNORE_CASE)

    /** `float(s)` for a str; null where Python raises ValueError. (Non-ASCII digits, which Python accepts, are refused.) */
    fun parseFloat(s: String): Double? {
        val t = strip(s)
        if (FLOAT_SPECIAL.matches(t)) {
            val neg = t.startsWith("-")
            return if (t.trimStart('+', '-').lowercase() == "nan") Double.NaN else if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        if (!FLOAT_DEC.matches(t)) return null
        return t.replace("_", "").toDouble()
    }

    /** `float(x)` for a JSON value; null where Python raises (TypeError, ValueError, OverflowError). */
    fun toFloat(v: PyValue): Double? = when (v) {
        is PyValue.Bool -> if (v.value) 1.0 else 0.0
        is PyValue.Int -> BigDecimal(v.value).toDouble().takeIf { it.isFinite() }
        is PyValue.Float -> v.value
        is PyValue.Str -> parseFloat(v.value)
        else -> null
    }

    /** Python's `round(x)` for a float: to the nearest integer, ties to even. */
    fun round(x: Double): Long = Math.rint(x).toLong()

    /** Python's `int(x)` for a float: truncation toward zero. */
    fun int(x: Double): Long = x.toLong()
}
