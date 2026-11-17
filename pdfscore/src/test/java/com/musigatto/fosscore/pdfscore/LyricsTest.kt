package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Chord
import com.musigatto.fosscore.pdfscore.music.Lyric
import com.musigatto.fosscore.pdfscore.music.Lyric.Syllabic
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    private val space = 5.0
    private val top = 100.0
    private val g4 = top + 3 * space

    private val staffLines = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }
    private val clef = DrawnGlyph("gClef", Box(55.0, top - 1.5 * space, 55.0 + 2.7 * space, top + 5.5 * space))

    private fun head(x: Double) = DrawnGlyph("noteheadBlack", Box(x, g4 - space / 2, x + 1.3 * space, g4 + space / 2))
    private fun stem(head: DrawnGlyph) = DrawnLine(head.box.right, head.box.centerY, head.box.right, head.box.centerY - 3.5 * space, 0.5)

    /** Each note's lyric, left to right, null when it has none. */
    private fun sung(heads: List<DrawnGlyph>, texts: List<DrawnText>): List<Lyric?> {
        val page = PageDrawing(600.0, 800.0, listOf(clef) + heads, staffLines + heads.map { stem(it) }, emptyList(), texts)
        val reading = StaffNotes.read(page, Staves.find(page)).single().single()
        val attached = Lyrics.attach(page, listOf(reading to Rhythms.read(page, reading)))
        return attached.single().second.flatMap { m -> m.events.map { (it.event as Chord).lyric } }
    }

    /** A syllable written under each note: the hyphen at a syllable's end says the word goes on. */
    @Test
    fun syllablesUnderTheNotesAreTheirLyrics() {
        val heads = listOf(head(100.0), head(130.0), head(160.0))
        val under = top + 4 * space + 3 * space
        val texts = listOf(
            DrawnText("Lu-", "font_1", 99.0, under, 10.0),
            DrawnText("cie", "font_1", 129.0, under, 10.0),
            DrawnText("vit,", "font_1", 160.0, under, 10.0),
        )
        assertEquals(
            listOf(Lyric("Lu", Syllabic.BEGIN), Lyric("cie", Syllabic.END), Lyric("vit,", Syllabic.SINGLE)),
            sung(heads, texts),
        )
    }

    /** Lyrics over a piano staff (a song's words written over its accompaniment) go with the notes under them. */
    @Test
    fun wordsOverTheStaffAreItsLyrics() {
        val heads = listOf(head(100.0), head(130.0))
        val over = top - 2 * space
        val texts = listOf(DrawnText("je", "font_1", 100.0, over, 10.0), DrawnText("sais", "font_1", 128.0, over, 10.0))
        assertEquals(listOf(Lyric("je", Syllabic.SINGLE), Lyric("sais", Syllabic.SINGLE)), sung(heads, texts))
    }

    /** Words close together come as one run of text: each word goes to the note it stands by. */
    @Test
    fun aRunOfWordsIsSplitOverItsNotes() {
        val heads = listOf(head(100.0), head(115.0), head(140.0))
        val under = top + 4 * space + 3 * space
        val texts = listOf(DrawnText("on  vit,  Lu-", "font_1", 99.0, under, 10.0), DrawnText("cie", "font_1", 139.0, under, 10.0))
        assertEquals(
            listOf(Lyric("on", Syllabic.SINGLE), Lyric("vit,", Syllabic.SINGLE), Lyric("Lu", Syllabic.BEGIN)),
            sung(heads, texts).take(3),
        )
    }

    /** A font's ligature ("ﬁ" as one glyph) is read as its letters: the lyric is the word. */
    @Test
    fun ligaturesAreTheirLetters() {
        val heads = listOf(head(100.0))
        val texts = listOf(DrawnText("ﬁn", "font_1", 100.0, top + 7 * space, 10.0))
        assertEquals(listOf(Lyric("fin", Syllabic.SINGLE)), sung(heads, texts))
    }

    /** A measure number, a tempo's "= 62", a direction and an instrument's name are no lyrics. */
    @Test
    fun numbersTemposDirectionsAndNamesAreNoLyrics() {
        val heads = listOf(head(100.0), head(130.0))
        val over = top - 2 * space
        val texts = listOf(
            DrawnText("23", "font_1", 52.0, over, 8.0),
            DrawnText("= 62", "font_4", 100.0, over, 12.0),
            DrawnText("Rall.", "font_4", 128.0, over, 12.0),
            DrawnText("Pia.", "font_1", 20.0, g4, 12.0),
        )
        assertEquals(listOf<Lyric?>(null, null), sung(heads, texts))
    }
}
