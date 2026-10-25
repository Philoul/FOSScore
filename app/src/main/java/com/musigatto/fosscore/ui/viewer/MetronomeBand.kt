package com.musigatto.fosscore.ui.viewer

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.metronome.BEAT_UNITS
import com.musigatto.fosscore.metronome.ClickSound
import com.musigatto.fosscore.metronome.MAX_BEATS
import com.musigatto.fosscore.metronome.MAX_BPM
import com.musigatto.fosscore.metronome.MAX_COUNT_IN
import com.musigatto.fosscore.metronome.MAX_SUBDIVISION
import com.musigatto.fosscore.metronome.MAX_TURN_AHEAD
import com.musigatto.fosscore.metronome.MIN_BEATS
import com.musigatto.fosscore.metronome.MIN_BPM
import com.musigatto.fosscore.metronome.Meter
import com.musigatto.fosscore.metronome.MetronomeMode
import com.musigatto.fosscore.metronome.MetronomeSettings
import com.musigatto.fosscore.metronome.TapTempo
import com.musigatto.fosscore.metronome.Tick
import com.musigatto.fosscore.metronome.tempoName
import kotlin.math.roundToInt

// the band holds the Italian name of the tempo from this width
private val BAND_WITH_TEMPO_NAME = 520.dp
// the tempo name or the bar heard: room for "Prestissimo" and "Mesure 88 / 88"
private val BAND_TEXT_WIDTH = 112.dp
private val BAND_TEMPO_WIDTH = 64.dp
// the dots of the beats: the first one bigger (the accent)
private val BEAT_DOT = 10.dp
private val ACCENT_DOT = 14.dp
// the page border flash: its width and how long it takes to fade
private val FLASH_WIDTH = 10.dp
private const val FLASH_MS = 180
// the compact settings: rows, small − / + buttons and their value, buttons with less padding
private val COMPACT_ROW = 40.dp
private val MINI_BUTTON = 36.dp
private val MINI_VALUE = 28.dp
private val COMPACT_BUTTON = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
// in landscape the settings spread wider and stay low
private val WIDE_PANEL = 640.dp

/**
 * The metronome band, in the bottom bar: ▶ / ⏸, a dot for each beat of the bar (the one heard
 * lit), − ♩ = N + (repeating while held), TAP, the settings and ✕. [barText] tells where the
 * page turns stand ("Bar 3 / 8") when they follow the metronome. With [onPlayback] (a MusicXML
 * score) the metronome button, lit, gives the place back to the playback band instead of ✕.
 */
@Composable
fun MetronomeBand(
    running: Boolean,
    tick: Tick?,
    settings: MetronomeSettings,
    barText: String?,
    onStartStop: () -> Unit,
    onBpm: (Int) -> Unit,
    onTap: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onPlayback: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.fillMaxWidth()) {
    val wide = maxWidth >= BAND_WITH_TEMPO_NAME
    val bpm by rememberUpdatedState(settings.bpm)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandButton(
            stringResource(if (running) R.string.metronome_stop else R.string.metronome_start),
            active = true,
            onClick = onStartStop,
        ) { ToolIcon(if (running) ICON_PAUSE else ICON_PLAY) }
        Spacer(Modifier.width(8.dp))
        BeatDots(settings.meter.pulses, tick?.beat?.takeIf { running }, counting = (tick?.bar ?: 0) < 0)
        Spacer(Modifier.width(8.dp))
        RepeatButton("−", stringResource(R.string.tempo_slower), enabled = settings.bpm > MIN_BPM, size = BAND_BUTTON) {
            onBpm(bpm - 1)
        }
        Text(
            stringResource(R.string.tempo_value, settings.bpm),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(BAND_TEMPO_WIDTH),
        )
        RepeatButton("+", stringResource(R.string.tempo_faster), enabled = settings.bpm < MAX_BPM, size = BAND_BUTTON) {
            onBpm(bpm + 1)
        }
        if (wide) {
            Text(
                barText ?: tempoName(settings.bpm),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                // a fixed width: the band must not move under the finger as the bars go by
                modifier = Modifier.width(BAND_TEXT_WIDTH),
            )
        }
        TextButton(onClick = onTap) { Text(stringResource(R.string.metronome_tap)) }
        BandButton(stringResource(R.string.metronome_settings), active = false, onClick = onSettings) { ToolIcon(ICON_OPTIONS) }
        if (onPlayback != null) {
            BandButton(stringResource(R.string.menu_playback), active = true, onClick = onPlayback) { ToolIcon(ICON_METRONOME) }
        } else {
            BandButton(stringResource(R.string.metronome_close), active = false, onClick = onClose) { LineIcon(LINE_CLOSE, size = 20.dp) }
        }
    }
}

