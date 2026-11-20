package com.musigatto.fosscore.ui.viewer

import kotlin.math.min

/**
 * How a PDF lays its music out, in its staff spaces: its page's [width] and [height], and the
 * height of the [music] on its fullest page, from the top line of its first system to the bottom
 * line of its last. The MusicXML view of the PDF copies it (maintainer, 2026-11-20): the same lines
 * and pages, the music the size it is on the PDF.
 */
data class PdfLook(val width: Double, val height: Double, val music: Double) {
    /**
     * The music size (Verovio's scale) of a page [pageWidth] × [viewportHeight] px that shows the
     * music as large as the PDF shown whole on that screen, and every page of it whole between
     * the [marginTop] and [marginBottom] px the bars take.
     */
    fun scale(pageWidth: Int, viewportHeight: Int, marginTop: Int, marginBottom: Int): Int {
        // the PDF shown whole: px per staff space
        val shown = min(pageWidth / width, viewportHeight / height)
        val same = shown / VEROVIO_SPACE_PX * 100
        // Verovio spaces lines out more than an engraver does, and writes the title over the
        // first one: room for it, and Verovio's page margins (MARGIN, 40 units, on both sides)
        val usable = (viewportHeight - marginTop - marginBottom).coerceAtLeast(1)
        val fit = usable / ((music * PDF_MUSIC_ROOM + PDF_MUSIC_AIR) * VEROVIO_SPACE_PX / 100 + 0.8)
        return min(same, fit).toInt()
    }
}

// Verovio's staff space at a scale of 100, in px (a staff 72 px high)
private const val VEROVIO_SPACE_PX = 18.0

/** How much higher than the PDF's the lines of music come out in Verovio. */
internal const val PDF_MUSIC_ROOM = 1.2

/** Staff spaces more for what is not between the staff lines: the title, lyrics under the last line. */
internal const val PDF_MUSIC_AIR = 8.0
