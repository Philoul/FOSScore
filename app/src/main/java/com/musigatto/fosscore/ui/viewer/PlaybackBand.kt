package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Part
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Material's 24 dp paths (play_arrow, pause, skip_previous, repeat), drawn with ToolIcon
private const val ICON_PLAY = "M8,5v14l11,-7z"
private const val ICON_PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
private const val ICON_TO_START = "M6,6h2v12H6zM9.5,12l8.5,6V6z"
private const val ICON_LOOP =
    "M7,7h10v3l4,-4,-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4,4,4v-3h12v-6h-2v4z"

// the band's buttons: a little under M3's 48 dp, so the band stays one slim row
private val BAND_BUTTON = 44.dp

// a held − / + waits this long, then repeats at this pace
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 70L
// the typed tempo: "♩ =" and three digits
private val TEMPO_FIELD_WIDTH = 112.dp

// the cursor: a light band over the notes being played and a dot on each notehead
private val CURSOR_BAND = Color(0x3300A0FF)
private val CURSOR_HEAD = Color(0x8800A0FF)

/**
 * The playback band, in the bottom bar above the page dots: ⏮ (back to the start of the loop or
 * of the piece), ▶ / ⏸, the loop (on / off, the whole piece unless A or B is set), the A and B
 * markers (a tap sets one where the music is, a second tap clears it) and the tempo, which opens
 * the playback options. It shows with the bars (centre tap); the music goes on when they hide.
 */
