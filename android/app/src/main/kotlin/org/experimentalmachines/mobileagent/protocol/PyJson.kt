package org.experimentalmachines.mobileagent.protocol

import java.math.BigInteger

/**
 * JSON values as Python's `json` module produces them, so the action parser can follow
 * `train/agent_format.parse_action` line by line (Python truthiness, ints vs floats, duplicate keys).
 */
sealed interface PyValue {
    /** Python's `bool(value)`. */
    val truthy: Boolean

    data object None : PyValue { override val truthy = false }
    data class Bool(val value: Boolean) : PyValue { override val truthy get() = value }
    data class Int(val value: BigInteger) : PyValue { override val truthy get() = value.signum() != 0 }
    data class Float(val value: Double) : PyValue { override val truthy get() = value != 0.0 }
    data class Str(val value: String) : PyValue { override val truthy get() = value.isNotEmpty() }
    data class List(val items: kotlin.collections.List<PyValue>) : PyValue { override val truthy get() = items.isNotEmpty() }

    /** Insertion-ordered; a duplicate key keeps its first position and takes the last value, as a Python dict does. */
    data class Dict(val map: LinkedHashMap<String, PyValue>) : PyValue { override val truthy get() = map.isNotEmpty() }
}

class PyJsonError(message: String, val pos: kotlin.Int) : Exception(message)

/**
 * A port of CPython's `json.scanner.py_make_scanner` + `json.decoder` (strict=True): the same grammar,
 * NaN / Infinity / -Infinity accepted (rejected with [allowConstants] = false, as `parse_constant` raising does),
 * control characters refused inside strings, and duplicate keys refused with [rejectDuplicates]
 * (`object_pairs_hook` raising does).
 */
