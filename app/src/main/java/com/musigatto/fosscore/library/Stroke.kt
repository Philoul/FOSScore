package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "strokes",
    indices = [Index("sheetHash", "page")]
)
data class Stroke(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetHash: String,
    val page: Int,
    // normalized points (0..1) encoded as "x;y|x;y|…" (see encodePoints)
    val points: String,
    // width as a fraction of the page height
    val width: Float,
    // ARGB ink colour (null = theme colour)
    val color: Int? = null
)