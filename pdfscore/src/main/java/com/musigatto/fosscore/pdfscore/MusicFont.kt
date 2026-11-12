package com.musigatto.fosscore.pdfscore

/**
 * A music symbol as one font draws it: its SMuFL [name] and its outline, in staff spaces (SMuFL
 * fonts are 4 spaces per em), y down, from the font's origin.
 */
class FontSymbol(val font: String, val name: String, val polygons: List<List<Point>>) {
    val box: Box = bounds(polygons)

    companion object {
        fun bounds(polygons: List<List<Point>>): Box {
            val points = polygons.flatten()
            if (points.isEmpty()) return Box(0.0, 0.0, 0.0, 0.0)
            return Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        }
    }
}

/** A music font's symbols. */
class MusicFont(val name: String, val symbols: List<FontSymbol>) {
    companion object {
        private val GLYPH = Regex("""<g c="([0-9A-Fa-f]+)"[^>]* n="([^"]+)"""")
        private val UNITS = Regex("""units-per-em="(\d+)"""")
        private val PATH = Regex("""\sd="([^"]*)"""")

        /**
         * A font in Verovio's data format, which the app already holds for its MusicXML rendering:
         * `<name>.xml` lists the glyphs (code and SMuFL name), `<name>/<code>.xml` holds each one's
         * SVG path in font units, y up. [read] gives a file's text by its path in that folder, null
         * when missing: a font is added by adding its files, whatever reads them (assets, disk).
         */
        fun readVerovio(name: String, read: (String) -> String?): MusicFont? {
            val index = read("$name.xml") ?: return null
            val space = (UNITS.find(index)?.groupValues?.get(1)?.toDouble() ?: 1000.0) / 4
            val symbols = GLYPH.findAll(index).mapNotNull { match ->
                val (code, symbolName) = match.destructured
                val d = read("$name/$code.xml")?.let { PATH.find(it)?.groupValues?.get(1) } ?: return@mapNotNull null
                val polygons = SvgPath.parse(d)?.map { polygon -> polygon.map { Point(it.x / space, -it.y / space) } }
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                FontSymbol(name, symbolName, polygons)
            }.toList()
            return MusicFont(name, symbols)
        }
    }
}
