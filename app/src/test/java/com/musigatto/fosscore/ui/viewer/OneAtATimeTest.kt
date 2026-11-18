package com.musigatto.fosscore.ui.viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneAtATimeTest {

    /** ▶ pressed while the music of the same PDF is read in the background: it waits for that reading. */
    @Test
    fun aSecondAskJoinsTheReadingGoingOn() = runTest {
        val readings = OneAtATime<String, Int>()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val background = readings.join(this, "pdf") { started++; release.await(); 42 }
        val play = readings.join(this, "pdf") { started++; 7 }
        assertTrue(readings.isRunning("pdf"))
        release.complete(Unit)
        assertEquals(42, background.await())
        assertEquals(42, play.await())
        assertEquals(1, started)
    }

    @Test
    fun anotherKeyIsReadOnItsOwn() = runTest {
        val readings = OneAtATime<String, Int>()
        val a = readings.join(this, "a") { 1 }
        val b = readings.join(this, "b") { 2 }
        assertEquals(1, a.await())
        assertEquals(2, b.await())
    }

    /** Once over, a reading can be made again (the menu reads the music again). */
    @Test
    fun aFinishedReadingIsMadeAgain() = runTest {
        val readings = OneAtATime<String, Int>()
        assertEquals(1, readings.join(this, "pdf") { 1 }.await())
        assertFalse(readings.isRunning("pdf"))
        val again = async { readings.join(this@runTest, "pdf") { 2 }.await() }
        assertEquals(2, again.await())
    }

    @Test
    fun aHurriedReadingStaysHurriedUntilItEnds() = runTest {
        val readings = OneAtATime<String, Int>()
        val release = CompletableDeferred<Unit>()
        val reading = readings.join(this, "pdf") { release.await(); 1 }
        assertFalse(readings.isHurried("pdf"))
        readings.hurry("pdf")
        assertTrue(readings.isHurried("pdf"))
        release.complete(Unit)
        reading.await()
        assertFalse(readings.isHurried("pdf"))
    }
}
