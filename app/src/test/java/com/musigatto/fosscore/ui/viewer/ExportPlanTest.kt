package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanTest {

    private val fit = RectPx(0f, 0f, 612f, 792f)

    /** A box measured on the page (0..fit) as the plan holds it: a share of the page. */
    private fun norm(r: RectPx) = RectPx(
        r.left / fit.width, r.top / fit.height, r.width / fit.width, r.height / fit.height,
    )
    private fun plan(
        stamps: List<Stamp> = emptyList(),
        strokes: List<Stroke> = emptyList(),
        layerId: Long? = null,
    ) = buildExportPlan(
        stamps = stamps,
        strokes = strokes,
        layerName = { "Capa $it" },
        fit = { fit },
        measureText = { _, _ -> 100f to 24f },
        layerId = layerId,
    )

    private fun ink(layer: Long, points: String = "0.1;0.1|0.5;0.5") = Stroke(
        sheetId = 1, page = 0, points = points, width = 0.004f, color = 0xFFFF0000.toInt(), layer = layer,
    )

    @Test
    fun inkGoesToItsLayerAndCarriesTheLayerName() {
        val p = plan(strokes = listOf(ink(layer = 2)), layerId = 2)

        val annot = p.pages.single().annots.single() as Annot.Path
        assertTrue(annot.ink)
        assertEquals("Capa 2", annot.contents)
        assertEquals("Capa 2", annot.layer)
        assertEquals(0xFFFF0000.toInt(), annot.color)
    }

    @Test
    fun layerFilterExcludesOtherLayersAndNullIncludesAll() {
        val strokes = listOf(ink(layer = 1), ink(layer = 2))

        assertEquals(1, plan(strokes = strokes, layerId = 1).pages.single().annots.size)
        assertEquals(1, plan(strokes = strokes, layerId = 2).pages.single().annots.size)
        assertEquals(2, plan(strokes = strokes, layerId = null).pages.single().annots.size)
    }

    @Test
    fun highlighterBecomesAQuadWithItsBoundingBox() {
        val stroke = Stroke(
            sheetId = 1, page = 0, points = "0.10;0.20|0.30;0.40", width = 0.004f,
            color = 0xFFFFFF00.toInt(), kind = Stroke.HIGHLIGHTER, layer = 1,
        )

        val annot = plan(strokes = listOf(stroke)).pages.single().annots.single() as Annot.Highlight
        assertEquals(HIGHLIGHTER_ALPHA, annot.opacity, 0.0001f)
        assertEquals(0.10f, annot.rect.left, 0.0001f)
        assertEquals(0.20f, annot.rect.top, 0.0001f)
        assertEquals(0.20f, annot.rect.width, 0.0001f)
        assertEquals(0.20f, annot.rect.height, 0.0001f)
    }

    @Test
    fun staffHasFiveLinesFromTenPoints() {
        val points = (0..4).joinToString("|") { i ->
            val y = 0.1f + i * 0.02f
            "0.1;$y|0.9;$y"
        }
        val stroke = Stroke(sheetId = 1, page = 0, points = points, width = 0.003f, kind = Stroke.SHAPE_STAFF, layer = 1)

        val annot = plan(strokes = listOf(stroke)).pages.single().annots.single() as Annot.Path
        assertEquals(5, annot.subpaths.size)
        assertFalse(annot.ink)
        assertTrue(annot.subpaths.all { it.size == 2 })
    }

    @Test
    fun aClosedShapeIsClosedAndAFilledOneSaysSo() {
        val closed = "0.1;0.1|0.4;0.1|0.4;0.4|0.1;0.1"
        val filled = Stroke(sheetId = 1, page = 0, points = closed, width = 0.003f, kind = Stroke.SHAPE_FILLED, layer = 1)
        val outline = Stroke(sheetId = 1, page = 0, points = closed, width = 0.003f, kind = Stroke.SHAPE, layer = 1)

        val f = plan(strokes = listOf(filled)).pages.single().annots.single() as Annot.Path
        assertTrue("filled shape: closed", f.closed)
        assertTrue("filled shape: filled", f.filled)

        val o = plan(strokes = listOf(outline)).pages.single().annots.single() as Annot.Path
        assertTrue("closed outline: closed", o.closed)
        assertFalse("closed outline: not filled", o.filled)
    }

    @Test
    fun aSymbolStampBecomesAGlyphWithItsCodepoint() {
        val stamp = Stamp(sheetId = 1, page = 0, symbol = "FERMATA", x = 0.5f, y = 0.5f, size = 0.04f, layer = 1)

        val annot = plan(stamps = listOf(stamp)).pages.single().annots.single() as Annot.Glyph
        assertEquals(StampSymbol.FERMATA.codepoint, annot.codepoint)
        assertEquals("FERMATA", annot.contents)
        assertEquals(norm(stampRect(stamp, fit)), annot.rect)
    }

    @Test
    fun anUnknownSymbolIsSkipped() {
        val stamp = Stamp(sheetId = 1, page = 0, symbol = "NOT_A_SYMBOL", x = 0.5f, y = 0.5f, size = 0.04f, layer = 1)

        val p = plan(stamps = listOf(stamp))
        assertTrue(p.pages.isEmpty())
    }

    @Test
    fun aTextStampBecomesAFreeTextWithTheMeasuredBox() {
        val stamp = Stamp(
            sheetId = 1, page = 0, symbol = Stamp.TEXT_SYMBOL, text = "rit.", x = 0.5f, y = 0.5f,
            size = 0.04f, font = "SERIF", bold = true, italic = true, layer = 1,
        )

        val annot = plan(stamps = listOf(stamp)).pages.single().annots.single() as Annot.Text
        assertEquals("rit.", annot.contents)
        assertEquals("rit.", annot.text)
        assertEquals(TextFont.SERIF, annot.font)
        assertTrue(annot.bold)
        assertTrue(annot.italic)
        assertEquals(norm(textBoxRect(stamp, 100f, 24f, fit)), annot.rect)
    }

    @Test
    fun pagesComeSorted() {
        val p = plan(
            stamps = listOf(
                Stamp(sheetId = 1, page = 2, symbol = "FERMATA", x = .5f, y = .5f, size = .04f, layer = 1),
                Stamp(sheetId = 1, page = 0, symbol = "FERMATA", x = .5f, y = .5f, size = .04f, layer = 1),
            )
        )
        assertEquals(listOf(0, 2), p.pages.map { it.page })
    }
}