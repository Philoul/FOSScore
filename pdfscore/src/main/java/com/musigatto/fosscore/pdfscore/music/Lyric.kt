package com.musigatto.fosscore.pdfscore.music

/**
 * A syllable sung on a note: its [text], and whether it is a word of its own or begins, goes on or
 * ends a word cut by hyphens ("Lu-cie": begin, end).
 */
data class Lyric(val text: String, val syllabic: Syllabic) {
    enum class Syllabic(val musicXml: String) { SINGLE("single"), BEGIN("begin"), MIDDLE("middle"), END("end") }
}
