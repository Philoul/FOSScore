package com.musigatto.fosscore.ui.viewer

import android.graphics.Bitmap
import kotlin.math.min

// a blank band is at least this part of the page high: the space between the lines of a staff
// (crossed by the barlines) is not one; lyrics under a system can leave little more than this
private const val MIN_BAND = 0.01f
// a pixel this light is paper
private const val PAPER = 0xE0
// length of each margin tick marking a cut through the music (screen dp)
const val CUT_TICK_DP = 24f
// rows read per getPixels call: one JNI call per row was the cost, the whole page in one IntArray
// would be another 16 MB on top of the bitmap
private const val PIXEL_CHUNK_ROWS = 64

/**
 * The blank bands of a drawn page, where a turned half page can cut without cutting a system
 * (HalfSplit.bandsOf). Read from the picture itself, so it works the same for PDF and MusicXML.
 */
fun Bitmap.blankBands(): List<Band> {
    val rows = BooleanArray(height)
    val chunk = IntArray(width * min(height, PIXEL_CHUNK_ROWS).coerceAtLeast(1))
    var y = 0
    while (y < height) {
        val n = min(PIXEL_CHUNK_ROWS, height - y)
        getPixels(chunk, 0, width, 0, y, width, n)
        blankRows(chunk, width, n).copyInto(rows, y)
        y += n
    }
    return HalfSplit.bandsOf(rows, (height * MIN_BAND).toInt().coerceAtLeast(2))
}

/** Which rows of a tightly packed [width] x [height] pixel chunk are paper (blank). */
internal fun blankRows(pixels: IntArray, width: Int, height: Int): BooleanArray {
    val blank = BooleanArray(height)
    for (y in 0 until height) {
        var i = y * width
        val end = i + width
        var rowBlank = true
        while (i < end) {
            val p = pixels[i++]
            if ((p ushr 24) != 0) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                if (r < PAPER || g < PAPER || (p and 0xFF) < PAPER) {
                    rowBlank = false
                    break
                }
            }
        }
        blank[y] = rowBlank
    }
    return blank
}
