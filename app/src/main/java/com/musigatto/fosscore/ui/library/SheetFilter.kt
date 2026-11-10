package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.normalize

/** The scores whose details hold every word of [query]; [tags] are each score's tags, by id. */
fun filterSheets(sheets: List<Sheet>, query: String, tags: Map<Long, List<String>> = emptyMap()): List<Sheet> {
    val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return sheets
    return sheets.filter { sheet ->
        val haystack = normalize(
            (listOf(sheet.title, sheet.composer, sheet.genre, sheet.musicalKey) + tags[sheet.id].orEmpty())
                .joinToString(" ")
        )
        tokens.all { haystack.contains(it) }
    }
}
