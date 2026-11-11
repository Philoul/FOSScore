package com.musigatto.fosscore.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A tag on a score, as the library list needs it: which score, and the tag's name. */
data class SheetTagName(val sheetId: Long, val tagId: Long, val name: String)

/**
 * The free tags (kind FREE) and the scores they are on. Genres are tags of their own kind, still
 * kept as text on the score until their field is built: these queries leave them out.
 */
@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE kind = 'FREE' ORDER BY name COLLATE NOCASE, id")
    fun observeFree(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE kind = 'FREE'")
    suspend fun free(): List<Tag>

    @Query(
        "SELECT st.sheetId AS sheetId, t.id AS tagId, t.name AS name FROM sheet_tags st " +
            "JOIN tags t ON t.id = st.tagId WHERE t.kind = 'FREE' ORDER BY t.name COLLATE NOCASE, t.id"
    )
    fun observeSheetTags(): Flow<List<SheetTagName>>

    @Query(
        "SELECT t.* FROM tags t JOIN sheet_tags st ON st.tagId = t.id " +
            "WHERE st.sheetId = :sheetId AND t.kind = 'FREE' ORDER BY t.name COLLATE NOCASE, t.id"
    )
    suspend fun tagsOf(sheetId: Long): List<Tag>

    /** The free tags as the tags screen lists them: name and how many scores carry each. */
    @Query(
        "SELECT t.id AS id, t.name AS name, COUNT(st.sheetId) AS size FROM tags t " +
            "LEFT JOIN sheet_tags st ON st.tagId = t.id WHERE t.kind = 'FREE' " +
            "GROUP BY t.id ORDER BY t.name COLLATE NOCASE, t.id"
    )
    fun observeSummaries(): Flow<List<GroupSummary>>

    @Query("SELECT name FROM tags WHERE id = :id")
    fun observeName(id: Long): Flow<String?>

    /** The scores carrying the tag [tagId]; each is its own entry. */
    @Query(
        "SELECT sh.id AS entryKey, sh.* FROM sheet_tags st JOIN sheets sh ON sh.id = st.sheetId " +
            "WHERE st.tagId = :tagId ORDER BY sh.title COLLATE NOCASE, sh.id"
    )
    fun observeEntries(tagId: Long): Flow<List<GroupEntry>>

    /** Every tag, of every kind: their names are unique across kinds. */
    @Query("SELECT * FROM tags")
    suspend fun all(): List<Tag>

    @Insert
    suspend fun insert(tag: Tag): Long

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sheet_tags WHERE tagId = :tagId")
    suspend fun unlinkAll(tagId: Long)

    @Query("DELETE FROM sheet_tags WHERE sheetId = :sheetId AND tagId = :tagId")
    suspend fun unlink(sheetId: Long, tagId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(links: List<SheetTag>)

    @Query("DELETE FROM sheet_tags WHERE sheetId = :sheetId AND tagId IN (SELECT id FROM tags WHERE kind = 'FREE')")
    suspend fun unlinkFree(sheetId: Long)
}
