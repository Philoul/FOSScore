package com.musigatto.fosscore.pdfscore

/**
 * The tags of an SVG document, in order, each with its attributes: enough to read MuPDF's SVG
 * without an XML library (a DOM is slow on a weak device). Text between tags, comments, the XML
 * declaration and the doctype are skipped; attribute values have their character references
 * decoded ("&#xe0a4;", "&amp;").
 */
internal object SvgTags {
    /** A tag: an opening one, a [closing] one ("</g>"), or a [selfClosing] one ("<path …/>"). */
    class Tag(val name: String, private val attributes: Map<String, String>, val closing: Boolean, val selfClosing: Boolean) {
        /** The attribute's value, "" when absent. */
        fun get(attribute: String): String = attributes[attribute].orEmpty()
    }

    fun scan(text: String): List<Tag> {
        val tags = ArrayList<Tag>()
        var at = text.indexOf('<')
        while (at >= 0 && at < text.length) {
            when {
                text.startsWith("<!--", at) -> {
                    at = text.indexOf("-->", at).let { if (it < 0) text.length else it + 3 }
                    at = text.indexOf('<', at)
                    continue
                }
                text.startsWith("<?", at) || text.startsWith("<!", at) -> {
                    at = text.indexOf('<', text.indexOf('>', at).let { if (it < 0) text.length else it })
                    continue
                }
            }
            var i = at + 1
            val closing = i < text.length && text[i] == '/'
            if (closing) i++
            val nameStart = i
            while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
            val name = text.substring(nameStart, i)
            val attributes = HashMap<String, String>()
            var selfClosing = false
            while (i < text.length && text[i] != '>') {
                val c = text[i]
                when {
                    c == '/' -> {
                        selfClosing = true
                        i++
                    }
                    c.isWhitespace() -> i++
                    else -> {
                        val keyStart = i
                        while (i < text.length && text[i] != '=' && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
                        val key = text.substring(keyStart, i)
                        while (i < text.length && text[i].isWhitespace()) i++
                        if (i < text.length && text[i] == '=') {
                            i++
                            while (i < text.length && text[i].isWhitespace()) i++
                            val quote = text.getOrNull(i)
                            if (quote == '"' || quote == '\'') {
                                val end = text.indexOf(quote, i + 1).let { if (it < 0) text.length else it }
                                attributes[key] = decode(text.substring(i + 1, end))
                                i = end + 1
                            }
                        } else {
                            attributes[key] = ""
                        }
                    }
                }
            }
            tags += Tag(name, attributes, closing, selfClosing)
            at = text.indexOf('<', i)
        }
        return tags
    }

    /** A value with its character references decoded. */
    private fun decode(value: String): String {
        if (value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val end = if (c == '&') value.indexOf(';', i) else -1
            if (end < 0) {
                out.append(c)
                i++
                continue
            }
            val entity = value.substring(i + 1, end)
            val code = when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                else -> NAMED[entity]
            }
            if (code == null) {
                out.append(c)
                i++
            } else {
                out.appendCodePoint(code)
                i = end + 1
            }
        }
        return out.toString()
    }

    private val NAMED = mapOf("amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code, "apos" to '\''.code)
}
