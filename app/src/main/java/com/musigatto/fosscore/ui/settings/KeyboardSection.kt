package com.musigatto.fosscore.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.viewer.KeyAction
import com.musigatto.fosscore.ui.viewer.KeyBinding
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.Shortcut

// Android's own name for "Show virtual keyboard" (Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD is
// hidden from apps, but the value can be read)
private const val SHOW_IME_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard"

// keys that only change another one: a shortcut is the key pressed with them
private val MODIFIER_KEYS = setOf(
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_FUNCTION,
)

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

/** The keyboard and the pedals: the keyboard on the screen while a pedal is connected, and the keys' shortcuts. */
@Composable
internal fun KeyboardSection() {
    ScreenKeyboardRow()
    ShortcutsList()
}

/**
 * Most page-turner pedals say they are a keyboard, and Android then keeps the keyboard on the screen
 * away: a text cannot be typed on the page while the pedal is connected. The switch that brings it
 * back is Android's own, for every app, and an app may not turn it itself: one line shows where it
 * stands and opens the screen where it is; why, only while it is off. Set once, it stays.
 */
@Composable
private fun ScreenKeyboardRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(screenKeyboardWithPedal(context)) }
    // read again on the way back from Android's settings
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) on = screenKeyboardWithPedal(context) }
    val open = stringResource(R.string.prefs_screen_keyboard_open)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = open) {
                // no settings screen on some stripped-down devices
                runCatching { context.startActivity(Intent(keyboardSettingsAction(Build.VERSION.SDK_INT))) }
            },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.prefs_screen_keyboard), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (on) R.string.prefs_screen_keyboard_on else R.string.prefs_screen_keyboard_off),
                style = MaterialTheme.typography.bodyMedium,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (!on) {
        Text(
            stringResource(R.string.prefs_screen_keyboard_why),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Each key the viewer answers, with its action to change or the key to remove; + adds one, and the defaults come back with one button. */
@Composable
private fun ShortcutsList() {
    val context = LocalContext.current
    var shortcuts by remember { mutableStateOf(Settings.keyShortcuts(context)) }
    var adding by remember { mutableStateOf(false) }
    Text(stringResource(R.string.prefs_shortcuts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    shortcuts.bindings.forEach { binding ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(shortcutLabel(binding.shortcut), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            ActionChooser(binding.action) { action ->
                shortcuts = shortcuts.with(binding.copy(action = action))
                Settings.setKeyShortcuts(context, shortcuts)
            }
            val remove = stringResource(R.string.action_delete)
            IconButton(
                onClick = {
                    shortcuts = shortcuts.without(binding.shortcut)
                    Settings.setKeyShortcuts(context, shortcuts)
                },
                modifier = Modifier.semantics { contentDescription = remove },
            ) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Row {
        OutlinedButton(onClick = { adding = true }) { Text("+ " + stringResource(R.string.prefs_shortcuts_add)) }
        TextButton(onClick = {
            Settings.resetKeyShortcuts(context)
            shortcuts = Settings.keyShortcuts(context)
        }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.prefs_shortcuts_reset)) }
    }
    if (adding) {
        AddShortcutDialog(
            actionOf = { shortcut -> shortcuts.bindings.firstOrNull { it.shortcut == shortcut }?.action },
            onCancel = { adding = false },
            onAdd = { binding ->
                shortcuts = shortcuts.with(binding)
                Settings.setKeyShortcuts(context, shortcuts)
                adding = false
            },
        )
    }
}

// the action of a key, and the menu of the other ones
@Composable
private fun ActionChooser(action: KeyAction, onChoose: (KeyAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(actionLabel(action) + "  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            KeyAction.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(actionLabel(choice)) }, onClick = {
                    open = false
                    onChoose(choice)
                })
            }
        }
    }
}

/**
 * "Press the key": the next key of the keyboard or the pedal is the shortcut (with Shift if held),
 * another press replaces it, then the action is chosen. A key already set says what it did; adding
 * it changes its action. Back still closes the dialog.
 */
@Composable
private fun AddShortcutDialog(actionOf: (Shortcut) -> KeyAction?, onCancel: () -> Unit, onAdd: (KeyBinding) -> Unit) {
    var shortcut by remember { mutableStateOf<Shortcut?>(null) }
    var action by remember { mutableStateOf(KeyAction.NEXT_PAGE) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.prefs_shortcuts_add)) },
        text = {
            Column(
                modifier = Modifier
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        val code = e.nativeKeyEvent.keyCode
                        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return@onPreviewKeyEvent false
                        if (code in MODIFIER_KEYS) return@onPreviewKeyEvent true
                        if (e.type == KeyEventType.KeyDown) {
                            val pressed = Shortcut(code, e.isShiftPressed)
                            shortcut = pressed
                            actionOf(pressed)?.let { action = it }
                        }
                        // the dialog's buttons must not take the key (Enter, arrows)
                        true
                    },
            ) {
                val pressed = shortcut
                if (pressed == null) {
                    Text(stringResource(R.string.prefs_shortcuts_press), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(shortcutLabel(pressed), style = MaterialTheme.typography.headlineSmall)
                    actionOf(pressed)?.let { was ->
                        Text(
                            stringResource(R.string.prefs_shortcuts_was, actionLabel(was)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ActionChooser(action) { action = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shortcut?.let { onAdd(KeyBinding(it, action)) } }, enabled = shortcut != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun actionLabel(action: KeyAction): String = stringResource(
    when (action) {
        KeyAction.PREV_PAGE -> R.string.key_action_prev_page
        KeyAction.NEXT_PAGE -> R.string.key_action_next_page
    }
)

// a key as it is printed on it: arrows and page keys as signs, the named keys translated, the others by Android's name
@Composable
private fun shortcutLabel(shortcut: Shortcut): String {
    val key = when (shortcut.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_PAGE_UP -> "Page ↑"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page ↓"
        KeyEvent.KEYCODE_SPACE -> stringResource(R.string.key_space)
        KeyEvent.KEYCODE_ENTER -> stringResource(R.string.key_enter)
        KeyEvent.KEYCODE_NUMPAD_ENTER -> stringResource(R.string.key_numpad_enter)
        else -> KeyEvent.keyCodeToString(shortcut.keyCode).removePrefix("KEYCODE_").replace('_', ' ')
    }
    return if (shortcut.shift) stringResource(R.string.key_shift) + " + " + key else key
}
