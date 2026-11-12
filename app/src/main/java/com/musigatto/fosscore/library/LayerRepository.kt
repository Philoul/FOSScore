package com.musigatto.fosscore.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

interface LayerStore {
    fun observe(sheetId: Long): Flow<List<Layer>>
    suspend fun insert(layer: Layer): Long
    suspend fun rename(id: Long, name: String)
    suspend fun setVisible(id: Long, visible: Boolean)
    /** One transaction: the layer row and all marks it carries. */
    suspend fun deleteWithMarks(id: Long)
    /** Marks per layer for the WHOLE sheet (viewer flows only bring pages in view). */
    suspend fun markCounts(sheetId: Long): Map<Long, Int>
}

class LayerRepository(private val db: AppDatabase) : LayerStore {
    override fun observe(sheetId: Long): Flow<List<Layer>> = db.layerDao().observe(sheetId)

    override suspend fun insert(layer: Layer): Long = db.layerDao().insert(layer)

    override suspend fun rename(id: Long, name: String) = db.layerDao().rename(id, name)

    override suspend fun setVisible(id: Long, visible: Boolean) = db.layerDao().setVisible(id, visible)

    override suspend fun deleteWithMarks(id: Long) {
        db.withTransaction {
            db.stampDao().deleteByLayer(id)
            db.strokeDao().deleteByLayer(id)
            db.layerDao().delete(id)
        }
    }

    override suspend fun markCounts(sheetId: Long): Map<Long, Int> {
        val stampCounts = db.stampDao().marksPerLayer(sheetId).associate { it.layer to it.n }
        val strokeCounts = db.strokeDao().marksPerLayer(sheetId).associate { it.layer to it.n }
        val allLayers = (stampCounts.keys + strokeCounts.keys).toSet()
        return allLayers.associateWith { layer ->
            (stampCounts[layer] ?: 0) + (strokeCounts[layer] ?: 0)
        }
    }
}