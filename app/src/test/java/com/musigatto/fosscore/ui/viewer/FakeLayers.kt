package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Layer
import com.musigatto.fosscore.library.LayerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [LayerStore]: the flow emits on every change, like Room's does. */
internal class FakeLayers(initial: List<Layer> = emptyList()) : LayerStore {
    val state = MutableStateFlow(initial)
    val inserted = mutableListOf<Layer>()
    val deleted = mutableListOf<Long>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    override fun observe(sheetId: Long): Flow<List<Layer>> = state

    override suspend fun insert(layer: Layer): Long {
        val id = nextId++
        inserted += layer
        state.value = state.value + layer.copy(id = id)
        return id
    }

    override suspend fun rename(id: Long, name: String) {
        state.value = state.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun setVisible(id: Long, visible: Boolean) {
        state.value = state.value.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    override suspend fun deleteWithMarks(id: Long) {
        deleted += id
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun markCounts(sheetId: Long): Map<Long, Int> = emptyMap()
}