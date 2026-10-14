package com.amitjha.kosh.mail

/** Converts e-mail HTML into readable plain text and extracts label/value pairs from tables. */
object HtmlText {
    private val dropBlocks = Regex("(?is)<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val lineBreakTags = Regex("(?i)<\\s*br\\s*/?\\s*>|</\\s*(p|div|tr|li|h[1-6]|table|ul|ol|section|article)\\s*>|<\\s*(p|div|tr|li|table)\\b[^>]*>")
    private val cellEnd = Regex("(?i)</\\s*t[dh]\\s*>")
    private val tags = Regex("(?s)<[^>]+>")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
    private val namedEntities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&apos;" to "'", "&rupee;" to "₹", "&ndash;" to "-", "&mdash;" to "-", "&rsquo;" to "'",
        "&lsquo;" to "'", "&ldquo;" to "\"", "&rdquo;" to "\"", "&bull;" to "•", "&copy;" to "©",
        "&reg;" to "®", "&trade;" to "™", "&hellip;" to "...", "&zwnj;" to "", "&zwj;" to "",
    )

    /** Plain text with one line per block element and table cells separated by " | ". */
    fun toText(html: String): String {
        var s = comments.replace(html, " ")
        s = dropBlocks.replace(s, " ")
        s = cellEnd.replace(s, " | ")
        s = lineBreakTags.replace(s, "\n")
        s = tags.replace(s, " ")
        s = decodeEntities(s)
        return s.lines()
            .map { line -> line.replace(' ', ' ').replace(Regex("[ \\t]+"), " ").trim().trim('|').trim() }
            .filter { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() || c == '₹' } }
            .joinToString("\n")
    }

    fun decodeEntities(text: String): String {
        var s = numericEntity.replace(text) { m ->
            val radix = if (m.groupValues[1].isNotEmpty()) 16 else 10
            val code = m.groupValues[2].toIntOrNull(radix)
            if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        for ((k, v) in namedEntities) s = s.replace(k, v, ignoreCase = true)
        return s
    }

    /**
     * Label/value pairs from HTML tables laid out as a header row followed by a value row
     * (common in statement summaries), e.g. "Total Amount Due" over "12,345.00".
     */
    fun tablePairs(html: String): List<Pair<String, String>> {
        val cleaned = dropBlocks.replace(comments.replace(html, " "), " ")
        val rows = Regex("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>").findAll(cleaned).map { row ->
            Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>").findAll(row.groupValues[1]).map { cell ->
                decodeEntities(tags.replace(cell.groupValues[1], " "))
                    .replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
            }.toList()
        }.filter { it.isNotEmpty() }.toList()

        val pairs = ArrayList<Pair<String, String>>()
        for (i in 0 until rows.size - 1) {
            val header = rows[i]
            val values = rows[i + 1]
            if (header.size >= 2 && header.size == values.size &&
                header.all { it.isNotEmpty() && !it.any(Char::isDigit) } &&
                values.count { it.any(Char::isDigit) } >= header.size / 2
            ) {
                header.zip(values).forEach { (h, v) -> if (v.isNotEmpty()) pairs.add(h to v) }
            }
        }
        return pairs
    }
}
