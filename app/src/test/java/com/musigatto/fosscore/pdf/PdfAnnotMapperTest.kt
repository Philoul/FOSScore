package com.musigatto.fosscore.pdf

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping from normalized fit coordinates (0..1, y pointing down, as the overlay uses) to
 * PDF page space (MediaBox, y pointing up, with /Rotate).
 * Only the writing direction (the inverses come back with the import from PDF).
 */
class PdfAnnotMapperTest {

    private val box = PdfPageSpace(0f, 0f, 612f, 792f, 0)

    @Test
    fun cornersNoRotation() {
        // top-left of the fit = top-left of the page (y pointing up)
        val tl = PdfAnnotMapper.normalizedToPdf(Offset(0f, 0f), box)
        assertEquals(0f, tl.x, 1e-4f)
        assertEquals(792f, tl.y, 1e-4f)
        val br = PdfAnnotMapper.normalizedToPdf(Offset(1f, 1f), box)
        assertEquals(612f, br.x, 1e-4f)
        assertEquals(0f, br.y, 1e-4f)
    }
}
