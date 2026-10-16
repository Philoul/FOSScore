package org.fosscore.spike.musicxml

/**
 * Two things Verovio's MusicXML import drops, put back in the MEI it exports, so that an
 * unmodified Verovio can be used (no patch to maintain at each Verovio update):
 * - lyrics placed above the staff (`<lyric placement="above">` is ignored on import);
 * - the size of the page-header lines (`<credit-words font-size>` is ignored, so the title
 *   came out as small as the composer).
 */
object MeiFixes {
    private val PG_HEAD = Regex("""<pgHead\b[^>]*>(.*?)</pgHead>""", RegexOption.DOT_MATCHES_ALL)
    private val REND = Regex("""<rend\b""")

    /** True when [lyricsAbove] or [creditSizes] ask for a change, so the MEI round trip is worth it. */
    fun needed(lyricsAbove: Boolean, creditSizes: List<Int?>) = lyricsAbove || creditSizes.any { it != null }

    fun apply(mei: String, lyricsAbove: Boolean, creditSizes: List<Int?>): String {
        var out = if (lyricsAbove) mei.replace("<verse ", "<verse place=\"above\" ") else mei
        // Verovio writes one <rend> per first-page credit, in file order, in the first <pgHead>.
        val head = PG_HEAD.find(out) ?: return out
        var index = 0
        val sized = REND.replace(head.groupValues[1]) { m ->
            val size = creditSizes.getOrNull(index++)
            if (size == null) m.value else "${m.value} fontsize=\"$size%\""
        }
        val range = head.groups[1]!!.range
        out = out.replaceRange(range, sized)
        return out
    }
}
