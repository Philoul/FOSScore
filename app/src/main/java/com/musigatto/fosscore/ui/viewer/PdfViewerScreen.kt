package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.pdf.MuPdfDoc
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The render (memory) ceiling now lives in MuPdfDoc.MAX_RENDER_PIXELS: MuPDF rasterizes at
// whatever resolution we ask for, so zoom looks sharp instead of "slightly soft".
private const val LOG_TAG = "FOSScore-PDF"

// TEMPORARY: short format for the zoom diagnostic log on the S6 Lite (removed once the bug is
// closed). No Locale so it does not change with the device language.
private fun f1(v: Float): String = if (v.isFinite()) ((v * 10).roundToInt() / 10f).toString() else "?"

// Touch noise with fingers still: ±1 px over ~400 px between fingers ≈ 0.25 %. With a 0.4 % dead
// zone the zoom ignores the noise (otherwise the content "breathes" and seems to tremble).
private const val ZOOM_NOISE_RATIO = 0.004f

// Zoom that double tap jumps to (2.5x: readable without losing the page context).
private const val DOUBLE_TAP_ZOOM = 2.5f

// Zoom cap. The reference viewers (AndroidPdfViewer, vfr/Viewer, GrapheneOS) do NOT scale the
// page with a transform: they re-render it at the current zoom, so the GPU never rescales the
// texture. Our layer does scale it, which is why it must be capped: with MAX_RENDER_PIXELS = 4M
// and a page that fits in ~1.6M px, the most MuPDF can deliver without rescaling is ~2.5x.
// Beyond that the GPU upscales with nearest neighbor = "cooking". Only raise it if you also
// raise MuPdfDoc's pixel budget.
private const val MAX_ZOOM = 2.5f

// Re-render during the pinch: minimum between renders (150 ms) and settle wait before the final
// render (250 ms with no zoom change). The reference viewers re-render during the gesture
// (AndroidPdfViewer doRenderDuringScale, GrapheneOS onRenderPage(2)); throttled, the bitmap
// follows the zoom and the GPU never rescales the texture.
private const val RENDER_DURING_ZOOM_MS = 150L
private const val RENDER_SETTLE_MS = 250L

// state of the "drag a handle" gesture: which object is scaled from which corner
private sealed interface Resizing {
    val startPos: Offset

    data class OfStamp(
        val stamp: Stamp,
        override val startPos: Offset
    ) : Resizing

    data class OfStroke(
        val stroke: Stroke,
        val ptsPx: List<Offset>,
        val center: Offset,   // box center: scaling grows outward from here
        override val startPos: Offset
    ) : Resizing
}

// inverts page colors (white bg -> black, ink -> white) for OLED dark mode; applied as a
// draw-time colorFilter so the render cache is untouched.
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)
private val INVERT_FILTER = ColorFilter.colorMatrix(ColorMatrix(INVERT_MATRIX))

private fun centroid(changes: List<PointerInputChange>): Offset =
    changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()

