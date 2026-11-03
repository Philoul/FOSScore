package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A value typed in a number field (the music size) is laid out with the panel still open. */
class TypedValueTest {
    @Test
    fun aValueTypedInRangeAppliesWithoutLeavingTheField() {
        assertEquals(83, typedToApply("83", 50..200, current = 85))
    }

    @Test
    fun aValueStillBeingTypedOrOutOfRangeWaits() {
        // "1" of "120", "8" of "83": out of range until the last digit
        assertNull(typedToApply("8", 50..200, current = 85))
        assertNull(typedToApply("", 50..200, current = 85))
        assertNull(typedToApply("250", 50..200, current = 85))
        // the value already laid out: nothing to do again
        assertNull(typedToApply("85", 50..200, current = 85))
    }
}
