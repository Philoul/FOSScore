package com.musigatto.fosscore.ui.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.sound.SoundBank
import com.musigatto.fosscore.sound.SoundBankEntry
import com.musigatto.fosscore.ui.viewer.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the widest the preferences get on a tablet: lines longer than this are hard to read
private const val MAX_WIDTH_DP = 640

/**
 * The app's preferences, over the screen it was opened from. For now one sub-menu, MIDI, with the
 * sound banks; the other settings of the app join it later (language, page-turn keys...).
 */
@Composable
fun PreferencesScreen(onDone: () -> Unit) {
    var midi by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (midi) midi = false else onDone() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = MAX_WIDTH_DP.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (midi) {
                Text(stringResource(R.string.prefs_midi), style = MaterialTheme.typography.headlineSmall)
                SoundBanks()
                OutlinedButton(onClick = { midi = false }) { Text(stringResource(R.string.action_back)) }
            } else {
                Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.headlineSmall)
                Entry(stringResource(R.string.prefs_midi), stringResource(R.string.prefs_midi_summary)) { midi = true }
                HorizontalDivider()
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}

// a sub-menu: its name, and what it holds under it
@Composable
private fun Entry(title: String, summary: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The sound banks: the default one picked with its radio button, the ones added deleted with
 * their button (the built-in one stays), and one more added from the device's files.
 */
@Composable
private fun SoundBanks() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var banks by remember { mutableStateOf(emptyList<SoundBankEntry>()) }
    var chosen by remember { mutableStateOf(Settings.soundBank(app)) }
    var adding by remember { mutableStateOf(false) }
    var notRead by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SoundBankEntry?>(null) }

    suspend fun reload() {
        banks = withContext(Dispatchers.IO) { SoundBank.list(app) }
        // a default bank that is gone falls back to the built-in one
        if (banks.none { it.id == chosen }) chosen = SoundBank.BUILT_IN
    }
    LaunchedEffect(Unit) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        adding = true
        notRead = false
        scope.launch {
            val added = withContext(Dispatchers.IO) { SoundBank.add(app, uri) }
            adding = false
            notRead = added == null
            reload()
        }
    }

    Text(stringResource(R.string.sound_banks_label), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.sound_banks_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.sound_bank_default), style = MaterialTheme.typography.labelLarge)
    banks.forEach { bank ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = bank.id == chosen, role = Role.RadioButton) {
                    chosen = bank.id
                    Settings.setSoundBank(app, bank.id)
                },
        ) {
            RadioButton(selected = bank.id == chosen, onClick = null)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(bank.name, style = MaterialTheme.typography.bodyLarge)
                val size = Formatter.formatShortFileSize(context, bank.size)
                Text(
                    if (bank.builtIn) "${stringResource(R.string.sound_bank_built_in)} · $size" else size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!bank.builtIn) {
                TextButton(onClick = { deleting = bank }) { Text(stringResource(R.string.sound_bank_delete)) }
            }
        }
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !adding) {
        Text(stringResource(if (adding) R.string.sound_bank_adding else R.string.sound_bank_add))
    }
    if (notRead) {
        Text(stringResource(R.string.sound_bank_not_read), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    deleting?.let { bank ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sound_bank_delete_title, bank.name)) },
            text = { Text(stringResource(R.string.sound_bank_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { SoundBank.delete(app, bank.id) }
                        if (chosen == bank.id) Settings.setSoundBank(app, SoundBank.BUILT_IN)
                        reload()
                    }
                }) { Text(stringResource(R.string.sound_bank_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
