package com.musigatto.fosscore.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stroke of ink. See [Stamp] for [sheetId], [measure] and [staff]. */
@Entity(
    tableName = "strokes",
    foreignKeys = [ForeignKey(entity = Sheet::class, parentColumns = ["id"], childColumns = ["sheetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sheetId", "page")]
)
data class Stroke(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sheetId: Long,
    val page: Int,
    // points as 0..1, packed as "x,y|x,y|…"
    val points: String,
    // width as a share of the page height
    val width: Float,
    // ARGB ink color (null = theme color)
    val color: Int? = null,
    // on a MusicXML score, the measure and staff this ink hangs on; null = a place on the page
    val measure: Int? = null,
    val staff: Int? = null,
    // the word it was written as: the strokes of one word share an id and move together.
    // 0 = ink from before this column, grouped by distance instead (groupInk)
    val groupId: Long = 0,
    /**
     * What the stroke is: [INK] free ink, [SHAPE] a shape's outline (generated, drawn with its own
     * width or per-kind fill), [SHAPE_FILLED] a shape drawn filled (a solid: the eraser deletes it
     * whole; the export writes it as a filled polygon), [SHAPE_STAFF] a staff: its points come in
     * pairs, each pair one line of the staff, so one stroke draws N lines, [HIGHLIGHTER] free ink
     * drawn translucent (selected and erased like ink, never grouped in words).
     */
    val kind: Int = 0,
    // the layer this mark belongs to; 0 = marks from before layers existed (always visible)
    @ColumnInfo(defaultValue = "0") val layer: Long = 0,
) {
    companion object {
        const val INK = 0
        const val SHAPE = 1
        const val SHAPE_FILLED = 2
        const val SHAPE_STAFF = 3
        const val HIGHLIGHTER = 4

        /** The shapes' column default in old rows: everything that was here before is ink. */
        fun isShape(kind: Int): Boolean = kind == SHAPE || kind == SHAPE_FILLED || kind == SHAPE_STAFF
        fun isFilled(kind: Int): Boolean = kind == SHAPE_FILLED
        fun isHighlight(kind: Int): Boolean = kind == HIGHLIGHTER
    }
}
