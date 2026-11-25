package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Test

class StavesTest {
    private fun staffLines(top: Double, left: Double = 50.0, right: Double = 550.0, space: Double = 5.0) =
        (0..4).map { DrawnLine(left, top + it * space, right, top + it * space, 0.5) }

    private fun page(lines: List<DrawnLine>, glyphs: List<DrawnGlyph> = emptyList()) =
        PageDrawing(600.0, 800.0, glyphs, lines, emptyList(), emptyList())

    @Test
    fun fiveEvenLinesAreAStaff() {
        val staves = Staves.find(page(staffLines(100.0))).single().staves
        assertEquals(1, staves.size)
        assertEquals(100.0, staves[0].top, 1e-9)
        assertEquals(5.0, staves[0].space, 1e-9)
    }

    /**
     * Long lines between staves (a pedal's, an octave's) can be evenly spaced, by chance, with lines
     * of the staves: the staves are still the tight ones, not five lines picked across them.
     */
    @Test
    fun strayLinesMakeNoStaffWithLinesOfOthers() {
        val strays = listOf(14.0, 129.0).map { DrawnLine(50.0, it, 550.0, it, 0.5) }
        // 14, 52 (the first staff's last line), 88, 129 (a stray), 163: about 37 apart
        val staves = Staves.find(page(strays + staffLines(32.0) + staffLines(83.0) + staffLines(148.0))).flatMap { it.staves }
        assertEquals(listOf(32.0, 83.0, 148.0), staves.map { it.top })
    }

    @Test
    fun stepsCountHalfSpacesFromTheBottomLine() {
        val staff = Staff(listOf(100.0, 105.0, 110.0, 115.0, 120.0), 50.0, 550.0)
        assertEquals(0, staff.step(120.0))
        assertEquals(1, staff.step(117.4))
        assertEquals(8, staff.step(100.0))
        assertEquals(-2, staff.step(125.0))
    }

    @Test
    fun aStaffDrawnMeasureByMeasureIsOneStaff() {
        val split = staffLines(100.0, 50.0, 300.0) + staffLines(100.0, 300.5, 550.0)
        val staff = Staves.find(page(split)).single().staves.single()
        assertEquals(550.0, staff.right, 1e-9)
    }

    /**
     * Verovio draws the staff a measure at a time, its pieces a few thousandths of a point apart in
     * height: the last piece, a hair higher, came first, and the staff kept its start (Au clair de
     * la lune: a first system 98 points long, 14 measures read of 16).
     */
    @Test
    fun aStaffDrawnInPiecesAHairApartStartsAtItsFirstPiece() {
        val pieces = listOf(Triple(50.0, 200.0, 0.0), Triple(200.0, 350.0, 0.002), Triple(350.0, 550.0, -0.003))
        val lines = pieces.flatMap { (left, right, dy) -> staffLines(100.0 + dy, left, right) }
        val staff = Staves.find(page(lines)).single().staves.single()
        assertEquals(50.0, staff.left, 1e-9)
        assertEquals(550.0, staff.right, 1e-9)
    }

    /** A horizontal beam is a long filled rectangle too: it must not break the staff it crosses. */
    @Test
    fun aBeamAcrossTheStaffLeavesTheStaff() {
        val beam = DrawnLine(200.0, 108.0, 260.0, 108.0, 2.5)
        val staves = Staves.find(page(staffLines(100.0) + beam)).single().staves
        assertEquals(listOf(100.0, 105.0, 110.0, 115.0, 120.0), staves.single().lineYs)
    }

    /** A beam centred on a staff line is not joined into it (the line would take the beam's thickness). */
    @Test
    fun aBeamLyingOnAStaffLineLeavesTheStaff() {
        val beam = DrawnLine(40.0, 100.0, 120.0, 100.0, 2.5)
        val staves = Staves.find(page(listOf(beam) + staffLines(100.0))).single().staves
        assertEquals(listOf(100.0, 105.0, 110.0, 115.0, 120.0), staves.single().lineYs)
    }

    @Test
    fun unevenLinesAreNotAStaff() {
        val lines = listOf(100.0, 105.0, 110.0, 118.0, 120.0).map { DrawnLine(50.0, it, 550.0, it, 0.5) }
        assertEquals(emptyList<StaffSystem>(), Staves.find(page(lines)))
    }

    @Test
    fun aBracedPairJoinedByABarlineIsOneSystemAndTheNextStaffAnother() {
        val lines = staffLines(100.0) + staffLines(150.0) + staffLines(250.0) +
            DrawnLine(50.0, 100.0, 50.0, 170.0, 1.0)
        val brace = DrawnGlyph("brace", Box(42.0, 99.0, 48.0, 171.0))
        val systems = Staves.find(page(lines, listOf(brace)))
        assertEquals(listOf(2, 1), systems.map { it.staves.size })
        assertEquals(listOf(0..1), systems[0].braces)
        assertEquals(emptyList<IntRange>(), systems[1].braces)
    }

    /**
     * Two systems side by side on one row (the end of a piece, then its coda after a gap: Pas de
     * boogie woogie, page 6): each staff goes with the one above it on its side, not with the
     * staff beside it, read left to right.
     */
    @Test
    fun twoSystemsSideBySideAreTwoSystemsOfThreeStaves() {
        val tops = listOf(100.0, 150.0, 200.0)
        val lines = tops.flatMap { staffLines(it, 50.0, 280.0) + staffLines(it, 320.0, 550.0) } +
            DrawnLine(50.0, 100.0, 50.0, 220.0, 1.0) + DrawnLine(320.0, 100.0, 320.0, 220.0, 1.0)
        val systems = Staves.find(page(lines))
        assertEquals(listOf(3, 3), systems.map { it.staves.size })
        assertEquals(listOf(50.0, 320.0), systems.map { it.staves.first().left })
        assertEquals(listOf(listOf(50.0, 50.0, 50.0), listOf(320.0, 320.0, 320.0)), systems.map { s -> s.staves.map { it.left } })
    }

    /** MuseScore 3 draws its braces as outlines; a scan's brace will come the same way. */
    @Test
    fun aBraceDrawnAsATallCurvedShapeJoinsTheStaves() {
        val lines = staffLines(100.0) + staffLines(150.0)
        val outline = DrawnShape(listOf(Point(46.0, 99.0), Point(42.0, 135.0), Point(48.0, 171.0), Point(44.0, 135.0)), true)
        val page = PageDrawing(600.0, 800.0, emptyList(), lines, listOf(outline), emptyList())
        val system = Staves.find(page).single()
        assertEquals(listOf(0..1), system.braces)
    }

    @Test
    fun aBraceAloneJoinsTheStaves() {
        val lines = staffLines(100.0) + staffLines(150.0)
        val brace = DrawnGlyph("brace", Box(42.0, 99.0, 48.0, 171.0))
        assertEquals(listOf(2), Staves.find(page(lines, listOf(brace))).map { it.staves.size })
    }
}
