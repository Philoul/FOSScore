package org.fosscore.spike.musicxml

/**
 * Where the notes and systems of one rendered page are, in page pixels (the coordinates of the
 * bitmap MuPDF draws at scale 1), read from Verovio's SVG. Used to draw the playback cursor over
 * the bitmap without rendering the page again.
 */
class PageGeometry private constructor(
    /** Every note id on the page, hidden ones included (to know whether a note is on this page). */
    val noteIds: Set<String>,
    /** Notehead centre and system extent of the visible notes. */
    private val heads: Map<String, Head>,
    /** Page width in SVG units; bitmap pixels = units * bitmapWidth / width. */
    val width: Float
) {
    class Head(val x: Float, val y: Float, val top: Float, val bottom: Float, val space: Float)

    fun head(id: String): Head? = heads[id]

    companion object {
        private val INNER = Regex("""class="definition-scale"[^>]*viewBox="0 0 ([\d.]+) ([\d.]+)"""")
        private val MARGIN = Regex("""class="page-margin" transform="translate\((-?[\d.]+), (-?[\d.]+)\)"""")
        private val SYSTEM = Regex("""class="system"""")
        private val STAFF_LINES = Regex("""class="staff">((?:<path d="M-?\d+ -?\d+ L-?\d+ -?\d+" stroke-width="\d+"/>)+)""")
        private val LINE_Y = Regex("""<path d="M-?\d+ (-?\d+) L""")
        private val NOTE = Regex("""<g id="([^"]+)" class="note"""")
        private val HEAD = Regex(
            """<g id="([^"]+)" class="note"[^>]*><g class="notehead"><use[^>]*transform="translate\((-?\d+), (-?\d+)\)"""
        )

        /** Parses the SVG as Verovio wrote it (before the MuPDF fixes). Units are inner SVG units. */
        fun parse(svg: String): PageGeometry {
            val inner = INNER.find(svg)
            val innerWidth = inner?.groupValues?.get(1)?.toFloat() ?: 1f
            val margin = MARGIN.find(svg)
            val mx = margin?.groupValues?.get(1)?.toFloat() ?: 0f
            val my = margin?.groupValues?.get(2)?.toFloat() ?: 0f

            // Vertical extent and staff space of each system, by its start offset in the text.
            class System(val start: Int, val top: Float, val bottom: Float, val space: Float)
            val starts = SYSTEM.findAll(svg).map { it.range.first }.toList()
            val systems = starts.mapIndexed { i, start ->
                val end = starts.getOrElse(i + 1) { svg.length }
                val ys = STAFF_LINES.findAll(svg.substring(start, end))
                    .flatMap { m -> LINE_Y.findAll(m.groupValues[1]).map { it.groupValues[1].toFloat() } }.toList()
                val space = if (ys.size >= 2) ys[1] - ys[0] else 180f
                System(start, (ys.minOrNull() ?: 0f) + my, (ys.maxOrNull() ?: 0f) + my, space)
            }

            val heads = HEAD.findAll(svg).associate { m ->
                val system = systems.lastOrNull { it.start < m.range.first }
                val space = system?.space ?: 180f
                // The notehead glyph starts at x and is about 1.2 staff spaces wide.
                m.groupValues[1] to Head(
                    m.groupValues[2].toFloat() + mx + space * 0.6f,
                    m.groupValues[3].toFloat() + my,
                    system?.top ?: 0f,
                    system?.bottom ?: 0f,
                    space
                )
            }
            return PageGeometry(NOTE.findAll(svg).map { it.groupValues[1] }.toSet(), heads, innerWidth)
        }
    }
}
