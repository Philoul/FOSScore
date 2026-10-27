package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.MetronomeSettings
import com.musigatto.fosscore.metronome.Tick
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat
import kotlin.math.roundToInt

/**
 * The annotation bar: one row of tools, with the options of the selected tool dropping from it.
 * It only draws and reports taps; the viewer decides what each tool does.
 */
@Composable
internal fun AnnotationBar(
    activeSymbol: StampSymbol?,
    lastSymbol: StampSymbol,
    openPanel: ToolPanel?,
    drawingTool: Boolean,
    eraserTool: Boolean,
    invert: Boolean,
    activeColor: Int?,
    penWidthPx: Float,
    eraserPx: Float,
    placePx: Float,
    defaultStampPx: Float,
    pageTouched: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canDelete: Boolean,
    onToolTap: (ToolPanel) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onStampSymbol: (StampSymbol) -> Unit,
    onStampSize: (Float) -> Unit,
    onSaveDefault: () -> Unit,
    onPenWidth: (Float) -> Unit,
    onEraserPx: (Float) -> Unit,
    onColor: (Int?) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // landscape: the same side inset the title bar takes, so the tools do not run
            // under the navigation bar
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
            )
    ) {
        // the tools are drawn glyphs now, with no text of their own: the names come from here
        val penLabel = stringResource(R.string.tool_pen)
        val eraserLabel = stringResource(R.string.tool_eraser)
        val undoLabel = stringResource(R.string.action_undo)
        val redoLabel = stringResource(R.string.action_redo)
        val deleteLabel = stringResource(R.string.action_delete)
        // one row of tools; the stamps tool comes first (spec §5 row 2). Each tool opens its
        // own options panel on a second tap, so no size slider sits in the bar all the time.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState())
                // the tool row, not the whole column: an open panel must not move the
                // page down with it
                .onSizeChanged { onHeight(it.height) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // stamps tool (spec §5): shows the last stamp used. Tap once to select it,
            // again to open its panel (symbols + size). It never touches the selection.
            StampPaletteButton(
                symbol = activeSymbol ?: lastSymbol,
                selected = activeSymbol != null,
                onClick = { onToolTap(ToolPanel.Stamps) }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onToolTap(ToolPanel.Pen) },
                colors = if (drawingTool) ButtonDefaults.buttonColors()
                else ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.semantics {
                    contentDescription = penLabel
                },
            ) { ToolIcon(ICON_PENCIL) }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { onToolTap(ToolPanel.Eraser) },
                colors = if (eraserTool) ButtonDefaults.buttonColors()
                else ButtonDefaults.outlinedButtonColors(),
                modifier = Modifier.semantics {
                    contentDescription = eraserLabel
                },
            ) { EraserIcon() }
            Spacer(Modifier.width(6.dp))
            // the tint for ink and stamps, in its own little panel (it used to be a whole
            // row of swatches eating the width of the bar)
            ColorSwatchButton(
                // null is the default colour: draw the ink it really gives
                color = inkTint(activeColor, invert),
                selected = openPanel == ToolPanel.Color,
                onClick = { onToolTap(ToolPanel.Color) }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.semantics { contentDescription = undoLabel },
            ) { ToolIcon(ICON_UNDO) }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.semantics { contentDescription = redoLabel },
            ) { ToolIcon(ICON_REDO) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                enabled = canDelete,
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) { ToolIcon(ICON_TRASH) }
        }
        // one tool panel at a time drops from the bar (spec §5). They all step aside
        // while a finger is on the page, like forScore's panels.
        val panel = openPanel
        if (panel != null && !pageTouched) {
            when (panel) {
                ToolPanel.Stamps -> StampPalettePanel(
                    active = activeSymbol ?: lastSymbol,
                    sizePx = placePx,
                    onSymbol = onStampSymbol,
                    onSize = onStampSize,
                    onSaveDefault = onSaveDefault,
                    isDefault = placePx.roundToInt() == defaultStampPx.roundToInt(),
                )

                ToolPanel.Pen -> ToolSizePanel(
                    label = stringResource(R.string.thickness_label),
                    value = penWidthPx,
                    range = MIN_PEN_PX..MAX_PEN_PX,
                    onValue = onPenWidth,
                )

                ToolPanel.Eraser -> ToolSizePanel(
                    label = stringResource(R.string.size_label),
                    value = eraserPx,
                    range = MIN_ERASER_PX..MAX_ERASER_PX,
                    onValue = onEraserPx,
                )

                ToolPanel.Color -> ColorPanel(
                    active = activeColor,
                    invert = invert,
                    onPick = onColor
                )
            }
        }
    }
}

