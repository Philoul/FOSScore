package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Lyric
import com.musigatto.fosscore.pdfscore.music.Lyric.Syllabic
import kotlin.math.abs

/**
 * Reads the lyrics of a system and gives each syllable to the note it is sung on.
 * - A text is a lyric unless it is only digits (a measure number), a tempo's "= 62", a direction
 *   ("Rall."), or written left of the staves (an instrument's name).
 * - It belongs to the top staff when written over it (a song's words over its accompaniment), else
 *   to the staff it is written under.
 * - Words close together come as one run: each word's place is estimated from its characters, and
 *   it goes to the nearest note of the staff's upper voice still free, left to right.
 * - A syllable ending with a hyphen (or followed by one) begins or goes on a word; the next one goes
 *   on or ends it.
 */
object Lyrics {
    fun attach(page: PageDrawing, staves: List<Pair<StaffReading, List<MeasureReading>>>): List<Pair<StaffReading, List<MeasureReading>>> {
        val top = staves.firstOrNull()?.first?.staff ?: return staves
        val byStaff = page.texts.filter { lyricLike(it, top) }.groupBy { text -> staffOf(text, staves) }
        return staves.mapIndexed { s, (reading, measures) ->
            val texts = byStaff[s] ?: return@mapIndexed reading to measures
            val sung = sung(texts.sortedBy { it.x }, measures, reading.staff.space)
            reading to measures.map { measure ->
                measure.copy(events = measure.events.map { placed -> sung[placed]?.let { placed.copy(event = (placed.event as Chord).copy(lyric = it)) } ?: placed })
            }
        }
    }

    /** The staff (index) a lyric belongs to: the top one when over it, else the one it is under; -1 for none. */
    private fun staffOf(text: DrawnText, staves: List<Pair<StaffReading, List<MeasureReading>>>): Int {
        val top = staves.first().first.staff
        if (text.y < top.top - 0.3 * top.space && text.y >= top.top - BAND * top.space) return 0
        return staves.withIndex()
            .filter { (_, s) -> text.y > s.first.staff.bottom + 0.5 * s.first.staff.space && text.y <= s.first.staff.bottom + BAND * s.first.staff.space }
            .minByOrNull { (_, s) -> text.y - s.first.staff.bottom }?.index ?: -1
    }

    private fun lyricLike(text: DrawnText, staff: Staff): Boolean {
        val words = text.text.trim()
        // a line read from the pixels (OutlineText) is the words over the music, never a lyric
        return text.font != OutlineText.FONT && words.isNotEmpty() && text.x >= staff.left - staff.space && !words.all { it.isDigit() } &&
            !words.startsWith("=") && !Directions.isWords(words)
    }

    /** One word of a run: its text and the x of its middle, estimated from the characters before it. */
    private class Word(val text: String, val x: Double)

    private fun words(text: DrawnText): List<Word> {
        val char = CHAR_WIDTH * text.size
        // a font's ligatures ("ﬁ" as one glyph) are their letters
        val letters = java.text.Normalizer.normalize(text.text, java.text.Normalizer.Form.NFKC)
        return Regex("""\S+""").findAll(letters).map { Word(it.value, text.x + (it.range.first + it.value.length / 2.0) * char) }.toList()
    }

    /** Each word to the nearest free note of the upper voice, left to right; then the syllables. */
    private fun sung(texts: List<DrawnText>, measures: List<MeasureReading>, space: Double): Map<PlacedEvent, Lyric> {
        val notes = measures.flatMap { it.events }.filter { (it.event as? Chord)?.let { c -> !c.grace } == true }
        val upper = notes.minOfOrNull { it.event.voice } ?: return emptyMap()
        val melody = notes.filter { it.event.voice == upper }.sortedBy { it.box.centerX }
        val taken = mutableMapOf<PlacedEvent, String>()
        var after = Double.NEGATIVE_INFINITY
        val order = mutableListOf<Pair<PlacedEvent?, String>>()
        for (word in texts.flatMap { words(it) }) {
            // a lone hyphen between syllables only says the word goes on
            if (word.text.all { it == '-' }) {
                order += null to "-"
                continue
            }
            val note = melody.filter { it !in taken && it.box.centerX > after && abs(it.box.centerX - word.x) <= REACH * space }
                .minByOrNull { abs(it.box.centerX - word.x) }
            note?.let {
                taken[it] = word.text
                after = it.box.centerX
            }
            order += note to word.text
        }
        val result = mutableMapOf<PlacedEvent, Lyric>()
        var inWord = false
        order.forEachIndexed { i, (note, text) ->
            if (note == null) {
                if (text == "-") inWord = true
                return@forEachIndexed
            }
            val goesOn = text.endsWith("-") || order.getOrNull(i + 1)?.second == "-"
            val syllabic = when {
                inWord && goesOn -> Syllabic.MIDDLE
                inWord -> Syllabic.END
                goesOn -> Syllabic.BEGIN
                else -> Syllabic.SINGLE
            }
            result[note] = Lyric(text.trimEnd('-'), syllabic)
            inWord = goesOn
        }
        return result
    }

    /** How far from its staff (in spaces) a lyric is written. */
    private const val BAND = 5.0

    /** A character's width, as a share of the text's size (a guess good enough to tell words apart). */
    private const val CHAR_WIDTH = 0.5

    /** How far from a note (in spaces) the middle of its syllable may be. */
    private const val REACH = 3.0
}
