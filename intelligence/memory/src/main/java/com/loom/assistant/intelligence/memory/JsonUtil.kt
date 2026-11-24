package com.loom.assistant.intelligence.memory

/**
 * Minimal JSON reader/writer for the exact subset the conversation store
 * produces: objects with string/number/boolean/null values, arrays, nesting.
 * No external dependency (manual build classpath is android.jar + stdlib).
 */
internal object JsonUtil {

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

    fun messageToJson(m: ChatMessage): String {
        val img = if (m.imagePath != null) ""","imagePath":"${escape(m.imagePath)}"""" else ""
        return """{"role":"${escape(m.role)}","text":"${escape(m.text)}","timestamp":${m.timestamp},"modality":"${escape(m.modality)}"$img}"""
    }

    fun conversationToJson(c: Conversation): String {
        val msgs = c.messages.joinToString(",") { messageToJson(it) }
        return """{"id":"${escape(c.id)}","title":"${escape(c.title)}","createdAt":${c.createdAt},"updatedAt":${c.updatedAt},"messages":[$msgs]}"""
    }

    fun indexToJson(summaries: List<ConversationSummary>): String {
        val items = summaries.joinToString(",") {
            """{"id":"${escape(it.id)}","title":"${escape(it.title)}","updatedAt":${it.updatedAt},"preview":"${escape(it.preview)}","messageCount":${it.messageCount}}"""
        }
        return "[$items]"
    }

    // ---- reader ----

    fun parseConversation(json: String): Conversation {
        val p = Parser(json)
        val o = p.parseObject()
        val id = o["id"] as String
        val title = o["title"] as String
        val createdAt = (o["createdAt"] as Number).toLong()
        val updatedAt = (o["updatedAt"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val msgs = (o["messages"] as List<Map<String, Any?>>).map { m ->
            ChatMessage(
                role = m["role"] as String,
                text = m["text"] as String,
                timestamp = (m["timestamp"] as Number).toLong(),
                modality = (m["modality"] as? String) ?: "text",
                imagePath = m["imagePath"] as? String,
            )
        }.toMutableList()
        return Conversation(id, title, createdAt, updatedAt, msgs)
    }

    fun parseIndex(json: String): List<ConversationSummary> {
        if (json.isBlank()) return emptyList()
        val p = Parser(json)
        @Suppress("UNCHECKED_CAST")
        return (p.parseArray() as List<Map<String, Any?>>).map { o ->
            ConversationSummary(
                id = o["id"] as String,
                title = o["title"] as String,
                updatedAt = (o["updatedAt"] as Number).toLong(),
                preview = (o["preview"] as? String) ?: "",
                messageCount = ((o["messageCount"] as? Number)?.toInt()) ?: 0,
            )
        }
    }

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
            return when (val c = peek()) {
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
