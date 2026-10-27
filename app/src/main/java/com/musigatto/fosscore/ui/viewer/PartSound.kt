package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.sound.PartSound
import com.musigatto.fosscore.sound.SoundBankEntry
import com.musigatto.fosscore.sound.SoundFont

// the instrument list of the sound dialog: tall enough for a dozen names, short enough for a phone
private const val INSTRUMENTS_HEIGHT_DP = 360

/**
 * The sounds of the parts, for the playback panel: the [banks] there are (the built-in one first),
 * the [default] one, and the instruments of a bank, read off the main thread by [instrumentsOf].
 */
class SoundChoices(
    val banks: List<SoundBankEntry>,
    val default: String,
    val instrumentsOf: suspend (bankId: String) -> List<SoundFont.Instrument>,
)

/**
 * Under a part of the playback panel: its [sound] (null: the instrument its score gives it), as
 * a button that opens the list of instruments to choose from.
 */
@Composable
internal fun PartSoundButton(partName: String, sound: PartSound?, choices: SoundChoices, onPick: (PartSound?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val instruments by produceState(emptyList<SoundFont.Instrument>(), sound?.bankId) {
        value = sound?.let { choices.instrumentsOf(it.bankId) }.orEmpty()
    }
    val label = sound?.let { s ->
        val name = instruments.firstOrNull { it.bank == s.bank && it.program == s.program }?.name ?: "${s.program + 1}"
        val bank = choices.banks.firstOrNull { it.id == s.bankId }?.name
        if (choices.banks.size > 1 && bank != null) "$name · $bank" else name
    } ?: stringResource(R.string.part_sound_as_written)
    TextButton(onClick = { open = true }, modifier = Modifier.padding(start = 36.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        PartSoundDialog(partName, sound, choices, onDismiss = { open = false }) {
            open = false
            onPick(it)
        }
    }
}

/**
 * The instrument of a part: "as in the score", or one of a bank's instruments (its melodic ones
 * first, then its drum kits). With several banks, the bank is chosen first, above the list.
 */
@Composable
private fun PartSoundDialog(
    partName: String,
    sound: PartSound?,
    choices: SoundChoices,
    onDismiss: () -> Unit,
    onPick: (PartSound?) -> Unit,
) {
    var bankId by remember { mutableStateOf(sound?.bankId ?: choices.default) }
    val instruments by produceState(emptyList<SoundFont.Instrument>(), bankId) { value = choices.instrumentsOf(bankId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.part_sound_title, partName)) },
        text = {
            Column {
                if (choices.banks.size > 1) {
                    Text(stringResource(R.string.part_sound_bank), style = MaterialTheme.typography.labelLarge)
                    choices.banks.forEach { bank ->
                        OptionRow(bank.name, bank.id == bankId, Role.RadioButton) { bankId = bank.id }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = INSTRUMENTS_HEIGHT_DP.dp)) {
                    item {
                        Choice(stringResource(R.string.part_sound_as_written), sound == null) { onPick(null) }
                    }
                    val (melodic, drums) = instruments.partition { it.bank != SoundFont.DRUM_BANK }
                    items(melodic) { i ->
                        Choice("${i.program + 1}  ${i.name}", sound == PartSound(bankId, i.bank, i.program)) {
                            onPick(PartSound(bankId, i.bank, i.program))
                        }
                    }
                    if (drums.isNotEmpty()) {
                        item { Text(stringResource(R.string.part_sound_drums), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    items(drums) { i ->
                        Choice(i.name, sound == PartSound(bankId, i.bank, i.program)) { onPick(PartSound(bankId, i.bank, i.program)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// one line of the list, 48 dp high at least: a touch target
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}
