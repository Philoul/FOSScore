package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
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
 * How far down the annotation bar can be dragged (px): down to the bottom of the screen, above the
 * navigation bar. A score that does not fill the height leaves room under it, and that is where
 * the bar is out of the way. [pageHeightPx] is the room the page has below the bar at the top.
 */
internal fun annotationBarLowest(pageHeightPx: Int, bottomInsetPx: Int): Float =
    (pageHeightPx - bottomInsetPx).coerceAtLeast(0).toFloat()

/**
 * The annotation bar: the title bar's own row while annotating (spec §5: forScore replaces it).
 * ✕ leaves at the leading edge, ✓ and the grip at the trailing one, and the tools scroll between them when
 * they do not fit. Each tool's options drop from the tool itself, not from the bar's edge.
 * It only draws and reports taps; the viewer decides what each tool does.
 */
@Composable
internal fun AnnotationBar(
    tool: AnnotationTool,
    stampSymbol: StampSymbol,
    openPanel: AnnotationTool?,
    invert: Boolean,
    penColor: Int?,
    stampColor: Int?,
    textColor: Int?,
    penWidthPx: Float,
    eraserPx: Float,
    textPx: Float,
    placePx: Float,
    defaultStampPx: Float,
    pageTouched: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canDelete: Boolean,
    onTool: (AnnotationTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDragBar: (Float) -> Unit,
    onDragState: (Boolean) -> Unit,
    onStampSymbol: (StampSymbol) -> Unit,
    onStampSize: (Float) -> Unit,
    onSaveDefault: () -> Unit,
    onPenWidth: (Float) -> Unit,
    onEraserPx: (Float) -> Unit,
    onTextPx: (Float) -> Unit,
    onPenColor: (Int?) -> Unit,
    onStampColor: (Int?) -> Unit,
    onTextColor: (Int?) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // where each tool sits on screen, so its panel hangs from the tool and not from the bar's edge
    val toolX = remember { mutableStateMapOf<AnnotationTool, Float>() }
    var slotX by remember { mutableFloatStateOf(0f) }
    var slotWidth by remember { mutableIntStateOf(0) }
    var panelWidth by remember { mutableIntStateOf(0) }
    // the strip the bar paints over the status bar, mirrored below it: without it the tools sit
    // low against the page and the bar looks top-heavy
    val statusBarDp = with(LocalDensity.current) { WindowInsets.systemBars.getTop(this).toDp() }
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
        val selectLabel = stringResource(R.string.tool_select)
        val penLabel = stringResource(R.string.tool_pen)
        val eraserLabel = stringResource(R.string.tool_eraser)
        val stampLabel = stringResource(R.string.tool_stamp)
        val textLabel = stringResource(R.string.tool_text)
        val undoLabel = stringResource(R.string.action_undo)
        val redoLabel = stringResource(R.string.action_redo)
        val deleteLabel = stringResource(R.string.action_delete)
        val cancelLabel = stringResource(R.string.action_cancel)
        val doneLabel = stringResource(R.string.annotate_done)
        val moveLabel = stringResource(R.string.annotate_move_bar)
        // ✕ at the leading edge and ✓ at the trailing one (Apple HIG: Cancel leading, Done
        // trailing), outside the scroll so they never hide behind the tools. The tools keep the
        // rest of the width.
        //
        // This row is the top bar while annotating (the title bar steps aside), so it carries the
        // status bar inset, and it is measured with it: the page starts below the whole row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onHeight(it.height) }
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp + statusBarDp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ✕ and ✓ are the two ways out, at the ends where they cannot be reached by accident.
            // They read as actions, like undo and redo: no fill, never "selected" (issue #56).
            ActionButton(icon = ICON_CLOSE, label = cancelLabel, enabled = true, onClick = onCancel)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The tools (issue #56): exactly one is in hand, so they read as radio buttons.
                // The arrow selects, moves and resizes; the rest act on what they create.
                ToolButton(
                    active = tool == AnnotationTool.SELECT,
                    panelOpen = false,
                    label = selectLabel,
                    onClick = { onTool(AnnotationTool.SELECT) },
                ) { ToolIcon(ICON_SELECT) }
                Spacer(Modifier.width(4.dp))
                ToolButton(
                    active = tool == AnnotationTool.PEN,
                    panelOpen = openPanel == AnnotationTool.PEN,
                    label = penLabel,
                    onClick = { onTool(AnnotationTool.PEN) },
                    modifier = Modifier.onGloballyPositioned {
                        toolX[AnnotationTool.PEN] = it.positionInWindow().x
                    },
                ) { ToolIcon(ICON_PENCIL) }
                Spacer(Modifier.width(4.dp))
                ToolButton(
                    active = tool == AnnotationTool.ERASER,
                    panelOpen = openPanel == AnnotationTool.ERASER,
                    label = eraserLabel,
                    onClick = { onTool(AnnotationTool.ERASER) },
                    modifier = Modifier.onGloballyPositioned {
                        toolX[AnnotationTool.ERASER] = it.positionInWindow().x
                    },
                ) { EraserIcon() }
                Spacer(Modifier.width(4.dp))
                // the stamp shows the symbol it will place, so the bar tells what is armed
                ToolButton(
                    active = tool == AnnotationTool.STAMP,
                    panelOpen = openPanel == AnnotationTool.STAMP,
                    label = stampLabel,
                    onClick = { onTool(AnnotationTool.STAMP) },
                    modifier = Modifier.onGloballyPositioned {
                        toolX[AnnotationTool.STAMP] = it.positionInWindow().x
                    },
                ) {
                    val px = with(LocalDensity.current) { 22.dp.toPx() }
                    StampView(stampSymbol, sizePx = px, color = LocalContentColor.current)
                }
                Spacer(Modifier.width(4.dp))
                ToolButton(
                    active = tool == AnnotationTool.TEXT,
                    panelOpen = openPanel == AnnotationTool.TEXT,
                    label = textLabel,
                    onClick = { onTool(AnnotationTool.TEXT) },
                    modifier = Modifier.onGloballyPositioned {
                        toolX[AnnotationTool.TEXT] = it.positionInWindow().x
                    },
                ) { ToolIcon(ICON_TEXT) }
                ToolDivider()
                // Actions (issue #56): never "selected", always the same plain look; greyed when
                // the action is not available.
                ActionButton(icon = ICON_UNDO, label = undoLabel, enabled = canUndo, onClick = onUndo)
                Spacer(Modifier.width(4.dp))
                ActionButton(icon = ICON_REDO, label = redoLabel, enabled = canRedo, onClick = onRedo)
                Spacer(Modifier.width(4.dp))
                ActionButton(icon = ICON_TRASH, label = deleteLabel, enabled = canDelete, onClick = onDelete)
            }
            Spacer(Modifier.width(8.dp))
            // ✓ is the way to keep and leave: the one action in the accent colour
            ActionButton(
                icon = ICON_CHECK,
                label = doneLabel,
                enabled = true,
                tint = MaterialTheme.colorScheme.primary,
                onClick = onDone,
            )
            // the grip: the bar can be dragged down out of the way (spec §5), and ✓ puts it back
            // at the top. At the trailing edge, away from the menu's ☰ at the leading one, and
            // drawn with two lines so it does not read as a menu. It only turns see-through while
            // a finger is on it.
            Box(
                modifier = Modifier
                    // 48dp is the minimum touch target; the glyph stays small inside it
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragState(true) },
                            onDragEnd = { onDragState(false) },
                            onDragCancel = { onDragState(false) },
                        ) { change, drag ->
                            change.consume()
                            onDragBar(drag.y)
                        }
                    }
                    .semantics { contentDescription = moveLabel },
                contentAlignment = Alignment.Center,
            ) { GripIcon() }
        }
        // one tool panel at a time drops from the bar (spec §5). They all step aside
        // while a finger is on the page, like forScore's panels. The panel hangs from its own
        // tool and stays inside the bar's width (colour, at the right, would overflow).
        val panel = openPanel
        if (panel != null && !pageTouched) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { slotX = it.positionInWindow().x }
                    .onSizeChanged { slotWidth = it.width }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            val raw = ((toolX[panel] ?: slotX) - slotX).roundToInt()
                            IntOffset(raw.coerceIn(0, (slotWidth - panelWidth).coerceAtLeast(0)), 0)
                        }
                        .onSizeChanged { panelWidth = it.width }
                ) {
                    when (panel) {
                        AnnotationTool.STAMP -> StampPalettePanel(
                            active = stampSymbol,
                            sizePx = placePx,
                            color = stampColor,
                            invert = invert,
                            onColor = onStampColor,
                            onSymbol = onStampSymbol,
                            onSize = onStampSize,
                            onSaveDefault = onSaveDefault,
                            isDefault = placePx.roundToInt() == defaultStampPx.roundToInt(),
                        )

                        // the pen and the stamp carry their own tint (issue #56): the colour is a
                        // setting of each tool, not a tool of its own on the bar
                        AnnotationTool.PEN -> ToolSizePanel(
                            label = stringResource(R.string.thickness_label),
                            value = penWidthPx,
                            range = MIN_PEN_PX..MAX_PEN_PX,
                            onValue = onPenWidth,
                            lead = { ColorChoices(active = penColor, invert = invert, onPick = onPenColor) },
                        )

                        AnnotationTool.ERASER -> ToolSizePanel(
                            label = stringResource(R.string.size_label),
                            value = eraserPx,
                            range = MIN_ERASER_PX..MAX_ERASER_PX,
                            onValue = onEraserPx,
                        )

                        AnnotationTool.TEXT -> ToolSizePanel(
                            label = stringResource(R.string.size_label),
                            value = textPx,
                            range = MIN_TEXT_PX..MAX_TEXT_PX,
                            onValue = onTextPx,
                            lead = { ColorChoices(active = textColor, invert = invert, onPick = onTextColor) },
                        )

                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * A tool of the annotation bar (issue #56). Three looks, and only three: plain when out of hand,
 * inverse video when in hand, outline when in hand with its options open. It never turns itself
 * off; picking another tool is how you leave it.
 *
 * It is announced as a selected radio button, not only shown by its colour (WCAG 1.4.1).
 */
@Composable
private fun ToolButton(
    active: Boolean,
    panelOpen: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (active && !panelOpen) scheme.primary else Color.Transparent
    val tint = when {
        active && panelOpen -> scheme.primary
        active -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    Surface(
        color = fill,
        shape = RoundedCornerShape(10.dp),
        border = if (active && panelOpen) BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.RadioButton
                selected = active
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/**
 * An action of the annotation bar (issue #56): undo, redo, delete, ✕, ✓. Never "selected", always
 * the same plain look; greyed when it cannot be done. [tint] is for the one action that leads out
 * of the bar with the marks kept.
 */
@Composable
private fun ActionButton(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val color = tint ?: if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { ToolIcon(icon) }
    }
}

/** Separates the tools from the actions, as the sketch in issue #56 draws them. */
@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * Asked before ✕ throws away what was drawn since the pen was picked up (issue #50): the undo
 * history is not enough to get it back, so the loss is named before it happens.
 */@Composable
internal fun DiscardAnnotationDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.annotate_discard_title)) },
        text = { Text(stringResource(R.string.annotate_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.annotate_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.annotate_keep_editing)) }
        },
    )
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
