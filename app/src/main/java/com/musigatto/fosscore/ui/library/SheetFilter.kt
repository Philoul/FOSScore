package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Credits
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.normalize

/**
 * The scores whose details hold every word of [query]: title, genre, key, comment, and by score id
 * their [tags] and the people they are by ([credits]).
 */
fun filterSheets(
    sheets: List<Sheet>,
    query: String,
    tags: Map<Long, List<String>> = emptyMap(),
    credits: Map<Long, Credits> = emptyMap(),
): List<Sheet> {
    val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return sheets
    return sheets.filter { sheet ->
        val people = credits[sheet.id]?.let { it.composers + it.performers }.orEmpty()
        val haystack = normalize(
            (listOf(sheet.title, sheet.genre, sheet.musicalKey, sheet.comment) + tags[sheet.id].orEmpty() + people)
                .joinToString(" ")
        )
        tokens.all { haystack.contains(it) }
    }
}
