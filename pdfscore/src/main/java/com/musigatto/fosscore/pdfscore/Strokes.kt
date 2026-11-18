package com.musigatto.fosscore.pdfscore

/**
 * The straight strokes of a page: its lines, and its thin fills (a PDF printed without
 * fonts may draw an ending's bracket and hook so: LilyPond's do), each as the line through its
 * middle.
 */
object Strokes {
    // the thickest a filled rectangle drawn as a stroke is, in points
    private const val THIN = 1.0

    // the shortest, in points
    private const val LONG = 2.0

    fun of(page: PageDrawing): List<DrawnLine> = page.lines + page.shapes.mapNotNull { shape ->
        // thinner than a point, a fill is a stroke whatever its outline (a rectangle, rounded ends)
        val box = shape.box
        when {
            box.height <= THIN && box.width >= LONG -> DrawnLine(box.left, box.centerY, box.right, box.centerY, box.height)
            box.width <= THIN && box.height >= LONG -> DrawnLine(box.centerX, box.top, box.centerX, box.bottom, box.width)
            else -> null
        }
    }
}
