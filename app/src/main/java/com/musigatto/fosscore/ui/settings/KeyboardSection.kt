package com.musigatto.fosscore.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R

// Android's own name for "Show virtual keyboard" (Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD is
// hidden from apps, but the value can be read)
private const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"

/** The Android screen that holds the "Show virtual keyboard" switch, on [sdk]. */
// the action is a plain string, copied into the app; it is only used from Android 7 on, which
// lint cannot see through [sdk]
@SuppressLint("InlinedApi")
internal fun keyboardSettingsAction(sdk: Int): String =
    if (sdk >= Build.VERSION_CODES.N) AndroidSettings.ACTION_HARD_KEYBOARD_SETTINGS
    else AndroidSettings.ACTION_INPUT_METHOD_SETTINGS

/** Whether the keyboard on the screen shows while a keyboard is plugged in: Android's switch. */
internal fun screenKeyboardWithPedal(context: Context): Boolean =
    runCatching { AndroidSettings.Secure.getInt(context.contentResolver, SHOW_IME_WITH_HARD_KEYBOARD, 0) == 1 }
        .getOrDefault(false)

/**
 * The keyboard and the pedals. Most page-turner pedals say they are a keyboard, and Android then
 * keeps the keyboard on the screen away: a text cannot be typed on the page while the pedal is
 * connected. The switch that brings it back is Android's own, for every app, and an app may not
 * turn it itself: this shows where it stands and opens the screen where it is. Set once, it stays.
 */
@Composable
internal fun KeyboardSection() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(screenKeyboardWithPedal(context)) }
    // read again on the way back from Android's settings
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) on = screenKeyboardWithPedal(context) }
    Text(stringResource(R.string.prefs_screen_keyboard), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(if (on) R.string.prefs_screen_keyboard_on else R.string.prefs_screen_keyboard_off),
        style = MaterialTheme.typography.bodyMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    Text(
        stringResource(R.string.prefs_screen_keyboard_why),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = {
        // no settings screen on some stripped-down devices
        runCatching { context.startActivity(Intent(keyboardSettingsAction(Build.VERSION.SDK_INT))) }
    }) { Text(stringResource(R.string.prefs_screen_keyboard_open)) }
}
