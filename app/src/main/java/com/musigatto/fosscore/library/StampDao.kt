package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StampDao {
    // one page only: see StrokeDao.observePage
    @Query("SELECT * FROM stamps WHERE sheetId = :sheetId AND page = :page ORDER BY id")
    fun observePage(sheetId: Long, page: Int): Flow<List<Stamp>>

    // one page and its neighbours: see StrokeDao.observeRange
    @Query("SELECT * FROM stamps WHERE sheetId = :sheetId AND page BETWEEN :from AND :to ORDER BY id")
    fun observeRange(sheetId: Long, from: Int, to: Int): Flow<List<Stamp>>

    // the stamps of a MusicXML score: they hang on measures, not on pages (score.MeasureAnchors)
    @Query("SELECT * FROM stamps WHERE sheetId = :sheetId AND measure IS NOT NULL ORDER BY id")
    fun observeAnchored(sheetId: Long): Flow<List<Stamp>>

    @Query("DELETE FROM stamps WHERE sheetId = :sheetId AND measure IN (:measures)")
    suspend fun deleteMeasures(sheetId: Long, measures: List<Int>)

    @Insert
    suspend fun insert(stamp: Stamp): Long

    @Insert
    suspend fun insertAll(stamps: List<Stamp>)

    @Update
    suspend fun update(stamp: Stamp)

    @Delete
    suspend fun delete(stamp: Stamp)

    @Delete
    suspend fun deleteAll(stamps: List<Stamp>)

    @Query("DELETE FROM stamps WHERE sheetId = :sheetId")
    suspend fun deleteForSheet(sheetId: Long)

    @Query("DELETE FROM stamps WHERE sheetId = :sheetId AND page = :page")
    suspend fun deletePage(sheetId: Long, page: Int)

    @Query("SELECT layer, COUNT(*) AS n FROM stamps WHERE sheetId = :sheetId GROUP BY layer")
    suspend fun marksPerLayer(sheetId: Long): List<LayerCount>

    @Query("DELETE FROM stamps WHERE layer = :layer")
    suspend fun deleteByLayer(layer: Long)
}
