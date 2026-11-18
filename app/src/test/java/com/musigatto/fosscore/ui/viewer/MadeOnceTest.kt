package com.musigatto.fosscore.ui.viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MadeOnceTest {
    /**
     * The player warmed when a score is attached and the one ▶ starts were made at the same time:
     * two players, the music on one, the bar following the other (it stood at bar 1).
     */
    @Test
    fun twoAskingAtOnceGetTheSameThing() = runBlocking(Dispatchers.Default) {
        val made = AtomicInteger()
        val once = MadeOnce {
            delay(50)
            Any().also { made.incrementAndGet() }
        }
        val got = (1..4).map { async { once.get() } }.awaitAll()
        assertEquals(1, made.get())
        got.forEach { assertSame(got.first(), it) }
    }
}
