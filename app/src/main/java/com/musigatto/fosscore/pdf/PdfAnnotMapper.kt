package com.musigatto.fosscore.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** PDF page space for annotations: MediaBox + /Rotate normalized to 0/90/180/270. */
data class PdfPageSpace(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rotation: Int) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
}

/**
 * Mapping between normalized fit coordinates (0..1, y pointing down: what strokes and stamps
 * store) and PDF page points (MediaBox, y pointing up, unrotated: where /Annots live).
 * Pure, no JNI: unit-testable on the JVM.
 */
object PdfAnnotMapper {

    fun normalizedToPdf(p: Offset, s: PdfPageSpace): Offset {
        val (ux, uy) = displayedToUnrotated(p.x, p.y, s.rotation)
        return Offset(s.x0 + ux * s.w, s.y0 + uy * s.h)
    }

    // y-up fractions: displayed <-> unrotated (/Rotate turns clockwise)
    private fun displayedToUnrotated(dx: Float, dyDown: Float, rot: Int): Pair<Float, Float> {
        val dxUp = dx
        val dyUp = 1f - dyDown
        return when (rot) {
            90 -> Pair(1f - dyUp, dxUp)
            180 -> Pair(1f - dxUp, 1f - dyUp)
            270 -> Pair(dyUp, 1f - dxUp)
            else -> Pair(dxUp, dyUp)
        }
    }

    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun argbToPdfRgb(argb: Int): FloatArray {
        val c = Color(argb)
        return floatArrayOf(c.red, c.green, c.blue)
    }

    // stroke width: share of the fit height <-> points (the fit measures the rotated page)
    fun widthFracToPt(frac: Float, s: PdfPageSpace): Float =
        frac * if (s.rotation == 90 || s.rotation == 270) s.w else s.h
}
