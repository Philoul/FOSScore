package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OutlineSymbolsTest {
    private val space = 5.0
    private val top = 100.0

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double) =
        (0..32).map { i -> Point(cx + rx * cos(2 * PI * i / 32), cy + ry * sin(2 * PI * i / 32)) }

    private val classifier = SymbolClassifier(
        listOf(
            FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5))),
            FontSymbol("A", "noteheadHalf", listOf(ellipse(0.0, 0.0, 0.6, 0.5), ellipse(0.0, 0.0, 0.33, 0.27).reversed())),
            FontSymbol("A", "augmentationDot", listOf(ellipse(0.0, 0.0, 0.2, 0.2))),
        ),
    )

    /** A page printed without its fonts: staff lines, then each symbol a filled outline. */
    private fun page(vararg fills: List<List<Point>>): PageDrawing {
        val builder = PageBuilder(600.0, 800.0)
        (0..4).forEach { builder.fill(listOf(rect(50.0, top + it * space - 0.25, 550.0, top + it * space + 0.25)), curved = false) }
        fills.forEach { builder.fill(it, curved = true) }
        return builder.build()
    }

    private fun rect(l: Double, t: Double, r: Double, b: Double) = listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b), Point(l, t))

    /** An outline on a staff is named as the symbol it looks like, at the staff's scale, and is no shape any more. */
    @Test
    fun anOutlineOnAStaffIsNamed() {
        val head = listOf(ellipse(100.0, top + 2 * space, 0.6 * space, 0.5 * space))
        val named = OutlineSymbols(classifier).name(page(head))
        assertEquals(listOf("noteheadBlack"), named.glyphs.map { it.name })
        assertEquals(100.0, named.glyphs.single().box.centerX, 0.01)
        assertEquals(emptyList<DrawnShape>(), named.shapes)
    }

    /**
     * A fermata (an arc over a dot) looks like no letter: it is named (Pas de boogie woogie, printed
     * without its fonts: none of its fermatas was read).
     */
    @Test
    fun aFermataIsNamed() {
        // an arc, thick, over a dot
        fun arc(cx: Double, cy: Double, r: Double): List<Point> =
            (0..16).map { i -> Point(cx - r * cos(PI * i / 16), cy - r * sin(PI * i / 16)) } +
                (16 downTo 0).map { i -> Point(cx - 0.75 * r * cos(PI * i / 16), cy - 0.6 * r * sin(PI * i / 16)) }
        val fermata = SymbolClassifier(listOf(FontSymbol("A", "fermataAbove", listOf(arc(0.0, 0.0, 1.0), ellipse(0.0, -0.2, 0.15, 0.15)))))
        val drawn = listOf(arc(100.0, top - 2 * space, space), ellipse(100.0, top - 2.2 * space, 0.15 * space, 0.15 * space))
        val named = OutlineSymbols(fermata).name(page(drawn))
        assertEquals(listOf("fermataAbove"), named.glyphs.map { it.name })
        // a flat arc, no dot (a tie): it looks like one only a little, and holds no note (Lettre à France, page 2)
        val flat = (0..16).map { i -> Point(100.0 - space * cos(PI * i / 16), top - 2 * space - 0.3 * space * sin(PI * i / 16)) } +
            (16 downTo 0).map { i -> Point(100.0 - space * cos(PI * i / 16), top - 2 * space - 0.15 * space * sin(PI * i / 16)) }
        assertEquals(emptyList<String?>(), OutlineSymbols(fermata).name(page(listOf(flat))).glyphs.map { it.name })
    }

    /** The outlines of one fill are one symbol: a half note's head is its outline and its hole. */
    @Test
    fun theSubpathsOfOneFillAreOneSymbol() {
        val y = top + 2 * space
        val half = listOf(ellipse(100.0, y, 0.6 * space, 0.5 * space), ellipse(100.0, y, 0.33 * space, 0.27 * space).reversed())
        val named = OutlineSymbols(classifier).name(page(half))
        assertEquals(listOf("noteheadHalf"), named.glyphs.map { it.name })
    }

    /** A beam (a long four-cornered fill) stays a shape for the rhythm to count, whatever it looks like. */
    @Test
    fun aBeamIsNoSymbol() {
        // a font symbol drawn just like it
        val dash = SymbolClassifier(listOf(FontSymbol("A", "flag8thUp", listOf(rect(0.0, 0.0, 4.0, 0.5)))))
        val beam = listOf(rect(100.0, top + 1.0, 120.0, top + 3.5))
        val named = OutlineSymbols(dash).name(page(beam))
        assertEquals(emptyList<DrawnGlyph>(), named.glyphs)
        assertEquals(1, named.shapes.size)
    }

    /**
     * A sixteenth's short beam is as small as a whole rest: a four-cornered fill is a rest only
     * upright and hanging from a line (or sitting on one); else it stays a beam.
     */
    @Test
    fun aShortBeamIsNoRestButARectangleOnALineIs() {
        val rests = SymbolClassifier(listOf(FontSymbol("A", "restWhole", listOf(rect(0.0, 0.0, 1.2, 0.5)))))
        val hook = listOf(listOf(Point(100.0, top + 6.0), Point(106.0, top + 5.0), Point(106.0, top + 7.5), Point(100.0, top + 8.5), Point(100.0, top + 6.0)))
        val rest = listOf(rect(140.0, top + space, 146.0, top + space + 2.5))
        val named = OutlineSymbols(rests).name(page(hook, rest))
        assertEquals(listOf(140.0), named.glyphs.map { it.box.left })
        assertEquals(1, named.shapes.size)
    }

    /**
     * Only symbols no letter or digit looks like are named by their look: a measure number drawn
     * as outlines ("15") is no octave line, an "f" of the lyrics no forte. A time signature's digits
     * are, inside the staff.
     */
    @Test
    fun lettersAndNumbersAreNoSymbols() {
        val letters = SymbolClassifier(
            listOf(
                FontSymbol("A", "quindicesima", listOf(ellipse(0.0, 0.0, 0.8, 0.6))),
                FontSymbol("A", "timeSig4", listOf(ellipse(0.0, 0.0, 0.81, 0.61))),
            ),
        )
        val above = listOf(ellipse(100.0, top - 3 * space, 0.8 * space, 0.6 * space))
        val inside = listOf(ellipse(140.0, top + 3 * space, 0.81 * space, 0.61 * space))
        val named = OutlineSymbols(letters).name(page(above, inside))
        assertEquals(listOf("timeSig4"), named.glyphs.map { it.name })
    }

    /**
     * A rectangle over the staff with no ledger line is no rest (a hyphen of the lyrics written
     * between a piano's staves: read as whole rests, it pushed its voice past the measure's end,
     * J'te l'dis quand même, 2026-11-18); hanging from a ledger line, it is one.
     */
    @Test
    fun aRestOutOfTheStaffHangsFromALedgerLine() {
        val rests = SymbolClassifier(listOf(FontSymbol("A", "restWhole", listOf(rect(0.0, 0.0, 1.3, 0.5)))))
        val hyphen = listOf(rect(100.0, top - 2.5, 106.5, top))
        val hanging = listOf(rect(200.0, top - space, 206.5, top - space + 2.5))
        val ledger = DrawnLine(198.0, top - space, 208.5, top - space, 0.5)
        val drawn = page(hyphen, hanging)
        val named = OutlineSymbols(rests).name(PageDrawing(drawn.width, drawn.height, drawn.glyphs, drawn.lines + ledger, drawn.shapes, drawn.texts))
        assertEquals(listOf(200.0), named.glyphs.map { it.box.left })
    }

    /**
     * A letter of the lyrics under the staff (an "l", an "n") looks like a breve rest: read as one,
     * it put two whole notes of rest in the measure and pushed its notes into the next ones (J'ai
     * encore rêvé d'elle, measure 7, 2026-11-19). Out of the staff, it needs a ledger line too.
     */
    @Test
    fun aBreveRestOutOfTheStaffHangsFromALedgerLine() {
        val rests = SymbolClassifier(listOf(FontSymbol("A", "restDoubleWhole", listOf(rect(0.0, 0.0, 0.5, 1.0)))))
        // where a ledger line would be, two spaces under the staff, but none is drawn
        val letter = listOf(rect(100.0, top + 6 * space, 102.5, top + 7 * space))
        val inside = listOf(rect(200.0, top + space, 202.5, top + 2 * space))
        val named = OutlineSymbols(rests).name(page(letter, inside))
        assertEquals(listOf(200.0), named.glyphs.map { it.box.left })
    }

    /**
     * A small note's head (a cue line, "synthé", printed at about 70 %) looks like a head at that
     * scale only: held by its short stem, it is one (J'ai encore rêvé d'elle, measure 18, where the
     * synth's notes were not read); a small round fill without a stem (a letter "o") is not.
     */
    @Test
    fun aSmallHeadOnAStemIsAHead() {
        val y = top + 2 * space
        val small = listOf(ellipse(100.0, y, 0.6 * 0.7 * space, 0.5 * 0.7 * space))
        val letter = listOf(ellipse(200.0, y, 0.6 * 0.7 * space, 0.5 * 0.7 * space))
        val stem = DrawnLine(100.0 + 0.42 * space, y, 100.0 + 0.42 * space, y - 1.9 * space, 0.25)
        val drawn = page(small, letter)
        val named = OutlineSymbols(classifier).name(PageDrawing(drawn.width, drawn.height, drawn.glyphs, drawn.lines + stem, drawn.shapes, drawn.texts))
        // named at the small scale (sure), the letter at most at the normal one (unsure, too small)
        assertEquals(listOf(100.0), named.glyphs.filter { it.name == "noteheadBlack" && it.confidence > 0.9 }.map { it.box.centerX })
        assertEquals(emptyList<Double>(), named.glyphs.filter { it.box.centerX > 150 && it.confidence > 0.9 }.map { it.box.centerX })
    }

    /**
     * A flat beam hook (a sixteenth after a dotted eighth) is a rectangle on a line like a whole
     * rest, but it holds on to a stem: it stays a beam for the rhythm (read as a rest, it pushed
     * its voice past the measure's end, J'te l'dis quand même, 2026-11-18).
     */
    @Test
    fun aRectangleHeldByAStemIsABeamHookNoRest() {
        val rests = SymbolClassifier(listOf(FontSymbol("A", "restWhole", listOf(rect(0.0, 0.0, 1.3, 0.5)))))
        val hook = listOf(rect(100.0, top + space, 106.5, top + space + 2.5))
        val stem = DrawnLine(106.5, top + space, 106.5, top + 4 * space, 0.5)
        val drawn = page(hook)
        val named = OutlineSymbols(rests).name(PageDrawing(drawn.width, drawn.height, drawn.glyphs, drawn.lines + stem, drawn.shapes, drawn.texts))
        assertEquals(emptyList<DrawnGlyph>(), named.glyphs)
    }

    /**
     * A jump's sign named by its look must look like it: a false segno (J'te l'dis quand même,
     * read at 0.71 where the real ones score 0.91) would make the music jump.
     */
    @Test
    fun aJumpSignThatLooksLittleLikeOneIsNoSign() {
        val signs = SymbolClassifier(listOf(FontSymbol("A", "segno", listOf(ellipse(0.0, 0.0, 0.8, 1.1))), FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5)))))
        val alike = listOf(ellipse(100.0, top - 3 * space, 0.8 * space, 1.1 * space))
        // a sign it looks like at 0.75 only
        val y = top - 4 * space
        val unlike = listOf(listOf(Point(200.0, y), Point(200.0 + 1.6 * space, y), Point(200.0 + 0.8 * space, y + 2.2 * space), Point(200.0, y)))
        val named = OutlineSymbols(signs).name(page(alike, unlike))
        assertEquals(listOf(100.0), named.glyphs.map { it.box.centerX })
    }

    /**
     * A sign drawn in its parts (a segno: its S, its two dots) needs a little less: Pas de boogie
     * woogie's segnos scored 0.785 with three outlines. The same look in one outline is no sign.
     */
    @Test
    fun aJumpSignInItsPartsIsASignAtALowerScore() {
        val signs = SymbolClassifier(listOf(FontSymbol("A", "segno", listOf(ellipse(0.0, 0.0, 0.8, 1.1))), FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5)))))
        val y = top - 4 * space
        val (a, b, c) = Triple(Point(200.0, y), Point(200.0 + 1.6 * space, y), Point(200.0 + 0.8 * space, y + 2.2 * space))
        val middle = Point(200.0 + 0.8 * space, y + 0.7 * space)
        // the triangle of the test above, in three parts
        val parts = listOf(listOf(a, b, middle, a), listOf(b, c, middle, b), listOf(c, a, middle, c))
        val named = OutlineSymbols(signs).name(page(parts))
        assertEquals(listOf("segno"), named.glyphs.map { it.name })
    }

    /**
     * A clef named by its look must look like one: a false bass clef change (J'te l'dis quand
     * même, measure 16, at 0.57 where the clefs score 0.91 and more) put the treble staff in bass
     * clef, its notes two octaves too low.
     */
    @Test
    fun aClefThatLooksLittleLikeOneIsNoClef() {
        val clefs = SymbolClassifier(listOf(FontSymbol("A", "fClefChange", listOf(ellipse(0.0, 0.0, 0.8, 1.1))), FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5)))))
        val alike = listOf(ellipse(100.0, top + space, 0.8 * space, 1.1 * space))
        val y = top + space
        val unlike = listOf(listOf(Point(200.0, y), Point(200.0 + 1.6 * space, y), Point(200.0 + 0.8 * space, y + 2.2 * space), Point(200.0, y)))
        val named = OutlineSymbols(clefs).name(page(alike, unlike))
        assertEquals(listOf(100.0), named.glyphs.filter { it.name == "fClefChange" }.map { it.box.centerX })
    }

    /**
     * A tuplet's number printed without its font, over or under the staff (no bracket: Lettre à
     * France's triplets read as plain eighths, their measures too long), is named; inside the
     * staff a digit is a time signature's.
     */
    @Test
    fun aTupletNumberOutOfTheStaffIsNamed() {
        val digits = SymbolClassifier(listOf(FontSymbol("A", "tuplet3", listOf(ellipse(0.0, 0.0, 0.46, 0.65)))))
        val over = listOf(ellipse(100.0, top - 2 * space, 0.46 * space, 0.65 * space))
        val inside = listOf(ellipse(140.0, top + 2 * space, 0.46 * space, 0.65 * space))
        val named = OutlineSymbols(digits).name(page(over, inside))
        assertEquals(listOf("tuplet3"), named.glyphs.map { it.name })
        assertEquals(100.0, named.glyphs.single().box.centerX, 0.01)
    }

    /**
     * A metronome mark's note ("♩ = 70" printed without its font) is named over the staff, where
     * the tempo is written, and only there: a note symbol inside the staff is a note.
     */
    @Test
    fun aMetronomeMarksNoteIsNamedOverTheStaffOnly() {
        val notes = SymbolClassifier(listOf(FontSymbol("A", "metNoteQuarterUp", listOf(ellipse(0.0, 0.0, 0.7, 1.4)))))
        val over = listOf(ellipse(100.0, top - 4 * space, 0.7 * space, 1.4 * space))
        val inside = listOf(ellipse(140.0, top + 2 * space, 0.7 * space, 1.4 * space))
        val named = OutlineSymbols(notes).name(page(over, inside))
        assertEquals(listOf("metNoteQuarterUp"), named.glyphs.map { it.name })
        assertEquals(100.0, named.glyphs.single().box.centerX, 0.01)
    }

    /**
     * A metronome mark drawn as a head, a stem line and "=" (J'ai encore rêvé d'elle: "♩ = 100"
     * not read, played at 120): a head well over the staff, on a stem, with "=" after it, is the
     * mark's note. A note over the staff on its ledger lines is a note.
     */
    @Test
    fun aHeadOnAStemBeforeAnEqualSignOverTheStaffIsAMetronomeNote() {
        val y = top - 5 * space
        val head = listOf(ellipse(100.0, y, 0.45 * space, 0.38 * space))
        val stem = DrawnLine(100.0 + 0.4 * space, y, 100.0 + 0.4 * space, y - 2.6 * space, 0.3)
        val equal = listOf(rect(108.0, y - 1.2, 114.0, y - 0.7), rect(108.0, y + 0.7, 114.0, y + 1.2))
        // a high note on a ledger line, with a stem too, and a word after it ("=" alone makes it)
        val note = listOf(ellipse(200.0, top - 2 * space, 0.6 * space, 0.5 * space))
        val noteStem = DrawnLine(200.0 + 0.55 * space, top - 2 * space, 200.0 + 0.55 * space, top + 1.5 * space, 0.3)
        val ledger = DrawnLine(195.0, top - 2 * space, 205.0, top - 2 * space, 0.5)
        val drawn = page(head, equal, note)
        val named = OutlineSymbols(classifier).name(PageDrawing(drawn.width, drawn.height, drawn.glyphs, drawn.lines + stem + noteStem + ledger, drawn.shapes, drawn.texts))
        assertEquals(listOf("metNoteQuarterUp"), named.glyphs.filter { it.box.centerX < 150 }.map { it.name })
        assertEquals(listOf("noteheadBlack"), named.glyphs.filter { it.box.centerX > 150 }.map { it.name })
    }

    /**
     * The same mark printed with the note in one piece (head and stem one fill) and "=" as two
     * lines, as J'ai encore rêvé d'elle has it: the note is compared with the metronome notes only.
     */
    @Test
    fun aOnePieceNoteBeforeTwoLinesOverTheStaffIsAMetronomeNote() {
        val notes = SymbolClassifier(
            listOf(
                FontSymbol("A", "metNoteQuarterUp", listOf(ellipse(0.0, 0.0, 0.6, 0.5), rect(0.5, -3.0, 0.62, 0.0))),
                FontSymbol("A", "noteheadBlack", listOf(ellipse(0.0, 0.0, 0.6, 0.5))),
                FontSymbol("A", "flag8thUp", listOf(rect(0.0, 0.0, 0.8, 3.0))),
            ),
        )
        val y = top - 6 * space
        // much smaller than the font's (0.8 x 2.4 spaces against 1.2 x 3.5): compared at its size, it is too small for any
        val k = 0.6 * space
        val note = listOf(ellipse(100.0, y, 0.6 * k, 0.5 * k), rect(100.0 + 0.5 * k, y - 3 * k, 100.0 + 0.62 * k, y))
        val equal = listOf(DrawnLine(108.0, y - 1.0, 114.0, y - 1.0, 0.4), DrawnLine(108.0, y + 1.0, 114.0, y + 1.0, 0.4))
        val drawn = page(note)
        val named = OutlineSymbols(notes).name(PageDrawing(drawn.width, drawn.height, drawn.glyphs, drawn.lines + equal, drawn.shapes, drawn.texts))
        assertEquals(listOf("metNoteQuarterUp"), named.glyphs.map { it.name })
    }

    /** A page whose music font names its symbols has no symbol drawn as outlines: its curves (ties, slurs) stay shapes. */
    @Test
    fun aPageWithAMusicFontIsLeftAsItIs() {
        val y = top + 2 * space
        val builder = PageBuilder(600.0, 800.0)
        (0..4).forEach { builder.fill(listOf(rect(50.0, top + it * space - 0.25, 550.0, top + it * space + 0.25)), curved = false) }
        builder.glyph(DrawnGlyph("noteheadBlack", Box(200.0, y - 2.5, 206.5, y + 2.5)))
        builder.fill(listOf(ellipse(100.0, y, 0.6 * space, 0.5 * space)), curved = true)
        val named = OutlineSymbols(classifier).name(builder.build())
        assertEquals(listOf("noteheadBlack"), named.glyphs.map { it.name })
        assertEquals(1, named.shapes.size)
    }

    /** What looks like nothing known (a slur, a letter) stays a shape. */
    @Test
    fun anUnknownOutlineStaysAShape() {
        val bar = listOf(rect(100.0, top, 140.0, top + 3.0))
        val named = OutlineSymbols(classifier).name(page(bar))
        assertEquals(emptyList<DrawnGlyph>(), named.glyphs)
        assertEquals(1, named.shapes.size)
    }
}
