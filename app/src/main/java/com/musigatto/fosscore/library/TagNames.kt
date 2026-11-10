package com.musigatto.fosscore.library

import java.text.Normalizer

private val MARKS = Regex("\\p{M}")

/** Case and accents folded: "Tángo" and "tango" are the same word for the search and the tags. */
fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

/** Two spellings of one tag ("Rock", "rock", "rôck") name the same tag. */
fun sameTag(a: String, b: String): Boolean = normalize(a.trim()) == normalize(b.trim())

/** [names] trimmed, without blanks and without a second spelling of a tag: the first one stays. */
fun distinctTagNames(names: List<String>): List<String> =
    names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { normalize(it) }

/**
 * The tags a `sheets.tags` text holds. Before v5 they were written "duo, practice" (a tag could
 * hold a space); the tag field then wrote them separated by spaces. A text with a comma or a
 * semicolon is the old form, so only those split it.
 */
fun splitTagText(text: String): List<String> {
    val parts = if (text.any { it == ',' || it == ';' }) text.split(',', ';') else text.split(Regex("\\s+"))
    return distinctTagNames(parts)
}

/**
 * The tags table built from every score's text (v9): one tag per name however many scores carry
 * it or however it is spelled, the first spelling met (scores in [sheets] order) as its name.
 */
object TagBackfill {
    /** [names] become tags 1..n; each link is a score's id and the index of its tag in [names]. */
    data class Plan(val names: List<String>, val links: List<Pair<Long, Int>>)

    fun of(sheets: List<Pair<Long, String>>): Plan {
        val names = mutableListOf<String>()
        val index = HashMap<String, Int>()
        val links = mutableListOf<Pair<Long, Int>>()
        for ((sheetId, text) in sheets) {
            for (name in splitTagText(text)) {
                val at = index.getOrPut(normalize(name)) { names.add(name); names.size - 1 }
                links += sheetId to at
            }
        }
        return Plan(names, links)
    }
}
