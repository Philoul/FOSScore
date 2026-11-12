package com.musigatto.fosscore.ui.viewer

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A deleted or erased mark takes its selection box with it: no empty square stays on the page. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionClearedTest {

    private object NoStamps : StampStore {
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stamp>>> = emptyFlow()
        override suspend fun insert(stamp: Stamp) = 1L
        override suspend fun update(stamp: Stamp) = Unit
        override suspend fun delete(stamp: Stamp) = Unit
        override suspend fun deleteAll(stamps: List<Stamp>) = Unit
        override suspend fun restorePage(sheetId: Long, page: Int, stamps: List<Stamp>) = Unit
    }

    private object NoStrokes : StrokeStore {
        override fun observe(sheetId: Long, pages: IntRange): Flow<Map<Int, List<Stroke>>> = emptyFlow()
        override suspend fun insert(stroke: Stroke) = 1L
        override suspend fun insertAll(strokes: List<Stroke>) = Unit
        override suspend fun update(stroke: Stroke) = Unit
        override suspend fun updateAll(strokes: List<Stroke>) = Unit
        override suspend fun delete(stroke: Stroke) = Unit
        override suspend fun deleteAll(strokes: List<Stroke>) = Unit
        override suspend fun restorePage(sheetId: Long, page: Int, strokes: List<Stroke>) = Unit
    }

    private val stamp = Stamp(id = 7, sheetId = 1, page = 0, symbol = "TEXT", x = 0.5f, y = 0.5f, size = 0.05f, text = "rit.")
    private val stroke = Stroke(id = 9, sheetId = 1, page = 0, points = "0.1,0.1|0.2,0.2", width = 0.01f)
    private val page = PageEdit(listOf(stamp), listOf(stroke))

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun noMainDispatcher() = Dispatchers.resetMain()

    // a text moved (its box drawn where the finger left it), then the ink around it
    private fun selected() = AnnotationViewModel(1, NoStamps, NoStrokes, FakeLayers()).apply {
        selectedId = stamp.id
        dragStamp = stamp.copy(x = 0.6f)
        selectedStrokeIds = setOf(stroke.id)
        dragStrokes = listOf(stroke)
    }

    @Test
    fun aDeletedSelectionLeavesNoBox() {
        val editor = selected()
        editor.deleteSelection(0, stamp, listOf(stroke), page)
        assertNull(editor.dragStamp)
        assertNull(editor.dragStrokes)
    }

    @Test
    fun anErasedSelectionLeavesNoBox() {
        val editor = selected()
        editor.erase(0, listOf(stamp), listOf(stroke), emptyList(), page)
        assertNull(editor.dragStamp)
        assertNull(editor.dragStrokes)
    }
}