@Composable
fun PlaybackBand(
    state: PlayState,
    loop: Loop,
    bpm: Int,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onLoop: () -> Unit,
    onA: () -> Unit,
    onB: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandButton(
            stringResource(if (loop.on) R.string.playback_to_loop_start else R.string.playback_to_start),
            active = false,
            onClick = onRestart,
        ) { ToolIcon(ICON_TO_START) }
        val playing = state == PlayState.PLAYING
        BandButton(
            stringResource(if (playing) R.string.playback_pause else R.string.playback_play),
            active = true,
            enabled = state != PlayState.PREPARING,
            onClick = onPlayPause,
        ) { ToolIcon(if (playing) ICON_PAUSE else ICON_PLAY) }
        Spacer(Modifier.width(8.dp))
        BandButton(
            stringResource(if (loop.on) R.string.loop_off else R.string.loop_on),
            active = loop.on,
            onClick = onLoop,
        ) { ToolIcon(ICON_LOOP) }
        BandButton(
            stringResource(if (loop.a != null) R.string.loop_clear_a else R.string.loop_set_a),
            active = loop.a != null,
            onClick = onA,
        ) { Text("A", style = MaterialTheme.typography.labelLarge) }
        BandButton(
            stringResource(if (loop.b != null) R.string.loop_clear_b else R.string.loop_set_b),
            active = loop.b != null,
            onClick = onB,
        ) { Text("B", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOptions) {
            Text(stringResource(R.string.tempo_value, bpm), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A band button: filled while what it stands for is on (the loop, a marker set, ▶). */
@Composable
private fun BandButton(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier
        .size(BAND_BUTTON)
        .semantics { contentDescription = label }
    if (active) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}

/**
 * The playback options panel (menu › Playback): the tempo as ♩ = N, and the parts heard, last and
 * in their own scroll box, as in the mockup. The parts shown are in Score layout.
 */
@Composable
fun PlaybackOptionsOverlay(
    bpm: Int,
    writtenBpm: Int,
    minBpm: Int,
    maxBpm: Int,
    onTempo: (Int) -> Unit,
    parts: List<Part>,
    heard: Set<String>,
    onHeard: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelOverlay(stringResource(R.string.menu_playback), onDismiss, modifier) {
        SectionLabel(stringResource(R.string.tempo_section_label))
        TempoRow(bpm, minBpm, maxBpm, onTempo)
        TextButton(onClick = { onTempo(writtenBpm) }, enabled = bpm != writtenBpm) {
            Text(stringResource(R.string.tempo_as_written, writtenBpm))
        }
        if (parts.size > 1) {
            SectionLabel(stringResource(R.string.heard_section_label))
            Column(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                parts.forEach { part ->
                    val isHeard = part.id in heard
                    // the last part heard cannot be muted: the music would go silent
                    OptionRow(
                        part.name.ifBlank { part.id },
                        isHeard,
                        Role.Checkbox,
                        enabled = !isHeard || heard.size > 1,
                    ) { onHeard(part.id, !isHeard) }
                }
            }
        }
    }
}

/**
 * − [♩ = N] +: the tempo is typed in place (kept with Done or when the field loses focus, and
 * brought into the range), and − / + change it by one beat per minute on each press, at once (not
 * on release), repeating while held after a short wait. As AndroidAPS's NumberInputRow.
 */
@Composable
private fun TempoRow(bpm: Int, minBpm: Int, maxBpm: Int, onTempo: (Int) -> Unit) {
    // the held button reads the tempo it changes, not the one it saw when the press began
    val current by rememberUpdatedState(bpm)
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(TextFieldValue(bpm.toString())) }
    var focused by remember { mutableStateOf(false) }
    val typed = text.text.toIntOrNull()
    val wrong = typed == null || typed !in minBpm..maxBpm
    // − / + and "As written" change the tempo from outside the field
    LaunchedEffect(bpm) { if (typed != bpm) text = TextFieldValue(bpm.toString()) }
    fun keep() {
        val value = (typed ?: current).coerceIn(minBpm, maxBpm)
        text = TextFieldValue(value.toString())
        if (value != current) onTempo(value)
    }
    // the panel may close while the field still has the focus
    val keepLatest by rememberUpdatedState(::keep)
    val focusedLatest by rememberUpdatedState(focused)
    DisposableEffect(Unit) { onDispose { if (focusedLatest) keepLatest() } }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tempo_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RepeatButton("−", stringResource(R.string.tempo_slower), enabled = bpm > minBpm) {
            focus.clearFocus()
            onTempo((current - 1).coerceAtLeast(minBpm))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { new -> text = new.copy(text = new.text.filter(Char::isDigit).take(3)) },
            prefix = { Text("♩ =") },
            singleLine = true,
            isError = wrong,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .width(TEMPO_FIELD_WIDTH)
                .onFocusChanged { state ->
                    // leaving the field keeps what was typed; entering it selects the value, to type over it
                    if (focused && !state.isFocused) keep()
                    if (!focused && state.isFocused) text = text.copy(selection = TextRange(0, text.text.length))
                    focused = state.isFocused
                },
        )
        RepeatButton("+", stringResource(R.string.tempo_faster), enabled = bpm < maxBpm) {
            focus.clearFocus()
            onTempo((current + 1).coerceAtMost(maxBpm))
        }
    }
    if (focused) {
        Text(
            stringResource(R.string.tempo_range, minBpm, maxBpm),
            style = MaterialTheme.typography.bodySmall,
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/** A button that acts on press, then again and again while held. */
@Composable
private fun RepeatButton(text: String, label: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val color = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { step(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    step()
                    val repeating = scope.launch {
                        delay(REPEAT_DELAY_MS)
                        while (true) {
                            step()
                            delay(REPEAT_EVERY_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    repeating.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = color)
    }
}

/**
 * Draws the playback cursor for [notes] on a page whose [geometry] is in SVG units and which
 * covers [pageWidth] px from ([left], [top]): a band over the system, a dot on each notehead.
 */
fun DrawScope.drawPlaybackCursor(geometry: PageGeometry, notes: List<String>, left: Float, top: Float, pageWidth: Float) {
    val heads = notes.mapNotNull { geometry.head(it) }
    if (heads.isEmpty()) return
    val k = pageWidth / geometry.width
    val space = heads.first().space * k
    val l = left + heads.minOf { it.x } * k - space
    val r = left + heads.maxOf { it.x } * k + space
    val t = top + heads.minOf { it.top } * k - 2 * space
    val b = top + heads.maxOf { it.bottom } * k + 2 * space
    drawRect(CURSOR_BAND, Offset(l, t), Size(r - l, b - t))
    heads.forEach { h -> drawCircle(CURSOR_HEAD, radius = space * 0.8f, center = Offset(left + h.x * k, top + h.y * k)) }
}

/** The cursor over a bitmap drawn with ContentScale.Fit in a box of this canvas's size. */
@Composable
fun FitPlaybackCursor(geometry: PageGeometry, notes: List<String>, bitmapWidth: Int, bitmapHeight: Int, modifier: Modifier) {
    Canvas(modifier) {
        val fit = minOf(size.width / bitmapWidth, size.height / bitmapHeight)
        val dx = (size.width - bitmapWidth * fit) / 2
        val dy = (size.height - bitmapHeight * fit) / 2
        drawPlaybackCursor(geometry, notes, dx, dy, bitmapWidth * fit)
    }
}
