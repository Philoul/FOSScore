package com.musigatto.fosscore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoftInputModeTest {

    @Test
    fun theKeyboardStaysHiddenWhenTheWindowGetsTheFocusBack() {
        // With stateHidden, closing a dialog (naming a setlist) gave the focus back to the window,
        // and Android 7 opened the keyboard again for the library's search field, still focused
        // under the setlists. stateAlwaysHidden hides it each time; a tap on a field still opens it.
        val activity = File("src/main/AndroidManifest.xml").readText()
            .substringAfter("android:name=\".MainActivity\"").substringBefore(">")

        assertTrue(activity, "stateAlwaysHidden" in activity)
    }
}
