package com.musigatto.fosscore.ui.viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesToShowTest {

    @Test
    fun theSameNotesAskedTwiceAreShownTwice() = runTest {
        // ⏮ with the cursor already at the start, after the pages were turned by hand
        val notes = NotesToShow()
        val seen = mutableListOf<List<String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { notes.requests.toList(seen) }
        notes.show(listOf("n1"))
        notes.show(listOf("n1"))
        job.cancel()
        assertEquals(listOf(listOf("n1"), listOf("n1")), seen)
    }
}
