package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.pdfscore.FollowPlace
import com.musigatto.fosscore.score.MusicPlaces
import java.util.Locale

/** A page's size, in the units its music was read in (points for a drawn page, pixels for a scan). */
internal data class PageSize(val width: Double, val height: Double)

/**
 * Where the music of a PDF was read, page by page (PDF pages, 0 up): kept beside its companion
 * ([PdfCompanion]), so the playback bar follows the notes on the PDF itself and its pages turn
 * with the music. Each page is in the units it was read in: the bar is drawn in fractions of it.
 */
internal class CompanionPlaces private constructor(
    private val pages: Map<Int, PageGeometry>,
    private val pageOfNote: Map<String, Int>,
) : MusicPlaces {
    override fun geometry(index: Int): PageGeometry? = pages[index]

    override suspend fun pageOf(id: String): Int = pageOfNote[id] ?: -1

    companion object {
        private const val HEADER = "fosscore-places 1"

        private const val READER = "reader"

        /** [places] of the notes on the PDF [pages] (by PDF page index), as text to keep, read by the [reader] of that version. */
        fun write(pages: Map<Int, PageSize>, places: Map<String, FollowPlace>, reader: Int = 0): String = buildString {
            append(HEADER).append('\n')
            append("$READER $reader\n")
            for ((index, size) in pages.toSortedMap()) append("page $index ${n(size.width)} ${n(size.height)}\n")
            for ((id, p) in places) {
                append("note $id ${p.page} ${n(p.x)} ${n(p.y)} ${n(p.top)} ${n(p.bottom)} ${n(p.space)} ${n(p.lineEnd)}\n")
            }
        }

        private fun n(v: Double) = String.format(Locale.ROOT, "%.2f", v)

        /** The version of the reader that wrote [text] ([write]); null when it does not say. */
        fun readerOf(text: String): Int? = text.lineSequence().take(2).toList().takeIf { it.firstOrNull()?.trim() == HEADER }
            ?.getOrNull(1)?.trim()?.split(' ')?.takeIf { it.size == 2 && it[0] == READER }?.get(1)?.toIntOrNull()

        /** The places [write] kept; none when the text is not theirs. */
        fun read(text: String): CompanionPlaces {
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != HEADER) return CompanionPlaces(emptyMap(), emptyMap())
            val sizes = HashMap<Int, PageSize>()
            val heads = HashMap<Int, HashMap<String, PageGeometry.Head>>()
            val pageOf = HashMap<String, Int>()
            for (line in lines) {
                val f = line.trim().split(' ')
                when {
                    f[0] == "page" && f.size == 4 -> {
                        val index = f[1].toIntOrNull() ?: continue
                        val w = f[2].toDoubleOrNull() ?: continue
                        val h = f[3].toDoubleOrNull() ?: continue
                        sizes[index] = PageSize(w, h)
                    }
                    f[0] == "note" && f.size == 9 -> {
                        val page = f[2].toIntOrNull() ?: continue
                        val v = f.subList(3, 9).map { it.toFloatOrNull() ?: Float.NaN }
                        if (v.any { it.isNaN() }) continue
                        heads.getOrPut(page) { HashMap() }[f[1]] = PageGeometry.Head(v[0], v[1], v[2], v[3], v[4], v[5])
                        pageOf[f[1]] = page
                    }
                }
            }
            val pages = sizes.mapValues { (index, size) ->
                PageGeometry.of(size.width.toFloat(), size.height.toFloat(), heads[index].orEmpty())
            }
            return CompanionPlaces(pages, pageOf.filterValues { it in pages })
        }
    }
}
