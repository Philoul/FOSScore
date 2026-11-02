package com.musigatto.fosscore.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.MAX_BPM
import com.musigatto.fosscore.metronome.MIN_BPM
import com.musigatto.fosscore.metronome.MetronomeSettings
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat

/**
 * The menu and its panels, over everything: one at a time (ViewerMenuViewModel), the panels
 * under the bar at the top while it shows.
 */
@Composable
internal fun ViewerOverlays(
    surface: ViewerSurface,
    menu: ViewerMenuViewModel,
    format: SheetFormat,
    onOpenLibrary: () -> Unit,
    editing: Boolean,
    onToggleEditing: () -> Unit,
    onShowChrome: () -> Unit,
    metronomeVm: MetronomeViewModel,
    playbackVm: PlaybackViewModel,
    metronome: MetronomeSettings,
    pageSource: PageSource?,
    pageCount: Int,
    currentPage: Int,
    onJumpPage: (Int) -> Unit,
    isLandscape: Boolean,
    meterFromScore: Boolean,
    parts: List<Part>,
    heardPicked: Set<String>?,
    shownIds: Set<String>,
    scoreLayout: ScoreLayout,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    invert: Boolean,
    onSetInvert: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onSetKeepScreenOn: (Boolean) -> Unit,
    dimPct: Float,
    onSetDimPct: (Float) -> Unit,
    onCommitDim: () -> Unit,
    twoUp: Boolean,
    onSetTwoUp: (Boolean) -> Unit,
    halfEnabled: Boolean,
    onSetHalf: (Boolean) -> Unit,
    tempoFactor: Float,
    panelTop: Dp,
) {
    CompositionLocalProvider(LocalPanelTop provides panelTop) {
        when (surface) {
            ViewerSurface.NONE -> Unit
            ViewerSurface.MENU -> ViewerMenuPanel(
                isMusicXml = format.isMusicXml,
                onSelect = { item ->
                    menu.dismiss()
                    when (item) {
                        ViewerMenuItem.LIBRARY -> onOpenLibrary()
                        ViewerMenuItem.DISPLAY -> menu.show(ViewerSurface.DISPLAY)
                        ViewerMenuItem.LAYOUT -> menu.show(ViewerSurface.LAYOUT)
                        ViewerMenuItem.PLAYBACK -> {
                            metronomeVm.show(false)
                            menu.show(ViewerSurface.PLAYBACK)
                        }
                        // its settings, as for playback; its band takes the place of the playback band
                        ViewerMenuItem.METRONOME -> {
                            playbackVm.stop()
                            metronomeVm.show(true)
                            onShowChrome()
                            menu.show(ViewerSurface.METRONOME)
                        }
                        ViewerMenuItem.ANNOTATE -> if (!editing) onToggleEditing()
                        // greyed out in the menu: never chosen
                        else -> Unit
                    }
                },
                onDismiss = menu::dismiss,
            )
            ViewerSurface.METRONOME -> {
                // a MusicXML score has one tempo, the one of playback, and a tempo as written
                val written = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
                val pages = BarPages(metronome.barCounts, pageCount)
                MetronomeOverlay(
                    settings = metronome,
                    bpmRange = if (format.isMusicXml) bpmOf(MIN_TEMPO_FACTOR, written)..bpmOf(MAX_TEMPO_FACTOR, written) else MIN_BPM..MAX_BPM,
                    writtenBpm = if (format.isMusicXml) bpmOf(1f, written) else null,
                    wide = isLandscape,
                    meterFromScore = meterFromScore,
                    pageBars = if (format.isMusicXml || pageCount == 0) null else PageBars(
                        page = currentPage,
                        pageCount = pageCount,
                        bars = pages.barsOf(currentPage).takeIf { it > 0 } ?: metronome.meter.beats.coerceAtLeast(4),
                        firstBar = pages.firstBar(currentPage),
                    ),
                    onBpm = { bpm ->
                        if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, written)) else metronomeVm.setBpm(bpm)
                    },
                    onMeter = metronomeVm::setMeter,
                    onMode = metronomeVm::setMode,
                    onSound = metronomeVm::setSound,
                    onVolume = metronomeVm::setVolume,
                    onAccent = metronomeVm::setAccent,
                    onSubdivision = metronomeVm::setSubdivision,
                    onCountIn = metronomeVm::setCountIn,
                    onAutoTurn = metronomeVm::setAutoTurn,
                    onTurnAhead = metronomeVm::setTurnAhead,
                    // the page behind changes too, to check the number of its last bar
                    onBarsPage = onJumpPage,
                    onBars = { metronomeVm.setBars(currentPage, it) },
                    onCopyBars = { metronomeVm.copyBarsToFollowing(currentPage) },
                    onDismiss = menu::dismiss,
                )
            }
            ViewerSurface.DISPLAY -> DisplayOptionsOverlay(
                pageModes = !editing,
                halfEnabled = halfEnabled,
                onSetHalf = onSetHalf,
                twoUp = twoUp,
                onSetTwoUp = onSetTwoUp,
                twoUpVisible = isLandscape,
                themeLabel = themeMode.label(),
                onToggleTheme = onToggleTheme,
                invert = invert,
                onToggleInvert = { onSetInvert(!invert) },
                keepScreenOn = keepScreenOn,
                onToggleKeepScreenOn = { onSetKeepScreenOn(!keepScreenOn) },
                dimPct = dimPct,
                onDimChange = onSetDimPct,
                onDimChangeFinished = onCommitDim,
                onDismiss = menu::dismiss,
            )
            ViewerSurface.PLAYBACK -> {
                val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
                PlaybackOptionsOverlay(
                    bpm = bpmOf(tempoFactor, writtenBpm),
                    writtenBpm = bpmOf(1f, writtenBpm),
                    minBpm = bpmOf(MIN_TEMPO_FACTOR, writtenBpm),
                    maxBpm = bpmOf(MAX_TEMPO_FACTOR, writtenBpm),
                    onTempo = { playbackVm.setTempo(factorOf(it, writtenBpm)) },
                    turnAhead = metronome.turnAhead,
                    onTurnAhead = metronomeVm::setTurnAhead,
                    parts = parts,
                    heard = heardParts(heardPicked, shownIds, parts),
                    onHeard = { id, on -> playbackVm.setHeard(id, on, parts) },
                    onDismiss = menu::dismiss,
                )
            }
            ViewerSurface.LAYOUT -> ScoreLayoutOverlay(
                layout = scoreLayout,
                parts = parts,
                onMusicSize = menu::setMusicSize,
                onTranspose = menu::transpose,
                onLyrics = menu::setLyrics,
                onKeepHiddenLyrics = menu::setKeepHiddenLyrics,
                onPartShown = menu::setPartShown,
                onDismiss = menu::dismiss,
            )
        }
    }
}

internal fun inkTint(color: Int?, invert: Boolean): Color {
    val c = color?.let { com.musigatto.fosscore.ui.viewer.stampColor(it, Color.Black) } ?: Color.Black
    return if (invert) c.copy(red = 1f - c.red, green = 1f - c.green, blue = 1f - c.blue) else c
}
