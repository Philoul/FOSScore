package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Layer
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.StampStore
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.StrokeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayerStateTest {

    private class RecordingStamps : StampStore {
        val inserted = mutableListOf<Stamp>()
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stamp>>> = emptyFlow()
        override suspend fun insert(stamp: Stamp): Long { inserted += stamp; return 1L }
        override suspend fun update(stamp: Stamp) = Unit
        override suspend fun delete(stamp: Stamp) = Unit
        override suspend fun deleteAll(stamps: List<Stamp>) = Unit
        override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) = Unit
    }

    private class RecordingStrokes : StrokeStore {
        val inserted = mutableListOf<Stroke>()
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stroke>>> = emptyFlow()
        override suspend fun insert(stroke: Stroke): Long { inserted += stroke; return 1L }
        override suspend fun insertAll(strokes: List<Stroke>) = Unit
        override suspend fun update(stroke: Stroke) = Unit
        override suspend fun updateAll(strokes: List<Stroke>) = Unit
        override suspend fun delete(stroke: Stroke) = Unit
        override suspend fun deleteAll(strokes: List<Stroke>) = Unit
        override suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>) = Unit
    }

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun noMainDispatcher() = Dispatchers.resetMain()

    private fun editor(layers: FakeLayers, strokes: RecordingStrokes = RecordingStrokes()) =
        AnnotationViewModel(1, RecordingStamps(), strokes, layers)

    @Test
    fun ensureLayerCreatesOneLayerAndMakesItActive() {
        val layers = FakeLayers()
        val editor = editor(layers)

        editor.ensureLayer()
        editor.ensureLayer()

        assertEquals(1, layers.inserted.size)
        assertEquals(1, editor.layerList.size)
        assertEquals(editor.layerList.single().id, editor.activeLayerId)
    }

    @Test
    fun addInkWritesTheActiveLayerOnTheStoredRow() {
        val layers = FakeLayers(listOf(Layer(7L, 1L, "", true)))
        val strokes = RecordingStrokes()
        val editor = editor(layers, strokes)

        editor.addInk(0, Stroke(sheetId = 1, page = 0, points = "0.1,0.1|0.2,0.2", width = 0.01f), PageEdit(emptyList(), emptyList()))

        assertEquals(7L, strokes.inserted.single().layer)
        // the optimistic copy carries the layer too, but is never filtered by it
        assertEquals(7L, editor.pendingInk.single().layer)
    }

    @Test
    fun deleteLayerClearsHistorySelectionAndMovesTheActiveLayer() {
        val layers = FakeLayers(listOf(Layer(1L, 1L, "", true), Layer(2L, 1L, "", true)))
        val editor = editor(layers)
        editor.setActiveLayer(1L)
        editor.pushUndo(PageEdit(emptyList(), emptyList()), 0)
        editor.selectedId = 5L

        editor.deleteLayer(1L)

        assertEquals(listOf(1L), layers.deleted)
        assertFalse("undo must not revive rows of the deleted layer", editor.canUndo)
        assertFalse(editor.canRedo)
        assertNull(editor.selectedId)
        assertEquals(2L, editor.activeLayerId)
    }

    @Test
    fun deleteLayerDoesNothingForTheOnlyLayer() {
        val layers = FakeLayers(listOf(Layer(1L, 1L, "", true)))
        val editor = editor(layers)

        editor.deleteLayer(1L)

        assertTrue(layers.deleted.isEmpty())
        assertEquals(1, editor.layerList.size)
    }

    @Test
    fun setLayerVisibleReachesTheStoreButNeverHidesTheActiveLayer() {
        val layers = FakeLayers(listOf(Layer(1L, 1L, "", true), Layer(2L, 1L, "", true)))
        val editor = editor(layers)
        editor.setActiveLayer(1L)

        editor.setLayerVisible(1L, false)
        assertTrue("the active layer never hides", editor.layerList.first { it.id == 1L }.visible)

        editor.setLayerVisible(2L, false)
        assertFalse(editor.layerList.first { it.id == 2L }.visible)
    }
}