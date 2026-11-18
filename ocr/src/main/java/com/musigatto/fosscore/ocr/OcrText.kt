package com.musigatto.fosscore.ocr

/** What a line of text is expected to hold: words, or digits only (a measure number). */
enum class TextKind { LINE, DIGITS }

/** What is read, cleaned of the usual misreadings of the text of a score. */
object OcrText {
    // the letters a digit is often read as, in a printed score's fonts
    private val AS_DIGIT = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'l' to '1', 'I' to '1', '|' to '1', 'i' to '1', 'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2')
    private val SPACES = Regex("""\s+""")
    private val PER_MINUTE = Regex("""=\s*(?:c\.?\s*)?([0-9OoDlI|iSsBZz]{2,3})\b""")

    /** [text] as read for a [kind] of line, or null when nothing is left of it. */
    fun clean(text: String, kind: TextKind): String? = when (kind) {
        TextKind.DIGITS -> text.mapNotNull { c -> if (c.isDigit()) c else AS_DIGIT[c] }.joinToString("")
        TextKind.LINE -> text.trim().replace(SPACES, " ")
    }.takeIf { it.isNotEmpty() }

    /** The beats a minute of a tempo mark read as "= 70" (the note before it is a symbol, read apart). */
    fun perMinute(text: String): Int? =
        PER_MINUTE.find(text)?.groupValues?.get(1)?.let { clean(it, TextKind.DIGITS) }?.toIntOrNull()?.takeIf { it in 20..400 }
}
