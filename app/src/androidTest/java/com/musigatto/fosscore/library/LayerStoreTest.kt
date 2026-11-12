package com.musigatto.fosscore.library

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: LayerStore
    private var sheetId = 0L

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = LayerRepository(db)
        runBlocking {
            sheetId = db.sheetDao().insert(
                Sheet(
                    fileName = "song.pdf",
                    path = "/data/sheets/song.pdf",
                    hash = "0123456789abcdef0123456789abcdef",
                    title = "Song",
                    composer = "Composer",
                    genre = "Folk",
                    musicalKey = "F",
                    tags = "",
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeReturnsLayersInIdOrder() = runBlocking {
        val first = store.insert(Layer(sheetId = sheetId, name = "Capa 1"))
        val second = store.insert(Layer(sheetId = sheetId, name = "Capa 2"))

        assertEquals(
            listOf(Layer(first, sheetId, "Capa 1", true), Layer(second, sheetId, "Capa 2", true)),
            store.observe(sheetId).first(),
        )
    }

    @Test
    fun renameAndVisibilityReachTheStore() = runBlocking {
        val id = store.insert(Layer(sheetId = sheetId, name = ""))

        store.rename(id, "Melodía")
        store.setVisible(id, false)

        val layer = store.observe(sheetId).first().single()
        assertEquals("Melodía", layer.name)
        assertFalse(layer.visible)
    }

    @Test
    fun markCountsSumStampsAndStrokesPerLayer() = runBlocking {
        val one = store.insert(Layer(sheetId = sheetId))
        val two = store.insert(Layer(sheetId = sheetId))
        db.stampDao().insertAll(
            listOf(
                stamp(one), stamp(one),
                stamp(two),
            )
        )
        db.strokeDao().insert(stroke(two))

        assertEquals(mapOf(one to 2, two to 2), store.markCounts(sheetId))
    }

    @Test
    fun deleteWithMarksDeletesTheLayerAndOnlyItsMarks() = runBlocking {
        val one = store.insert(Layer(sheetId = sheetId))
        val two = store.insert(Layer(sheetId = sheetId))
        db.stampDao().insertAll(listOf(stamp(one), stamp(two)))
        db.strokeDao().insert(stroke(one))

        store.deleteWithMarks(one)

        assertEquals(listOf(Layer(two, sheetId, "", true)), store.observe(sheetId).first())
        val stamps = db.stampDao().observeRange(sheetId, 0, 99).first()
        assertEquals(1, stamps.size)
        assertEquals(two, stamps[0].layer)
        val strokes = db.strokeDao().observeRange(sheetId, 0, 99).first()
        assertTrue("the deleted layer's ink is gone too", strokes.isEmpty())
    }

    private fun stamp(layer: Long) = Stamp(
        sheetId = sheetId, page = 0, symbol = "FERMATA", x = .5f, y = .5f, size = .04f, layer = layer,
    )

    private fun stroke(layer: Long) = Stroke(
        sheetId = sheetId, page = 0, points = "0.1,0.1|0.2,0.2", width = 0.003f, layer = layer,
    )
}