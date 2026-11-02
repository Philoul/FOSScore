package com.musigatto.fosscore.musicxml

/**
 * Where the notes, systems and measures of one page are, read from Verovio's SVG. Lets the viewer
 * draw a playback cursor over the page bitmap without rendering the page again, and hang
 * annotations on a measure rather than on a place of the page.
 *
 * Positions are in SVG units: multiply by `bitmapWidth / width` to get bitmap pixels.
 */
class PageGeometry private constructor(
    /** Every note id on the page, hidden ones included (to know whether a note is on this page). */
    val noteIds: Set<String>,
    private val heads: Map<String, Head>,
    /** Page width in SVG units. */
    val width: Float,
    /** Page height in SVG units. */
    val height: Float = 0f,
    /** The measures of the page, in reading order. */
    val measures: List<Measure> = emptyList(),
) {
    /** Notehead centre ([x], [y]), the vertical extent of its system and the staff [space]. */
    class Head(val x: Float, val y: Float, val top: Float, val bottom: Float, val space: Float)

    /**
     * A measure: its number as the file writes it ([n], "" without one, free text such as "12a"
     * or "X1"), its [staves], and its [rank]: its place in the file (1 = first `<measure>` of a
     * part), from the id [MusicXmlScore] gives it ([measureId]); null without that id.
     */
    class Measure(val n: String, val staves: List<StaffBox>, val rank: Int? = null)

    /**
     * One staff of a measure: staff number [n] (1 = top staff of the score), from the [left] to
     * the [right] end of its lines, [top] and [bottom] line, and the [space] between two lines.
     * Its music starts at [start], the left of the measure's first notehead: after the clef, key
     * and time signature when the measure begins a line ([left] when it has no note).
     */
    class StaffBox(
        val n: Int,
        val left: Float,
        val right: Float,
        val top: Float,
        val bottom: Float,
        val space: Float,
        val start: Float = left,
    )

    /** The visible notehead with this id, or null (hidden note, or not on this page). */
    fun head(id: String): Head? = heads[id]

    companion object {
        private val INNER = Regex("""class="definition-scale"[^>]*viewBox="0 0 ([\d.]+) ([\d.]+)"""")
        private val MARGIN = Regex("""class="page-margin" transform="translate\((-?[\d.]+), (-?[\d.]+)\)"""")
        private val SYSTEM = Regex("""class="system"""")
        // a staff's lines; the staff may carry its number (data-n, see VerovioOptions)
        private val STAFF_LINES = Regex("""class="staff"(?: data-n="(\d+)")?>((?:<path d="M-?\d+ -?\d+ L-?\d+ -?\d+" stroke-width="\d+"/>)+)""")
        private val MEASURE = Regex("""<g id="([^"]*)" class="measure"(?: data-n="([^"]*)")?>""")
        private val LINE = Regex("""<path d="M(-?\d+) (-?\d+) L(-?\d+) """)
        private val LINE_Y = Regex("""<path d="M-?\d+ (-?\d+) L""")
        private val NOTE = Regex("""<g id="([^"]+)" class="note"""")
        private val HEAD = Regex(
            """<g id="([^"]+)" class="note"[^>]*><g class="notehead"><use[^>]*transform="translate\((-?\d+), (-?\d+)\)"""
        )

        // Verovio's default staff space at scale 100 (1/10 mm units), used when a system has no lines.
        private const val DEFAULT_SPACE = 180f

        private const val MEASURE_ID = "fosscore-measure-"

        /** The id of the measure at [rank] in the file (1 = first), which Verovio keeps in its SVG. */
        fun measureId(rank: Int): String = "$MEASURE_ID$rank"

        /**
         * Parses the SVG as Verovio wrote it, with `svgFormatRaw` (one line, before the MuPDF fixes).
         */
        fun parse(svg: String): PageGeometry {
            val inner = INNER.find(svg)
            val innerWidth = inner?.groupValues?.get(1)?.toFloat() ?: 1f
            val innerHeight = inner?.groupValues?.get(2)?.toFloat() ?: 0f
            val margin = MARGIN.find(svg)
            val mx = margin?.groupValues?.get(1)?.toFloat() ?: 0f
            val my = margin?.groupValues?.get(2)?.toFloat() ?: 0f

            // Vertical extent and staff space of each system, by its start offset in the text.
            class System(val start: Int, val top: Float, val bottom: Float, val space: Float)
            val starts = SYSTEM.findAll(svg).map { it.range.first }.toList()
            val systems = starts.mapIndexed { i, start ->
                val end = starts.getOrElse(i + 1) { svg.length }
                val ys = STAFF_LINES.findAll(svg.substring(start, end))
                    .flatMap { m -> LINE_Y.findAll(m.groupValues[2]).map { it.groupValues[1].toFloat() } }.toList()
                val space = if (ys.size >= 2) ys[1] - ys[0] else DEFAULT_SPACE
                System(start, (ys.minOrNull() ?: 0f) + my, (ys.maxOrNull() ?: 0f) + my, space)
            }

            // an invisible note (a line of lyrics, a hidden voice) gets no cursor: it is not drawn
            val heads = HEAD.findAll(svg).filterNot { "visibility=\"hidden\"" in it.value }.associate { m ->
                val system = systems.lastOrNull { it.start < m.range.first }
                val space = system?.space ?: DEFAULT_SPACE
                // The notehead glyph starts at x and is about 1.2 staff spaces wide.
                m.groupValues[1] to Head(
                    m.groupValues[2].toFloat() + mx + space * 0.6f,
                    m.groupValues[3].toFloat() + my,
                    system?.top ?: 0f,
                    system?.bottom ?: 0f,
                    space
                )
            }
            // Verovio draws the staff lines measure by measure: their ends are the measure's ends
            val measureStarts = MEASURE.findAll(svg).toList()
            val measures = measureStarts.mapIndexed { i, m ->
                val end = measureStarts.getOrNull(i + 1)?.range?.first ?: svg.length
                val body = svg.substring(m.range.last, end)
                // the first notehead drawn in the measure, on any staff: the staves start together
                val firstHead = HEAD.findAll(body).filterNot { "visibility=\"hidden\"" in it.value }
                    .minOfOrNull { it.groupValues[2].toFloat() + mx }
                val staves = STAFF_LINES.findAll(body).mapIndexedNotNull { k, staff ->
                    val lines = LINE.findAll(staff.groupValues[2]).toList()
                    val ys = lines.map { it.groupValues[2].toFloat() }
                    lines.firstOrNull()?.let { first ->
                        val left = first.groupValues[1].toFloat() + mx
                        val right = first.groupValues[3].toFloat() + mx
                        StaffBox(
                            n = staff.groupValues[1].toIntOrNull() ?: (k + 1),
                            left = left,
                            right = right,
                            top = ys.min() + my,
                            bottom = ys.max() + my,
                            space = if (ys.size >= 2) (ys.max() - ys.min()) / (ys.size - 1) else DEFAULT_SPACE,
                            start = firstHead?.takeIf { it > left && it < right } ?: left,
                        )
                    }
                }.toList()
                val rank = m.groupValues[1].takeIf { it.startsWith(MEASURE_ID) }?.removePrefix(MEASURE_ID)?.toIntOrNull()
                Measure(m.groupValues[2], staves, rank)
            }.filter { it.staves.isNotEmpty() }
            return PageGeometry(NOTE.findAll(svg).map { it.groupValues[1] }.toSet(), heads, innerWidth, innerHeight, measures)
        }
    }
}
