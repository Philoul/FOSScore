package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StrokeRepository(context: Context) : StrokeStore {
    private val db = AppDatabase.get(context)
    private val dao = db.strokeDao()

    override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stroke>>> =
        dao.observeRange(sheetId, pages.first, pages.last).map { rows -> rows.groupBy { it.page } }

    override suspend fun insert(stroke: Stroke): Long = dao.insert(stroke)

    override suspend fun update(stroke: Stroke) = dao.update(stroke)

    // a moved or stretched word: one transaction, not one write per stroke
    override suspend fun updateAll(strokes: List<Stroke>) = dao.updateAll(strokes)

    override suspend fun delete(stroke: Stroke) = dao.delete(stroke)

    override suspend fun deleteAll(strokes: List<Stroke>) = dao.deleteAll(strokes)

    // the eraser cuts one stroke into pieces: the survivors go back in one write
    override suspend fun insertAll(strokes: List<Stroke>) = dao.insertAll(strokes)

    suspend fun listBySheet(sheetId: Long): List<Stroke> = dao.listBySheet(sheetId)

    // puts a page back from an undo snapshot: drops what is there, inserts the snapshot. One
    // transaction, so a crash cannot leave the page half restored.
    override suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>) {
        db.withTransaction {
            dao.deletePage(sheetId, page)
            dao.insertAll(strokes)
        }
    }
}
