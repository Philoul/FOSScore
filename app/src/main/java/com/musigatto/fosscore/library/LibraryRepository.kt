package com.musigatto.fosscore.library

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.musigatto.fosscore.R
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed class ImportResult {
    data class Ok(val sheet: Sheet) : ImportResult()
    data class Duplicate(val existing: Sheet) : ImportResult()
    // The UI resolves messageRes in the selected language; detail is an untranslated system message
    data class Error(@StringRes val messageRes: Int, val detail: String? = null) : ImportResult()
}

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.sheetDao()
    private val stampDao = db.stampDao()
    private val strokeDao = db.strokeDao()

    fun sheets(): Flow<List<Sheet>> = dao.observeAll()

    suspend fun byHash(hash: String): Sheet? = dao.byHash(hash)

    suspend fun importSheet(uri: Uri): ImportResult = try {
        val fileName = appContext.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment ?: "score.pdf"
        val dir = File(appContext.filesDir, "sheets").apply { mkdirs() }
        val destination = File(dir, "${UUID.randomUUID()}.pdf")
        val hash = try {
            val input = appContext.contentResolver.openInputStream(uri)
            if (input == null) return ImportResult.Error(R.string.error_file_open)
            SheetImporter.import(input, destination)
        } catch (e: IOException) {
            destination.delete()
            return ImportResult.Error(R.string.import_error_read)
        }

        dao.byHash(hash)?.let { existing ->
            destination.delete()
            return ImportResult.Duplicate(existing)
        }

        val sheet = Sheet(
            fileName = fileName,
            path = destination.absolutePath,
            hash = hash,
            title = SheetImporter.titleFrom(fileName),
            composer = "",
            genre = "",
            musicalKey = "",
            tags = ""
        )
        ImportResult.Ok(sheet.copy(id = dao.insert(sheet)))
    } catch (e: Exception) {
        ImportResult.Error(R.string.import_error_failed, e.message)
    }

    suspend fun save(sheet: Sheet) = dao.update(sheet)

    suspend fun delete(sheet: Sheet) {
        dao.delete(sheet)
        stampDao.deleteForSheet(sheet.hash)
        strokeDao.deleteForSheet(sheet.hash)
        try {
            File(sheet.path).delete()
        } catch (_: Exception) {
            // ponytail: row already deleted; a missing/failing file doesn't block the deletion
        }
    }
}
