package com.musigatto.fosscore.ui.viewer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyShortcutsTest {
    private val defaults = KeyShortcuts.DEFAULT

    @Test
    fun theDefaultsTurnThePagesAsBefore() {
        listOf(
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        ).forEach { assertEquals(it.toString(), KeyAction.NEXT_PAGE, defaults.actionFor(it)) }
        listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)
            .forEach { assertEquals(it.toString(), KeyAction.PREV_PAGE, defaults.actionFor(it)) }
        assertEquals(KeyAction.PREV_PAGE, defaults.actionFor(KeyEvent.KEYCODE_SPACE, shift = true))
    }

    @Test
    fun theSixKeysComeFirstInTheirOrder() {
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            ),
            defaults.bindings.take(6).map { it.shortcut.keyCode },
        )
    }

    @Test
    fun otherKeysDoNothing() {
        assertNull(defaults.actionFor(KeyEvent.KEYCODE_A))
        assertNull(defaults.actionFor(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun keysAreTheTextsWhileTyping() {
        // a text typed on the page takes every key: Enter a new line, Space a space, the arrows
        // the cursor, and a letter bound to an action is still a letter
        val withLetter = defaults.with(KeyBinding(Shortcut(KeyEvent.KEYCODE_N), KeyAction.NEXT_PAGE))
        listOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_N,
        ).forEach { assertNull(it.toString(), withLetter.actionFor(it, typing = true)) }
        assertNull(withLetter.actionFor(KeyEvent.KEYCODE_SPACE, shift = true, typing = true))
        assertEquals(KeyAction.NEXT_PAGE, withLetter.actionFor(KeyEvent.KEYCODE_N))
    }

    @Test
    fun shiftWithAKeyBoundWithoutItDoesWhatTheKeyDoes() {
        assertEquals(KeyAction.NEXT_PAGE, defaults.actionFor(KeyEvent.KEYCODE_PAGE_DOWN, shift = true))
    }

    @Test
    fun aKeyBoundAgainChangesItsActionInPlace() {
        val changed = defaults.with(KeyBinding(Shortcut(KeyEvent.KEYCODE_DPAD_UP), KeyAction.NEXT_PAGE))
        assertEquals(defaults.bindings.size, changed.bindings.size)
        assertEquals(KeyBinding(Shortcut(KeyEvent.KEYCODE_DPAD_UP), KeyAction.NEXT_PAGE), changed.bindings.first())
    }

    @Test
    fun aNewKeyIsAddedAtTheEnd() {
        val b = KeyBinding(Shortcut(KeyEvent.KEYCODE_B), KeyAction.PREV_PAGE)
        val added = defaults.with(b)
        assertEquals(b, added.bindings.last())
        assertEquals(KeyAction.PREV_PAGE, added.actionFor(KeyEvent.KEYCODE_B))
    }

    @Test
    fun aRemovedKeyDoesNothingAnyMore() {
        val removed = defaults.without(Shortcut(KeyEvent.KEYCODE_ENTER))
        assertNull(removed.actionFor(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun theShortcutsAreReadBackAsTheyWereSaved() {
        val custom = defaults
            .without(Shortcut(KeyEvent.KEYCODE_SPACE))
            .with(KeyBinding(Shortcut(KeyEvent.KEYCODE_B, shift = true), KeyAction.NEXT_PAGE))
        assertEquals(custom, KeyShortcuts.parse(custom.format()))
    }

    @Test
    fun everyActionIsReadBack() {
        val all = KeyShortcuts(KeyAction.entries.mapIndexed { i, action -> KeyBinding(Shortcut(KeyEvent.KEYCODE_F1 + i), action) })
        assertEquals(all, KeyShortcuts.parse(all.format()))
    }

    @Test
    fun nothingSavedMeansTheDefaultsAndAnEmptyListStaysEmpty() {
        assertEquals(defaults, KeyShortcuts.parse(null))
        val none = KeyShortcuts(emptyList())
        assertEquals(none, KeyShortcuts.parse(none.format()))
    }

    @Test
    fun anUnreadableEntryIsSkipped() {
        // an action a newer version knew, or a damaged entry
        val read = KeyShortcuts.parse("92:0:PREV_PAGE;93:0:GONE_ACTION;94:0:OPEN_SHEET:42;x:y;66:1:NEXT_PAGE")
        assertEquals(
            listOf(
                KeyBinding(Shortcut(92), KeyAction.PREV_PAGE),
                KeyBinding(Shortcut(66, shift = true), KeyAction.NEXT_PAGE),
            ),
            read.bindings,
        )
    }
}
