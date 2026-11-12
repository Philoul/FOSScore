package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LayerDao {
    @Query("SELECT * FROM layers WHERE sheetId = :sheetId ORDER BY id")
    fun observe(sheetId: Long): Flow<List<Layer>>

    @Insert
    suspend fun insert(layer: Layer): Long

    @Query("UPDATE layers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE layers SET visible = :visible WHERE id = :id")
    suspend fun setVisible(id: Long, visible: Boolean)

    @Query("DELETE FROM layers WHERE id = :id")
    suspend fun delete(id: Long)
}