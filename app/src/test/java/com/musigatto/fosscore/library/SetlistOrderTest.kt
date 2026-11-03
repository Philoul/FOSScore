package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SetlistOrderTest {

    @Test
    fun aPlaceMovedUpOrDownTakesItsNewPlace() {
        val order = listOf(10L, 11L, 12L, 13L)
        assertEquals(listOf(11L, 10L, 12L, 13L), order.moved(1, 0))
        assertEquals(listOf(10L, 12L, 11L, 13L), order.moved(1, 2))
        assertEquals(listOf(11L, 12L, 13L, 10L), order.moved(0, 3))
    }

    @Test
    fun aMoveOutOfTheListChangesNothing() {
        val order = listOf(10L, 11L)
        assertSame(order, order.moved(0, 2))
        assertSame(order, order.moved(-1, 0))
        assertSame(order, order.moved(1, 1))
    }

    @Test
    fun theNextScoreIsTheNextPlaceEvenForTheSameScoreTwice() {
        // places 1..4; places 2 and 4 hold the same score: the order goes by place, not by score
        val places = listOf(1L, 2L, 3L, 4L)
        assertEquals(3L, neighbour(places, 2L, 1))
        assertEquals(4L, neighbour(places, 3L, 1))
        assertEquals(3L, neighbour(places, 4L, -1))
        assertNull(neighbour(places, 4L, 1))
        assertNull(neighbour(places, 1L, -1))
    }

    @Test
    fun aPlaceRemovedWhileItsScoreIsOpenHasNoNeighbour() {
        assertNull(neighbour(listOf(1L, 3L), 2L, 1))
    }

    @Test
    fun aDraggedRowPassesItsNeighbourAtHalfItsHeight() {
        assertEquals(0, dragStep(20f, 60f))
        assertEquals(1, dragStep(31f, 60f))
        assertEquals(-1, dragStep(-31f, 60f))
        // not measured yet: it stays
        assertEquals(0, dragStep(100f, 0f))
    }
}
