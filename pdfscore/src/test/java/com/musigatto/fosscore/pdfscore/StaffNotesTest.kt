package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
import com.musigatto.fosscore.pdfscore.music.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class StaffNotesTest {
    private val space = 5.0

    private fun staffLines(top: Double) = (0..4).map { DrawnLine(50.0, top + it * space, 550.0, top + it * space, 0.5) }

    /** A symbol whose reference line, [fraction] down its box of [height] spaces, is at [y]. */
    private fun glyph(name: String, x: Double, y: Double, height: Double, fraction: Double, width: Double = 1.2) =
        DrawnGlyph(name, Box(x, y - fraction * height * space, x + width * space, y + (1 - fraction) * height * space))

    private fun head(x: Double, y: Double, name: String = "noteheadBlack") = glyph(name, x, y, 1.0, 0.5, 1.3)
    private fun gClef(x: Double, staffTop: Double) = glyph("gClef", x, staffTop + 3 * space, 7.0, 0.64, 2.7)
    private fun fClef(x: Double, staffTop: Double) = glyph("fClef", x, staffTop + space, 3.5, 0.31, 2.7)
    private fun sharp(x: Double, y: Double) = glyph("accidentalSharp", x, y, 2.7, 0.5, 1.0)
    private fun flat(x: Double, y: Double) = glyph("accidentalFlat", x, y, 2.5, 0.72, 0.9)
    private fun natural(x: Double, y: Double) = glyph("accidentalNatural", x, y, 2.7, 0.5, 0.7)
    private fun barline(x: Double, top: Double, bottom: Double) = DrawnLine(x, top, x, bottom, 0.8)

    private fun read(lines: List<DrawnLine>, glyphs: List<DrawnGlyph>): List<List<StaffReading>> {
        val page = PageDrawing(600.0, 800.0, glyphs, lines, emptyList(), emptyList())
        return StaffNotes.read(page, Staves.find(page))
    }

    @Test
    fun pitchesOnATrebleStaff() {
        val top = 100.0
        val notes = listOf(
            head(100.0, top + 4 * space), // bottom line: E4
            head(120.0, top + 3.5 * space), // F4
            head(140.0, top), // top line: F5
            head(160.0, top + 5 * space), // first ledger line below: C4
        )
        val staff = read(staffLines(top), listOf(gClef(55.0, top)) + notes).single().single()
        assertEquals(Clef(Clef.Sign.G, 2), staff.clef)
        assertEquals(listOf("E4", "F4", "F5", "C4"), staff.notes.map { it.pitch.toString() })
    }

    @Test
    fun pitchesOnABassStaff() {
        val top = 100.0
        val staff = read(staffLines(top), listOf(fClef(55.0, top), head(100.0, top), head(120.0, top + 4 * space)))
            .single().single()
        assertEquals(Clef(Clef.Sign.F, 4), staff.clef)
        assertEquals(listOf("A3", "G2"), staff.notes.map { it.pitch.toString() })
    }

    @Test
    fun theKeyAndAccidentalsHoldUntilTheBarline() {
        val top = 100.0
        val f5 = top
        val glyphs = listOf(
            gClef(55.0, top),
            sharp(70.0, f5), // key: F sharp
            head(100.0, f5), // F#5 by the key
            natural(115.0, f5), head(122.0, f5), // F5
            head(140.0, f5), // still F5 in this measure
            head(180.0, f5), // F#5 again after the barline
            head(200.0, top + 3.5 * space), // F4: the key's sharp holds on every octave
        )
        val staff = read(staffLines(top) + barline(160.0, top, top + 4 * space), glyphs).single().single()
        assertEquals(Key(1), staff.key)
        assertEquals(listOf(160.0), staff.barlines)
        assertEquals(listOf("F#5", "F5", "F5", "F#5", "F#4"), staff.notes.map { it.pitch.toString() })
        assertEquals(listOf(0, 0, 0, 1, 1), staff.notes.map { it.measure })
        // the accidental drawn before a note, what a renderer must draw again: only the natural
        assertEquals(listOf(null, 0, null, null, null), staff.notes.map { it.accidental })
    }

    /** A section's barline drawn at the start of a system, after the clef and key, closes no measure: nothing came before it. */
    @Test
    fun aBarlineRightAfterTheKeyClosesNoMeasure() {
        val top = 100.0
        val bottom = top + 4 * space
        val glyphs = listOf(gClef(55.0, top), sharp(70.0, top), head(100.0, top + 3 * space), head(150.0, top + 3 * space))
        val staff = read(staffLines(top) + barline(82.0, top, bottom) + barline(130.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(130.0), staff.barlines)
        assertEquals(listOf(0, 1), staff.notes.map { it.measure })
        assertEquals(Key(1), staff.key)
    }

    @Test
    fun aFlatKeyIsReadFromTheFlatsReferenceLine() {
        val top = 100.0
        val b4 = top + 2 * space
        val e5 = top + 0.5 * space
        val glyphs = listOf(gClef(55.0, top), flat(70.0, b4), flat(76.0, e5), head(100.0, b4), head(120.0, e5))
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(Key(-2), staff.key)
        assertEquals(listOf("Bb4", "Eb5"), staff.notes.map { it.pitch.toString() })
    }

    @Test
    fun aNoteBetweenTheStavesOfAGrandStaffGoesToTheCloserOne() {
        val upper = 100.0
        val lower = 150.0
        val lines = staffLines(upper) + staffLines(lower) + barline(50.0, upper, lower + 4 * space)
        val glyphs = listOf(
            gClef(55.0, upper), fClef(55.0, lower),
            head(100.0, upper + 5 * space), // C4 just under the treble staff
            head(120.0, lower - space), // C4 just over the bass staff
        )
        val system = read(lines, glyphs).single()
        assertEquals(listOf("C4"), system[0].notes.map { it.pitch.toString() })
        assertEquals(listOf("C4"), system[1].notes.map { it.pitch.toString() })
    }

    /** A note on ledger lines over a system is that system's, not the one above it (also within reach). */
    @Test
    fun aNoteBetweenTwoSystemsGoesToTheCloserStaff() {
        val upper = 100.0
        val lower = 160.0 // a separate system: no line joins them
        val glyphs = listOf(
            gClef(55.0, upper), gClef(55.0, lower),
            head(100.0, lower - 3 * space), // on the third ledger line over the lower staff: E6
        )
        val systems = read(staffLines(upper) + staffLines(lower), glyphs)
        assertEquals(2, systems.size)
        assertEquals(emptyList<String>(), systems[0].single().notes.map { it.pitch.toString() })
        assertEquals(listOf("E6"), systems[1].single().notes.map { it.pitch.toString() })
    }

    /**
     * A chord's top note over the bass staff can be closer to the treble staff: it stays with the
     * notes on its stem.
     */
    @Test
    fun aChordNoteBetweenTheStavesStaysWithItsChord() {
        val upper = 100.0
        val lower = 135.0 // three spaces under the treble staff
        val stem = DrawnLine(100.0 + 1.3 * space, lower + 2 * space, 100.0 + 1.3 * space, lower - 5.5 * space, 0.5)
        val lines = staffLines(upper) + staffLines(lower) + barline(50.0, upper, lower + 4 * space) + stem
        val glyphs = listOf(
            gClef(55.0, upper), fClef(55.0, lower),
            head(100.0, lower + 2 * space), // D3
            head(100.0, lower - space), // C4, a ledger line over the bass staff
            head(100.0, lower - 2 * space), // E4 (one space under the treble staff, two over the bass one)
        )
        val system = read(lines, glyphs).single()
        assertEquals(emptyList<String>(), system[0].notes.map { it.pitch.toString() })
        assertEquals(listOf("D3", "C4", "E4"), system[1].notes.map { it.pitch.toString() })
    }

    /** A lone note between the staves whose stem runs down into the bass staff is the bass staff's. */
    @Test
    fun aNoteBetweenTheStavesGoesWhereItsStemRuns() {
        val upper = 100.0
        val lower = 135.0
        val head = head(100.0, upper + 4 * space + 1.5 * space) // 1.5 spaces under the treble staff, 2 over the bass one
        val stem = DrawnLine(100.0, head.box.centerY, 100.0, lower + 1.5 * space, 0.5)
        val lines = staffLines(upper) + staffLines(lower) + barline(50.0, upper, lower + 4 * space) + stem
        val system = read(lines, listOf(gClef(55.0, upper), fClef(55.0, lower), head)).single()
        assertEquals(emptyList<String>(), system[0].notes.map { it.pitch.toString() })
        assertEquals(listOf("D4"), system[1].notes.map { it.pitch.toString() })
    }

    /** "8" over the staff and its dashed line: the notes under it sound (and are written in MusicXML) an octave up. */
    @Test
    fun anOttavaRaisesTheNotesUnderItsLine() {
        val top = 100.0
        val ottava = DrawnGlyph("ottava", Box(95.0, top - 3.5 * space, 100.0, top - 2 * space))
        val dashes = (0 until 6).map { i -> DrawnLine(102.0 + i * 8, top - 3.4 * space, 106.0 + i * 8, top - 3.4 * space, 0.5) }
        val hook = DrawnLine(146.0, top - 3.4 * space, 146.0, top - 2.6 * space, 0.5)
        val glyphs = listOf(gClef(55.0, top), ottava, head(100.0, top), head(140.0, top), head(170.0, top))
        val staff = read(staffLines(top) + dashes + hook, glyphs).single().single()
        assertEquals(listOf("F6", "F6", "F5"), staff.notes.map { it.pitch.toString() })
        // how far each sounds from where it is drawn: what MusicXML's octave-shift says
        assertEquals(listOf(1, 1, 0), staff.notes.map { it.octaves })
    }

    @Test
    fun aStemAlongANoteIsNoBarline() {
        val top = 100.0
        val glyphs = listOf(gClef(55.0, top), head(100.0, top + 4 * space))
        val stem = DrawnLine(100.0 + 1.3 * space, top + 4 * space, 100.0 + 1.3 * space, top - 0.5 * space, 0.5)
        val staff = read(staffLines(top) + stem, glyphs).single().single()
        assertEquals(emptyList<Double>(), staff.barlines)
    }

    /** A cautionary parenthesis around a note is drawn with the noteheads' glyphs, but is no note. */
    @Test
    fun aParenthesisAroundANoteIsNoNote() {
        val top = 100.0
        val glyphs = listOf(
            gClef(55.0, top),
            glyph("noteheadParenthesisLeft", 97.0, top + 4 * space, 2.0, 0.5, 0.5),
            head(100.0, top + 4 * space),
            glyph("noteheadParenthesisRight", 107.0, top + 4 * space, 2.0, 0.5, 0.5),
        )
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(listOf("E4"), staff.notes.map { it.pitch.toString() })
    }

    /** A key drawn after a barline in the middle of a system holds from there, over the next barlines. */
    @Test
    fun aKeyChangeAtABarlineHoldsFromThere() {
        val top = 100.0
        val bottom = top + 4 * space
        val c5 = top + 1.5 * space
        val glyphs = listOf(
            gClef(55.0, top), sharp(70.0, top),
            head(100.0, c5), // C5: one sharp, F
            sharp(165.0, top), sharp(171.0, c5), // two sharps, F and C
            head(195.0, c5), // C#5 by the new key
            head(240.0, c5), // still C#5 after the next barline
            head(260.0, top + 3.5 * space), // F#4
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(220.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(Key(1), Key(2)), staff.keys.map { it.value })
        assertEquals(listOf("C5", "C#5", "C#5", "F#4"), staff.notes.map { it.pitch.toString() })
    }

    /** Naturals cancelling the old key are the new key's (none here), not a note's. */
    @Test
    fun cancellingNaturalsAreAKeyChange() {
        val top = 100.0
        val bottom = top + 4 * space
        val c5 = top + 1.5 * space
        val glyphs = listOf(
            gClef(55.0, top), sharp(70.0, top), sharp(76.0, c5),
            head(100.0, top), // F#5
            natural(165.0, top), natural(171.0, c5),
            head(190.0, top), // F5
            head(260.0, top + 3.5 * space), // F4: no key any more
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(240.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(Key(2), Key(0)), staff.keys.map { it.value })
        assertEquals(listOf("F#5", "F5", "F4"), staff.notes.map { it.pitch.toString() })
    }

    /** A sharp right after a barline, against its note, is the note's: no key change. */
    @Test
    fun aNoteAccidentalAfterABarlineIsNoKeyChange() {
        val top = 100.0
        val bottom = top + 4 * space
        val f5 = top
        val glyphs = listOf(
            gClef(55.0, top),
            head(100.0, f5),
            sharp(163.0, f5), head(169.5, f5), // F#5
            head(190.0, f5), // still F#5 in this measure
            head(230.0, f5), // F5 after the barline
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(210.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(Key(0)), staff.keys.map { it.value })
        assertEquals(listOf("F5", "F#5", "F#5", "F5"), staff.notes.map { it.pitch.toString() })
    }

    /** Two digits one over the other, the upper one over the middle line, are a time signature; "C" is 4/4. */
    @Test
    fun timeSignaturesAreReadWhereverTheyAre() {
        val top = 100.0
        val bottom = top + 4 * space
        fun digit(d: Int, x: Double, upper: Boolean) = glyph("timeSig$d", x, if (upper) top + space else top + 3 * space, 2.0, 0.5, 1.5)
        val glyphs = listOf(
            gClef(55.0, top), digit(3, 70.0, true), digit(4, 70.0, false),
            head(100.0, top),
            digit(1, 165.0, true), digit(2, 173.0, true), digit(8, 169.0, false),
            head(190.0, top),
            glyph("timeSigCommon", 225.0, top + 2 * space, 2.0, 0.5, 1.5),
            head(250.0, top),
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(220.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(TimeSignature(3, 4), TimeSignature(12, 8), TimeSignature(4, 4)), staff.times.map { it.value })
        assertEquals(listOf(70.0, 165.0, 225.0), staff.times.map { it.x })
    }

    /** A clef drawn in the middle of a system reads the notes after it, and is listed where it is. */
    @Test
    fun aClefChangeIsListedWhereItIs() {
        val top = 100.0
        val glyphs = listOf(gClef(55.0, top), head(100.0, top + 4 * space), fClef(150.0, top), head(180.0, top + 4 * space))
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(listOf(Clef(Clef.Sign.G, 2), Clef(Clef.Sign.F, 4)), staff.clefs.map { it.value })
        assertEquals(listOf("E4", "G2"), staff.notes.map { it.pitch.toString() })
    }

    /**
     * Naturals alone cancel the whole key before (the new key has no sign): one natural in a key of
     * three flats, after a barline and a little away from its note, is the note's.
     */
    @Test
    fun aNaturalAloneInAKeyOfThreeFlatsIsANotes() {
        val top = 100.0
        val bottom = top + 4 * space
        val b4 = top + 2 * space
        val glyphs = listOf(
            gClef(55.0, top), flat(70.0, b4), flat(76.0, top + 0.5 * space), flat(82.0, top + 2.5 * space),
            head(100.0, b4), // Bb4
            natural(163.0, b4), head(171.0, b4), // B4, its natural a space away
            head(240.0, b4), // Bb4 again
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(220.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(Key(-3)), staff.keys.map { it.value })
        assertEquals(listOf("Bb4", "B4", "Bb4"), staff.notes.map { it.pitch.toString() })
    }

    /** In a tight passage, an accidental may overlap its head a little: it is still the note's. */
    @Test
    fun anAccidentalOverlappingItsHeadIsTheNotes() {
        val top = 100.0
        val f4 = top + 3.5 * space
        val glyphs = listOf(
            gClef(55.0, top),
            sharp(97.0, f4), head(100.0, f4), // the sharp's right edge 0.4 space into the head
        )
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(listOf("F#4"), staff.notes.map { it.pitch.toString() })
    }

    /**
     * A key's signs have their places on the staff: in bass clef, its F sharp is on F3, not F2. A
     * chord's sharps staggered after a barline (MuseJazz's) are the chord's, not a key of one sharp.
     */
    @Test
    fun aSharpBelowAKeysPlaceIsNoKey() {
        val top = 100.0
        val bottom = top + 4 * space
        val f2 = bottom + 0.5 * space
        val f3 = top + space
        val glyphs = listOf(
            fClef(55.0, top), head(100.0, top + 2 * space), // D3
            sharp(162.0, f2), sharp(167.5, f3), head(172.0, f2), head(172.0, f3), // F#2 and F#3, the low sharp a space away
            head(240.0, f2), // F2 in the next measure
        )
        val staff = read(staffLines(top) + barline(160.0, top, bottom) + barline(220.0, top, bottom), glyphs).single().single()
        assertEquals(listOf(Key(0)), staff.keys.map { it.value })
        assertEquals(listOf("D3", "F#2", "F#3", "F2"), staff.notes.map { it.pitch.toString() })
    }

    /**
     * A font glyph's origin is on the step it marks, wherever its box is: Finale Broadway draws its
     * sharp higher in its box than other fonts, its middle a step above the note.
     */
    @Test
    fun aFontAccidentalIsOnTheStepOfItsOrigin() {
        val top = 100.0
        val f4 = top + 3.5 * space
        val high = sharp(90.0, f4 - 0.8 * space)
        val glyphs = listOf(
            gClef(55.0, top),
            high.copy(x = high.box.left, y = f4, size = 20.0), head(100.0, f4),
        )
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(listOf("F#4"), staff.notes.map { it.pitch.toString() })
    }

    /** A narrow clef (MuseJazz's) leaves almost 2 spaces before the key: the key is still the clef's. */
    @Test
    fun aKeyFarFromANarrowClefIsTheStartKey() {
        val top = 100.0
        val b4 = top + 2 * space
        val glyphs = listOf(
            gClef(55.0, top), // right edge at 68.5
            flat(77.5, b4), flat(83.5, top + 0.5 * space), // 1.8 spaces after the clef
            head(110.0, b4), // Bb4
        )
        val staff = read(staffLines(top), glyphs).single().single()
        assertEquals(listOf(Key(-2)), staff.keys.map { it.value })
        assertEquals(listOf("Bb4"), staff.notes.map { it.pitch.toString() })
    }
}
