package com.musigatto.fosscore.ui.viewer

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PageWorkTest {

    @Test
    fun pagesAreDrawnBelowTheScreenButNotInTheBackgroundGroup() {
        // below the main thread (0, raised to display) so the frames and taps come first, and
        // under the background priority, which Android starves when the CPU is busy
        assertTrue(PageWork.PRIORITY > Process.THREAD_PRIORITY_DEFAULT)
        assertTrue(PageWork.PRIORITY < Process.THREAD_PRIORITY_BACKGROUND)
    }

    @Test
    fun aThreadTakesItsPriorityBeforeItRunsTheWork() {
        val events = mutableListOf<String>()
        val done = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor(PageWork.Threads(PageWork.PRIORITY) { events += "priority $it" })
        pool.execute {
            events += "work"
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(listOf("priority ${PageWork.PRIORITY}", "work"), events)
    }
}
