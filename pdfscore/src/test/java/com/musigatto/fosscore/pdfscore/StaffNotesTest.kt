package com.musigatto.fosscore.pdfscore

import com.musigatto.fosscore.pdfscore.music.Clef
import com.musigatto.fosscore.pdfscore.music.Key
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
}
