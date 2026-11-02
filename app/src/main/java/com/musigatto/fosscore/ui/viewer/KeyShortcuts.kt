package com.musigatto.fosscore.ui.viewer

import android.view.KeyEvent

enum class PageTurn { NEXT, PREV }

/**
 * What a key of a keyboard or a page-turner pedal can do, in the order the preferences offer them.
 * A new action is one entry here and one branch where the viewer runs it.
 */
enum class KeyAction { PREV_PAGE, NEXT_PAGE }

/** A key, by Android's number for it (the KEYCODE_ of [KeyEvent]), with Shift held or not. */
data class Shortcut(val keyCode: Int, val shift: Boolean = false)

data class KeyBinding(val shortcut: Shortcut, val action: KeyAction)

/**
 * The keys the viewer answers, each with its action, in the order the preferences list them. Most
 * pedals send one of the default pairs; the user changes, removes or adds keys in the preferences.
 */
data class KeyShortcuts(val bindings: List<KeyBinding>) {

    /**
     * The action of a key pressed with Shift or not; Shift with a key bound without it does what the
     * key does. While a text is [typing] on the page every key is the text's (Enter a new line, Space
     * a space, the arrows the cursor, a letter a letter): none runs an action.
     */
    fun actionFor(keyCode: Int, shift: Boolean = false, typing: Boolean = false): KeyAction? =
        if (typing) null else find(Shortcut(keyCode, shift)) ?: if (shift) find(Shortcut(keyCode)) else null

    private fun find(shortcut: Shortcut): KeyAction? = bindings.firstOrNull { it.shortcut == shortcut }?.action

    /** [binding] in place of the one on the same key, or added at the end for a new key. */
    fun with(binding: KeyBinding): KeyShortcuts =
        if (bindings.any { it.shortcut == binding.shortcut }) {
            KeyShortcuts(bindings.map { if (it.shortcut == binding.shortcut) binding else it })
        } else {
            KeyShortcuts(bindings + binding)
        }

    fun without(shortcut: Shortcut): KeyShortcuts = KeyShortcuts(bindings.filterNot { it.shortcut == shortcut })

    /** The text kept in the preferences: `keyCode:shift:ACTION` entries separated by `;`. */
    fun format(): String = bindings.joinToString(SEPARATOR) {
        listOf(it.shortcut.keyCode, if (it.shortcut.shift) 1 else 0, it.action.name).joinToString(FIELD)
    }

    companion object {
        private const val SEPARATOR = ";"
        private const val FIELD = ":"

        private fun key(keyCode: Int, action: KeyAction, shift: Boolean = false) = KeyBinding(Shortcut(keyCode, shift), action)

        /**
         * The six arrow and page keys first, then the keys that some pedals send and that turned
         * pages before the shortcuts could be set: Space (Shift+Space goes back, as in a browser)
         * and Enter.
         */
        val DEFAULT = KeyShortcuts(
            listOf(
                key(KeyEvent.KEYCODE_DPAD_UP, KeyAction.PREV_PAGE),
                key(KeyEvent.KEYCODE_DPAD_DOWN, KeyAction.NEXT_PAGE),
                key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyAction.NEXT_PAGE),
                key(KeyEvent.KEYCODE_DPAD_LEFT, KeyAction.PREV_PAGE),
                key(KeyEvent.KEYCODE_PAGE_UP, KeyAction.PREV_PAGE),
                key(KeyEvent.KEYCODE_PAGE_DOWN, KeyAction.NEXT_PAGE),
                key(KeyEvent.KEYCODE_SPACE, KeyAction.NEXT_PAGE),
                key(KeyEvent.KEYCODE_SPACE, KeyAction.PREV_PAGE, shift = true),
                key(KeyEvent.KEYCODE_ENTER, KeyAction.NEXT_PAGE),
                key(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyAction.NEXT_PAGE),
            )
        )

        /** The shortcuts saved as [format] wrote them, the defaults when none were; an entry that cannot be read is skipped. */
        fun parse(text: String?): KeyShortcuts = text?.let { saved ->
            KeyShortcuts(saved.split(SEPARATOR).mapNotNull(::parseEntry))
        } ?: DEFAULT

        private fun parseEntry(entry: String): KeyBinding? {
            // the action is the rest of the entry, so a later one may carry its own value after it
            // (a score, a set list) and still be skipped whole by this version
            val fields = entry.split(FIELD, limit = 3)
            if (fields.size != 3) return null
            val keyCode = fields[0].toIntOrNull() ?: return null
            val action = KeyAction.entries.firstOrNull { it.name == fields[2] } ?: return null
            return KeyBinding(Shortcut(keyCode, fields[1] == "1"), action)
        }
    }
}
