package com.amitjha.kosh.mail

/**
 * Minimal JSON reader for Gmail API responses: objects become [Map], arrays [List], strings [String],
 * numbers [Long] or [Double], plus [Boolean] and null.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Trailing characters at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Bad literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                expect(':')
                map[key] = value()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected , or } at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(value())
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected , or ] at $pos")
                }
            }
        }

        private fun expect(c: Char) {
            if (atEnd() || s[pos] != c) throw IllegalArgumentException("Expected '$c' at $pos")
            pos++
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()

internal fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()
