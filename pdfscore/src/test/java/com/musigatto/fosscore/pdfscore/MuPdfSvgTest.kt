package com.musigatto.fosscore.pdfscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuPdfSvgTest {
    /** A page as MuPDF writes it with text as paths: a staff line, a stem, a beam, a notehead and two letters. */
    private val svg = """
        <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" width="595" height="842" viewBox="0 0 595 842">
        <defs>
        <clipPath id="clip_1"><path transform="matrix(.2,0,0,.2,0,0)" d="M0 0H2977V4208H0Z"/></clipPath>
        <path id="font_4_2" d="M0 -.125H.295V.125H0Z"/>
        <path id="font_2_1" d="M0 0H.5V.7H0Z"/>
        <path id="font_2_2" d="M0 0H.4V.5H0Z"/>
        </defs>
        <g clip-path="url(#clip_1)">
        <path transform="matrix(.2,0,0,.2,40,100)" stroke-width="2" fill="none" stroke="#000000" d="M0 0H2500"/>
        <path transform="matrix(.2,0,0,.2,0,0)" stroke-width="4.5" fill="none" stroke="#000000" d="M500 400V600"/>
        <path transform="matrix(.2,0,0,.2,0,0)" d="M500 400L800 380 800 405 500 425Z"/>
        <use data-text="&#xe0a4;" xlink:href="#font_4_2" transform="matrix(20,0,0,-20,100,120)"/>
        <use data-text="L" xlink:href="#font_2_1" transform="matrix(10,0,0,-10,300,50)"/>
        <use data-text="a" xlink:href="#font_2_2" transform="matrix(10,0,0,-10,305.5,50)"/>
        </g>
        </svg>
    """.trimIndent()

    private val page = MuPdfSvg.read(svg.toByteArray(), mapOf(0xE0A4 to "noteheadBlack"))

    @Test
    fun strokesAreLinesInPagePoints() {
        val horizontal = page.lines.single { it.isHorizontal }
        assertEquals(DrawnLine(40.0, 100.0, 540.0, 100.0, 0.4), horizontal.copy(width = Math.round(horizontal.width * 10) / 10.0))
        val stem = page.lines.single { it.isVertical }
        assertEquals(100.0, stem.x1, 1e-9)
        assertEquals(0.9, stem.width, 1e-9)
    }

    @Test
    fun aSlantedFillIsAShape() {
        val beam = page.shapes.single()
        assertEquals(Box(100.0, 76.0, 160.0, 85.0), beam.box)
    }

    /** A glyph's origin and size come from its transform, its box from its outline, its name from its code point. */
    @Test
    fun aMusicGlyphIsNamedAndPlaced() {
        val head = page.glyphs.single()
        assertEquals("noteheadBlack", head.name)
        assertEquals(100.0, head.x, 1e-9)
        assertEquals(120.0, head.y, 1e-9)
        assertEquals(20.0, head.size, 1e-9)
        assertEquals(Box(100.0, 117.5, 105.9, 122.5), head.box.let { Box(it.left, it.top, Math.round(it.right * 10) / 10.0, it.bottom) })
    }

    @Test
    fun lettersMakeText() {
        val text = page.texts.single()
        assertEquals("La", text.text)
        assertTrue(text.size == 10.0 && text.x == 300.0 && text.y == 50.0)
    }
}
