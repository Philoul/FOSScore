package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Part

// Material's 24 dp paths (play_arrow, pause, skip_previous, repeat), drawn with ToolIcon
private const val ICON_PLAY = "M8,5v14l11,-7z"
private const val ICON_PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
private const val ICON_TO_START = "M6,6h2v12H6zM9.5,12l8.5,6V6z"
private const val ICON_LOOP =
    "M7,7h10v3l4,-4,-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4,4,4v-3h12v-6h-2v4z"

// the cursor: a light band over the notes being played and a dot on each notehead
private val CURSOR_BAND = Color(0x3300A0FF)
private val CURSOR_HEAD = Color(0x8800A0FF)

/**
 * The playback band, in the bottom bar above the page dots: ⏮ (back to A, or to the start),
 * ▶ / ⏸, the loop button (first tap A, second tap B and loop, third tap off), and the tempo,
 * which opens the playback options. It shows with the bars (centre tap) and the music goes on
 * when they hide.
 */
@Composable
fun PlaybackBand(
    state: PlayState,
    loop: LoopState,
    bpm: Int,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onLoop: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val restartLabel = stringResource(
            if (loop is LoopState.Off) R.string.playback_to_start else R.string.playback_to_a
        )
        IconButton(onClick = onRestart, modifier = Modifier.semantics { contentDescription = restartLabel }) {
            ToolIcon(ICON_TO_START)
        }
        val playing = state == PlayState.PLAYING
        val playLabel = stringResource(if (playing) R.string.playback_pause else R.string.playback_play)
        FilledTonalIconButton(
            onClick = onPlayPause,
            enabled = state != PlayState.PREPARING,
            modifier = Modifier.semantics { contentDescription = playLabel },
        ) {
            ToolIcon(if (playing) ICON_PAUSE else ICON_PLAY)
        }
        LoopButton(loop, onLoop)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onOptions) {
            Text(stringResource(R.string.tempo_value, bpm), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The loop button shows where the cycle is: plain when off, "A" once A is marked, filled while looping. */
@Composable
private fun LoopButton(loop: LoopState, onLoop: () -> Unit) {
    val label = stringResource(
        when (loop) {
            LoopState.Off -> R.string.loop_mark_a
            is LoopState.FromA -> R.string.loop_mark_b
            is LoopState.Looping -> R.string.loop_off
        }
    )
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(ICON_LOOP)
            if (loop is LoopState.FromA) Text("A", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (loop is LoopState.Looping) {
        FilledTonalIconButton(onClick = onLoop, modifier = Modifier.semantics { contentDescription = label }) { content() }
    } else {
        IconButton(onClick = onLoop, modifier = Modifier.semantics { contentDescription = label }) { content() }
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
    canSlower: Boolean,
    canFaster: Boolean,
    onTempo: (Int) -> Unit,
    onTempoAsWritten: () -> Unit,
    parts: List<Part>,
    heard: Set<String>,
    onHeard: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelOverlay(stringResource(R.string.menu_playback), onDismiss, modifier) {
        SectionLabel(stringResource(R.string.tempo_section_label))
        StepperRow(
            label = stringResource(R.string.tempo_label),
            value = stringResource(R.string.tempo_value, bpm),
            lessLabel = stringResource(R.string.tempo_slower),
            moreLabel = stringResource(R.string.tempo_faster),
            canLess = canSlower,
            canMore = canFaster,
            onLess = { onTempo(bpm - TEMPO_STEP_BPM) },
            onMore = { onTempo(bpm + TEMPO_STEP_BPM) },
        )
        TextButton(onClick = onTempoAsWritten, enabled = bpm != writtenBpm) {
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
