package com.amitjha.kosh.sms

/**
 * The community-maintained merchant → category dataset bundled as `kosh/merchants.json`.
 *
 * Format (see docs/DATASET.md): `{"schemaVersion": 1, "rules": [{"category": "food", "brands": [...],
 * "generic": [...]}]}`. Rules are checked in order and the first match wins; patterns are case-insensitive
 * regular expressions. Loading never throws: on any problem [load] returns null and the built-in rules apply.
 */
object MerchantDataset {
    const val RESOURCE = "/kosh/merchants.json"
    const val SUPPORTED_SCHEMA = 1

    data class RuleSpec(val category: String, val brands: List<String>, val generic: List<String>)

    data class Dataset(val schemaVersion: Int, val rules: List<RuleSpec>)

    /** The bundled dataset, or null if it is missing, malformed or of an unsupported schema. */
    fun load(): Dataset? = runCatching {
        val text = MerchantDataset::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return null
        parse(text)
    }.getOrNull()

    /** Parses and validates a dataset; throws [IllegalArgumentException] with a readable reason if invalid. */
    fun parse(json: String): Dataset {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Top level must be an object")
        val version = (root["schemaVersion"] as? Number)?.toInt() ?: throw IllegalArgumentException("Missing schemaVersion")
        require(version == SUPPORTED_SCHEMA) { "Unsupported schemaVersion $version" }
        val rules = (root["rules"] as? List<*>) ?: throw IllegalArgumentException("Missing rules array")
        val specs = rules.mapIndexed { i, r ->
            val m = r as? Map<*, *> ?: throw IllegalArgumentException("Rule $i must be an object")
            val category = m["category"] as? String ?: throw IllegalArgumentException("Rule $i has no category")
            require(category in CategoryGuesser.CATEGORY_KEYS) { "Rule $i: unknown category '$category'" }
            val brands = strings(m["brands"], "Rule $i brands")
            val generic = strings(m["generic"] ?: emptyList<String>(), "Rule $i generic")
            require(brands.isNotEmpty()) { "Rule $i ($category) has no brands" }
            (brands + generic).forEach { p ->
                runCatching { Regex(p) }.onFailure { throw IllegalArgumentException("Rule $i ($category): bad pattern '$p'") }
            }
            RuleSpec(category, brands, generic)
        }
        require(specs.isNotEmpty()) { "No rules" }
        return Dataset(version, specs)
    }

    private fun strings(value: Any?, what: String): List<String> {
        val list = value as? List<*> ?: throw IllegalArgumentException("$what must be an array")
        return list.map { it as? String ?: throw IllegalArgumentException("$what must contain strings") }.filter { it.isNotBlank() }
    }
}

/** A minimal JSON reader (objects, arrays, strings, numbers, booleans, null) so core/sms stays dependency-free. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.i == text.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "Unexpected end of input" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Unexpected token at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val k = str()
                require(k !in out) { "Duplicate key '$k'" }
                skipWs()
                require(s[i] == ':') { "Expected ':' at $i" }
                i++
                out[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipWs()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("Bad escape \\$e at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            require(t.isNotEmpty()) { "Unexpected character at $start" }
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}
