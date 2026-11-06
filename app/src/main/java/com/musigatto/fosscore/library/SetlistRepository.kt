package com.musigatto.fosscore.library

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** The setlists of the library: named, ordered lists of its scores. */
class SetlistRepository(context: Context) {
    private val dao = AppDatabase.get(context.applicationContext).setlistDao()

    fun setlists(): Flow<List<SetlistSummary>> = dao.observeSummaries()

    fun setlist(id: Long): Flow<Setlist?> = dao.observe(id)

    fun entries(setlistId: Long): Flow<List<SetlistEntry>> = dao.observeEntries(setlistId)

    suspend fun create(name: String): Long = dao.insert(Setlist(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun append(setlistId: Long, sheetId: Long) = dao.append(setlistId, sheetId)

    suspend fun remove(itemId: Long) = dao.removeItem(itemId)

    /** The places [itemIds] of a setlist, in this order (after a drag). */
    suspend fun reorder(itemIds: List<Long>) = dao.reorder(itemIds)

    /** Moves the place at [from] to [to] in [entries], the setlist as shown. */
    suspend fun move(entries: List<SetlistEntry>, from: Int, to: Int) {
        val ids = entries.map { it.itemId }
        val order = ids.moved(from, to)
        if (order !== ids) dao.reorder(order)
    }
}
