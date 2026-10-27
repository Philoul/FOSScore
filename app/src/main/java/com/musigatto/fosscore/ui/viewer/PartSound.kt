package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.sound.PartSound
import com.musigatto.fosscore.sound.SoundBankEntry
import com.musigatto.fosscore.sound.SoundFont
import kotlinx.coroutines.launch

// the instrument list of the sound dialog: tall enough for a dozen names, short enough for a phone
private const val INSTRUMENTS_HEIGHT_DP = 360

// General MIDI puts its instruments in families of 8 programs (pianos, organs, guitars...)
private const val FAMILY_SIZE = 8

// lines left above the instrument chosen when the list opens on it: its neighbours show too
private const val LINES_ABOVE = 2

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
 * The instrument the score gives [this] part: its MIDI program (piano when it names none), in the
 * [default] bank, or a drum kit for a part on the drum channel.
 */
fun Part.scoreSound(default: String): PartSound =
    PartSound(default, if (drums) SoundFont.DRUM_BANK else 0, ((program ?: 1) - 1).coerceIn(0, 127))

/**
 * Under a part of the playback panel: the instrument it plays, [sound] or the score's own (then
 * marked as such), as a button that opens the list of instruments. In the list every tap is
 * heard at once while the music plays ([onTry]); OK keeps the sound ([onPick]), Cancel goes back
 * to the one before ([onCancel]).
 */
@Composable
internal fun PartSoundButton(
    part: Part,
    sound: PartSound?,
    choices: SoundChoices,
    onTry: (PartSound?) -> Unit,
    onPick: (PartSound?) -> Unit,
    onCancel: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val score = part.scoreSound(choices.default)
    val playing = sound ?: score
    val instruments by produceState(emptyList<SoundFont.Instrument>(), playing.bankId) {
        value = choices.instrumentsOf(playing.bankId)
    }
    val name = instruments.firstOrNull { it.bank == playing.bank && it.program == playing.program }?.name ?: "${playing.program + 1}"
    val bank = choices.banks.firstOrNull { it.id == playing.bankId }?.name
    TextButton(onClick = { open = true }, modifier = Modifier.padding(start = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (choices.banks.size > 1 && bank != null) "$name · $bank" else name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playing == score) FromScoreTag()
        }
    }
    if (open) {
        // the score's own instrument: nothing of the user's to keep
        fun ofUser(chosen: PartSound) = chosen.takeIf { it != score }
        PartSoundDialog(
            partName = part.name.ifBlank { part.id },
            plays = playing,
            score = score,
            choices = choices,
            onTry = { onTry(ofUser(it)) },
            onCancel = {
                open = false
                onCancel()
            },
            onOk = {
                open = false
                onPick(ofUser(it))
            },
        )
    }
}

// the mark of the instrument the score gives a part
@Composable
private fun FromScoreTag() {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(R.string.part_sound_from_score),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// one line of the instrument list: a family's name, or an instrument
private sealed interface Line {
    data class Family(val name: String) : Line
    data class Sound(val instrument: SoundFont.Instrument, val label: String) : Line
}

/**
 * The instrument of a part, opened on the one it [plays] so its neighbours are at hand: the
 * melodic instruments by General MIDI family (a bank's variations next to their program), then
 * the drum kits. The [score]'s own is marked, and "Original" goes back to it. Another bank keeps
 * the same instrument, to compare the two. Every choice is tried at once ([onTry]); only OK and
 * Cancel close the list.
 */
@Composable
private fun PartSoundDialog(
    partName: String,
    plays: PartSound,
    score: PartSound,
    choices: SoundChoices,
    onTry: (PartSound) -> Unit,
    onCancel: () -> Unit,
    onOk: (PartSound) -> Unit,
) {
    var chosen by remember { mutableStateOf(plays) }
    val scope = rememberCoroutineScope()
    // the bank's instruments, with the bank they are of: a list still of the bank before is not used
    val loaded by produceState<Pair<String, List<SoundFont.Instrument>>?>(null, chosen.bankId) {
        value = chosen.bankId to choices.instrumentsOf(chosen.bankId)
    }
    val instruments = loaded?.takeIf { it.first == chosen.bankId }?.second.orEmpty()
    val families = stringArrayResource(R.array.gm_families)
    val drumsTitle = stringResource(R.string.part_sound_drums)
    val lines = remember(instruments) {
        val (melodic, drums) = instruments.partition { it.bank != SoundFont.DRUM_BANK }
        buildList {
            var family = -1
            for (i in melodic.sortedWith(compareBy({ it.program }, { it.bank }))) {
                if (i.program / FAMILY_SIZE != family) {
                    family = i.program / FAMILY_SIZE
                    families.getOrNull(family)?.let { add(Line.Family(it)) }
                }
                add(Line.Sound(i, "${i.program + 1}  ${i.name}"))
            }
            if (drums.isNotEmpty()) add(Line.Family(drumsTitle))
            drums.forEach { add(Line.Sound(it, it.name)) }
        }
    }

    fun choose(sound: PartSound) {
        chosen = sound
        onTry(sound)
    }

    val list = rememberLazyListState()
    LaunchedEffect(lines) {
        val at = lines.indexOfFirst { it is Line.Sound && PartSound(chosen.bankId, it.instrument.bank, it.instrument.program) == chosen }
        if (at >= 0) list.scrollToItem((at - LINES_ABOVE).coerceAtLeast(0))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        // a tap beside the list must not drop the sounds being compared
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.part_sound_title, partName)) },
        text = {
            Column {
                OutlinedButton(onClick = { choose(score) }, enabled = chosen != score) {
                    Text(stringResource(R.string.part_sound_original))
                }
                if (choices.banks.size > 1) {
                    Text(stringResource(R.string.part_sound_bank), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    choices.banks.forEach { bank ->
                        OptionRow(bank.name, bank.id == chosen.bankId, Role.RadioButton) {
                            if (bank.id != chosen.bankId) {
                                scope.launch { sameIn(bank.id, chosen, choices.instrumentsOf(bank.id))?.let { choose(it) } }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn(state = list, modifier = Modifier.heightIn(max = INSTRUMENTS_HEIGHT_DP.dp)) {
                    items(lines.size) { k ->
                        when (val line = lines[k]) {
                            is Line.Family -> Text(
                                line.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                            is Line.Sound -> {
                                val sound = PartSound(chosen.bankId, line.instrument.bank, line.instrument.program)
                                Choice(line.label, sound == chosen, sound == score) { choose(sound) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(chosen) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * [sound] in bank [bankId], whose instruments are [list]: the same bank and program, or the same
 * program in its first bank (0, or the drum kits'), or the first instrument of the same kind.
 */
internal fun sameIn(bankId: String, sound: PartSound, list: List<SoundFont.Instrument>): PartSound? {
    val drums = sound.bank == SoundFont.DRUM_BANK
    val same = list.firstOrNull { it.bank == sound.bank && it.program == sound.program }
        ?: list.firstOrNull { it.bank == (if (drums) SoundFont.DRUM_BANK else 0) && it.program == sound.program }
        ?: list.firstOrNull { (it.bank == SoundFont.DRUM_BANK) == drums }
        ?: return null
    return PartSound(bankId, same.bank, same.program)
}

// one instrument, 48 dp high at least (a touch target), marked when it is the score's own
@Composable
private fun Choice(label: String, selected: Boolean, fromScore: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
        if (fromScore) FromScoreTag()
    }
}
