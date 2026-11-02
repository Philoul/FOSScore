package com.musigatto.fosscore.score

import android.content.Context
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.StampStore
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.StrokeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The marks of a MusicXML score ([pages]), for the viewer's editing code: it reads and writes the
 * stamps and the ink of a page in the page's coordinates, as on a PDF, and they are kept hung on
 * the measures ([MeasureAnchors]). A page shows the marks of its measures, once its geometry is
 * known; a mark written on a page hangs on the staff it is over.
 */
class MusicXmlMarks(context: Context, private val pages: MusicXmlPages) {
    private val stampDao = AppDatabase.get(context).stampDao()
    private val strokeDao = AppDatabase.get(context).strokeDao()

    private fun slots(page: Int): List<StaffSlot> = pages.geometry(page)?.let(MeasureAnchors::slotsOf).orEmpty()

    // where a line of music hands over to the next on [page]: through its blank paper, as drawn
    private fun boundaries(page: Int, slots: List<StaffSlot>) = MeasureAnchors.boundaries(slots, pages.blankBands(page))

    private fun anchorOf(stamp: Stamp, page: Int = stamp.page) =
        slots(page).let { MeasureAnchors.anchor(stamp, it, boundaries(page, it)) }

    private fun anchorOf(stroke: Stroke, page: Int = stroke.page) =
        slots(page).let { MeasureAnchors.anchor(stroke, it, boundaries(page, it)) }

    // the measures of a page: what an undo of that page puts back
    private fun measures(slots: List<StaffSlot>): List<Int> = slots.map { it.measure }.distinct()

    val stamps: StampStore = object : StampStore {
        override fun observe(sheetId: Long, page: Int): Flow<List<Stamp>> =
            combine(stampDao.observeAnchored(sheetId), pages.geometryVersion) { rows, _ ->
                val slots = slots(page)
                rows.mapNotNull { MeasureAnchors.project(it, slots, page) }
            }

        override suspend fun insert(stamp: Stamp): Long =
            anchorOf(stamp)?.let { stampDao.insert(it) } ?: -1

        override suspend fun update(stamp: Stamp) {
            anchorOf(stamp)?.let { stampDao.update(it) }
        }

        // a row is deleted by its id: the page's copy will do
        override suspend fun delete(stamp: Stamp) = stampDao.delete(stamp)

        override suspend fun deleteAll(stamps: List<Stamp>) = stampDao.deleteAll(stamps)

        override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) {
            val slots = slots(page)
            if (slots.isEmpty()) return
            stampDao.deleteMeasures(sheetId, measures(slots))
            stampDao.insertAll(stamps.mapNotNull { anchorOf(it, page) })
        }
    }

    val strokes: StrokeStore = object : StrokeStore {
        override fun observe(sheetId: Long, page: Int): Flow<List<Stroke>> =
            combine(strokeDao.observeAnchored(sheetId), pages.geometryVersion) { rows, _ ->
                val slots = slots(page)
                rows.mapNotNull { MeasureAnchors.project(it, slots, page) }
            }

        override suspend fun insert(stroke: Stroke): Long =
            anchorOf(stroke)?.let { strokeDao.insert(it) } ?: -1

        override suspend fun insertAll(strokes: List<Stroke>) {
            strokeDao.insertAll(strokes.mapNotNull { anchorOf(it) })
        }

        override suspend fun update(stroke: Stroke) {
            anchorOf(stroke)?.let { strokeDao.update(it) }
        }

        override suspend fun delete(stroke: Stroke) = strokeDao.delete(stroke)

        override suspend fun deleteAll(strokes: List<Stroke>) = strokeDao.deleteAll(strokes)

        override suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>) {
            val slots = slots(page)
            if (slots.isEmpty()) return
            strokeDao.deleteMeasures(sheetId, measures(slots))
            strokeDao.insertAll(strokes.mapNotNull { anchorOf(it, page) })
        }
    }
}