class PyJsonParser(
    private val s: String,
    private val allowConstants: Boolean = true,
    private val rejectDuplicates: Boolean = false,
) {
    /** `JSONDecoder.raw_decode(s, idx)`: one value starting exactly at [start] (no whitespace skipped); returns it and the end index. */
    fun rawDecode(start: kotlin.Int): Pair<PyValue, kotlin.Int> = scanOnce(start, 0)

    /** `json.loads(s)`: optional whitespace, one value, optional whitespace, end of string. */
    fun loads(): PyValue {
        var i = skipWs(0)
        val (v, end) = scanOnce(i, 0)
        i = skipWs(end)
        if (i != s.length) throw PyJsonError("Extra data", i)
        return v
    }

    private fun skipWs(from: kotlin.Int): kotlin.Int {
        var i = from
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        return i
    }

    private fun scanOnce(idx: kotlin.Int, depth: kotlin.Int): Pair<PyValue, kotlin.Int> {
        if (depth > MAX_DEPTH) throw PyJsonError("too deep", idx)
        if (idx >= s.length) throw PyJsonError("Expecting value", idx)
        return when (val c = s[idx]) {
            '"' -> scanString(idx + 1).let { (str, end) -> PyValue.Str(str) to end }
            '{' -> scanObject(idx + 1, depth)
            '[' -> scanArray(idx + 1, depth)
            'n' -> if (s.startsWith("null", idx)) PyValue.None to idx + 4 else throw PyJsonError("Expecting value", idx)
            't' -> if (s.startsWith("true", idx)) PyValue.Bool(true) to idx + 4 else throw PyJsonError("Expecting value", idx)
            'f' -> if (s.startsWith("false", idx)) PyValue.Bool(false) to idx + 5 else throw PyJsonError("Expecting value", idx)
            'N' -> constant("NaN", Double.NaN, idx)
            'I' -> constant("Infinity", Double.POSITIVE_INFINITY, idx)
            else -> {
                val m = NUMBER.matchAt(s, idx)
                if (m != null) {
                    val (integer, frac, exp) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    val v = if (frac.isNotEmpty() || exp.isNotEmpty()) {
                        PyValue.Float((integer + frac + exp).toDouble())
                    } else {
                        PyValue.Int(BigInteger(integer))
                    }
                    v to m.range.last + 1
                } else if (c == '-' && s.startsWith("-Infinity", idx)) {
                    constant("-Infinity", Double.NEGATIVE_INFINITY, idx)
                } else {
                    throw PyJsonError("Expecting value", idx)
                }
            }
        }
    }

    private fun constant(name: String, value: Double, idx: kotlin.Int): Pair<PyValue, kotlin.Int> {
        if (!s.startsWith(name, idx)) throw PyJsonError("Expecting value", idx)
        if (!allowConstants) throw PyJsonError("non-finite number", idx)
        return PyValue.Float(value) to idx + name.length
    }

    /** py_scanstring with strict=True; [start] is just after the opening quote. */
    private fun scanString(start: kotlin.Int): Pair<String, kotlin.Int> {
        val out = StringBuilder()
        var i = start
        while (true) {
            if (i >= s.length) throw PyJsonError("Unterminated string", start - 1)
            val c = s[i]
            when {
                c == '"' -> return out.toString() to i + 1
                c == '\\' -> {
                    if (i + 1 >= s.length) throw PyJsonError("Unterminated string", start - 1)
                    when (val e = s[i + 1]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val uni = hex4(i + 2)
                            val next = i + 6
                            // A high surrogate followed by an escaped low surrogate becomes one code point (the same
                            // UTF-16 pair in Kotlin); a lone surrogate stays as it is, as in Python. As in CPython's C
                            // scanner, a malformed second escape is an error.
                            if (uni in 0xD800..0xDBFF && s.startsWith("\\u", next)) {
                                val low = hex4(next + 2)
                                if (low in 0xDC00..0xDFFF) {
                                    out.append(uni.toChar()).append(low.toChar())
                                    i = next + 6
                                    continue
                                }
                            }
                            out.append(uni.toChar())
                            i = next
                            continue
                        }
                        else -> throw PyJsonError("Invalid \\escape: $e", i)
                    }
                    i += 2
                }
                c.code < 0x20 -> throw PyJsonError("Invalid control character", i)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    private fun hex4(at: kotlin.Int): kotlin.Int {
        if (at + 4 > s.length) throw PyJsonError("Invalid \\uXXXX escape", at)
        val h = s.substring(at, at + 4)
        // Python's _decode_uXXXX: exactly four hex digits ("0x.." and signs are refused).
        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw PyJsonError("Invalid \\uXXXX escape", at)
        return h.toInt(16)
    }

    private fun scanObject(start: kotlin.Int, depth: kotlin.Int): Pair<PyValue, kotlin.Int> {
        val map = LinkedHashMap<String, PyValue>()
        var i = skipWs(start)
        if (i < s.length && s[i] == '}') return PyValue.Dict(map) to i + 1
        while (true) {
            if (i >= s.length || s[i] != '"') throw PyJsonError("Expecting property name enclosed in double quotes", i)
            val (key, afterKey) = scanString(i + 1)
            i = skipWs(afterKey)
            if (i >= s.length || s[i] != ':') throw PyJsonError("Expecting ':' delimiter", i)
            i = skipWs(i + 1)
            val (value, afterValue) = scanOnce(i, depth + 1)
            if (rejectDuplicates && map.containsKey(key)) throw PyJsonError("duplicate key", i)
            map[key] = value
            i = skipWs(afterValue)
            if (i < s.length && s[i] == '}') return PyValue.Dict(map) to i + 1
            if (i >= s.length || s[i] != ',') throw PyJsonError("Expecting ',' delimiter", i)
            i = skipWs(i + 1)
        }
    }

    private fun scanArray(start: kotlin.Int, depth: kotlin.Int): Pair<PyValue, kotlin.Int> {
        val items = ArrayList<PyValue>()
        var i = skipWs(start)
        if (i < s.length && s[i] == ']') return PyValue.List(items) to i + 1
        while (true) {
            val (value, after) = scanOnce(i, depth + 1)
            items += value
            i = skipWs(after)
            if (i < s.length && s[i] == ']') return PyValue.List(items) to i + 1
            if (i >= s.length || s[i] != ',') throw PyJsonError("Expecting ',' delimiter", i)
            i = skipWs(i + 1)
        }
    }

    private companion object {
        // json.scanner.NUMBER_RE
        val NUMBER = Regex("""(-?(?:0|[1-9][0-9]*))(\.[0-9]+)?([eE][-+]?[0-9]+)?""")
        const val MAX_DEPTH = 500
    }
}

/** `json.dumps(obj, ensure_ascii=False, separators=(",", ":"))` for the value types an action holds. */
object PyJsonWriter {
    fun string(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
