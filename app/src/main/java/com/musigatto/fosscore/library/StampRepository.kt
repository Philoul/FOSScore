package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StampRepository(context: Context) : StampStore {
    private val db = AppDatabase.get(context)
    private val dao = db.stampDao()

    override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stamp>>> =
        dao.observeRange(sheetId, pages.first, pages.last).map { rows -> rows.groupBy { it.page } }

    override suspend fun insert(stamp: Stamp): Long = dao.insert(stamp)

    override suspend fun update(stamp: Stamp) = dao.update(stamp)

    override suspend fun delete(stamp: Stamp) = dao.delete(stamp)

    override suspend fun deleteAll(stamps: List<Stamp>) = dao.deleteAll(stamps)

    // puts a page back from an undo snapshot: drops what is there, inserts the snapshot. One
    // transaction, so a crash cannot leave the page half restored.
    override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) {
        db.withTransaction {
            dao.deletePage(sheetId, page)
            dao.insertAll(stamps)
        }
    }
}
