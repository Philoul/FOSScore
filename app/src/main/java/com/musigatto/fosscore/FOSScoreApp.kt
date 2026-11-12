package com.musigatto.fosscore

import android.app.Application
import com.musigatto.fosscore.library.AppDatabase
import com.musigatto.fosscore.library.LayerRepository
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.StampRepository
import com.musigatto.fosscore.library.StrokeRepository
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.sound.SoundBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// ponytail: hand-rolled DI, one DB + four repos; move to Hilt when the graph grows
class FOSScoreApp : Application() {
    val repository: LibraryRepository by lazy { LibraryRepository(this) }
    val stampRepository: StampRepository by lazy { StampRepository(this) }
    val strokeRepository: StrokeRepository by lazy { StrokeRepository(this) }
    val layerRepository: LayerRepository by lazy { LayerRepository(AppDatabase.get(this)) }

    // cleanup work that must outlive the UI (e.g. MuPdfDoc.close())
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // MusicXML scores laid out and drawn ahead, whenever the user leaves the tablet alone
        ScorePrecompute.start(this, applicationScope, repository.sheets())
        // a sound bank whose preparation was cut short when the app closed goes on by itself
        SoundBank.prepareAll(this)
    }
}
