package com.musigatto.fosscore.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Espacio de página PDF para anotaciones: MediaBox + /Rotate normalizado a 0/90/180/270. */
data class PdfPageSpace(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rotation: Int) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
}

/**
 * Mapeo entre coordenadas normalizadas de encaje (0..1, y-hacia-abajo: las que guardan los
 * trazos/sellos) y puntos de página PDF (MediaBox, y-hacia-arriba, sin rotar: donde viven
 * los /Annots). Puro, sin JNI: unit-testeable en JVM.
 */
object PdfAnnotMapper {

    fun normalizedToPdf(p: Offset, s: PdfPageSpace): Offset {
        val (ux, uy) = displayedToUnrotated(p.x, p.y, s.rotation)
        return Offset(s.x0 + ux * s.w, s.y0 + uy * s.h)
    }

    // fracciones y-hacia-arriba: display <-> sin rotar (rotación horaria del /Rotate)
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

    // ancho del trazo: fracción del alto de encaje <-> puntos (el encaje mide lo rotado)
    fun widthFracToPt(frac: Float, s: PdfPageSpace): Float =
        frac * if (s.rotation == 90 || s.rotation == 270) s.w else s.h
}
