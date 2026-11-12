package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.StampStore
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.StrokeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticMarkTest {

    private class DelayedStamps(private val insertedId: CompletableDeferred<Long>) : StampStore {
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stamp>>> = emptyFlow()
        override suspend fun insert(stamp: Stamp): Long = insertedId.await()
        override suspend fun update(stamp: Stamp) = Unit
        override suspend fun delete(stamp: Stamp) = Unit
        override suspend fun deleteAll(stamps: List<Stamp>) = Unit
        override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) = Unit
    }

    private class DelayedStrokes(private val insertedId: CompletableDeferred<Long>) : StrokeStore {
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stroke>>> = emptyFlow()
        override suspend fun insert(stroke: Stroke): Long = insertedId.await()
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

    @Test
    fun inkIsVisibleBeforeInsertCompletesAndSettlesIfFlowArrivesFirst() {
        val insertedId = CompletableDeferred<Long>()
        val editor = AnnotationViewModel(1, DelayedStamps(CompletableDeferred()), DelayedStrokes(insertedId), FakeLayers())
        val stroke = Stroke(sheetId = 1, page = 0, points = "0.1,0.1|0.2,0.2", width = 0.01f)

        editor.addInk(0, stroke, PageEdit(emptyList(), emptyList()))
        assertEquals(1, editor.pendingInk.size)
        assertTrue(editor.pendingInk.single().id < 0L)

        editor.settle(emptyList(), listOf(stroke.copy(id = 42)))
        insertedId.complete(42)
        // Room's page flow lands once the row exists: that settle drops the optimistic copy
        editor.settle(emptyList(), listOf(stroke.copy(id = 42)))

        assertTrue(editor.pendingInk.isEmpty())
    }

    @Test
    fun stampIsVisibleBeforeInsertCompletesAndSettlesIfFlowArrivesFirst() {
        val insertedId = CompletableDeferred<Long>()
        val editor = AnnotationViewModel(1, DelayedStamps(insertedId), DelayedStrokes(CompletableDeferred()), FakeLayers())
        val stamp = Stamp(sheetId = 1, page = 0, symbol = "FLAT", x = 0.5f, y = 0.5f, size = 0.05f)

        editor.placeStamp(0, stamp, PageEdit(emptyList(), emptyList()))
        assertEquals(1, editor.pendingStamps.size)
        assertTrue(editor.pendingStamps.single().id < 0L)

        editor.settle(listOf(stamp.copy(id = 42)), emptyList())
        insertedId.complete(42)
        // Room's page flow lands once the row exists: that settle drops the optimistic copy
        editor.settle(listOf(stamp.copy(id = 42)), emptyList())

        assertTrue(editor.pendingStamps.isEmpty())
        assertEquals(42L, editor.selectedId)
    }
}
