package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.StampRepository
import com.musigatto.fosscore.library.StrokeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    val stampRepository: StampRepository by lazy { StampRepository(this) }
    val strokeRepository: StrokeRepository by lazy { StrokeRepository(this) }
    // ponytail: manual DI with 1 DB + 3 repos; Hilt once the graph grows
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    // cleanup tasks that must outlive the composition (e.g. PdfRenderer's close())
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}