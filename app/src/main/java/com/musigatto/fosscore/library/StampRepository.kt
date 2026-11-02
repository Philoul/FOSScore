package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

class StampRepository(context: Context) : StampStore {
    private val dao = AppDatabase.get(context).stampDao()

    override fun observe(sheetId: Long, page: Int): Flow<List<Stamp>> = dao.observePage(sheetId, page)

    override suspend fun insert(stamp: Stamp): Long = dao.insert(stamp)

    override suspend fun update(stamp: Stamp) = dao.update(stamp)

    override suspend fun delete(stamp: Stamp) = dao.delete(stamp)

    override suspend fun deleteAll(stamps: List<Stamp>) = dao.deleteAll(stamps)

    // puts a page back from an undo snapshot: drops what is there, inserts the snapshot
    override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) {
        dao.deletePage(sheetId, page)
        dao.insertAll(stamps)
    }
}
