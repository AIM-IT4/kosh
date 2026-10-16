package com.amitjha.kosh.data

/**
 * Transaction tags are stored in one column as `|Goa trip|Reimbursable|`: the outer bars let a query match a whole
 * tag with `LIKE '%|goa trip|%'` (SQLite's LIKE ignores ASCII case). Tags are trimmed, single-spaced, at most
 * [MAX_LENGTH] characters, without `|`, and compared case-insensitively.
 */
object Tags {
    const val MAX_LENGTH = 30

    fun clean(tag: String): String = tag.replace("|", " ").replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH).trim()

    fun decode(stored: String): List<String> = stored.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun encode(tags: Collection<String>): String {
        val unique = LinkedHashMap<String, String>()
        tags.map(::clean).filter { it.isNotEmpty() }.forEach { unique.putIfAbsent(it.lowercase(), it) }
        return if (unique.isEmpty()) "" else unique.values.joinToString("|", prefix = "|", postfix = "|")
    }

    /** The LIKE pattern matching transactions carrying [tag]. */
    fun pattern(tag: String): String = "%|" + clean(tag).replace("%", "").replace("_", "") + "|%"

    fun has(stored: String, tag: String): Boolean = decode(stored).any { it.equals(clean(tag), ignoreCase = true) }
}