/**
 * The bottom of the reading chrome (spec §2): the metronome or playback band, when it shows, and
 * the page selector. Nothing else.
 */
@Composable
internal fun BottomChrome(
    metronomeVm: MetronomeViewModel,
    playbackVm: PlaybackViewModel,
    metronome: MetronomeSettings,
    metronomeOn: Boolean,
    metronomeTick: Tick?,
    metronomeShown: Boolean,
    pageSource: PageSource?,
    pageCount: Int,
    currentPage: Int,
    format: SheetFormat,
    playState: PlayState,
    loop: Loop,
    tempoFactor: Float,
    playbackFailed: Boolean,
    onStartStop: () -> Unit,
    onJump: (Int) -> Unit,
    onShowSurface: (ViewerSurface) -> Unit,
    onHeight: (Int) -> Unit,
    notesOnScreen: () -> Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(MaterialTheme.colorScheme.surface)
            // edge-to-edge: the knob was sitting in the navigation gesture area
            .navigationBarsPadding()
    ) {
        if (metronomeShown) {
            // the bar heard, counted from the start of the piece as the score numbers
            // its bars; a PDF score knows them once the bars of its pages are counted
            val barText = metronomeVm.barHeard(metronomeTick)
                ?.takeIf { format.isMusicXml || BarPages(metronome.barCounts, pageCount).known }
                ?.let { stringResource(R.string.metronome_bar, it + 1) }
            val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
            MetronomeBand(
                running = metronomeOn,
                tick = metronomeTick,
                settings = metronome,
                barText = barText,
                onStartStop = onStartStop,
                // a MusicXML score has one tempo, the one of playback
                onBpm = { bpm ->
                    if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, writtenBpm)) else metronomeVm.setBpm(bpm)
                },
                onTap = {
                    metronomeVm.tap()?.let { bpm ->
                        if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, writtenBpm))
                    }
                },
                onSettings = { onShowSurface(ViewerSurface.METRONOME) },
                onClose = { metronomeVm.show(false) },
                // a MusicXML score: the same button switches back to the playback band
                onPlayback = if (format.isMusicXml) ({ metronomeVm.show(false) }) else null,
            )
        } else if (format.isMusicXml) {
            val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
            PlaybackBand(
                state = playState,
                loop = loop,
                bpm = bpmOf(tempoFactor, writtenBpm),
                minBpm = bpmOf(MIN_TEMPO_FACTOR, writtenBpm),
                maxBpm = bpmOf(MAX_TEMPO_FACTOR, writtenBpm),
                onPlayPause = {
                    metronomeVm.stop()
                    playbackVm.playPause(notesOnScreen())
                },
                onRestart = playbackVm::restart,
                onLoop = playbackVm::toggleLoop,
                onA = playbackVm::tapA,
                onB = playbackVm::tapB,
                onTempo = { playbackVm.setTempo(factorOf(it, writtenBpm)) },
                onOptions = { onShowSurface(ViewerSurface.PLAYBACK) },
                onMetronome = {
                    playbackVm.stop()
                    metronomeVm.show(true)
                },
            )
            if (playbackFailed) {
                Text(
                    stringResource(R.string.playback_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        PageSelector(
            pageCount = pageCount,
            currentPage = currentPage,
            onJump = onJump,
            // air under the knob: flush with the edge it sat inside the system
            // navigation gesture area and was hard to grab
            modifier = Modifier.padding(
                start = 24.dp,
                end = 24.dp,
                // under a band, the dots need no air above them
                top = if (format.isMusicXml || metronomeShown) 0.dp else 10.dp,
                bottom = 10.dp,
            ),
        )
    }
}
