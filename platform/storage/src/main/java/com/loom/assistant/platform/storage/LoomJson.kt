package com.loom.assistant.platform.storage

/**
 * Minimal public JSON reader/writer for the file-backed stores.
 * Supports objects, arrays, strings, numbers, booleans, null.
 * No external dependency (manual build classpath is android.jar + stdlib).
 */
object LoomJson {

    fun escape(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        return b.toString()
    }

    fun str(s: String): String = "\"${escape(s)}\""

    fun obj(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "\"${escape(k)}\":$v" }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    fun parseObject(json: String): Map<String, Any?> = Parser(json).parseObject()

    fun parseArray(json: String): List<Any?> = Parser(json).parseArray()

    @Suppress("UNCHECKED_CAST")
    fun objList(m: Map<String, Any?>, key: String): List<Map<String, Any?>> =
        (m[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

    fun getStr(m: Map<String, Any?>, key: String, default: String = ""): String =
        m[key] as? String ?: default

    fun getLong(m: Map<String, Any?>, key: String, default: Long = 0L): Long =
        (m[key] as? Number)?.toLong() ?: default

    fun getInt(m: Map<String, Any?>, key: String, default: Int = 0): Int =
        (m[key] as? Number)?.toInt() ?: default

    fun getBool(m: Map<String, Any?>, key: String, default: Boolean = false): Boolean =
        m[key] as? Boolean ?: default

    private class Parser(val s: String) {
        var i = 0

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs(); expect(':'); skipWs()
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("bad object at $i")
                }
            }
        }

        fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("bad array at $i")
                }
            }
        }

        fun parseValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectLit("true"); true }
                'f' -> { expectLit("false"); false }
                'n' -> { expectLit("null"); null }
                else -> parseNumber()
            }
        }

        fun parseString(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }

        fun parseNumber(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("eof")
        fun expect(c: Char) { if (s[i++] != c) throw IllegalArgumentException("expected $c at ${i - 1}") }
        fun expectLit(lit: String) { if (!s.startsWith(lit, i)) throw IllegalArgumentException("expected $lit at $i"); i += lit.length }
    }
}
