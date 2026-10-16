package org.fosscore.spike.musicxml

import android.view.KeyEvent

/**
 * Keys that turn pages from a Bluetooth keyboard or a page-turner pedal (most pedals emulate one
 * of these pairs), as in FOSScore's PR #7. Shift+Space goes back, like in a browser.
 * Returns +1 (next), -1 (previous) or 0 (not a page-turn key).
 */
fun pageTurnFor(keyCode: Int, shift: Boolean): Int = when (keyCode) {
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 1
    KeyEvent.KEYCODE_SPACE -> if (shift) -1 else 1
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
    else -> 0
}