@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    sheetHash: String? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var mupdfDoc by remember { mutableStateOf<MuPdfDoc?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(Settings.lastPage(context, pdfUri.toString()))
    }
    var twoUp by rememberSaveable { mutableStateOf(isLandscape) }
    var halfEnabled by rememberSaveable { mutableStateOf(false) }
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    var navVisible by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) } // string resource
    // SnapshotStateMap: when a bitmap changes, only whoever reads THAT key is redrawn. With a plain
    // Map, changing the bitmap invalidated the Image + the ink Canvas + all the stamps
    // (felt as a jerk when sharpness changed mid-pinch).
    // double tap: fit <-> zoom. The time and position of the previous tap are stored.
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // velocity left when the finger lifts (pan inertia); cleared on touch
    var flingVel by remember { mutableStateOf(Offset.Zero) }
    val doubleTapSlopPx = with(LocalDensity.current) { 40.dp.toPx() }
    val bitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    // bitmap render step: the bitmap is rasterized at fit × renderScale (1:1 on screen,
    // zero GPU rescaling). Fed by the gesture via ZoomMath.renderBucket.
    var renderScale by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dimPct by remember { mutableStateOf(Settings.dimPct(context)) }
    var showDim by rememberSaveable { mutableStateOf(false) }
    var invert by remember { mutableStateOf(Settings.invert(context)) }

    val stampsFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).stampRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())
    val stampRepo = (context.applicationContext as FOSScoreApp).stampRepository

    val strokesFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).strokeRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())
    val strokeRepo = (context.applicationContext as FOSScoreApp).strokeRepository

    var editing by rememberSaveable { mutableStateOf(false) }
    var activeSymbol by rememberSaveable { mutableStateOf<StampSymbol?>(null) }
    // selected stamp (for the box with handles) or selected stroke
    var selectedStrokeId by remember { mutableStateOf<Long?>(null) }
    // handle in progress: (base object, grabbed corner) while scaling
    var resizing by remember { mutableStateOf<Resizing?>(null) }
    var activeColor by rememberSaveable { mutableStateOf<Int?>(null) }  // tint ARGB; null = theme
    var drawingTool by rememberSaveable { mutableStateOf(false) }      // pen mode: ink, not stamps
    var eraserTool by rememberSaveable { mutableStateOf(false) }      // eraser mode: erases stamps and strokes
    var penWidth by remember { mutableFloatStateOf(DEFAULT_STROKE_WIDTH) }  // ink width (fraction of page height)
    var dragStamp by remember { mutableStateOf<Stamp?>(null) }
    var dragStroke by remember { mutableStateOf<Stroke?>(null) }   // preview of moving/stretching a stroke
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var undoStack by remember { mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList()) }
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview of the stroke in progress (px)
    // to join the next stroke to the previous one if the stylus lifts only for a few ms
    val inkTail = remember { InkTail() }
    LaunchedEffect(currentPage, editing) { inkTail.last = null }
    // ink already released that the DB has not returned yet: without this the stroke flickers ~20 ms
    var pendingInk by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    LaunchedEffect(currentPage, editing) { pendingInk = emptyList() }
    // optimistic delete: the view hides deleted items instantly and the DB confirms afterwards.
    // ponytail: Set of ids + pruning via LaunchedEffect; enough because deleting never revives ids.
    var hiddenStampIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hiddenStrokeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val editScope = rememberCoroutineScope()

    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // MuPDF is not thread-safe either (it shares the global Context): serialize render/close so a
    // cancelled render (not cancellable mid-page) does not collide with the next one or with
    // close(). Ponytail: one mutex per document is enough; if there is ever parallel multi-document,
    // move it to FOSScoreApp. The bitmap cache is handled by MuPdfDoc.
    val rendererLock = remember(pdfUri) { Mutex() }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)

    val canEdit = sheetHash != null && !branch.showTwoUp && !(branch.showHalf && halfTurned)

    // geometry and stamps of the current page, shared between overlay and gestures.
    // The fit comes from MuPDF's size in points (not the bitmap), so the UI lays out
    // from the first frame and the overlay does not depend on the render having landed.
    val currentBmp = bitmaps[currentPage]
    val pageBox = mupdfDoc?.pageSize(currentPage)
    val pageStamps = if (sheetHash != null) {
        stamps.filter { it.page == currentPage && it.id !in hiddenStampIds }
    } else emptyList()
    val pageStrokes = if (sheetHash != null) {
        strokes.filter { it.page == currentPage && it.id !in hiddenStrokeIds }
    } else emptyList()
    val pageFit = if (pageBox != null && pageBox.height > 0f) {
        fitRect(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            pageBox.width / pageBox.height
        )
    } else RectPx(0f, 0f, 0f, 0f)
    // PURE fit rect (origin 0,0): content coordinates (the page itself, without pageFit's
    // centering margin). The whole overlay and the edit gesture live here.
    val hitFit = RectPx(0f, 0f, pageFit.width, pageFit.height)
    val currentEditing by rememberUpdatedState(editing)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentActiveSymbol by rememberUpdatedState(activeSymbol)
    val currentPageStamps by rememberUpdatedState(pageStamps)
    val currentPageStrokes by rememberUpdatedState(pageStrokes)
    val currentPageFit by rememberUpdatedState(pageFit)
    val currentDrawingTool by rememberUpdatedState(drawingTool)
    val currentEraserTool by rememberUpdatedState(eraserTool)
    // page centering base in the viewport and pure fit rect (origin 0,0):
    // content operations and zoom anchoring live in these coordinates
    val currentBase by rememberUpdatedState(Offset(pageFit.left, pageFit.top))
    val currentHitFit by rememberUpdatedState(RectPx(0f, 0f, pageFit.width, pageFit.height))
    // the reading modes (two-up, half page) scale the whole viewport; the single page
    // scales its content. The gesture captures it via rememberUpdatedState: always reads live.
    val currentSpread by rememberUpdatedState(branch.showTwoUp || (branch.showHalf && halfTurned))

    val selectedStamp = pageStamps.firstOrNull { it.id == selectedId }
    val selectedStroke = pageStrokes.firstOrNull { it.id == selectedStrokeId }

    // selection box in px: the stamp's or stroke's box. While dragging or stretching, the live
    // position (dragStamp/dragStroke) is used so the box follows the object.
    val selectionRect: RectPx? = when {
        dragStamp != null -> stampRect(dragStamp!!, hitFit)
        dragStroke != null -> strokeSelectionRect(
            decodePoints(dragStroke!!.points).map { normalizedToPx(it, hitFit) },
            dragStroke!!.width * hitFit.height
        )
        selectedStamp != null -> stampRect(selectedStamp, hitFit)
        selectedStroke != null -> strokeSelectionRect(
            decodePoints(selectedStroke.points).map { normalizedToPx(it, hitFit) },
            selectedStroke.width * hitFit.height
        )
        else -> null
    }
    val currentSelectionRect by rememberUpdatedState(selectionRect)
    val currentSelectedStamp by rememberUpdatedState(selectedStamp)
    val currentSelectedStroke by rememberUpdatedState(selectedStroke)

    // during a handle drag or a move, the object is drawn at its live position, not the box
    val showSelection = selectionRect != null && editing && canEdit &&
        !drawingTool && !eraserTool

    // pushes snapshots BEFORE each mutation, with their page; like forScore, 10 levels
    fun pushUndo(snapshot: PageEdit = PageEdit(pageStamps, pageStrokes), page: Int = currentPage) {
        undoStack = (undoStack + listOf(page to snapshot)).takeLast(10)
    }

    val applyNext: () -> Unit = {
        val f = branch.next()
        currentPage = f.page
        halfTurned = f.halfTurned
    }
    val applyPrev: () -> Unit = {
        val f = branch.prev()
        currentPage = f.page
        halfTurned = f.halfTurned
    }

    fun clampOffsets() {
        if (viewport == IntSize.Zero) return
        // Single page: the content is the page in its fit; the reading modes (two-up,
        // half page) scale the whole viewport. `currentSpread`/`currentPageFit` are states
        // updated on every composition: the gesture and the inertia, which capture this function
        // when they start, always read the live values.
        val vps = Size(viewport.width.toFloat(), viewport.height.toFloat())
        val (content, base) = if (currentSpread) {
            Size(viewport.width.toFloat(), viewport.height.toFloat()) to Offset.Zero
        } else {
            Size(currentPageFit.width, currentPageFit.height) to
                Offset(currentPageFit.left, currentPageFit.top)
        }
        val o = ZoomMath.clampOffset(Offset(offsetX, offsetY), content, vps, scale, base)
        offsetX = o.x
        offsetY = o.y
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(currentPage) {
        Settings.setLastPage(context, pdfUri.toString(), currentPage)
    }

    // the DB no longer has what we hid: clear the optimistic delete (and the drag preview)
    LaunchedEffect(stamps, strokes) {
        val liveStamps = stamps.map { it.id }.toSet()
        val liveStrokes = strokes.map { it.id }.toSet()
        if (hiddenStampIds.any { it !in liveStamps }) hiddenStampIds = hiddenStampIds intersect liveStamps
        if (hiddenStrokeIds.any { it !in liveStrokes }) hiddenStrokeIds = hiddenStrokeIds intersect liveStrokes
        dragStamp?.let { d ->
            if (stamps.any { it.id == d.id && it.x == d.x && it.y == d.y && it.size == d.size }) dragStamp = null
        }
        dragStroke?.let { d ->
            if (strokes.any { it.id == d.id && it.points == d.points && it.width == d.width }) dragStroke = null
        }
        // the DB already has this ink: out of the optimistic layer
        pendingInk = pendingInk.filter { p -> strokes.none { it.id == p.id && it.points == p.points } }
    }

    // MuPDF (prebuilt AAR, AGPL-3.0: see LICENSE). Opens and computes sizes in the background:
    // opening parses the PDF index and must not touch the main thread.
    LaunchedEffect(pdfUri) {
        try {
            // NonCancellable: if the user leaves while we open, the document is created anyway;
            // we close it below. Cancelling here would leave the native doc dangling (and its memory).
            val doc = withContext(NonCancellable + Dispatchers.IO) { MuPdfDoc.open(context, pdfUri) }
            if (!isActive) {
                withContext(NonCancellable + Dispatchers.IO) { doc.close() }
                return@LaunchedEffect
            }
            pageCount = doc.pageCount
            if (pageCount > 0) currentPage = currentPage.coerceIn(0, pageCount - 1)
            mupdfDoc = doc
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(LOG_TAG, "no se pudo abrir el archivo: ${e.message}", e)
            error = R.string.error_file_open
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo abrir: ${e::class.simpleName}: ${e.message}", e)
            error = R.string.viewer_error_invalid_pdf
        }
    }

    // reset zoom when changing page or mode (the render step too: otherwise every page
    // turned to would be rendered zoomed in)
    LaunchedEffect(currentPage, branch.showTwoUp, branch.showHalf, halfTurned) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        renderScale = 1f
    }

    // Pan inertia: on release, the movement continues with friction until the velocity dies
    // or it hits the edge. Without this, panning a zoomed page by finger is painfully slow.
    // ponytail: own friction loop instead of Animatable+decay (less code, same effect).
    LaunchedEffect(flingVel) {
        var v = flingVel
        flingVel = Offset.Zero
        if (v.getDistance() < 600f) return@LaunchedEffect
        var px = offsetX
        var py = offsetY
        val fromX = px
        val fromY = py
        Log.d(LOG_TAG, "INERCIA empieza v=${f1(v.x)},${f1(v.y)} off=${f1(px)},${f1(py)}")
        while (v.getDistance() > 20f) {
            delay(16)
            px += v.x * 0.016f
            py += v.y * 0.016f
            val bx = offsetX
            val by = offsetY
            offsetX = px
            offsetY = py
            clampOffsets()
            // the clamp stopped us against the edge: inertia adds nothing there
            if (offsetX == bx && offsetY == by) break
            v *= 0.965f   // long friction: the glide is felt, it does not cut off at 200 ms
        }
        Log.d(
            LOG_TAG,
            "INERCIA acaba off=${f1(offsetX)},${f1(offsetY)} " +
                "recorrido=${f1(offsetX - fromX)},${f1(offsetY - fromY)}"
        )
    }

    // Re-render at the current zoom (steps of 10% of the fit, ZoomMath.renderBucket): like the
    // reference viewers (AndroidPdfViewer: doRenderDuringScale; GrapheneOS: onRenderPage(2)),
    // the bitmap follows the zoom so the GPU never rescales it. During the pinch the step only
    // goes UP, with a minimum of 150 ms between renders; once settled (no scale change for
    // 250 ms) the final step is refined, and it can also go DOWN after zooming out.
    LaunchedEffect(mupdfDoc, viewport) {
        var lastUpgradeMs = 0L
        snapshotFlow { scale }.collectLatest { s ->
            val target = ZoomMath.renderBucket(s)
            val now = System.currentTimeMillis()
            if (target > renderScale && now - lastUpgradeMs >= RENDER_DURING_ZOOM_MS) {
                lastUpgradeMs = now
                renderScale = target
                return@collectLatest
            }
            delay(RENDER_SETTLE_MS)
            // if scale kept changing while we waited, the next emission picks it up
            if (target >= ZoomMath.renderBucket(scale)) renderScale = target
        }
    }

    // viewport is a key: on the first frame it is still IntSize.Zero and with no size there is no render
    LaunchedEffect(
        mupdfDoc, currentPage, branch.showTwoUp, branch.showHalf, halfTurned, renderScale, viewport
    ) {
        val r = mupdfDoc ?: return@LaunchedEffect
        val gen = ++renderGeneration
        val needed = when {
            branch.showTwoUp -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            branch.showHalf && halfTurned -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            else -> listOf(currentPage)
        }
        val isSpread = branch.showTwoUp || (branch.showHalf && halfTurned)
        // Single page: the render slot is the fit × zoom step (the bitmap is drawn 1:1 on
        // screen, the GPU rescales nothing). Reading modes (two-up, half page): the slot is
        // the viewport and the layer still scales (they are for reading, not for zooming in).
        val spreadSlot =
            if (isSpread) Pair(if (branch.showTwoUp) viewport.width / 2 else viewport.width, viewport.height)
            else null
        // Compose state reads BEFORE entering the render dispatcher
        val rs = renderScale
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        val fallback = needed.associateWith { bitmaps[it] }
        try {
            val session = rendererLock.withLock {
                withContext(Dispatchers.Default) {
                    // the real page size may not be loaded yet (pages beyond the preload):
                    // it is resolved inside the lock with loadSize (cheap parse of the page
                    // dictionary), the same path render follows for its fit
                    val slot = if (isSpread) spreadSlot!! else {
                        val box = r.ensurePageSize(currentPage)
                        if (box == null || box.height <= 0f) return@withContext emptyMap<Int, Bitmap>()
                        val fit = fitRect(vpW, vpH, box.width / box.height)
                        Pair((fit.width * rs).toInt(), (fit.height * rs).toInt())
                    }
                    val out = mutableMapOf<Int, Bitmap>()
                    for (idx in needed) {
                        // if the render fails we keep what is already shown: faint beats a hole
                        out[idx] = r.render(idx, slot.first, slot.second, 1) ?: fallback[idx] ?: continue
                    }
                    out
                }
            }
            // guard against an older, non-cancellable render landing after a newer one
            if (gen == renderGeneration) {
                for ((idx, bmp) in session) bitmaps[idx] = bmp
                // don't accumulate bitmaps of pages no longer shown (16 MB each at high sharpness)
                bitmaps.keys.filterNot { it in needed }.forEach { bitmaps.remove(it) }
                // no bitmap for this page means nothing to show: say so instead of leaving the
                // spinner spinning forever (happened with a blank page)
                if (bitmaps[currentPage] == null) {
                    Log.e(LOG_TAG, "la página $currentPage no se pudo renderizar")
                    error = R.string.viewer_error_render
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render failed: ${e::class.simpleName}: ${e.message}", e)
            if (gen == renderGeneration) error = R.string.viewer_error_render
        }
    }

    DisposableEffect(mupdfDoc) {
        val current = mupdfDoc
        onDispose {
            // never block main waiting for an in-flight render (ANR): close() goes to
            // applicationScope; the mutex FIFO queue guarantees it happens after the render.
            val scope = (context.applicationContext as FOSScoreApp).applicationScope
            scope.launch {
                rendererLock.withLock { current?.close() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
    ) {
        if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(error!!), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        } else if (bitmaps.isNotEmpty()) {
            // Reading modes (two-up, half page): the layer scales the whole viewport as before
            // (they are not used to zoom into details: offscreen sweeps away the "cooking" with no
            // noticeable sharpness cost). Single page (else branch): NO scale layer — the bitmap is
            // rendered at the current zoom and drawn 1:1; the overlay is re-rasterized as vector
            // (see plan 2026-09-30-zoom-render-1to1).
            val spreadMod = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            val gestureMod = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        flingVel = Offset.Zero   // touching stops the inertia
                        // screen -> fit coordinates (0..fitW x 0..fitH): the content no longer
                        // lives in a scaled layer, the gesture translates the finger point here
                        fun fitPoint(p: Offset): Offset {
                            val b = currentBase
                            return Offset(
                                (p.x - b.x - offsetX) / scale,
                                (p.y - b.y - offsetY) / scale
                            )
                        }
                        val downF = fitPoint(down.position)
                        // anchor base: the single page scales its content (centered at
                        // pageFit.left/top); the reading modes scale the whole viewport (origin 0)
                        val anchorBase = if (currentSpread) Offset.Zero else currentBase
                        var ids = setOf(down.id)
                        var prevCount = 1
                        var prevCentroid = down.position
                        var anchorDist = 0f
                        // pinch FOCUS: screen position where the fingers landed.
                        // The zoom grows around THIS fixed point and does not follow their sliding
                        // (it used to anchor to the live centroid + 2-finger pan: the touched point
                        // drifted away -> "it doesn't zoom where I want").
                        var pinchAnchor = down.position
                        // TEMPORARY: page point under the anchor when the gesture starts. Never
                        // recomputed: used to see whether something moves it during the pinch.
                        var pinchFit0 = downF
                        var totalPan = Offset.Zero
                        var isTransform = false
                        // TEMPORARY/diagnostic: if the gesture ever had 2+ fingers it was a
                        // pinch, and a pinch must NOT start pan inertia: when one finger lifts,
                        // the other slips a millimeter on release, that fed panVel and the
                        // page drifted off by itself hundreds of px AFTER a zoom that was
                        // correct. ponytail: upgrade if you want inertia after a pinch =
                        // measure the centroid velocity ONLY in frames where both fingers
                        // move together and the distance does not change.
                        var sawMultiTouch = false
                        var movingStamp: Stamp? = null
                        var movingOrigin: Stamp? = null
                        var moveDownId = down.id
                        var moveTrackingPos = downF
                        var movingStroke: Stroke? = null
                        var movingStrokeBase: Stroke? = null
                        var moveStrokeLastPos = downF
                        var inkActive = false
                        var inkPts = mutableListOf<Offset>()
                        var eraserActive = false
                        var eraserPath = mutableListOf<Offset>()
                        var scaleF = 1f   // accumulated handle factor (always from startPos)
                        var panVel = Offset.Zero
                        var lastPanMs = System.currentTimeMillis()
                        if (currentEditing && currentCanEdit && !currentDrawingTool && !currentEraserTool) {
                            // 1) a selection-box handle takes priority over everything else:
                            //    grabbing a corner stretches, grabbing inside moves.
                            val sel = currentSelectionRect
                            val corner = if (sel != null) hitHandle(downF, sel, currentHitFit) else null
                            if (corner != null) {
                                resizing = when (val s = currentSelectedStamp) {
                                    null -> currentSelectedStroke?.let { st ->
                                        val ptsPx = decodePoints(st.points)
                                            .map { normalizedToPx(it, currentHitFit) }
                                        Resizing.OfStroke(
                                            stroke = st,
                                            ptsPx = ptsPx,
                                            center = strokeBounds(ptsPx).center(),
                                            startPos = downF
                                        )
                                    }
                                    else -> Resizing.OfStamp(stamp = s, startPos = downF)
                                }
                            }
                            if (resizing == null) {
                                val hitS = hitStamp(downF, currentPageStamps, currentHitFit)
                                val hitT = if (hitS == null) {
                                    hitStroke(downF, currentPageStrokes, currentHitFit)
                                } else null
                                if (hitS != null) {
                                    movingStamp = hitS
                                    movingOrigin = hitS
                                    selectedId = hitS.id
                                    selectedStrokeId = null
                                    activeSymbol = runCatching { StampSymbol.valueOf(hitS.symbol) }.getOrNull()
                                    dragStamp = hitS
                                } else if (hitT != null) {
                                    movingStroke = hitT
                                    movingStrokeBase = hitT
                                    moveStrokeLastPos = downF
                                    selectedStrokeId = hitT.id
                                    selectedId = null
                                    dragStroke = hitT
                                }
                            }
                        }
                        if (currentEditing && currentCanEdit && currentEraserTool) {
                            eraserActive = true
                            eraserPath.add(downF)
                            inkPreview = listOf(downF)
                        }
                        if (currentEditing && currentCanEdit && currentDrawingTool) {
                            inkActive = true
                            inkPts.add(downF)
                            inkPreview = listOf(downF)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (resizing != null) {
                                    val rz = resizing
                                    resizing = null
                                    when (rz) {
                                        is Resizing.OfStamp -> {
                                            val base = rz.stamp
                                            val newSize = (base.size * scaleF)
                                                .coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                            if (kotlin.math.abs(newSize - base.size) > 1e-4f) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    stampRepo.update(base.copy(size = newSize))
                                                }
                                            }
                                        }
                                        is Resizing.OfStroke -> {
                                            val base = rz.stroke
                                            val pts = scalePoints(rz.ptsPx, rz.center, scaleF)
                                            val newW = (base.width * scaleF)
                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                            if (kotlin.math.abs(newW - base.width) > 1e-5f ||
                                                pts != rz.ptsPx
                                            ) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    strokeRepo.update(
                                                        base.copy(
                                                            points = encodePoints(
                                                                pts.map { pxToNormalized(it, currentHitFit) }
                                                            ),
                                                            width = newW
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        null -> {}  // resizing is a captured var: it can be null
                                    }
                                } else if (inkActive) {
                                    inkActive = false
                                    // ink capture lives in fit coordinates (F): the sampling
                                    // threshold is in screen px, so it is scaled by the zoom
                                    val pts = samplePoints(inkPts, MIN_SAMPLE_DIST_PX / scale)
                                    inkPts = mutableListOf()
                                    inkPreview = emptyList()
                                    if (pts.size >= 2 && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val norm = pts.map { pxToNormalized(it, currentHitFit) }
                                        val fit = currentHitFit
                                        val page = currentPage
                                        val hash = sheetHash
                                        val width = penWidth
                                        val color = activeColor
                                        val startPx = pts.first()   // comparison in px, not normalized
                                        editScope.launch {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val prev = inkTail.last
                                            val prevPts = prev?.points?.let { decodePoints(it) }
                                                ?.map { normalizedToPx(it, fit) } ?: emptyList()
                                            val canMerge = prev != null && prevPts.isNotEmpty() &&
                                                shouldMergeStroke(
                                                    prevPts.last(),
                                                    startPx,
                                                    fit,
                                                    now - inkTail.at,
                                                    prev.width == width && prev.color == color
                                                )
                                            Log.d(LOG_TAG, "ink: merge=$canMerge gap=${now - inkTail.at}ms prev=${prev != null} dist=${if (prevPts.isNotEmpty()) (startPx - prevPts.last()).getDistance() else -1f}px limit=${STROKE_MERGE_DIST_FRAC * fit.height}px")
                                            if (canMerge) {
                                                val merged = prev!!.copy(
                                                    points = encodePoints(decodePoints(prev.points) + norm)
                                                )
                                                strokeRepo.update(merged)
                                                inkTail.last = merged
                                                pendingInk = pendingInk.filter { it.id != merged.id } + merged
                                            } else {
                                                val s = Stroke(
                                                    sheetHash = hash,
                                                    page = page,
                                                    points = encodePoints(norm),
                                                    width = width,
                                                    color = color
                                                )
                                                val stored = s.copy(id = strokeRepo.insert(s))
                                                inkTail.last = stored
                                                pendingInk = pendingInk + stored
                                            }
                                            inkTail.at = now
                                        }
                                    }
                                } else if (eraserActive) {
                                    eraserActive = false
                                    val path = eraserPath
                                    eraserPath = mutableListOf()
                                    inkPreview = emptyList()
                                    if (path.isNotEmpty() && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val radiusPx = ERASE_RADIUS_FRAC * currentHitFit.height
                                        // the eraser always erases what it touches: to remove a whole
                                        // word, tap it (selects it) and then 🗑
                                        val goneStamps = currentPageStamps.filter { st ->
                                            val r = stampRect(st, currentHitFit)
                                            path.any { r.contains(it) }
                                        }
                                        val goneStrokes = mutableListOf<Stroke>()
                                        val newRuns = mutableListOf<Stroke>()
                                        for (st in currentPageStrokes) {
                                            val ptsPx = decodePoints(st.points).map { normalizedToPx(it, currentHitFit) }
                                            val runs = eraseStrokePoints(ptsPx, path, radiusPx)
                                            if (runs.size == 1 && runs[0].size == ptsPx.size) {
                                                // intact
                                            } else {
                                                goneStrokes.add(st)
                                                runs.forEach { r ->
                                                    newRuns.add(
                                                        st.copy(
                                                            id = 0,
                                                            points = encodePoints(r.map { pxToNormalized(it, currentHitFit) })
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        selectedId = null
                                        selectedStrokeId = null
                                        inkTail.last = null   // the previous stroke no longer exists
                                        // show it erased NOW: the DB lags behind (~20 ms)
                                        hiddenStampIds = hiddenStampIds + goneStamps.map { it.id }
                                        hiddenStrokeIds = hiddenStrokeIds + goneStrokes.map { it.id }
                                        editScope.launch {
                                            if (goneStamps.isNotEmpty()) stampRepo.deleteAll(goneStamps)
                                            if (goneStrokes.isNotEmpty()) {
                                                strokeRepo.deleteAll(goneStrokes)
                                                strokeRepo.insertAll(newRuns)
                                            }
                                        }
                                    }
                                } else if (movingStroke != null) {
                                    val done = movingStroke
                                    val base = movingStrokeBase
                                    movingStroke = null
                                    movingStrokeBase = null
                                    // keep the final position: if cleared here, the overlay draws the old
                                    // DB position for a few ms until the flow re-emits (glitch).
                                    dragStroke = done
                                    if (done != null && base != null) {
                                        if (done.points != base.points) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch { strokeRepo.update(done) }
                                        }
                                    }
                                } else if (movingStamp != null) {
                                    val done = movingStamp
                                    val origin = movingOrigin
                                    movingStamp = null
                                    movingOrigin = null
                                    // keep the final position: if cleared here, the overlay draws the old
                                    // DB position for a few ms until the flow re-emits (glitch).
                                    dragStamp = done
                                    if (done != null) {
                                        if (origin != null && (done.x != origin.x || done.y != origin.y)) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        }
                                        editScope.launch { stampRepo.update(done) }
                                    }
                                } else if (!isTransform) {
                                    // double tap: toggles fit <-> 2.5x at the tapped point. It is the
                                    // shortcut to see the whole page again (and to dive into a
                                    // detail) without fighting the toolbar buttons.
                                    val nowMs = System.currentTimeMillis()
                                    val isDoubleTap = nowMs - lastTapMs < 320L &&
                                        (down.position - lastTapPos).getDistance() < doubleTapSlopPx
                                    if (isDoubleTap && !currentEditing) {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            val k = DOUBLE_TAP_ZOOM / scale
                                            val no = ZoomMath.anchoredOffset(
                                                Offset(offsetX, offsetY), k, down.position, anchorBase
                                            )
                                            offsetX = no.x
                                            offsetY = no.y
                                            scale = DOUBLE_TAP_ZOOM
                                            clampOffsets()
                                            val f2 = fitPoint(down.position)
                                            val drawn2 = anchorBase + Offset(offsetX, offsetY) + f2 * scale
                                            Log.d(
                                                LOG_TAG,
                                                "ZOOM doble k=${f1(scale)} off=${f1(offsetX)},${f1(offsetY)} " +
                                                    "draw=${f1(anchorBase.x + offsetX)},${f1(anchorBase.y + offsetY)} " +
                                                    "toque=${f1(down.position.x)},${f1(down.position.y)} " +
                                                    "sale=${f1(drawn2.x)},${f1(drawn2.y)} " +
                                                    "residuo=${f1((drawn2 - down.position).getDistance())}"
                                            )
                                        }
                                        lastTapMs = 0L
                                    } else {
                                        lastTapMs = nowMs
                                        lastTapPos = down.position
                                        // Inertia only if the gesture was a real ONE-finger pan.
                                        // After a pinch (2+ fingers) the remaining finger slips
                                        // on release and fired a glide of hundreds of px that
                                        // carried the page far from the zoomed point.
                                        flingVel = if (!sawMultiTouch && panVel.getDistance() > 600f) {
                                            panVel
                                        } else {
                                            Offset.Zero
                                        }
                                    }
                                    Log.d(
                                        LOG_TAG,
                                        "FIN gesto k=${f1(scale)} off=${f1(offsetX)},${f1(offsetY)} " +
                                            "panVel=${f1(panVel.x)},${f1(panVel.y)} " +
                                            "flingVel=${f1(flingVel.x)},${f1(flingVel.y)} " +
                                            "multi=${sawMultiTouch} pan=${f1(totalPan.x)},${f1(totalPan.y)}"
                                    )
                                    if (isDoubleTap) {
                                        // already handled above: neither places a stamp nor turns the page
                                    } else if (currentEditing && currentCanEdit) {
                                        // tap in edit mode: places the active stamp at the tapped point
                                        val sym = currentActiveSymbol
                                        if (sym == null) {
                                            // no tool: just deselect
                                            selectedId = null
                                            selectedStrokeId = null
                                            dragStamp = null
                                            dragStroke = null
                                        }
                                        if (sym != null && sheetHash != null && scale <= 1f) {
                                            val pos = pxToNormalized(downF, currentHitFit)
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch {
                                                val id = stampRepo.insert(
                                                    Stamp(
                                                        sheetHash = sheetHash,
                                                        page = currentPage,
                                                        symbol = sym.name,
                                                        x = pos.x.coerceIn(0f, 1f),
                                                        y = pos.y.coerceIn(0f, 1f),
                                                        size = DEFAULT_STAMP_SIZE,
                                                        color = activeColor
                                                    )
                                                )
                                                selectedId = id
                                            }
                                        }
                                    } else {
                                        val isSwipe = scale <= 1f &&
                                            totalPan.getDistance() > swipeThresholdPx &&
                                            abs(totalPan.x) > abs(totalPan.y)
                                        if (isSwipe) {
                                            if (totalPan.x < 0f) applyNext() else applyPrev()
                                        } else {
                                            // tap zones (forScore-style): left third prev, right third next, center toggles nav
                                            val width = viewport.width
                                            when {
                                                width > 0 && down.position.x > width * 2f / 3f -> applyNext()
                                                width > 0 && down.position.x < width / 3f -> applyPrev()
                                                else -> navVisible = !navVisible
                                            }
                                        }
                                    }
                                }
                                break
                            }

                            if (resizing != null) {
                                // stretching by a handle: 1 pointer, scales about the center.
                                // the factor is recomputed from the start position, never accumulated
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val rz = resizing!!
                                val center = when (rz) {
                                    is Resizing.OfStamp -> stampRect(rz.stamp, currentHitFit).center()
                                    is Resizing.OfStroke -> rz.center
                                }
                                val f = scaleFactor(center, rz.startPos, fitPoint(tracking.position))
                                scaleF = f
                                when (rz) {
                                    is Resizing.OfStamp -> {
                                        dragStamp = rz.stamp.copy(
                                            size = (rz.stamp.size * f).coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                        )
                                    }
                                    is Resizing.OfStroke -> {
                                        val pts = scalePoints(rz.ptsPx, center, f)
                                        dragStroke = rz.stroke.copy(
                                            points = encodePoints(pts.map { pxToNormalized(it, currentHitFit) }),
                                            width = (rz.stroke.width * f).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                        )
                                    }
                                }
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStroke != null) {
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveStrokeLastPos, currentHitFit)
                                moveStrokeLastPos = fitPoint(tracking.position)
                                val ns = movingStroke!!.copy(
                                    points = encodePoints(
                                        decodePoints(movingStroke!!.points).map {
                                            Offset((it.x + m.x).coerceIn(0f, 1f), (it.y + m.y).coerceIn(0f, 1f))
                                        }
                                    )
                                )
                                movingStroke = ns
                                dragStroke = ns
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStamp != null) {
                                // move with 1 finger (the initial pointer, no jumps when adding a finger);
                                // no pinch rescaling — forScore adjusts the size with a slider
                                val tracking = pressed.firstOrNull { it.id == moveDownId } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveTrackingPos, currentHitFit)
                                moveTrackingPos = fitPoint(tracking.position)
                                val ns = movingStamp!!.copy(
                                    x = (movingStamp!!.x + m.x).coerceIn(0f, 1f),
                                    y = (movingStamp!!.y + m.y).coerceIn(0f, 1f)
                                )
                                movingStamp = ns
                                dragStamp = ns
                                prevCentroid = centroid(pressed)
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (inkActive) {
                                if (pressed.size > 1) {
                                    // a second finger arrived: drop the ink, let the pinch zoom
                                    inkActive = false
                                    inkPts = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = inkPts.last()
                                    // points captured in fit coordinates: the screen threshold
                                    // is scaled by the zoom
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        inkPts.add(fitPoint(tracking.position))
                                        inkPreview = inkPts.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            if (eraserActive) {
                                if (pressed.size > 1) {
                                    eraserActive = false
                                    eraserPath = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = eraserPath.last()
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        eraserPath.add(fitPoint(tracking.position))
                                        inkPreview = eraserPath.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            val nids = pressed.map { it.id }.toSet()
                            // Re-anchor if the finger SET OR their COUNT changes. Comparing only the
                            // set let 1 -> 2 -> 1 slip through: back to one finger, nids equaled
                            // ids again, no re-anchor happened and prevCentroid stayed at the
                            // midpoint of the two. Then delta was HALF THE SEPARATION of the fingers
                            // and the page teleported +-215 px when lifting a finger from the pinch
                            // (measured on the S6 Lite: off.y from -823 to -609 with k=1.8 constant).
                            // Compose's reference (detectTransformGestures) keeps no centroid
                            // between events: it derives the pan from previousPosition() per pointer,
                            // so a set change is handled by construction.
                            if (nids != ids || nids.size != prevCount) {
                                prevCount = nids.size
                                ids = nids
                                anchorDist = 0f      // re-anchors when the finger count changes
                                prevCentroid = centroid(pressed)
                            }

                            val c = centroid(pressed)
                            val delta = c - prevCentroid
                            totalPan += delta

                            if (pressed.size > 1) {
                                isTransform = true
                                sawMultiTouch = true
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (anchorDist == 0f) {
                                    anchorDist = d
                                    // FIXED FOCUS: the pointing finger (the first one to touch),
                                    // NOT the midpoint of the two. With the midpoint, planting a
                                    // finger on a measure and spreading with the other zoomed around
                                    // the middle: the targeted measure left the screen and another
                                    // one ended up in front of you ("it doesn't zoom where I want").
                                    // The second finger only sets the zoom level.
                                    // If the pointing finger lifts mid-gesture, the previous focus
                                    // is kept instead of jumping to the remaining finger.
                                    pressed.firstOrNull { it.id == down.id }?.let { pinchAnchor = it.position }
                                    pinchFit0 = fitPoint(pinchAnchor)
                                    Log.d(
                                        LOG_TAG,
                                        "ZOOM start ancla=${f1(pinchAnchor.x)},${f1(pinchAnchor.y)} " +
                                            "fit=${f1(pinchFit0.x)},${f1(pinchFit0.y)} " +
                                            "base=${f1(anchorBase.x)},${f1(anchorBase.y)} k=${f1(scale)}"
                                    )
                                } else if (d > 0f) {
                                    // INCREMENTAL: we apply how much the distance between the
                                    // fingers changed in THIS frame (ratio), not an absolute target.
                                    // With a smoothed absolute target the scale always lags
                                    // halfway -> slow zoom and the anchor drifts toward the center.
                                    val ratio = d / anchorDist
                                    anchorDist = d   // the next frame measures against this distance
                                    // dead zone against touch noise (±1 px, fingers still)
                                    if (abs(ratio - 1f) > ZOOM_NOISE_RATIO) {
                                        val target = (scale * ratio).coerceIn(1f, MAX_ZOOM)
                                        if (target != scale) {
                                            // anchoring: the point where the fingers landed stays
                                            // still (FIXED anchor, not the live centroid)
                                            val k = target / scale
                                            val no = ZoomMath.anchoredOffset(
                                                Offset(offsetX, offsetY), k, pinchAnchor, anchorBase
                                            )
                                            offsetX = no.x
                                            offsetY = no.y
                                            scale = target
                                            // TEMPORARY (S6 Lite diagnostic, no overlay).
                                            // REAL invariant: pinchFit0 was captured when the fingers
                                            // landed and is NOT recomputed, so "sale" is where that
                                            // score point really ends up. If it drifts from the
                                            // anchor, something moved it (a pan, a re-anchor).
                                            val drawn = anchorBase + Offset(offsetX, offsetY) + pinchFit0 * scale
                                            Log.d(
                                                LOG_TAG,
                                                "ZOOM k=${f1(scale)} off=${f1(offsetX)},${f1(offsetY)} " +
                                                    "ancla=${f1(pinchAnchor.x)},${f1(pinchAnchor.y)} " +
                                                    "sale=${f1(drawn.x)},${f1(drawn.y)} " +
                                                    "residuo=${f1((drawn - pinchAnchor).getDistance())}"
                                            )
                                        }
                                    }
                                }
                                // ponytail: NO 2-finger pan. The focus is pinned to the first-contact
                                // point; following the centroid's sliding (offset += delta)
                                // dragged the touched point and the user saw "it doesn't zoom
                                // where I want". To move the page, lift a finger and drag
                                // (1-finger branch below) or move the fingers once zoomed.
                            } else if (scale > 1f && delta.getDistance() > 0f) {
                                // single finger: pan (scaling is handled by the case above)
                                isTransform = true
                                offsetX += delta.x
                                offsetY += delta.y
                                // smoothed velocity, for the inertia on release
                                val nowMs = System.currentTimeMillis()
                                val dt = (nowMs - lastPanMs).coerceAtLeast(1L) / 1000f
                                // after a pinch the remaining finger slips on release: that is not
                                // a user drag, it is noise, and it was what moved the
                                // page after the zoom
                                if (dt < 0.2f && !sawMultiTouch) panVel = panVel * 0.7f + (delta / dt) * 0.3f
                                lastPanMs = nowMs
                                // TEMPORARY: a large pan is a page jump. It shows in the log if
                                // the movement does not come from a finger drag.
                                if (delta.getDistance() > 60f) {
                                    Log.d(
                                        LOG_TAG,
                                        "PAN salto delta=${f1(delta.x)},${f1(delta.y)} " +
                                            "off=${f1(offsetX)},${f1(offsetY)} multi=$sawMultiTouch"
                                    )
                                }
                            }
                            clampOffsets()
                            prevCentroid = c
                            event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                        }
                    }
                }

            when {
                branch.showTwoUp -> {
                    Row(modifier = spreadMod.then(gestureMod)) {
                        bitmaps[currentPage]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        bitmaps[currentPage + 1]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                branch.showHalf && halfTurned && bitmaps[currentPage] != null && bitmaps[currentPage + 1] != null -> {
                    // ponytail: compose both halves into a single bitmap the same size as one page,
                    // so it scales identically to the full page and the bottom (reading) half stays put.
                    // Assumes consecutive pages share the same dimensions.
                    val cur = bitmaps[currentPage]!!
                    val next = bitmaps[currentPage + 1]!!
                    val w = cur.width
                    val h = cur.height
                    val sepColor = MaterialTheme.colorScheme.primary.toArgb()
                    // remember keyed on the BITMAPS (not the map): with SnapshotStateMap, reading the
                    // keys above already registers the dependency and it only recomposes if they change
                    val combined = remember(cur, next, w, h) {
                        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
                        canvas.drawBitmap(next, Rect(0, 0, next.width, next.height / 2), Rect(0, 0, w, h / 2), paint)
                        canvas.drawBitmap(cur, Rect(0, cur.height / 2, cur.width, cur.height), Rect(0, h / 2, w, h), paint)
                        paint.color = sepColor
                        paint.strokeWidth = (h / 200f).coerceAtLeast(2f)
                        canvas.drawLine(0f, (h / 2).toFloat(), w.toFloat(), (h / 2).toFloat(), paint)
                        out
                    }
                    Image(
                        bitmap = combined.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (invert) INVERT_FILTER else null,
                        modifier = spreadMod.then(gestureMod)
                    )
                }
                else -> {
                    bitmaps[currentPage]?.let { bmp ->
                        // Single page: the bitmap is rendered at the current zoom (fit × scale, the
                        // same size it takes on screen) and drawn 1:1 — the GPU rescales nothing.
                        // The overlay (ink/stamps/selection) is drawn in fit coordinates and the
                        // zoom is applied in the drawing itself with translate+scale: vector,
                        // re-rasterized every frame and sharp at any zoom.
                        val density = LocalDensity.current
                        val k = scale
                        val posX = pageFit.left + offsetX
                        val posY = pageFit.top + offsetY
                        // clipToBounds: without this the zoomed page (3000x3922 box) is drawn
                        // outside the viewport and COVERS the app bars (seen on the S6 Lite:
                        // when zooming in, the mode bar and the page bar disappeared).
                        Box(modifier = Modifier.fillMaxSize().clipToBounds().then(gestureMod)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.viewer_page_description, currentPage + 1, pageCount),
                                contentScale = ContentScale.FillBounds,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier
                                    // ponytail: size() coerces the size to the parent's constraints
                                    // (the viewport's fillMaxSize Box) and, when the zoom overflowed the
                                    // box, FillBounds stretched the bitmap to the viewport aspect (0.6)
                                    // instead of the page's (0.765) → ~27% vertical stretch at
                                    // k>1.28. requiredSize ignores the parent's constraints: the box
                                    // really grows and the page aspect is preserved.
                                    .requiredSize(
                                        with(density) { (pageFit.width * k).toDp() },
                                        with(density) { (pageFit.height * k).toDp() }
                                    )
                                    // The POSITION goes through graphicsLayer (DRAW transform), not
                                    // Modifier.offset (which goes through LAYOUT). With offset the page
                                    // was placed somewhere other than where the model says: the
                                    // content slid out from under the fingers while the ink and the
                                    // stamps (Canvas + withTransform, which IS a draw transform)
                                    // stayed where they should and seemed to slide off the music.
                                    .graphicsLayer {
                                        translationX = posX
                                        translationY = posY
                                    }
                            )
                            if (canEdit) {
                                // ink/stamps default to black (not the theme's bluish primary)
                                val themeColor = Color.Black
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    withTransform({
                                        translate(posX, posY)
                                        scale(k, k, pivot = Offset.Zero)
                                    }) {
                                        fun drawStrokePath(pts: List<Offset>, color: Color, widthPx: Float) {
                                            if (pts.size < 2) return
                                            val path = Path()
                                            path.moveTo(pts[0].x, pts[0].y)
                                            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                                            drawPath(
                                                path,
                                                color = color,
                                                style = DrawStroke(
                                                    width = max(1f, widthPx),
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                        }
                                        // pendingInk goes first: if the flow still carries the previous
                                        // version (after a merge), the one being written wins
                                        for (stroke in (pendingInk + pageStrokes).distinctBy { it.id }) {
                                            // the one being moved/stretched is drawn only at its
                                            // live position (otherwise two copies remain = ghost)
                                            if (dragStroke?.id == stroke.id) continue
                                            drawStrokePath(
                                                decodePoints(stroke.points).map { normalizedToPx(it, hitFit) },
                                                stampColor(stroke.color, themeColor),
                                                stroke.width * hitFit.height
                                            )
                                        }
                                        // selected stroke moved/stretched live
                                        dragStroke?.let { ds ->
                                            drawStrokePath(
                                                decodePoints(ds.points).map { normalizedToPx(it, hitFit) },
                                                stampColor(ds.color, themeColor),
                                                ds.width * hitFit.height
                                            )
                                        }
                                        if (inkPreview.size >= 2) {
                                            drawStrokePath(
                                                inkPreview,
                                                if (eraserTool) Color.Red.copy(alpha = 0.4f)
                                                else stampColor(activeColor, themeColor),
                                                if (eraserTool) ERASE_RADIUS_FRAC * hitFit.height * 2f
                                                else penWidth * hitFit.height
                                            )
                                        }
                                    }
                                }
                                // Stamps: SAME transform as the page and the ink (translate+scale
                                // from the origin) but on the DRAW path. They used to go through the
                                // LAYOUT path (Modifier.offset + Modifier.size), i.e. two
                                // mechanisms for the same transformation: that is why stamps and music
                                // drifted apart when zooming. size() is also the same modifier that
                                // broke the page in round 4 (requiredSize was needed there).
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            // mandatory: by default the origin is the CENTER and the
                                            // scale would be applied from there, moving everything
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            translationX = posX
                                            translationY = posY
                                            scaleX = k
                                            scaleY = k
                                            // no compositingStrategy: this way the scale is a canvas
                                            // transform and glyphs are re-rasterized sharp. With
                                            // Offscreen it is rasterized to a bitmap and that is scaled (blurry)
                                        }
                                ) {
                                    for (stamp in pageStamps) {
                                        val shown = dragStamp?.takeIf { it.id == stamp.id } ?: stamp
                                        val sym = runCatching { StampSymbol.valueOf(shown.symbol) }.getOrNull()
                                            ?: continue
                                        val r = stampRect(shown, hitFit)
                                        StampView(
                                            symbol = sym,
                                            // in fit units: the scale comes from the layer, not the
                                            // stamp size (so it is not applied twice)
                                            sizePx = r.width,
                                            color = stampColor(shown.color, Color.Black),
                                            modifier = Modifier
                                                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                                                .requiredSize(
                                                    with(density) { r.width.toDp() },
                                                    with(density) { r.height.toDp() }
                                                )
                                        )
                                    }
                                }
                                // selection box ON TOP of everything: otherwise the handles
                                // are hidden by the very stamp being edited
                                if (showSelection && selectionRect != null) {
                                    val sel = selectionRect
                                    val hr = handleRadiusPx(hitFit)
                                    val frame = Color(0xFF00A0FF)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        withTransform({
                                            translate(posX, posY)
                                            scale(k, k, pivot = Offset.Zero)
                                        }) {
                                            drawRect(
                                                color = frame,
                                                topLeft = Offset(sel.left, sel.top),
                                                size = Size(sel.width, sel.height),
                                                style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                            )
                                            handleCenters(sel, hitFit).forEach { hc ->
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f)
                                                )
                                                drawRect(
                                                    color = frame,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f),
                                                    style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (dimPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimPct / 100f))
            )
        }

        if (navVisible) {
            val surface = MaterialTheme.colorScheme.surface

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(surface)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.viewer_mode), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        halfEnabled = !halfEnabled
                        halfTurned = false
                    },
                    enabled = !editing,
                    colors = if (halfEnabled) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("½") }
                if (isLandscape) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = false },
                        enabled = !editing,
                        colors = if (!twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("1") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = true },
                        enabled = !editing,
                        colors = if (twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("2") }
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onToggleTheme) { Text(stringResource(themeMode.labelRes)) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        invert = !invert
                        Settings.setInvert(context, invert)
                    },
                    colors = if (invert) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text(stringResource(R.string.viewer_invert)) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        editing = !editing
                        if (editing) {
                            twoUp = false
                            halfEnabled = false
                            halfTurned = false
                        } else {
                            undoStack = emptyList()
                            selectedId = null
                            selectedStrokeId = null
                            dragStamp = null
                            dragStroke = null
                            drawingTool = false
                            eraserTool = false
                            inkPreview = emptyList()
                            hiddenStampIds = emptySet()
                            hiddenStrokeIds = emptySet()
                        }
                    },
                    enabled = canEdit,
                    colors = if (editing) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text(stringResource(R.string.action_edit)) }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(surface)
            ) {
                if (showDim) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.viewer_dim), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = dimPct,
                            onValueChange = { dimPct = it },
                            onValueChangeFinished = { Settings.setDimPct(context, dimPct) },
                            valueRange = 0f..80f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )
                        Text(stringResource(R.string.percent_value, dimPct.toInt()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StampSymbol.entries.forEach { sym ->
                            StampPaletteButton(
                                symbol = sym,
                                selected = sym == activeSymbol,
                                onClick = {
                                    activeSymbol = sym
                                    drawingTool = false
                                    eraserTool = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // transparent swatch = no tint (theme color)
                        ColorSwatchButton(
                            color = Color.Transparent,
                            selected = activeColor == null,
                            onClick = {
                                val s = selectedStamp
                                activeColor = null
                                if (s != null && s.color != null) {
                                    pushUndo()
                                    editScope.launch { stampRepo.update(s.copy(color = null)) }
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        SWATCH_COLORS.forEach { c ->
                            ColorSwatchButton(
                                color = c,
                                selected = activeColor == c.toArgb(),
                                onClick = {
                                    activeColor = c.toArgb()
                                    val s = selectedStamp
                                    if (s != null && s.color != c.toArgb()) {
                                        pushUndo()
                                        editScope.launch { stampRepo.update(s.copy(color = c.toArgb())) }
                                    }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                drawingTool = !drawingTool
                                if (drawingTool) {
                                    activeSymbol = null
                                    eraserTool = false
                                    selectedId = null
                                }
                            },
                            colors = if (drawingTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("✏️") }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                eraserTool = !eraserTool
                                if (eraserTool) {
                                    activeSymbol = null
                                    drawingTool = false
                                    selectedId = null
                                    selectedStrokeId = null
                                    inkPreview = emptyList()
                                }
                            },
                            colors = if (eraserTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("🩹") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val (page, edit) = undoStack.lastOrNull() ?: return@Button
                                undoStack = undoStack.dropLast(1)
                                selectedId = null
                                selectedStrokeId = null
                                dragStamp = null
                                dragStroke = null
                                inkTail.last = null
                                if (sheetHash != null) {
                                    editScope.launch {
                                        stampRepo.restorePage(sheetHash, page, edit.stamps)
                                        strokeRepo.restorePage(sheetHash, page, edit.strokes)
                                    }
                                }
                            },
                            enabled = undoStack.isNotEmpty()
                        ) { Text("↶") }
                        Spacer(Modifier.width(8.dp))
                        if (drawingTool) {
                            Text(stringResource(R.string.viewer_pen_width), style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = penWidth,
                                onValueChange = { penWidth = it },
                                valueRange = MIN_STROKE_WIDTH..MAX_STROKE_WIDTH,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text(stringResource(R.string.percent_value, (penWidth * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selectedStamp != null) {
                            val s = selectedStamp
                            var sliderUndoPushed by remember { mutableStateOf(false) }
                            Text(stringResource(R.string.viewer_stamp_size), style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = s.size.coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE),
                                onValueChange = { v ->
                                    val current = pageStamps.firstOrNull { it.id == selectedId }
                                    if (current != null && v != current.size) {
                                        if (!sliderUndoPushed) {
                                            pushUndo()
                                            sliderUndoPushed = true
                                        }
                                        editScope.launch { stampRepo.update(current.copy(size = v)) }
                                    }
                                },
                                onValueChangeFinished = { sliderUndoPushed = false },
                                valueRange = MIN_STAMP_SIZE..MAX_STAMP_SIZE,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text(stringResource(R.string.percent_value, (s.size * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = {
                                val s = selectedStamp
                                val t = selectedStroke
                                if (s == null && t == null) return@Button
                                pushUndo()
                                selectedId = null
                                selectedStrokeId = null
                                inkTail.last = null
                                hiddenStampIds = hiddenStampIds + listOfNotNull(s?.id)
                                hiddenStrokeIds = hiddenStrokeIds + listOfNotNull(t?.id)
                                editScope.launch {
                                    s?.let { stampRepo.delete(it) }
                                    t?.let { strokeRepo.delete(it) }
                                }
                            },
                            enabled = selectedStamp != null || selectedStroke != null
                        ) { Text("🗑") }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = applyPrev,
                        enabled = branch.canPrev
                    ) { Text("◀") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        branch.label,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onBack) { Text("✕") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { showDim = !showDim },
                        colors = if (showDim) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("◐") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = applyNext,
                        enabled = branch.canNext
                    ) { Text("▶") }
                }
            }
        }

    }
}