package com.musigatto.fosscore.ui.settings

import android.provider.Settings as AndroidSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardSectionTest {

    @Test
    fun theSwitchIsOnThePhysicalKeyboardScreen() {
        // Android 7 and up: the screen of the physical keyboard holds "Show virtual keyboard"
        assertEquals(AndroidSettings.ACTION_HARD_KEYBOARD_SETTINGS, keyboardSettingsAction(24))
        assertEquals(AndroidSettings.ACTION_HARD_KEYBOARD_SETTINGS, keyboardSettingsAction(34))
        // Android 6 has no such screen: the languages and input one leads to it
        assertEquals(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS, keyboardSettingsAction(23))
    }
}
