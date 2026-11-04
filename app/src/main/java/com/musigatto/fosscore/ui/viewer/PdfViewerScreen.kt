package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.StampStore
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.StrokeStore
import com.musigatto.fosscore.library.isTextStamp
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.Meter
import com.musigatto.fosscore.metronome.MetronomeMode
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.score.MeasureAnchors
import com.musigatto.fosscore.score.MusicXmlMarks
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.MusicXmlScreen
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// The render (memory) cap now lives in MuPdfDoc.MAX_RENDER_PIXELS: MuPDF draws at whatever
// resolution we ask for, so zoom looks sharp instead of slightly soft.
private const val LOG_TAG = "FOSScore-PDF"

// Room the reading chrome takes over a MusicXML page, below the status bar and above the
// navigation bar: the title bar (8 dp of air, a 48 dp pill, 8 dp) and the page selector (48 dp
// track with 10 dp above and below). Keep in step with ViewerTitleBar and PageSelector.
private val TITLE_BAR_ROOM = 64.dp
private val SELECTOR_ROOM = 68.dp
// and, for MusicXML, the playback band (44 dp buttons) right above the page dots, which then
// lose their 10 dp of air on top
private val PLAYBACK_BAND_ROOM = 44.dp
private val SELECTOR_AIR = 10.dp
// how long the sizes of a MusicXML page must stay still before it is laid out
private const val LAYOUT_SETTLE_MS = 120L
// The top-left corner where the ☰ button sits when the bars are shown, as wide as the button with
// its margin: a tap there opens the menu even with the bars hidden. Far from the left third's edge,
// so page turns keep their zone.
private val MENU_CORNER = 112.dp

// Zoom the double tap jumps to (2.5x: readable without losing sight of the page).
internal const val DOUBLE_TAP_ZOOM = 2.5f

// The double tap is animated (~1 frame would teleport to the corner): 250 ms, with the exact
// anchor on every frame.
private const val DOUBLE_TAP_ANIM_MS = 250

// Re-draw during a pinch and its settling wait live with the drawing, in PageRender.kt.

// in half page, the cut of the next turn for [page] as laid out in [layout]: the turn makes
// [split], the dotted line shows it at [mark] (a fraction of the page height)
internal data class HalfCut(val page: Int, val layout: Int, val split: HalfSplit, val mark: Float)

// The tools of the annotation bar (issue #56): exactly one is in hand at a time. The arrow is the
// neutral one and the only one that selects and moves; every other tool acts on what it creates and
// keeps itself in hand until another tool is chosen.
internal enum class AnnotationTool {
    SELECT, PEN, SHAPE, ERASER, STAMP, TEXT;

    /** The arrow has no settings: tapping it again does nothing. */
    val hasPanel: Boolean get() = this != SELECT
}

// A stroke prepared for drawing: the Path is built in fit coordinates once per change, so a
// redraw (next pen point, pinch, pan) only re-issues it instead of decoding the points again.
// [filled] carries a solid shape (strokes.kinds is the shape kind): drawn Fill, no outline.
internal class InkDraw(val id: Long, val path: Path, val color: Color, val width: Float, val filled: Boolean)

private fun inkDrawsOf(strokes: List<Stroke>, fit: RectPx, invert: Boolean): List<InkDraw> =
    strokes.map { st ->
        InkDraw(
            id = st.id,
            path = inkPath(strokePointsPx(st, fit)),
            color = inkTint(st.color, invert),
            width = st.width * fit.height,
            filled = Stroke.isFilled(st.kind)
        )
    }

