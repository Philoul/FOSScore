package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StrokeDao {
    // one page only: the viewer draws one page, and re-reading the whole score on every stroke
    // made the cost of a stroke grow with the score (index: sheetId, page)
    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId AND page = :page ORDER BY id")
    fun observePage(sheetId: Long, page: Int): Flow<List<Stroke>>

    // the viewer's page and its neighbours in one query: a turn is drawn with the ink already in
    // hand, instead of waiting for the page's own query to come back (index: sheetId, page)
    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId AND page BETWEEN :from AND :to ORDER BY id")
    fun observeRange(sheetId: Long, from: Int, to: Int): Flow<List<Stroke>>

    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId ORDER BY page, id")
    suspend fun listBySheet(sheetId: Long): List<Stroke>

    // the ink of a MusicXML score: it hangs on measures, not on pages (score.MeasureAnchors)
    @Query("SELECT * FROM strokes WHERE sheetId = :sheetId AND measure IS NOT NULL ORDER BY id")
    fun observeAnchored(sheetId: Long): Flow<List<Stroke>>

    @Query("DELETE FROM strokes WHERE sheetId = :sheetId AND measure IN (:measures)")
    suspend fun deleteMeasures(sheetId: Long, measures: List<Int>)

    @Insert
    suspend fun insert(stroke: Stroke): Long

    @Insert
    suspend fun insertAll(strokes: List<Stroke>)

    @Update
    suspend fun update(stroke: Stroke)

    @Update
    suspend fun updateAll(strokes: List<Stroke>)

    @Delete
    suspend fun delete(stroke: Stroke)

    @Delete
    suspend fun deleteAll(strokes: List<Stroke>)

    @Query("DELETE FROM strokes WHERE sheetId = :sheetId")
    suspend fun deleteForSheet(sheetId: Long)

    @Query("DELETE FROM strokes WHERE sheetId = :sheetId AND page = :page")
    suspend fun deletePage(sheetId: Long, page: Int)

    @Query("SELECT layer, COUNT(*) AS n FROM strokes WHERE sheetId = :sheetId GROUP BY layer")
    suspend fun marksPerLayer(sheetId: Long): List<LayerCount>

    @Query("DELETE FROM strokes WHERE layer = :layer")
    suspend fun deleteByLayer(layer: Long)
}
