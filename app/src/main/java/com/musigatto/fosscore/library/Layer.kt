package com.musigatto.fosscore.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named annotation layer belonging to a sheet. Marks (stamps and strokes) reference a layer by
 * its [id]. The first layer of a sheet is created when the user starts annotating; migrations
 * create one layer per sheet that already has marks.
 */
@Entity(
    tableName = "layers",
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sheetId")],
)
data class Layer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    // User-given name; empty = default name, shown translated by position (a migration cannot write
    // translated text).
    val name: String = "",
    val visible: Boolean = true,
)

/** How many marks a layer of a sheet has (for the delete dialog and Share). */
data class LayerCount(val layer: Long, val n: Int)