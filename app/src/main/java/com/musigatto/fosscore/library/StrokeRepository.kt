package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StrokeRepository(context: Context) {
    private val dao = AppDatabase.get(context).strokeDao()

    fun observe(hash: String): Flow<List<Stroke>> = dao.observeBySheet(hash)

    suspend fun insert(stroke: Stroke): Long = dao.insert(stroke)

    suspend fun update(stroke: Stroke) = dao.update(stroke)

    suspend fun delete(stroke: Stroke) = dao.delete(stroke)

    suspend fun deleteAll(strokes: List<Stroke>) = dao.deleteAll(strokes)

    // the eraser splits a stroke into several: the surviving segments are re-inserted
    suspend fun insertAll(strokes: List<Stroke>) = dao.insertAll(strokes)

    suspend fun listBySheet(hash: String): List<Stroke> = dao.listBySheet(hash)

    // restores a page to a snapshot (undo): deletes the current state and inserts the snapshot
    suspend fun restorePage(hash: String, page: Int, strokes: List<Stroke>) {
        dao.deletePage(hash, page)
        dao.insertAll(strokes)
    }
}
