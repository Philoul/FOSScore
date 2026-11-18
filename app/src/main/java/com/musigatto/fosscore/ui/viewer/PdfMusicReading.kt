package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.pdf.MuPdfDoc
import com.musigatto.fosscore.pdf.MuPdfLock
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

/** The [count]th reading that ended, of the PDF [key] (null before any). */
internal data class ReadingEnded(val count: Int, val key: String?)

/**
 * Whether a viewer of the PDF [key] opens its companion (again): none is [opened] yet, it read the
 * music again itself ([readings] counts its readings, the one opened came after [openedAfter]), or
 * the reading that just [ended] was this PDF's (read again in the background by a newer reader).
 */
internal fun companionToOpen(opened: Boolean, openedAfter: Int, readings: Int, ended: ReadingEnded, key: String): Boolean =
    !opened || openedAfter != readings || ended.key == key

/**
 * One piece of work per key at a time: who asks for a key already being worked on gets that work
 * instead of a second one. A work can be hurried ([hurry]): it then stops yielding to the user.
 */
internal class OneAtATime<K : Any, V> {
    private val running = HashMap<K, Deferred<V>>()
    private val hurried: MutableSet<K> = ConcurrentHashMap.newKeySet()

    /** The work on [key] going on, or [work] started in [scope] for it. */
    fun join(scope: CoroutineScope, key: K, work: suspend () -> V): Deferred<V> = synchronized(running) {
        running[key]?.takeIf { it.isActive } ?: scope.async { work() }.also { started ->
            running[key] = started
            started.invokeOnCompletion {
                synchronized(running) { if (running[key] === started) running.remove(key) }
                hurried.remove(key)
            }
        }
    }

    fun isRunning(key: K): Boolean = synchronized(running) { running[key]?.isActive == true }

    /** Someone waits for the work on [key]: it no longer pauses for the user. */
    fun hurry(key: K) {
        if (isRunning(key)) hurried += key
    }

    fun isHurried(key: K): Boolean = key in hurried
}

/**
 * Prototype: the music of the library's PDFs read into their companions ([PdfCompanion]) ahead,
 * in the background, at a low priority, whenever the user leaves the tablet alone (as
 * [ScorePrecompute] lays the MusicXML scores out), newest import first. ▶ on a PDF whose music is
 * being read waits for that reading and hurries it; on one not read yet it reads it at once.
 */
internal object PdfMusicReading {
    /** The readings going on, by companion key (the PDF's hash). */
    val readings = OneAtATime<String, Conversion>()

    private val wake = MutableStateFlow(0)
    private val read = MutableStateFlow(ReadingEnded(0, null))

    /** The last reading that ended: a viewer of that PDF, or of one with no companion yet, opens it. */
    val finished: StateFlow<ReadingEnded> = read

    // after the start of the app, leave it the tablet a moment (ScorePrecompute starts at 5 s)
    private const val START_DELAY_MS = 20_000L

    // a thread of its own at the background priority: the reading never takes the tablet from the user
    private val low: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { work ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                work.run()
            }, "pdf-music").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** A PDF was added: there may be music to read. */
    fun changed() = wake.update { it + 1 }

    /** The reading of [key] ended: the viewers may open the companion it kept. */
    fun ended(key: String) = read.update { ReadingEnded(it.count + 1, key) }

    /** Where the work of the reading of [key] runs: the background thread, or anywhere once hurried. */
    fun workOn(key: String): CoroutineDispatcher = if (readings.isHurried(key)) Dispatchers.Default else low

    /** Starts the reading of the library's PDFs ([sheets]) in [scope], for as long as it lives. */
    fun start(context: Context, scope: CoroutineScope, sheets: Flow<List<Sheet>>) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS.milliseconds)
            // PDFs whose reading failed are not tried again until the app starts again
            val failed = mutableSetOf<String>()
            while (true) {
                val seen = wake.value
                val next = sheets.first()
                    .filter { SheetFormat.of(it.path) == SheetFormat.PDF && it.hash !in failed }
                    .sortedByDescending { it.id }
                    // not read yet, or read by an older reader
                    .firstOrNull { !PdfCompanion.isCurrent(app, it.hash) && !PdfCompanion.hasNoMusic(app, it.hash) }
                if (next == null) {
                    wake.first { it != seen }
                    continue
                }
                ScorePrecompute.awaitIdle()
                val done = readings.join(scope, next.hash) { readInBackground(app, next) }.await()
                if (done is Conversion.Failed && !PdfCompanion.hasNoMusic(app, next.hash)) failed += next.hash
            }
        }
    }

    private suspend fun readInBackground(context: Context, sheet: Sheet): Conversion {
        val key = sheet.hash
        Log.i(LOG_TAG, "reading the music of sheet ${sheet.id} in the background")
        val doc = MuPdfLock.mutex.withLockOn(Dispatchers.IO) { MuPdfDoc.open(context, Uri.fromFile(File(sheet.path))) }
        return try {
            convertPdfToMusicXml(
                context, doc, sheet.title, key,
                lock = { block -> MuPdfLock.mutex.withLockOn(Dispatchers.IO) { block() } },
                pause = { if (!readings.isHurried(key)) ScorePrecompute.awaitIdle() },
                work = { workOn(key) },
                onProgress = {},
            )
        } finally {
            withContext(NonCancellable) { MuPdfLock.mutex.withLockOn(Dispatchers.IO) { doc.close() } }
            ended(key)
        }
    }

    private const val LOG_TAG = "FOSScore-PdfScore"
}

private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockOn(dispatcher: CoroutineDispatcher, block: suspend () -> T): T {
    lock()
    try {
        return withContext(dispatcher) { block() }
    } finally {
        unlock()
    }
}