/** One dot per beat of the bar, [lit] the one heard; during the count-in they show in the error colour. */
@Composable
private fun BeatDots(beats: Int, lit: Int?, counting: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // a long bar (12/4...) keeps the band on one row: the beat number instead of dots
        if (beats > 8) {
            Text(lit?.let { "${it + 1}/$beats" } ?: "$beats", style = MaterialTheme.typography.labelLarge)
            return@Row
        }
        val on = if (counting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val off = MaterialTheme.colorScheme.outlineVariant
        repeat(beats) { i ->
            Box(
                Modifier
                    .size(if (i == 0) ACCENT_DOT else BEAT_DOT)
                    .background(if (i == lit) on else off, CircleShape)
            )
        }
    }
}

/** The visual metronome: the border of the page flashes on each beat, more strongly on the first. */
@Composable
fun BeatFlash(tick: Tick?, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == null) return@LaunchedEffect
        alpha.snapTo(if (tick.beat == 0) 1f else 0.55f)
        alpha.animateTo(0f, tween(FLASH_MS))
    }
    val color = if (tick?.beat == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .border(FLASH_WIDTH, color)
    )
}

/** The bars of the page shown, for counting the bars of each page of a PDF score in the settings. */
data class PageBars(val page: Int, val pageCount: Int, val bars: Int, val firstBar: Int)

/**
 * The tempo, the same in the metronome and the playback settings: its Italian name and
 * − ♩ = N + (typed in place), then on one row a slider, TAP (the tempo of the last taps) and,
 * when the score writes one, its tempo as written.
 */
@Composable
internal fun TempoBlock(bpm: Int, range: IntRange, writtenBpm: Int?, onTempo: (Int) -> Unit, compound: Boolean = false) {
    val tap = remember { TapTempo() }
    NumberFieldRow(
        label = tempoName(bpm),
        value = bpm,
        range = range,
        step = 1,
        lessLabel = stringResource(R.string.tempo_slower),
        moreLabel = stringResource(R.string.tempo_faster),
        rangeText = stringResource(R.string.tempo_range, range.first, range.last),
        onValue = onTempo,
        prefix = if (compound) "♩. =" else "♩ =",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = bpm.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onTempo(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { tap.tap(SystemClock.uptimeMillis())?.let { onTempo(it.coerceIn(range)) } }, contentPadding = COMPACT_BUTTON) {
            Text(stringResource(R.string.metronome_tap))
        }
        if (writtenBpm != null) {
            OutlinedButton(onClick = { onTempo(writtenBpm) }, enabled = bpm != writtenBpm, contentPadding = COMPACT_BUTTON) {
                Text(stringResource(R.string.tempo_as_written_short))
            }
        }
    }
}

/**
 * The metronome settings (menu › Metronome, or ⚙ of the band), compact so the page stays readable
 * under them: tempo; time signature (read only when the score gives it); what gives the beats
 * (clicks, flash, both); sound and volume; accent and clicks per beat; count-in; page turns and
 * bars ahead; for a PDF score, the bars of each page, counted page by page ([pageBars], the page
 * behind changes with ◀ ▶). At the top of the screen: the bottom of the page, with the number
 * of the last bar of the page, stays visible.
 */
