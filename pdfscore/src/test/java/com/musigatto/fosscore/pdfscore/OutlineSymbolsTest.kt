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
