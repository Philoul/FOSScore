package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.input.key.Key

enum class PageTurn { NEXT, PREV }

// keys that turn pages from a Bluetooth keyboard or a page-turner pedal (most pedals emulate one
// of these pairs). Shift+Space goes back, like in a browser. While a text is [typing] on the page,
// they are the text's: Enter a new line, Space a space, the arrows the cursor.
fun pageTurnFor(key: Key, shift: Boolean = false, typing: Boolean = false): PageTurn? = if (typing) null else when (key) {
    Key.PageDown, Key.DirectionRight, Key.DirectionDown, Key.Enter, Key.NumPadEnter -> PageTurn.NEXT
    Key.Spacebar -> if (shift) PageTurn.PREV else PageTurn.NEXT
    Key.PageUp, Key.DirectionLeft, Key.DirectionUp -> PageTurn.PREV
    else -> null
}