@Composable
fun MetronomeOverlay(
    settings: MetronomeSettings,
    bpmRange: IntRange,
    writtenBpm: Int?,
    meterFromScore: Boolean,
    pageBars: PageBars?,
    wide: Boolean,
    onBpm: (Int) -> Unit,
    onMeter: (Meter) -> Unit,
    onMode: (MetronomeMode) -> Unit,
    onSound: (ClickSound) -> Unit,
    onVolume: (Int) -> Unit,
    onAccent: (Boolean) -> Unit,
    onSubdivision: (Int) -> Unit,
    onCountIn: (Int) -> Unit,
    onAutoTurn: (Boolean) -> Unit,
    onTurnAhead: (Int) -> Unit,
    onBarsPage: (Int) -> Unit,
    onBars: (Int) -> Unit,
    onCopyBars: () -> Unit,
    onDismiss: () -> Unit,
) {
    PanelOverlay(
        stringResource(R.string.menu_metronome),
        onDismiss,
        maxWidth = if (wide) WIDE_PANEL else 420.dp,
    ) {
        TempoBlock(settings.bpm, bpmRange, writtenBpm, onBpm, compound = settings.meter.compound)
        HorizontalDivider()

        CompactRow(stringResource(R.string.metronome_time_signature)) {
            val meter = settings.meter
            if (meterFromScore) {
                Text(stringResource(R.string.metronome_meter_from_score, meter.toString()), style = MaterialTheme.typography.bodyLarge)
            } else {
                MiniStepper(
                    meter.beats.toString(), meter.beats > MIN_BEATS, meter.beats < MAX_BEATS,
                    { onMeter(meter.copy(beats = meter.beats - 1)) }, { onMeter(meter.copy(beats = meter.beats + 1)) },
                )
                Text("/", style = MaterialTheme.typography.bodyLarge)
                BEAT_UNITS.forEach { unit ->
                    ChoiceChip("$unit", meter.unit == unit) { onMeter(meter.copy(unit = unit)) }
                }
            }
        }
        CompactRow(stringResource(R.string.metronome_mode_label)) {
            DropdownButton(settings.mode, MetronomeMode.entries, { stringResource(it.label) }, onMode)
        }
        if (settings.mode != MetronomeMode.VISUAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownButton(settings.sound, ClickSound.entries, { stringResource(it.label) }, onSound)
                LineIcon(LINE_VOLUME)
                Slider(
                    value = settings.volume.toFloat(),
                    onValueChange = { onVolume(it.roundToInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
        }
        CompactRow(null) {
            CheckLabel(stringResource(R.string.metronome_accent), settings.accent) { onAccent(!settings.accent) }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.metronome_subdivision), style = MaterialTheme.typography.bodyLarge)
            MiniStepper(
                settings.subdivision.toString(), settings.subdivision > 1, settings.subdivision < MAX_SUBDIVISION,
                { onSubdivision(settings.subdivision - 1) }, { onSubdivision(settings.subdivision + 1) },
            )
        }
        CompactRow(stringResource(R.string.metronome_count_in)) {
            MiniStepper(
                settings.countIn.toString(), settings.countIn > 0, settings.countIn < MAX_COUNT_IN,
                { onCountIn(settings.countIn - 1) }, { onCountIn(settings.countIn + 1) },
            )
        }
        HorizontalDivider()

        CompactRow(null) {
            CheckLabel(stringResource(R.string.metronome_auto_turn), settings.autoTurn) { onAutoTurn(!settings.autoTurn) }
            Spacer(Modifier.weight(1f))
            TurnAheadStepper(settings.turnAhead, onTurnAhead)
        }
        if (pageBars != null && settings.autoTurn) {
            CompactRow(null) {
                MiniButton(LINE_PREVIOUS, stringResource(R.string.metronome_previous_page), pageBars.page > 0) { onBarsPage(pageBars.page - 1) }
                Text(stringResource(R.string.metronome_page_of, pageBars.page + 1, pageBars.pageCount), style = MaterialTheme.typography.bodyLarge)
                MiniButton(LINE_NEXT, stringResource(R.string.metronome_next_page), pageBars.page < pageBars.pageCount - 1) { onBarsPage(pageBars.page + 1) }
                Spacer(Modifier.weight(1f))
                MiniStepper(
                    pageBars.bars.toString(), pageBars.bars > 1, pageBars.bars < 99,
                    { onBars(pageBars.bars - 1) }, { onBars(pageBars.bars + 1) },
                )
                Text(
                    stringResource(R.string.metronome_bars_range, pageBars.firstBar + 1, pageBars.firstBar + pageBars.bars),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // the count of this page for every page after it
                MiniButton(LINE_COPY_DOWN, stringResource(R.string.metronome_copy_bars), true, onCopyBars)
            }
        }
    }
}

/** Bars ahead of the music the pages turn, with its label: for the metronome and for playback. */
@Composable
internal fun TurnAheadStepper(bars: Int, onBars: (Int) -> Unit) {
    Text(stringResource(R.string.turn_ahead), style = MaterialTheme.typography.bodyLarge)
    MiniStepper(bars.toString(), bars > 0, bars < MAX_TURN_AHEAD, { onBars(bars - 1) }, { onBars(bars + 1) })
}

/** The same, as a row of a settings panel. */
@Composable
internal fun TurnAheadRow(bars: Int, onBars: (Int) -> Unit) = CompactRow(null) {
    Spacer(Modifier.weight(1f))
    TurnAheadStepper(bars, onBars)
}

// a row of the compact settings: its [label] on the left (or none), the controls after it
@Composable
private fun CompactRow(label: String?, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = COMPACT_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
        }
        content()
    }
}

