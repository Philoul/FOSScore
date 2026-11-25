package com.musigatto.fosscore.ui.viewer

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Where the pages are drawn and the music laid out: work the screen waits for, but which must not
 * get in the way of what the user sees and does. On a slow tablet a page takes 100-200 ms to draw;
 * at the main thread's priority it took the CPU from the frames, and the playback bar stopped
 * while the next page was being prepared, and taps went unanswered.
 *
 * The order is: the sound (urgent audio), then the screen (Android already runs the main thread
 * and its render thread at -10 while the app is on top), then these threads. Not the background priority: Android moves
 * such threads into a group that gets a few percent of the CPU when it is busy, and a page would
 * then not be ready for its turn while the music plays.
 */
internal object PageWork {
    /** Below the main thread, above the background group (THREAD_PRIORITY_BACKGROUND, 10). */
    const val PRIORITY = 5

    // two: the draws share one lock, the second thread lets a layout or a prefetch queue behind it
    private const val THREADS = 2

    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(THREADS, Threads(PRIORITY, Process::setThreadPriority)).asCoroutineDispatcher()
    }

    /** Threads that take [priority] before they run anything. */
    class Threads(private val priority: Int, private val setPriority: (Int) -> Unit) : ThreadFactory {
        private var count = 0

        override fun newThread(task: Runnable): Thread =
            Thread({
                setPriority(priority)
                task.run()
            }, "page-work-${++count}").apply { isDaemon = true }
    }
}