@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    title: String,
    composer: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit = onBack,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onOpenPreferences: () -> Unit = {},
    sheetId: Long? = null,
    /** In a setlist, the setlist: ≡ in the menu opens it (the setlists otherwise). */
    onOpenSetlist: () -> Unit = onOpenLibrary,
    /**
     * In a setlist, turning past the last page opens the next score, before the first the one
     * before; each is told whether the top bar shows, for the other score to open the same way.
     */
    onPastEnd: ((Boolean) -> Unit)? = null,
    onBeforeStart: ((Boolean) -> Unit)? = null,
    /** Where the score opens: its first page (the next score of a setlist), its last one (the one before). */
    start: StartPage = StartPage.REMEMBERED,
    /** In a setlist, its name and the score's place in it, recalled next to the title. */
    setlistPlace: SetlistPlace? = null,
    /** The top bar shows on opening: the score before in the setlist was left with it. */
    chromeShown: Boolean = false,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // the menu, its panels and the score's layout settings: one ViewModel per score
    val menu: ViewerMenuViewModel = viewModel(
        key = "viewer-menu:$pdfUri",
        factory = ViewerMenuViewModel.factory(context, pdfUri.toString()),
    )
    val surface by menu.surface.collectAsState()
    val scoreLayout by menu.layout.collectAsState()
    val parts by menu.parts.collectAsState()

    // MusicXML playback: the MIDI player, the cursor and the A–B loop (PlaybackViewModel)
    val playbackVm: PlaybackViewModel = viewModel(
        key = "playback:$pdfUri",
        factory = PlaybackViewModel.factory(context, pdfUri.toString()),
    )
    val playState by playbackVm.state.collectAsState()
    val loop by playbackVm.loop.collectAsState()
    val cursorNotes by playbackVm.cursor.collectAsState()
    val tempoFactor by playbackVm.tempo.collectAsState()
    val heardPicked by playbackVm.heard.collectAsState()
    val playbackFailed by playbackVm.failed.collectAsState()
    val aheadNotes by playbackVm.ahead.collectAsState()
    // another score opened, or the viewer closed: this score's music stops (its ViewModel stays
    // in memory with the screen, and its player would go on playing)
    DisposableEffect(playbackVm) { onDispose { playbackVm.stop() } }
    // the music heard, read from the audio clock at every frame while it plays: the playback bar
    // glides with it (only the bar is drawn again, nothing is composed again)
    var playQuarters by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(playState, cursorNotes) {
        playQuarters = playbackVm.positionNow()
        if (playState == PlayState.PLAYING) while (true) withFrameNanos { playQuarters = playbackVm.positionNow() }
    }

    // the metronome: its band, its clicks and the page turns it drives (MetronomeViewModel)
    val metronomeVm: MetronomeViewModel = viewModel(
        key = "metronome:$pdfUri",
        factory = MetronomeViewModel.factory(context, pdfUri.toString()),
    )
    val metronome by metronomeVm.settings.collectAsState()
    val metronomeOn by metronomeVm.running.collectAsState()
    val metronomeShown by metronomeVm.shown.collectAsState()
    val metronomeTick by metronomeVm.tick.collectAsState()
    val meterFromScore by metronomeVm.meterFromScore.collectAsState()

    // the page edits: undo/redo, the ink and stamps waiting for the DB, the selection
    // (AnnotationViewModel). One per score, like the menu and the playback.
    val editor: AnnotationViewModel = viewModel(
        key = "annotation:$pdfUri",
        factory = AnnotationViewModel.factory(context, sheetId),
    )
    // how the pages show (two-up, half page, dim, invert, keep the screen on): one ViewModel for
    // every score, reading and writing the settings in one place
    val viewer: ViewerViewModel = viewModel(
        key = "viewer:$isLandscape",
        factory = ViewerViewModel.factory(context, isLandscape),
    )
    // a MusicXML score's bars and notes, for the page turns of the metronome
    var metronomeMusic by remember { mutableStateOf<Playback?>(null) }
    SideEffect { playbackVm.turnAhead = metronome.turnAhead }

    var pageSource by remember { mutableStateOf<PageSource?>(null) }
    // a MusicXML score has no pages of its own: they are laid out for this screen (MusicXmlPages)
    val format = remember(pdfUri) { SheetFormat.of(pdfUri.path.orEmpty()) }
    // bumped when a MusicXML score is laid out again: its pages changed under the same numbers
    var layoutVersion by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(if (start == StartPage.REMEMBERED) Settings.lastPage(context, pdfUri.toString()) else 0)
    }
    // opened from the score after it in a setlist: its last page, once its pages are counted
    var toLastPage by rememberSaveable(pdfUri) { mutableStateOf(start == StartPage.LAST) }
    // the same for every score, half page for each orientation (ViewerViewModel)
    val display by viewer.display.collectAsState()
    val twoUp = display.twoUp
    val halfEnabled = display.halfPage
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    // the chrome starts hidden: the app is the page (spec §0); in a setlist, as the score before left it
    var navVisible by rememberSaveable { mutableStateOf(chromeShown) }
    // the paper: the document, its bitmaps, the cut of a half page and the lock MuPDF needs
    val paper = rememberPagePaper(pdfUri)
    // the paper that is open: how many pages it has, and why it is not there if it is not.
    // Plain reads: the paper holds them as Compose state, so a change recomposes the screen.
    val pageCount = paper.pageCount
    val error = paper.error
    LaunchedEffect(pageCount, toLastPage) {
        if (toLastPage && pageCount > 0) {
            currentPage = pageCount - 1
            toLastPage = false
        }
    }
    // zoom and pan: read in the gesture's pointer loop and in the canvas' draw lambdas, never
    // in composition (a per-frame read here would recompose the screen on every pinch frame)
    val zoom = rememberZoomState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val dimPct = display.dimPct
    val invert = display.invert
    val keepScreenOn = display.keepScreenOn
    // the look of the top bar, chosen in the preferences; they open over the score, so a change
    // made there shows at once
    var chromeStyle by remember { mutableStateOf(Settings.chromeStyle(context)) }
    DisposableEffect(context) {
        val stop = Settings.onChromeStyleChange(context) { chromeStyle = it }
        onDispose { stop() }
    }

    // Applies only while the viewer is on screen: the library lets the device sleep as usual.
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn, playState) {
        // and always while the music plays (roadmap 4.7): nobody touches the screen then
        view.keepScreenOn = keepScreenOn || playState == PlayState.PLAYING
        onDispose { view.keepScreenOn = false }
    }

    // where the marks are kept: a PDF's pages, or a MusicXML score's measures, so they follow the
    // music when it is laid out again. The editing is the same for both.
    val marks = remember(pageSource) {
        (pageSource as? MusicXmlPages)?.let { MusicXmlMarks(context.applicationContext, it) }
    }
    val app = context.applicationContext as FOSScoreApp
    val stampStore: StampStore = marks?.stamps ?: app.stampRepository
    val strokeStore: StrokeStore = marks?.strokes ?: app.strokeRepository
    LaunchedEffect(stampStore, strokeStore) { editor.useStores(stampStore, strokeStore) }

    // one page's rows: the viewer draws one page, and re-reading the whole score on every stroke
    // made each stroke cost grow with the score
    val stampsFlow = remember(sheetId, currentPage, stampStore) {
        sheetId?.let { stampStore.observe(it, currentPage) } ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())

    val strokesFlow = remember(sheetId, currentPage, strokeStore) {
        sheetId?.let { strokeStore.observe(it, currentPage) } ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())

    // height of the annotation bar, measured: while annotating it is the top bar, and the page keeps
    // clear of it. Hoisted out of the chrome block because the bar is removed while the loupe is up
    // (and the height must survive that).
    var annBarPx by remember { mutableIntStateOf(0) }
    // the height of the bottom chrome (a band, the page dots), kept while it is hidden
    var bottomChromePx by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // ✕ asks before throwing away the session's marks, and only when there are some (issue #50)
    var confirmDiscard by remember { mutableStateOf(false) }
    // the page tag that flashes while annotating: the bar has no room for a page label, so the
    // number shows for a moment after a turn and goes away on its own
    var pageFlash by remember { mutableStateOf("") }
    // how far down the annotation bar was dragged out of the way (px). ✓ and ✕ put it back.
    var barDragY by remember { mutableFloatStateOf(0f) }
    // the bar goes down to the bottom of the screen, above the navigation bar
    val navigationBottomPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    // and its tool panels stay clear of the status bar and the navigation bar
    val statusTopPx = WindowInsets.systemBars.getTop(LocalDensity.current)
    // true only while a finger is on the grip: the bar is see-through while it moves, opaque
    // once it is parked
    var barDragging by remember { mutableStateOf(false) }
    var stampSymbol by rememberSaveable { mutableStateOf(StampSymbol.FLAT) }  // glyph of the stamp tool
    // the tool in hand (issue #56): the last one used, the arrow the very first time
    var tool by rememberSaveable { mutableStateOf(Settings.annotationTool(context)) }
    var openPanel by rememberSaveable { mutableStateOf<AnnotationTool?>(null) }  // its options, if any
    var placePx by rememberSaveable { mutableFloatStateOf(Settings.stampPx(context)) }  // size of the next stamp
    // last size saved as default (persisted): the panel greys its save button while they match
    var defaultStampPx by remember { mutableFloatStateOf(Settings.stampPx(context)) }
    var eraserPx by rememberSaveable { mutableFloatStateOf(DEFAULT_ERASER_PX) }  // eraser radius (px at zoom 1)
    // a finger on the page hides the open panel so it does not cover what you are placing (spec §5)
    var pageTouched by remember { mutableStateOf(false) }
    // Loupe to aim a stamp (screen px). `loupeAnchor` is the finger while it rests; after the
    // long-press timeout it turns into `loupeAt`, which then follows the finger. Both null = off.
    var loupeAnchor by remember { mutableStateOf<Offset?>(null) }
    var loupeAt by remember { mutableStateOf<Offset?>(null) }
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    // Only a long press opens the loupe (a tap still stamps straight away): hold, then drag.
    LaunchedEffect(loupeAnchor) {
        val anchor = loupeAnchor ?: return@LaunchedEffect
        delay(longPressMs.milliseconds)
        if (loupeAnchor == anchor) loupeAt = anchor
    }
    // selected stamp (for the box with handles) or the selected word of ink
    // one tint per tool (issue #56): the pen, the stamp and the TEXT tool keep their own colour
    var penColor by rememberSaveable { mutableStateOf<Int?>(null) }   // ARGB; null = theme
    var stampColor by rememberSaveable { mutableStateOf<Int?>(null) }
    var textColor by rememberSaveable { mutableStateOf<Int?>(null) }  // text tool color
    var shapeColor by rememberSaveable { mutableStateOf<Int?>(null) } // shapes' own tine (#56)
    // whether the next shape draws filled (the solid box that covers a line: white flips black in
    // negative mode, painted through inkTint like any other ink)
    var shapeFilled by rememberSaveable { mutableStateOf(false) }
    var shapeType by rememberSaveable { mutableStateOf(ShapeKind.LINE) }
    var shapeWidthPx by rememberSaveable { mutableFloatStateOf(Settings.shapePx(context)) }
    var defaultShapePx by remember { mutableFloatStateOf(Settings.shapePx(context)) }
    // sizes per tool: pen width, eraser radius, stamp size, text size (all px at zoom 1)
    var penWidthPx by remember { mutableFloatStateOf(DEFAULT_PEN_PX) }  // ink thickness (px at zoom 1)
    var textPx by rememberSaveable { mutableFloatStateOf(Settings.textPx(context)) }  // next new text's font size (px at zoom 1)
    var defaultTextPx by remember { mutableFloatStateOf(Settings.textPx(context)) }
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview of the stroke being drawn (px)
    val editScope = rememberCoroutineScope()
    // double-tap animation in flight: any new touch cancels it
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)
    // two pages on screen at once: the reading modes scale the whole viewport, a single page
    // scales its own content instead
    val spread = branch.showTwoUp || (branch.showHalf && halfTurned)

    // "Página 5 de 9" / "Páginas 3-4 de 9". Empty while the PDF still has no pages, so the pill
    // never says "Página 1 de 0".
    val pageLabel = visiblePageRange(
        currentPage,
        pageCount,
        spread,
    ).let { r ->
        when {
            r.isEmpty() -> ""
            r.first == r.last -> stringResource(R.string.desc_page, r.first + 1, pageCount)
            else -> stringResource(R.string.desc_page_range, r.first + 1, r.last + 1, pageCount)
        }
    }

    // while annotating the page number is not in the bar: it flashes when the page changes and
    // fades by itself. Keyed on the label so it also fires on entering annotation mode.
    LaunchedEffect(pageLabel, editing) {
        if (!editing || pageLabel.isEmpty()) {
            pageFlash = ""
            return@LaunchedEffect
        }
        pageFlash = pageLabel
        delay(1400.milliseconds)
        pageFlash = ""
    }

    // the second page of a spread. The viewer is reading, not editing, there, but its ink and its
    // stamps are drawn too: half of a two-up (or the top of a turned half page) would come out bare.
    val companion = if (spread && currentPage + 1 < pageCount) currentPage + 1 else null
    val companionStampsFlow = remember(sheetId, companion, stampStore) {
        if (sheetId != null && companion != null) {
            stampStore.observe(sheetId, companion)
        } else flowOf(emptyList())
    }
    val companionStamps by companionStampsFlow.collectAsState(initial = emptyList())
    val companionStrokesFlow = remember(sheetId, companion, strokeStore) {
        if (sheetId != null && companion != null) {
            strokeStore.observe(sheetId, companion)
        } else flowOf(emptyList())
    }
    val companionStrokes by companionStrokesFlow.collectAsState(initial = emptyList())

    // a MusicXML score is annotated too: its marks hang on its measures (MusicXmlMarks)
    val canEdit = sheetId != null && !branch.showTwoUp &&
        !(branch.showHalf && halfTurned)
    // the boundaries between the lines of music of a MusicXML page (0..1 down the page), drawn
    // while annotating: a mark belongs to the line on its side (MeasureAnchors)
    val musicXmlPages = pageSource as? MusicXmlPages
    val geometryVersion = musicXmlPages?.geometryVersion?.collectAsState()?.value
    val lineBoundaries = remember(musicXmlPages, currentPage, geometryVersion) {
        musicXmlPages?.let { pages ->
            pages.geometry(currentPage)?.let { MeasureAnchors.boundaries(MeasureAnchors.slotsOf(it), pages.blankBands(currentPage)) }
        }.orEmpty()
    }

    // marks and stamps of the current page, shared between the overlay and the gestures.
    // The fit comes from MuPDF's size in points (not from the bitmap), so the UI can lay out from
    // the first frame and the overlay does not wait for a draw to land.
    val pageBox = pageSource?.pageSize(currentPage)
    // the DAO already returns only this page's rows; pendingStamps are the ones it has not handed
    // back yet (distinctBy drops the copy once the flow shows them), and this drops the ones
    // deleted a second ago
    val pageStamps =
        (stamps + editor.pendingStamps).distinctBy { it.id }.filter { it.id !in editor.hiddenStampIds }
    val pageStrokes = remember(strokes, editor.hiddenStrokeIds) { strokes.filter { it.id !in editor.hiddenStrokeIds } }
    val pageFit = if (pageBox != null && pageBox.height > 0f) {
        fitRect(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            pageBox.width / pageBox.height
        )
    } else RectPx(0f, 0f, 0f, 0f)
    // PURE fit rect (origin 0,0): content coordinates (the page itself, without pageFit's
    // centring margin). The whole overlay and the edit gesture live here.
    val hitFit = RectPx(0f, 0f, pageFit.width, pageFit.height)
    // the ink that is on screen: what the DB returned plus what it has not handed back yet
    // (editor.pendingInk). Drawing, hit-testing and grouping must all see the same set, or a letter
    // written a second ago is not selectable and the word's box comes out short.
    val inkStrokes = (editor.pendingInk + pageStrokes).distinctBy { it.id }
    // the same ink, ready to draw: decode + Path once per change, not once per frame
    val inkDraws = remember(inkStrokes, hitFit, invert) { inkDrawsOf(inkStrokes, hitFit, invert) }
    // the MEASURED boxes of the visible TEXT stamps: one layout per change, the same box feeds the
    // draw (PageCanvas), the gesture's hit test and the selection frame, so they cannot diverge.
    // The style here must stay identical to the one TextStampView draws with, bar the color (the
    // box is the text's size, not its shade).
    val textMeasurer = rememberTextMeasurer()
    val measureDensity = LocalDensity.current
    val measureStyle = LocalTextStyle.current
    val textRects = remember(
        pageStamps, companionStamps, editor.dragStamp, hitFit, textMeasurer, measureDensity, measureStyle
    ) {
        buildMap {
            val measuredStamps = pageStamps.map { st ->
                editor.dragStamp?.takeIf { it.id == st.id } ?: st
            }
            for (st in measuredStamps + companionStamps) {
                if (!st.isTextStamp()) continue
                val layout = textMeasurer.measure(
                    st.text.orEmpty(),
                    textStampStyle(measureStyle, textFontPx(st.size, hitFit), measureDensity),
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    constraints = Constraints(
                        maxWidth = (hitFit.width * TEXT_MAX_WRAP - 16f).roundToInt().coerceAtLeast(1)
                    ),
                )
                put(st.id, textBoxRect(st, layout.size.width.toFloat(), layout.size.height.toFloat(), hitFit))
            }
        }
    }
    // a text's box as the page draws it ([TextStampView]): the same style and wrapping width as the
    // measured boxes above
    fun measureText(text: String, sizeFrac: Float): Pair<Float, Float> {
        val layout = textMeasurer.measure(
            text,
            textStampStyle(measureStyle, textFontPx(sizeFrac, hitFit), measureDensity),
            overflow = TextOverflow.Clip,
            softWrap = true,
            constraints = Constraints(maxWidth = (hitFit.width * TEXT_MAX_WRAP - 16f).roundToInt().coerceAtLeast(1)),
        )
        return layout.size.width.toFloat() to layout.size.height.toFloat()
    }
    // the text being typed (#35) is written once it is over: placed, edited or deleted as it stands
    fun finishTyping() {
        val draft = editor.typing ?: return
        if (sheetId == null || draft.page != currentPage || hitFit.width <= 0f) {
            editor.dropTyping()
            return
        }
        val (w, h) = measureText(draft.text, draft.size)
        val typed = draftStamp(draft, sheetId, w, h, hitFit)
        editor.endTyping(draft.page, typingOutcome(draft, typed), PageEdit(pageStamps, pageStrokes))
    }
    // while a text is typed in, the field draws it: its row steps aside
    val shownStamps = editor.typing?.origin?.let { o -> pageStamps.filter { it.id != o.id } } ?: pageStamps
    // the next page's ink, same fit: its path only has to land on the paper that page takes, and
    // PageOverlay stretches it there
    val companionInkDraws = remember(companionStrokes, hitFit, invert) {
        inkDrawsOf(companionStrokes, hitFit, invert)
    }
    // the zoom frame reads these through rememberUpdatedState: the gesture that calls it captured
    // it when it started, and must see the live page
    val currentPageFit by rememberUpdatedState(pageFit)
    // the reading modes (two-up, half page) scale the whole viewport; a single page scales its
    // content.
    val currentSpread by rememberUpdatedState(spread)

    val selectedStamp = pageStamps.firstOrNull { it.id == editor.selectedId }
    val selectedStrokes = inkStrokes.filter { it.id in editor.selectedStrokeIds }

    // selection box in px: the stamp's or the ink's. While dragging or stretching we use the
    // live position (editor.dragStamp/editor.dragStrokes) so the box follows the object.
    fun stampSelectionRect(stamp: Stamp): RectPx =
        if (stamp.isTextStamp()) textRects[stamp.id] ?: stampRect(stamp, hitFit)
        else stampRect(stamp, hitFit)
    val selectionRect: RectPx? = editor.dragStamp?.let(::stampSelectionRect)
        ?: editor.dragStrokes?.let { inkBounds(it, hitFit) }
        ?: selectedStamp?.let(::stampSelectionRect)
        ?: inkBounds(selectedStrokes, hitFit)

    // while a drag or a stretch is in progress we draw the object at its live position, not the box
    val showSelection = selectionRect != null && editing && canEdit &&
        tool == AnnotationTool.SELECT
    // corner handles belong to an INK selection: a stamp's size is the palette slider, and forScore
    // moves a placed stamp with the selection tool instead of resizing it by its corners (spec §5)
    val strokeSelection = selectedStamp == null && (selectedStrokes.isNotEmpty() || editor.dragStrokes != null)

    // what Annotate (menu) and Done (pill) do. Leaving edit mode must not leave anything armed or
    // selected behind.
    fun toggleEditing() {
        // the gate lives here, so it also covers the entry paths that are coming (Apple Pencil, long
        // press). Two-up or a turned half page is no reason to refuse: annotating goes back to one
        // page, below.
        if (!editing && sheetId == null) return
        editing = !editing
        if (editing) {
            viewer.singlePage()
            halfTurned = false
            // a fresh session: ✕ only throws away what is drawn from now on (issue #50)
            editor.beginSession()
            // the bar always starts at the top
            barDragY = 0f
        } else {
            // a text still being typed is kept as it stands, as ✓ keeps everything
            finishTyping()
            // back to the pages as the user set them
            viewer.restoreReadingMode()
            editor.clearHistory()
            // ✓ keeps everything: the session's start is no longer needed
            editor.clearSession()
            barDragY = 0f
            editor.clearSelection()
            // the tool stays for the next visit (issue #56: the one used last); only what it had
            // open is put away
            openPanel = null
            inkPreview = emptyList()
            editor.clearHidden()
        }
    }

    // S Pen hover: bringing the stylus close arms the pen, so annotating needs no trip to the bar
    // (Samsung S6 Lite reports hover for its pen). It only acts when coming from reading mode: once
    // you are annotating it must not fight the tool you picked (eraser, stamps...).
    var stylusNear by remember { mutableStateOf(false) }
    // Only the first approach arms the pen: after leaving edit mode, hovering again must not drag
    // the tools back over a page the user meant to read (or tap the chrome on).
    // ponytail: one auto-arm per visit to the score; the pill still enters edit mode by hand.
    var hoverArmed by remember { mutableStateOf(false) }
    LaunchedEffect(stylusNear) {
        if (!stylusNear || editing || hoverArmed) return@LaunchedEffect
        if (!canEdit) return@LaunchedEffect
        hoverArmed = true
        toggleEditing()
        tool = AnnotationTool.PEN
        openPanel = null
    }
    val stylusHoverMod = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.type == PointerType.Stylus }) continue
                when (event.type) {
                    // Exit is the reset: while the pen stays near, `stylusNear` does not change, so
                    // the effect cannot fire twice (leaving edit mode with the pen in hand is safe)
                    PointerEventType.Enter, PointerEventType.Move ->
                        if (event.changes.any { !it.pressed }) stylusNear = true
                    PointerEventType.Exit -> stylusNear = false
                }
            }
        }
    }

    // The frame each annotation snapshot is taken against. The gesture's pointerInput(Unit) never
    // restarts, so the callbacks captured there must read through a state holder: a plain val
    // (pageStamps, pageFit) would stay the first composition's forever, and every undo would put
    // the page of the screen's first frame back — the "undo undoes everything at once" bug.
    val currentPageEdit by rememberUpdatedState(PageEdit(pageStamps, pageStrokes))

    val applyNext: () -> Unit = {
        finishTyping()
        when (val turn = turnNext(branch, onPastEnd != null)) {
            is Turn.ToPage -> {
                playbackVm.pageTurnedByHand()
                currentPage = turn.flow.page
                halfTurned = turn.flow.halfTurned
            }
            Turn.ToOtherScore -> onPastEnd?.invoke(navVisible)
            Turn.Stay -> Unit
        }
    }
    val applyPrev: () -> Unit = {
        finishTyping()
        when (val turn = turnPrev(branch, onBeforeStart != null)) {
            is Turn.ToPage -> {
                playbackVm.pageTurnedByHand()
                currentPage = turn.flow.page
                halfTurned = turn.flow.halfTurned
            }
            Turn.ToOtherScore -> onBeforeStart?.invoke(navVisible)
            Turn.Stay -> Unit
        }
    }
    // The gesture's pointerInput(Unit) never restarts, so it must read the live turns: a captured
    // lambda keeps the `branch` it closed over and stops advancing past the first page.
    val currentApplyNext by rememberUpdatedState(applyNext)
    val currentApplyPrev by rememberUpdatedState(applyPrev)

    // The frame the zoom is clamped against: a single page scales its own content (centred on
    // pageFit), the reading modes scale the whole viewport. It reads currentPageFit/currentSpread
    // and viewport live, so the gesture and the double-tap animation get the frame on screen now.
    fun zoomFrameNow(): ZoomFrame {
        val vps = Size(viewport.width.toFloat(), viewport.height.toFloat())
        val fit = currentPageFit
        return if (currentSpread) {
            ZoomFrame(vps, vps, Offset.Zero)
        } else {
            ZoomFrame(Size(fit.width, fit.height), vps, Offset(fit.left, fit.top))
        }
    }

    // Animated zoom to a point (double tap): the scale is interpolated geometrically and anchored
    // on every frame with the pinch formula, so it lands exactly where it should, and you see it
    // travel. Going back to fit interpolates straight to (0,0). Cancels the previous one if any.
    fun animateZoomTo(targetScale: Float, anchor: Offset) {
        zoomAnimJob?.cancel()
        zoomAnimJob = editScope.launch {
            val frame = zoomFrameNow()
            val s0 = zoom.transform.scale
            val o0 = zoom.transform.offset
            if (s0 == targetScale) return@launch
            Animatable(0f).animateTo(1f, tween(DOUBLE_TAP_ANIM_MS, easing = FastOutSlowInEasing)) {
                val e = value
                zoom.set(
                    frame,
                    if (targetScale <= 1f) {
                        ZoomTransform(s0 + (1f - s0) * e, o0 * (1f - e))
                    } else {
                        val k = (targetScale / s0).pow(e)
                        ZoomTransform(
                            (s0 * k).coerceIn(1f, DOUBLE_TAP_ZOOM),
                            ZoomMath.anchoredOffset(o0, k, anchor, frame.base),
                        )
                    },
                )
            }
        }
    }

    // What the annotation bar asks for (issue #56): one tool in hand at a time. Choosing a tool
    // clears the selection (the arrow is what selects), a tool is never turned off, and tapping the
    // one in hand opens and closes its options. The choice is remembered for the next session.
    val chooseTool: (AnnotationTool) -> Unit = { picked ->
        if (picked == tool) {
            openPanel = if (picked.hasPanel && nextToolTap(true, openPanel == picked)) picked else null
        } else {
            tool = picked
            openPanel = null
            editor.clearSelection()
            inkPreview = emptyList()
            Settings.setAnnotationTool(context, picked)
        }
    }

    // ✕ leaves without what was drawn in this session, and asks first when there is something to
    // lose; ✓ is the plain way out and keeps everything (issue #50)
    val onCancelEditing: () -> Unit = {
        editor.dropTyping()
        if (editor.hasSessionChanges) confirmDiscard = true else toggleEditing()
    }

    val onUndo: () -> Unit = { editor.undo(currentPage, PageEdit(pageStamps, pageStrokes)) }

    val onRedo: () -> Unit = { editor.redo(currentPage, PageEdit(pageStamps, pageStrokes)) }

    val onDeleteSelection: () -> Unit = {
        editor.deleteSelection(currentPage, selectedStamp, selectedStrokes, PageEdit(pageStamps, pageStrokes))
    }

    BackHandler(onBack = onBack)
    // while a text is typed, back finishes it (the keyboard went with the first back)
    BackHandler(enabled = editor.typing != null) { finishTyping() }

    // keyboard / pedal page turns: the viewer root holds the focus, otherwise key events reach
    // nothing and only the on-screen buttons turn pages. Preview so a focused button or slider
    // does not swallow the key first.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocus.requestFocus() }
    // a text is typed with the text tool, while annotating: taking another tool or leaving
    // finishes it. Once no text has the keys, they go back to the viewer (pedals, page turns).
    LaunchedEffect(editing, tool) {
        if (editor.typing != null && (!editing || tool != AnnotationTool.TEXT)) finishTyping()
    }
    LaunchedEffect(editor.typing == null) { if (editor.typing == null) keyFocus.requestFocus() }
    // where the page's box starts in the window, and how far down the keyboard leaves it visible
    var viewportTop by remember { mutableFloatStateOf(0f) }
    // and where the viewer's own box starts (the annotation bar's place is counted from there)
    var screenTop by remember { mutableFloatStateOf(0f) }
    val visibleBottom by rememberVisibleBottom()

    // the page reached and when, on every turn and when the score closes: back to it if the
    // score opens again a short while later (Settings.lastPage)
    LaunchedEffect(currentPage) {
        Settings.setLastPage(context, pdfUri.toString(), currentPage)
    }
    DisposableEffect(pdfUri) {
        onDispose { Settings.setLastPage(context, pdfUri.toString(), currentPage) }
    }

    // the DB no longer has what we hid: clear the optimistic delete (and the drag preview)
    LaunchedEffect(stamps, strokes) { editor.settle(stamps, strokes) }

    // a fresh page (or leaving edit mode) must not keep the ink the DB has not handed back
    LaunchedEffect(currentPage, editing) { editor.pendingInk = emptyList() }

    // MuPDF (prebuilt AAR, AGPL-3.0: see LICENSE). Opens and works out sizes in the background:
    // opening parses the PDF index and must not touch the main thread.
    LaunchedEffect(pdfUri) {
        val doc = paper.open(context, format) ?: return@LaunchedEffect
        if (paper.pageCount > 0) currentPage = currentPage.coerceIn(0, paper.pageCount - 1)
        if (doc is MusicXmlPages) menu.onScoreRead(doc.parts)
        pageSource = doc
    }

    // MusicXML: break the music into pages of this screen's size, and again when the screen turns
    // or two-up starts (two pages of half the width). The reading position is kept as a fraction
    // of the score: the page breaks move, not the music.
    val densityDpi = configuration.densityDpi
    val twoUpSlots = isLandscape && twoUp && !halfEnabled
    // A MusicXML page fills the viewport, while the system bars, and the title bar and page
    // selector when the centre tap shows them, are drawn over it (a PDF has a paper margin there).
    // The music keeps their room free: the sidebars on both sides, the top and bottom chrome as
    // page margins. Fixed, so showing the bars never lays the music out again; the keyboard is not
    // one of them (safeDrawing counts it): a text typed on the page laid the score out again as the
    // keyboard came up, and the text being typed went with the old pages
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val barsX = max(bars.getLeft(density, LayoutDirection.Ltr), bars.getRight(density, LayoutDirection.Ltr))
    val chromeTop = bars.getTop(density) + with(density) { TITLE_BAR_ROOM.roundToPx() }
    val chromeBottom = bars.getBottom(density) +
        with(density) { (SELECTOR_ROOM - SELECTOR_AIR + PLAYBACK_BAND_ROOM).roundToPx() }
    LaunchedEffect(pageSource, viewport, twoUpSlots, scoreLayout, parts, barsX, chromeTop, chromeBottom, editing) {
        val pages = pageSource as? MusicXmlPages ?: return@LaunchedEffect
        if (viewport == IntSize.Zero) return@LaunchedEffect
        // the system bars settle over the first frames, restarting this effect each time: a
        // layout for sizes about to change took seconds on a slow tablet, and could not be cut
        // short, so the right one waited behind it
        delay(LAYOUT_SETTLE_MS.milliseconds)
        val usableW = (viewport.width - 2 * barsX).coerceAtLeast(1)
        val screen = MusicXmlScreen(
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            pageWidth = if (twoUpSlots) usableW / 2 else usableW,
            baseScale = LayoutSpec.defaultScale(viewport.width, viewport.height, densityDpi),
            // a very short window keeps at least half its height for the music
            marginTop = chromeTop.coerceAtMost(viewport.height / 4),
            marginBottom = chromeBottom.coerceAtMost(viewport.height / 4),
            twoUp = twoUpSlots,
        )
        // annotating spaces the lines of music out: room to write, and a clear line between them
        val spec = screen.spec(scoreLayout, parts, annotating = editing)
        // the other scores get laid out and drawn ahead for this screen too
        withContext(Dispatchers.IO) { MusicXmlPages.rememberScreen(context, screen) }
        try {
            val changed = paper.layoutPages(pages, spec)
            if (!changed) return@LaunchedEffect
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "MusicXML layout failed: ${e::class.simpleName}: ${e.message}", e)
            paper.error = R.string.err_open_file
            return@LaunchedEffect
        }
        val after = pages.pageCount
        if (after > 0) {
            currentPage = (if (pageCount > 0) currentPage * after / pageCount else currentPage)
                .coerceIn(0, after - 1)
        }
        halfTurned = false
        paper.pagesRelaidOut(after)
        layoutVersion++
    }

    // Playback plays what is loaded: a new transposition, new parts or new lyrics reload the score
    // with new note ids, and stop the music. A new size or a turned screen keeps it playing.
    val shownIds = scoreLayout.partsShown(parts)
    LaunchedEffect(pageSource, scoreLayout.semitones, shownIds, scoreLayout.lyrics, scoreLayout.keepHiddenLyrics) {
        playbackVm.attach(
            pageSource as? MusicXmlPages,
            shownIds,
            listOf(scoreLayout.semitones, shownIds, scoreLayout.lyrics, scoreLayout.keepHiddenLyrics),
        )
    }

    // a page's height in its SVG units, from the page box (null before the first page is read)
    val pageHeight = pageBox?.let { b -> { geo: PageGeometry -> geo.width * b.height / b.width } }

    // Where a turned half page cuts: between two systems, in the blank bands of the pages drawn
    // (HalfSplit), worked out by the paper and read once per picture
    fun halfSplit(): HalfSplit = paper.halfSplit(currentPage, layoutVersion)

    // Whether the notehead [id] is above [fraction] of its page's height (a hidden note counts as at the top).
    fun above(geo: PageGeometry, id: String, fraction: Float) =
        pageHeight == null || (geo.head(id)?.y ?: 0f) < pageHeight(geo) * fraction

    // The notes on screen, for ▶ (it starts where the eyes are) and for the follow below. A turned
    // half page shows the top of the next page over the bottom of this one.
    fun notesOnScreen(notes: Collection<String>? = null): Set<String> {
        val pages = pageSource as? MusicXmlPages ?: return emptySet()
        fun onPage(index: Int, keep: (PageGeometry, String) -> Boolean = { _, _ -> true }): List<String> {
            val geo = pages.geometry(index) ?: return emptyList()
            return (notes ?: geo.noteIds).filter { it in geo.noteIds && keep(geo, it) }
        }
        return when {
            branch.showTwoUp -> onPage(currentPage) + onPage(currentPage + 1)
            branch.showHalf && halfTurned -> {
                val split = halfSplit()
                onPage(currentPage + 1) { g, id -> above(g, id, split.upper) } +
                    onPage(currentPage) { g, id -> !above(g, id, split.lower) }
            }
            else -> onPage(currentPage)
        }.toSet()
    }

    val toFirstPage by playbackVm.toFirstPage.collectAsState()
    LaunchedEffect(toFirstPage) {
        if (toFirstPage == 0) return@LaunchedEffect
        currentPage = 0
        halfTurned = false
    }

    // Follow the music of a MusicXML score: [notes] are heard, [ahead] are where the reader looks
    // (the bars to turn ahead). The pages turn as PageFlow.follow says. A page turned by hand
    // during playback lets the reader look ahead: the follow waits until the music is on screen
    // again (as in the MusicXML prototype).
    suspend fun followMusic(notes: List<String>, ahead: List<String>, byHand: Boolean = true) {
        val pages = pageSource as? MusicXmlPages ?: return
        if (notes.isEmpty()) return
        val onScreen = notesOnScreen(notes).isNotEmpty()
        if (byHand && onScreen) playbackVm.musicOnScreen()
        if (byHand && playbackVm.followPaused) return
        val target = pages.pageOf(notes.first())
        if (target < 0) return
        val looked = ahead.ifEmpty { notes }
        val aheadPage = pages.pageOf(looked.first()).takeIf { it >= 0 } ?: target
        // below the top of the next page that a turned half page shows
        val upper = halfSplit().upper
        val aheadInLowerPart = pages.geometry(aheadPage)?.let { geo ->
            val heads = looked.filter { geo.head(it) != null }
            heads.isNotEmpty() && heads.none { above(geo, it, upper) }
        } ?: false
        val f = branch.follow(target, aheadInLowerPart, onScreen, aheadPage)
        currentPage = f.page
        halfTurned = f.halfTurned
    }

    LaunchedEffect(cursorNotes) { followMusic(cursorNotes, aheadNotes) }

    // a MusicXML score: the metronome keeps the tempo of playback and the score's time signature
    val scoreInfo = (pageSource as? MusicXmlPages)?.info
    LaunchedEffect(tempoFactor, scoreInfo) {
        if (scoreInfo != null) metronomeVm.followScore(bpmOf(tempoFactor, scoreInfo.tempo ?: DEFAULT_BPM), Meter.parse(scoreInfo.time))
    }

    // ▶ / ⏸ of the metronome: it counts the bars from the page on screen, the music stops
    fun startStopMetronome() {
        if (metronomeOn) {
            metronomeVm.stop()
            return
        }
        playbackVm.stop()
        if (format.isMusicXml) {
            editScope.launch {
                val music = playbackVm.music()
                metronomeMusic = music
                val onScreen = notesOnScreen()
                val from = music?.onsets?.firstOrNull { o -> o.notes.any { it in onScreen } }?.quarters ?: 0.0
                metronomeVm.start(music?.barAt(from) ?: 0)
            }
        } else {
            metronomeVm.start(BarPages(metronome.barCounts, pageCount).startBar(currentPage, branch.showHalf && halfTurned))
        }
    }

    // What a key of the keyboard or the pedal does (Settings › Keyboard and pedals): the same as the
    // button of the bar or the band. An action that has no meaning here (playback on a PDF score)
    // does nothing.
    fun runKeyAction(action: KeyAction) {
        val writtenBpm = (pageSource as? MusicXmlPages)?.info?.tempo ?: DEFAULT_BPM
        when (action) {
            KeyAction.PREV_PAGE -> applyPrev()
            KeyAction.NEXT_PAGE -> applyNext()
            KeyAction.FIRST_PAGE -> {
                currentPage = 0
                halfTurned = false
            }
            KeyAction.LAST_PAGE -> {
                currentPage = (pageCount - 1).coerceAtLeast(0)
                halfTurned = false
            }
            KeyAction.PLAY_PAUSE -> if (format.isMusicXml) {
                metronomeVm.stop()
                playbackVm.playPause(notesOnScreen())
            }
            KeyAction.RESTART -> if (format.isMusicXml) playbackVm.restart()
            KeyAction.LOOP -> if (format.isMusicXml) playbackVm.toggleLoop()
            KeyAction.METRONOME -> startStopMetronome()
            KeyAction.TAP_TEMPO -> metronomeVm.tap()?.let { bpm ->
                if (format.isMusicXml) playbackVm.setTempo(factorOf(bpm, writtenBpm))
            }
            KeyAction.SHOW_BAR -> if (!editing) navVisible = !navVisible
            // in, or out keeping what was drawn, as ✓
            KeyAction.ANNOTATE -> if (canEdit) toggleEditing()
        }
    }

    // The pages turn with the metronome: a MusicXML score knows its bars, a PDF score has the
    // bars of each page counted by the user (BarPages). Only forward: a page turned back by hand
    // stays until the metronome gets past it. pageCount: a score laid out again changes the bars.
    LaunchedEffect(metronomeTick, pageCount) {
        val bar = metronomeVm.barHeard(metronomeTick) ?: return@LaunchedEffect
        if (!metronome.autoTurn) return@LaunchedEffect
        val ahead = bar + metronome.turnAhead
        if (format.isMusicXml) {
            val music = metronomeMusic ?: return@LaunchedEffect
            val notes = music.notesOfBar(bar) ?: return@LaunchedEffect
            followMusic(notes, music.notesOfBar(ahead) ?: notes, byHand = false)
            return@LaunchedEffect
        }
        val pages = BarPages(metronome.barCounts, pageCount)
        if (!pages.known) return@LaunchedEffect
        val target = pages.pageOf(bar)
        val aheadPage = pages.pageOf(ahead)
        val onScreen = target == currentPage || (spread && target == currentPage + 1)
        val f = branch.follow(target, pages.inLowerHalf(ahead), onScreen, aheadPage)
        if (f.page > currentPage || (f.page == currentPage && f.halfTurned && !halfTurned)) {
            currentPage = f.page
            halfTurned = f.halfTurned
        }
    }

    // The paper's draws: the zoom step, the page on screen, the neighbours drawn ahead and the cut
    // of a half page (PageRender.kt)
    PageRenderEffects(
        paper = paper,
        doc = pageSource,
        page = currentPage,
        viewport = viewport,
        layoutVersion = layoutVersion,
        showTwoUp = branch.showTwoUp,
        showHalf = branch.showHalf,
        halfTurned = halfTurned,
        spread = spread,
        zoom = zoom,
    )
    // the document is freed off the main thread, after whatever draw is in flight
    DisposableEffect(pageSource) {
        val current = pageSource
        onDispose {
            current?.let { paper.close(it, (context.applicationContext as FOSScoreApp).applicationScope) }
        }
    }

    // Room the top chrome takes from the page while annotating, and nothing at all while reading:
    // the page is laid out next to the bars instead of under them (#19). While annotating there is
    // one bar only (the annotation bar, which carries the status bar inset in its own height).
    val chromeInset = with(LocalDensity.current) {
        (if (editing && canEdit) annBarPx else 0).toDp()
    }
    // With the metronome band, the bars stay on screen: a PDF page fits between the bar at the top
    // and the bottom chrome, which hid its first and last staves. (A MusicXML page is laid out
    // with that room already.)
    val bandShown = metronomeShown && !format.isMusicXml && !editing
    val bandInset = with(LocalDensity.current) { (if (bandShown) bottomChromePx else 0).toDp() }
    val bandTopInset = with(LocalDensity.current) { (if (bandShown) chromeTop else 0).toDp() }
    // a different page area is a different clamp: entering edit mode must not leave a zoomed page
    // parked outside its bounds
    LaunchedEffect(viewport) { zoom.set(zoomFrameNow(), zoom.transform) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenTop = it.positionInWindow().y }
            // the matte around the page: the theme background, and black when "Neg" is on (the page
            // turns black and the bands were left white)
            .background(if (invert) Color.Black else MaterialTheme.colorScheme.background)
            .then(stylusHoverMod)
            .onPreviewKeyEvent { e ->
                // read at each key: the preferences may have changed them since the score opened
                val action = Settings.keyShortcuts(context).actionFor(e.nativeKeyEvent.keyCode, e.isShiftPressed, typing = editor.typing != null)
                    ?: return@onPreviewKeyEvent false
                // a held key repeats: one action per press, not a burst
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    runKeyAction(action)
                }
                true
            }
            .focusRequester(keyFocus)
            .focusable()
    ) {
        // A text typed low on the page would be under the keyboard: the page goes up while it is
        // typed, just enough for the text and a line more to show above the keyboard.
        val typingNow = editor.typing
        // a bar parked at the bottom would be under the keyboard: it goes up above it while a
        // text is typed, and then the text has to show above the bar
        val keyboardTop = if (typingNow == null || visibleBottom == Int.MAX_VALUE) null else visibleBottom - screenTop
        val barY = annotationBarDragOverKeyboard(barDragY, annBarPx, statusTopPx, keyboardTop)
        val textRoomBottom = if (barY < barDragY) screenTop + annotationBarOffsetPx(barY, statusTopPx) else visibleBottom.toFloat()
        val lift = if (typingNow == null || hitFit.height <= 0f) 0f else {
            val k = zoom.transform.scale
            val (_, h) = measureText(typingNow.text.ifEmpty { " " }, typingNow.size)
            val line = textFontPx(typingNow.size, hitFit) * 1.3f
            val textBottom = viewportTop + pageFit.top + zoom.transform.offset.y +
                (typingNow.top * hitFit.height + h + line) * k
            (textBottom - textRoomBottom).coerceAtLeast(0f)
        }
        val pageLift by animateFloatAsState(lift, label = "pageLift")
        // The page lives in its own box: in edit mode it starts below the chrome instead of under
        // it (#19), and `viewport` is that box, so fit, zoom, tap zones and the loupe all follow.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = chromeInset + bandTopInset, bottom = bandInset)
                .onSizeChanged { viewport = it }
                .onGloballyPositioned { viewportTop = it.positionInWindow().y }
                // the touches follow the page up: the layer moves them with it
                .graphicsLayer { translationY = -pageLift }
        ) {
            if (error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(error), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                }
            } else if (paper.bitmaps.isNotEmpty()) {
                // Reading modes (two-up, half page): the layer scales the whole viewport as before
                // (they are not meant for zooming into detail: offscreen drawing sweeps away the
                // stretch at no real cost in sharpness). Single page (else branch): NO scaling layer —
                // the bitmap is drawn at the current zoom and shown 1:1; the overlay is redrawn as
                // vector (see plan 2026-09-30-zoom-render-1to1).
                val spreadMod = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // read in the layout phase, not in composition: no recomposition per frame
                        val t = zoom.transform
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = t.scale
                        scaleY = t.scale
                        translationX = t.offset.x
                        translationY = t.offset.y
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                val gestureMod = Modifier.pageGestures(
                    editing = editing,
                    canEdit = canEdit,
                    page = currentPage,
                    sheetId = sheetId,
                    pageFit = pageFit,
                    stamps = pageStamps,
                    strokes = pageStrokes,
                    inkStrokes = inkStrokes,
                    selectionRect = selectionRect,
                    selectedStamp = selectedStamp,
                    selectedStrokes = selectedStrokes,
                    tool = tool,
                    penWidthPx = penWidthPx,
                    eraserPx = eraserPx,
                    // each tool carries its own tint (#56); the shapes draw with theirs
                    activeColor = if (tool == AnnotationTool.SHAPE) shapeColor else penColor,
                    shapeKind = shapeType,
                    shapeWidthPx = shapeWidthPx,
                    shapeFilled = shapeFilled,
                    loupeAt = loupeAt,
                    zoom = zoom,
                    zoomFrame = zoomFrameNow(),
                    editor = editor,
                    onCancelZoom = { zoomAnimJob?.cancel() },
                    onDoubleTapZoom = { target, anchor -> animateZoomTo(target, anchor) },
                    onTurn = { turn ->
                        if (turn == PageTurn.NEXT) currentApplyNext() else currentApplyPrev()
                    },
                    onTapZone = { p ->
                        val width = viewport.width
                        val corner = with(density) { MENU_CORNER.toPx() } +
                            bars.getLeft(density, LayoutDirection.Ltr)
                        when {
                            p.x < corner && p.y < chromeTop -> menu.show(ViewerSurface.MENU)
                            width > 0 && p.x > width * 2f / 3f -> currentApplyNext()
                            width > 0 && p.x < width / 3f -> currentApplyPrev()
                            else -> navVisible = !navVisible
                        }
                    },
                    onPageTouched = { pageTouched = it },
                    onLoupeAnchor = { loupeAnchor = it },
                    onLoupe = { loupeAt = it },
                    onInkPreview = { inkPreview = it },
                    // a tap on the matte closes the panel and drops the selection; it does not
                    // change the tool (issue #56)
                    onToolsDown = {
                        inkPreview = emptyList()
                        openPanel = null
                        editor.clearSelection()
                    },
                    onPlaceStamp = { pos ->
                        if (tool == AnnotationTool.STAMP && sheetId != null) {
                            // the stamp stays in hand: the next tap places another one
                            // the whole square lands on the page, never half of it off the edge
                            // (placePx and tool are State, so a stale capture still reads fresh;
                            // the page state and the fit go through the holders declared above)
                            val fit = RectPx(0f, 0f, currentPageFit.width, currentPageFit.height)
                            val size = stampSizePxToFrac(placePx, fit)
                            val centre = stampCenterInside(pos.x, pos.y, size, fit)
                            editor.placeStamp(
                                currentPage,
                                Stamp(
                                    sheetId = sheetId,
                                    page = currentPage,
                                    symbol = stampSymbol.name,
                                    x = centre.x,
                                    y = centre.y,
                                    size = size,
                                    color = stampColor
                                ),
                                currentPageEdit,  // fresh: the delegates above the gesture
                            )
                        }
                    },
                    // TEXT tool (#35): the measured rects (draw = hit = selection) go straight
                    // from the shared map, so the gesture never diverges from the draw.
                    textRects = textRects,
                    // the text tool types on the page (#35): a tap on a text types in it, a tap
                    // elsewhere finishes the text being typed, or starts a new one there
                    onTextBoxTap = { norm, stamp, _ ->
                        if (tool == AnnotationTool.TEXT && sheetId != null && hitFit.width > 0f) {
                            val typing = editor.typing
                            when {
                                stamp != null && stamp.id != typing?.origin?.id -> {
                                    finishTyping()
                                    val box = textRects[stamp.id] ?: stampRect(stamp, hitFit)
                                    editor.startTyping(
                                        TextDraft(
                                            page = currentPage,
                                            origin = stamp,
                                            text = stamp.text.orEmpty(),
                                            size = stamp.size,
                                            color = stamp.color,
                                            left = box.left / hitFit.width,
                                            top = box.top / hitFit.height,
                                        )
                                    )
                                }
                                typing != null -> finishTyping()
                                else -> {
                                    val size = textSizePxToFrac(textPx, hitFit)
                                    val (_, lineH) = measureText(" ", size)
                                    val corner = newTextCorner(norm, lineH, hitFit)
                                    editor.startTyping(
                                        TextDraft(
                                            page = currentPage,
                                            origin = null,
                                            text = "",
                                            size = size,
                                            color = textColor,
                                            left = corner.x,
                                            top = corner.y,
                                        )
                                    )
                                }
                            }
                        }
                    },
                )

                PageCanvas(
                    bitmaps = paper.bitmaps,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    lastShown = paper.lastShown,
                    branch = branch,
                    halfTurned = halfTurned,
                    halfSplit = { halfSplit() },
                    pageHeight = pageHeight,
                    pageFit = pageFit,
                    hitFit = hitFit,
                    scale = { zoom.transform.scale },
                    offsetX = { zoom.transform.offset.x },
                    offsetY = { zoom.transform.offset.y },
                    invert = invert,
                    pageSource = pageSource,
                    playback = playbackVm.currentPlayback,
                    playQuarters = { playQuarters },
                    halfCut = paper.halfCut,
                    layoutVersion = layoutVersion,
                    canEdit = canEdit,
                    inkDraws = inkDraws,
                    lineBoundaries = if (editing) lineBoundaries else emptyList(),
                    dragStrokes = editor.dragStrokes,
                    inkPreview = inkPreview,
                    // the live stroke: the eraser's red trail, the pen's ink or the shape being aimed
                    previewColor = when (tool) {
                        AnnotationTool.ERASER -> Color.Red.copy(alpha = 0.4f)
                        AnnotationTool.SHAPE -> inkTint(shapeColor, invert)
                        else -> inkTint(penColor, invert)
                    },
                    previewWidthPx = when (tool) {
                        AnnotationTool.ERASER -> eraserPx * 2f
                        AnnotationTool.SHAPE -> shapeWidthPx
                        else -> penWidthPx
                    },
                    // the same rule the commit uses: fill is only for the closures. A filled
                    // line's preview would be a Fill of its 2-point path — nothing on screen
                    previewFilled = tool == AnnotationTool.SHAPE && shapeFilled && shapeType.canFill,
                    pageStamps = shownStamps,
                    dragStamp = editor.dragStamp,
                    selectionRect = selectionRect,
                    strokeSelection = strokeSelection,
                    showSelection = showSelection,
                    loupeAt = loupeAt,
                    placePx = placePx,
                    nextInkDraws = companionInkDraws,
                    nextStamps = companionStamps,
                    textRects = textRects,
                    // in the reading modes the whole viewport is scaled; a single page scales its
                    // own content, so the zoom layer is not used
                    modifier = if (spread) spreadMod.then(gestureMod) else gestureMod,
                )
                // the text being typed (#35): the keyboard's field, where the text is and as the
                // page draws it, in the same transform as the stamps (so at any zoom too)
                val draft = editor.typing
                if (draft != null && draft.page == currentPage && !spread && sheetId != null) {
                    val (w, h) = measureText(draft.text.ifEmpty { " " }, draft.size)
                    val box = textBoxRect(draftStamp(draft, sheetId, w, h, hitFit), w, h, hitFit)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = pageFit.left + zoom.transform.offset.x
                                translationY = pageFit.top + zoom.transform.offset.y
                                scaleX = zoom.transform.scale
                                scaleY = zoom.transform.scale
                            }
                    ) {
                        TypingField(
                            key = editor.typingKey,
                            initial = draft.text,
                            topLeft = Offset(box.left, box.top),
                            fontPx = textFontPx(draft.size, hitFit),
                            maxWidthPx = hitFit.width * TEXT_MAX_WRAP - 16f,
                            color = inkTint(draft.color, invert),
                            onText = { typed -> editor.retype { it.copy(text = typed) } },
                            onDone = { finishTyping() },
                        )
                    }
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        if (dimPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimPct / 100f))
            )
        }

        // the visual metronome: the border of the page flashes on the beats
        if (metronomeOn && metronome.mode != MetronomeMode.SOUND) BeatFlash(metronomeTick)

        // the loupe goes last: with a finger aiming a stamp the bars step aside, so it has the whole
        // page to read (it is drawn under them: they would cover the circle at the top).
        if ((navVisible || editing) && loupeAt == null) {

            // while annotating there is ONE bar: the annotation bar takes the top of the screen
            // and the title bar (or the icon bar) steps aside (spec §5: forScore replaces it).
            // ✕ / ✓ are its ends.
            if (!(editing && canEdit)) {
                val topBarModifier = Modifier
                    .align(Alignment.TopCenter)
                    // edge-to-edge: the pills were slipping under the status bar, and in landscape
                    // under the side navigation bar.
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
                if (chromeStyle == ChromeStyle.MENU) {
                    // ponytail: no ⌄ (score actions), no ⌕ (global search), no long-press-to-annotate yet.
                    ViewerTitleBar(
                        title = title,
                        composer = composer,
                        pageLabel = pageLabel,
                        setlistPlace = setlistPlace,
                        editing = editing,
                        onOpenMenu = { menu.show(ViewerSurface.MENU) },
                        onToggleEditing = { toggleEditing() },
                        annotatable = sheetId != null,
                        modifier = topBarModifier,
                    )
                } else {
                    // the entry whose panel shows: its icon is framed, and a second tap closes it
                    val open = when (surface) {
                        ViewerSurface.DISPLAY -> ViewerMenuItem.DISPLAY
                        ViewerSurface.LAYOUT -> ViewerMenuItem.LAYOUT
                        ViewerSurface.PLAYBACK -> ViewerMenuItem.PLAYBACK
                        ViewerSurface.METRONOME -> ViewerMenuItem.METRONOME
                        else -> null
                    }
                    ViewerIconBar(
                        title = title,
                        pageLabel = pageLabel,
                        setlistPlace = setlistPlace,
                        isMusicXml = format.isMusicXml,
                        annotatable = editEntryShown(editing, sheetId != null),
                        open = open,
                        onSelect = { item ->
                            menu.dismiss()
                            if (item != open) {
                                openMenuEntry(
                                    item, menu, metronomeVm, playbackVm, editing,
                                    onOpenLibrary, onOpenSetlist, onOpenPreferences, { toggleEditing() }, { navVisible = true },
                                )
                            }
                        },
                        onMore = { menu.show(ViewerSurface.MENU) },
                        onAnnotate = {
                            menu.dismiss()
                            toggleEditing()
                        },
                        modifier = topBarModifier,
                    )
                }
            }

            if (editing && canEdit) {
                val textPanel = textPanelValues(editor.typing, textPx, textColor, hitFit)
                    .let { it.copy(px = it.px.coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)) }
                AnnotationBar(
                    tool = tool,
                    stampSymbol = stampSymbol,
                    openPanel = openPanel,
                    invert = invert,
                    penColor = penColor,
                    stampColor = stampColor,
                    textColor = textPanel.color,
                    shapeKind = shapeType,
                    shapeColor = shapeColor,
                    shapeFilled = shapeFilled,
                    shapeWidthPx = shapeWidthPx,
                    defaultShapePx = defaultShapePx,
                    penWidthPx = penWidthPx,
                    eraserPx = eraserPx,
                    textPx = textPanel.px,
                    defaultTextPx = defaultTextPx,
                    placePx = placePx,
                    defaultStampPx = defaultStampPx,
                    pageTouched = pageTouched,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    canDelete = selectedStamp != null || selectedStrokes.isNotEmpty(),
                    onTool = chooseTool,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDelete = onDeleteSelection,
                    onCancel = onCancelEditing,
                    onDone = { toggleEditing() },
                    // dragging the grip moves the bar over the page; the page keeps its layout
                    onDragBar = { dy ->
                        barDragY = (barDragY + dy).coerceIn(0f, annotationBarLowest(viewport.height, navigationBottomPx))
                    },
                    onDragState = { dragging -> barDragging = dragging },
                    onStampSymbol = { stampSymbol = it },
                    onStampSize = { placePx = it },
                    onSaveDefault = {
                        Settings.setStampPx(context, placePx)
                        defaultStampPx = placePx
                    },
                    onPenWidth = { penWidthPx = it },
                    onEraserPx = { eraserPx = it },
                    onPenColor = { penColor = it },
                    // a text being edited takes the size and the colour; the next new one keeps its own
                    onTextPx = { px ->
                        if (editor.typing?.origin == null) textPx = px
                        editor.retype { it.copy(size = textSizePxToFrac(px, hitFit)) }
                    },
                    onSaveTextDefault = {
                        Settings.setTextPx(context, textPanel.px)
                        defaultTextPx = textPanel.px
                        textPx = textPanel.px
                    },
                    onTextColor = { argb ->
                        if (editor.typing?.origin == null) textColor = argb
                        editor.retype { it.copy(color = argb) }
                    },
                    onShapeKind = { shapeType = it },
                    onShapeColor = { shapeColor = it },
                    onShapeFilled = { shapeFilled = it },
                    onShapeWidth = { shapeWidthPx = it },
                    onSaveShapeDefault = {
                        Settings.setShapePx(context, shapeWidthPx)
                        defaultShapePx = shapeWidthPx
                    },
                    onStampColor = { argb ->
                        stampColor = argb
                        // the tint also recolours the selected stamp: the arrow changes a
                        // stamp's colour, it does not resize it
                        val current = selectedStamp
                        if (current != null) {
                            editor.recolorStamp(
                                currentPage, current, argb, PageEdit(pageStamps, pageStrokes)
                            )
                        }
                    },
                    onHeight = { annBarPx = it },
                    atTop = barY == 0f,
                    panelPlace = toolPanelPlace(barY, annBarPx, viewport.height + annBarPx, statusTopPx, navigationBottomPx),
                    // the top of the screen: while annotating this bar IS the title bar, and the
                    // grip moves it down out of the way, where it turns see-through so the page
                    // underneath stays readable
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, annotationBarOffsetPx(barY, statusTopPx)) }
                        .alpha(if (barDragging) 0.75f else 1f),
                )

                // The page tag (spec §5: the bar stays lean). It is a live region, so TalkBack
                // announces the new page as a status message (WCAG 4.1.3) and the tag is never
                // the only way to know where you are. Empty and transparent the rest of the time:
                // it changes in place instead of appearing, which is what screen readers hear.
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .alpha(if (pageFlash.isEmpty()) 0f else 1f),
                ) {
                    Text(
                        pageFlash,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            if (!editing) {
                BottomChrome(
                    metronomeVm = metronomeVm,
                    playbackVm = playbackVm,
                    metronome = metronome,
                    metronomeOn = metronomeOn,
                    metronomeTick = metronomeTick,
                    metronomeShown = metronomeShown,
                    pageSource = pageSource,
                    pageCount = pageCount,
                    currentPage = currentPage,
                    format = format,
                    playState = playState,
                    loop = loop,
                    tempoFactor = tempoFactor,
                    playbackFailed = playbackFailed,
                    onStartStop = { startStopMetronome() },
                    onJump = {
                        playbackVm.pageTurnedByHand()
                        currentPage = it
                    },
                    onShowSurface = { menu.show(it) },
                    onHeight = { bottomChromePx = it },
                    notesOnScreen = { notesOnScreen() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // the menu and its panels, over everything: one at a time (ViewerMenuViewModel), the panels
        // under the bar at the top while it shows
        val panelTop = with(density) { if (navVisible) (bars.getTop(density) + TITLE_BAR_ROOM.roundToPx()).toDp() else 0.dp }
        ViewerOverlays(
            surface = surface,
            menu = menu,
            format = format,
            onOpenLibrary = onOpenLibrary,
            onOpenPreferences = onOpenPreferences,
            onOpenSetlist = onOpenSetlist,
            editing = editing,
            onToggleEditing = { toggleEditing() },
            onShowChrome = { navVisible = true },
            metronomeVm = metronomeVm,
            playbackVm = playbackVm,
            metronome = metronome,
            pageSource = pageSource,
            pageCount = pageCount,
            currentPage = currentPage,
            onJumpPage = {
                currentPage = it.coerceIn(0, pageCount - 1)
                halfTurned = false
            },
            isLandscape = isLandscape,
            meterFromScore = meterFromScore,
            parts = parts,
            heardPicked = heardPicked,
            shownIds = shownIds,
            scoreLayout = scoreLayout,
            themeMode = themeMode,
            onToggleTheme = onToggleTheme,
            invert = invert,
            onSetInvert = viewer::setInvert,
            keepScreenOn = keepScreenOn,
            onSetKeepScreenOn = viewer::setKeepScreenOn,
            dimPct = dimPct,
            onSetDimPct = viewer::setDimPct,
            onCommitDim = viewer::commitDimPct,
            twoUp = twoUp,
            onSetTwoUp = viewer::setTwoUp,
            halfEnabled = halfEnabled,
            onSetHalf = {
                viewer.setHalfPage(it)
                halfTurned = false
            },
            tempoFactor = tempoFactor,
            panelTop = panelTop,
            chromeStyle = chromeStyle,
        )

        // ✕ with something drawn: say what would be lost before losing it (issue #50)
        if (confirmDiscard) {
            DiscardAnnotationDialog(
                onKeep = { confirmDiscard = false },
                onDiscard = {
                    confirmDiscard = false
                    editor.cancelSession()
                    toggleEditing()
                },
            )
        }
    }
}