// − value +, small: the settings must leave the page readable
@Composable
private fun MiniStepper(value: String, canLess: Boolean, canMore: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    RepeatButton("−", stringResource(R.string.metronome_fewer), enabled = canLess, size = MINI_BUTTON, onStep = onLess)
    Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.width(MINI_VALUE))
    RepeatButton("+", stringResource(R.string.metronome_more), enabled = canMore, size = MINI_BUTTON, onStep = onMore)
}

// a small button with a glyph, described for screen readers
@Composable
private fun MiniButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(MINI_BUTTON).semantics { contentDescription = label }) {
        LineIcon(icon, size = 20.dp)
    }
}

// a check box and its label, as one target
@Composable
private fun CheckLabel(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.selectable(selected = checked, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

// a choice among a few, in a menu that drops down: lighter than a list of radio buttons
@Composable
private fun <T> DropdownButton(value: T, values: List<T>, text: @Composable (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = COMPACT_BUTTON) { Text(text(value) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { v ->
                DropdownMenuItem(text = { Text(text(v)) }, onClick = {
                    open = false
                    onPick(v)
                })
            }
        }
    }
}

// a small choice among a few (a beat unit): filled when chosen
@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
            else Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private val MetronomeMode.label: Int
    get() = when (this) {
        MetronomeMode.SOUND -> R.string.metronome_mode_sound
        MetronomeMode.VISUAL -> R.string.metronome_mode_visual
        MetronomeMode.BOTH -> R.string.metronome_mode_both
    }

private val ClickSound.label: Int
    get() = when (this) {
        ClickSound.CLICK -> R.string.metronome_sound_click
        ClickSound.HIHAT -> R.string.metronome_sound_hihat
        ClickSound.WOODBLOCK -> R.string.metronome_sound_woodblock
        ClickSound.DIGITAL -> R.string.metronome_sound_digital
    }
