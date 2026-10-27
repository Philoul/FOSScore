package com.musigatto.fosscore.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Rect as AndroidRect
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

// inverts page colors (white bg -> black, ink -> white) for OLED dark mode; applied as a
// draw-time colorFilter so the render cache is untouched.
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)
private val INVERT_FILTER = ColorFilter.colorMatrix(ColorMatrix(INVERT_MATRIX))

// ink and stamps default to black (not the theme's bluish primary); with "Neg" they are inverted
// like the page, or black ink would disappear on the now-black background
internal fun inkTint(color: Int?, invert: Boolean): Color {
    val c = stampColor(color, Color.Black)
    return if (invert) c.inverted() else c
}

// the same negative as INVERT_MATRIX, for what is drawn with a color instead of a bitmap
internal fun Color.inverted(): Color = Color(1f - red, 1f - green, 1f - blue, alpha)

internal fun inkPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.size < 2) return path
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
    return path
}

private fun DrawScope.drawInkPath(pts: List<Offset>, color: Color, widthPx: Float) {
    if (pts.size < 2) return
    drawPath(
        inkPath(pts),
        color = color,
        style = DrawStroke(width = max(1f, widthPx), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * The page itself: paper, playback cursor, half-page cut line, ink, stamps, selection and the loupe.
 * It only draws what it is given; the reading modes and the fit rect are decided by the viewer.
 *
 * [scale], [offsetX] and [offsetY] are lambdas on purpose: they change on every pinch or pan frame
 * and are read ONLY inside draw/layout lambdas, so a zoom never recomposes or re-measures this.
 */
@Composable
internal fun PageCanvas(
    bitmaps: SnapshotStateMap<Int, Bitmap>,
    currentPage: Int,
    pageCount: Int,
    lastShown: Bitmap?,
    branch: PageFlow,
    halfTurned: Boolean,
    halfSplit: () -> HalfSplit,
    above: (PageGeometry, String, Float) -> Boolean,
    pageFit: RectPx,
    hitFit: RectPx,
    scale: () -> Float,
    offsetX: () -> Float,
    offsetY: () -> Float,
    invert: Boolean,
    pageSource: PageSource?,
    cursorNotes: List<String>,
    halfCut: HalfCut?,
    layoutVersion: Int,
    canEdit: Boolean,
    inkDraws: List<InkDraw>,
    dragStrokes: List<Stroke>?,
    inkPreview: List<Offset>,
    eraserTool: Boolean,
    activeColor: Int?,
    eraserPx: Float,
    penWidthPx: Float,
    pageStamps: List<Stamp>,
    dragStamp: Stamp?,
    selectionRect: RectPx?,
    strokeSelection: Boolean,
    showSelection: Boolean,
    loupeAt: Offset?,
    placePx: Float,
    spreadMod: Modifier,
    gestureMod: Modifier,
) {
    when {
        branch.showTwoUp -> {
            Row(modifier = spreadMod.then(gestureMod)) {
                for (index in listOf(currentPage, currentPage + 1)) {
                    val bmp = bitmaps[index] ?: continue
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (invert) INVERT_FILTER else null,
                            modifier = Modifier.fillMaxSize()
                        )
                        val geo = (pageSource as? MusicXmlPages)?.geometry(index)
                        if (geo != null && cursorNotes.isNotEmpty()) {
                            FitPlaybackCursor(geo, cursorNotes, bmp.width, bmp.height, Modifier.fillMaxSize())
                        }
                    }
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
            val split = halfSplit()
            // remember keyed on the BITMAPS (not on the map): with a SnapshotStateMap,
            // reading the keys above already registers the dependency, so this only
            // recomposes if they change
            val combined = remember(cur, next, w, h, split) {
                val out = createBitmap(w, h)
                val canvas = android.graphics.Canvas(out)
                val paint = android.graphics.Paint().apply { isFilterBitmap = true }
                // a MusicXML page cuts between systems: blank paper between the two parts
                canvas.drawColor(android.graphics.Color.WHITE)
                val upper = (h * split.upper).toInt()
                val lower = (h * split.lower).toInt()
                canvas.drawBitmap(next, AndroidRect(0, 0, next.width, (next.height * split.upper).toInt()), AndroidRect(0, 0, w, upper), paint)
                canvas.drawBitmap(cur, AndroidRect(0, (cur.height * split.lower).toInt(), cur.width, cur.height), AndroidRect(0, lower, w, h), paint)
                paint.color = sepColor
                paint.strokeWidth = (h / 200f).coerceAtLeast(2f)
                canvas.drawLine(0f, lower.toFloat(), w.toFloat(), lower.toFloat(), paint)
                out
            }
            Box(spreadMod.then(gestureMod)) {
                Image(
                    bitmap = combined.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (invert) INVERT_FILTER else null,
                    modifier = Modifier.fillMaxSize()
                )
                // the cursor, on whichever part the music is in
                val pages = pageSource as? MusicXmlPages
                if (pages != null && cursorNotes.isNotEmpty()) {
                    pages.geometry(currentPage + 1)?.let { geo ->
                        FitPlaybackCursor(geo, cursorNotes.filter { above(geo, it, split.upper) }, w, h, Modifier.fillMaxSize())
                    }
                    pages.geometry(currentPage)?.let { geo ->
                        FitPlaybackCursor(geo, cursorNotes.filter { !above(geo, it, split.lower) }, w, h, Modifier.fillMaxSize())
                    }
                }
            }
        }
        else -> {
            (bitmaps[currentPage] ?: lastShown)?.let { bmp ->
                // Single page: the bitmap is drawn at the current zoom (fit × scale, the
                // same size it takes on screen) and shown 1:1 — the GPU rescales nothing.
                // The overlay (ink/stamps/selection) is drawn in fit coordinates and the
                // zoom is applied inside the drawing with translate+scale: vector, redrawn
                // every frame, sharp at any zoom.
                // scale/offset are read ONLY in draw/measure lambdas below: reading them
                // here recomposed the whole screen on every pinch or pan frame.
                val density = LocalDensity.current
                // clipToBounds: without it the zoomed page (a 3000x3922 box) is drawn
                // outside the viewport and COVERS the app bars (seen on the test device:
                // zooming in made the mode bar and the page bar disappear).
                Box(modifier = Modifier.fillMaxSize().clipToBounds()
                    // Loupe: on a long press with a stamp armed (see the gesture) the whole
                    // scene is drawn again, magnified, inside a circle above the finger.
                    // drawContent() twice is the trick: the magnified view is exactly what
                    // is on screen (paper, ink, stamps, selection), not a second renderer
                    // that only knows about the bitmap.
                    .drawWithContent {
                        drawContent()
                        val at = loupeAt ?: return@drawWithContent
                        val margin = LOUPE_MARGIN_DP.dp.toPx()
                        val radius = LOUPE_RADIUS_DP.dp.toPx()
                        val center = loupeCenter(at, radius, margin, size)
                        // the footprint the stamp will take (screen px * zoom)
                        val k = scale()
                        clipPath(Path().apply { addOval(Rect(center = center, radius = radius)) }) {
                            withTransform({
                                translate(center.x, center.y)
                                scale(2f, 2f, pivot = Offset.Zero)
                                translate(-at.x, -at.y)
                            }) {
                                this@drawWithContent.drawContent()
                                drawRect(
                                    color = Color(0xFF00A0FF),
                                    topLeft = Offset(at.x - placePx * k / 2f, at.y - placePx * k / 2f),
                                    size = Size(placePx * k, placePx * k),
                                    style = DrawStroke(width = 2f)
                                )
                            }
                        }
                        // two rings: they have to read on white paper and in dark mode
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.45f),
                            radius = radius + 1.dp.toPx(),
                            center = center,
                            style = DrawStroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White,
                            radius = radius,
                            center = center,
                            style = DrawStroke(width = 2.dp.toPx())
                        )
                    }
                    .then(gestureMod)) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.desc_page, currentPage + 1, pageCount
                            ),
                            contentScale = ContentScale.FillBounds,
                            colorFilter = if (invert) INVERT_FILTER else null,
                            // The page is laid out at fit size and the zoom goes through
                            // graphicsLayer (a DRAW transform): reading scale/offset inside
                            // that lambda keeps a pinch from recomposing or re-measuring
                            // anything. At rest scale == the render step, so the factor is
                            // exactly 1 and the bitmap stays 1:1 (no resampling). POSITION
                            // has to be a draw transform, not Modifier.offset (LAYOUT): with
                            // offset the page slid out from under the ink and the stamps.
                            modifier = Modifier
                                .size(
                                    with(density) { pageFit.width.toDp() },
                                    with(density) { pageFit.height.toDp() }
                                )
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = scale()
                                    scaleY = scale()
                                    translationX = pageFit.left + offsetX()
                                    translationY = pageFit.top + offsetY()
                                }
                        )
                    // the playback cursor, at the page's place and zoom
                    val cursorGeo = (pageSource as? MusicXmlPages)?.geometry(currentPage)
                    if (cursorGeo != null && cursorNotes.isNotEmpty() && bitmaps[currentPage] != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawPlaybackCursor(
                                cursorGeo, cursorNotes, pageFit.left + offsetX(), pageFit.top + offsetY(),
                                pageFit.width * scale()
                            )
                        }
                    }
                    // in half page, a thin dotted line where the next turn cuts this page;
                    // a page cut through its music (no blank band) only gets a tick in
                    // each margin, so the line does not cross out a staff
                    halfCut?.takeIf { it.page == currentPage && it.layout == layoutVersion && branch.showHalf && !halfTurned }?.let { cut ->
                        val cutColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val left = pageFit.left + offsetX()
                            val right = left + pageFit.width * scale()
                            val y = pageFit.top + offsetY() + pageFit.height * scale() * cut.mark
                            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                            val spans = if (cut.split.onBlank) listOf(left to right)
                            else CUT_TICK_DP.dp.toPx().let { tick -> listOf(left to left + tick, right - tick to right) }
                            spans.forEach { (from, to) ->
                                drawLine(cutColor, Offset(from, y), Offset(to, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                            }
                        }
                    }
                    // the overlay belongs to currentPage: while lastShown is still the
                    // previous page's paper, its ink would land on the wrong music
                    if (canEdit && bitmaps[currentPage] != null) {
                        // committed ink: paths already built (inkDraws), so this canvas
                        // only re-issues them when something redraws the page
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            withTransform({
                                translate(pageFit.left + offsetX(), pageFit.top + offsetY())
                                scale(scale(), scale(), pivot = Offset.Zero)
                            }) {
                                for (d in inkDraws) {
                                    // the ink being moved/stretched is drawn only at its
                                    // live position (otherwise two copies = a ghost)
                                    if (dragStrokes?.any { it.id == d.id } == true) continue
                                    drawPath(
                                        d.path,
                                        d.color,
                                        style = DrawStroke(
                                            width = max(1f, d.width),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                        }
                        // live ink (the stroke being written, the eraser trail, the word
                        // being moved) in its own canvas: a pen point only redraws this one
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            withTransform({
                                translate(pageFit.left + offsetX(), pageFit.top + offsetY())
                                scale(scale(), scale(), pivot = Offset.Zero)
                            }) {
                                dragStrokes?.forEach { ds ->
                                    drawInkPath(
                                        strokePointsPx(ds, hitFit),
                                        inkTint(ds.color, invert),
                                        ds.width * hitFit.height
                                    )
                                }
                                if (inkPreview.size >= 2) {
                                    drawInkPath(
                                        inkPreview,
                                        if (eraserTool) Color.Red.copy(alpha = 0.4f)
                                        else inkTint(activeColor, invert),
                                        if (eraserTool) eraserPx * 2f
                                        else penWidthPx
                                    )
                                }
                            }
                        }
                        // Stamps: the SAME transform as the page and the ink (translate+scale
                        // from the origin) but on the DRAW path. They used to go through the
                        // LAYOUT path (Modifier.offset + Modifier.size), two mechanisms for
                        // the same transform: that is why stamps and music drifted apart when
                        // zooming. size() is also the modifier that broke the page before
                        // (requiredSize was needed there).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // required: the default origin is the CENTRE and the
                                    // scale would apply from there, moving everything
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    translationX = pageFit.left + offsetX()
                                    translationY = pageFit.top + offsetY()
                                    scaleX = scale()
                                    scaleY = scale()
                                    // no compositingStrategy: that way the scale is a canvas
                                    // transform and the glyphs are redrawn sharp. With Offscreen
                                    // it rasterizes to a bitmap and scales that (blurry)
                                }
                        ) {
                            for (stamp in pageStamps) {
                                val shown = dragStamp?.takeIf { it.id == stamp.id } ?: stamp
                                val sym = runCatching { StampSymbol.valueOf(shown.symbol) }.getOrNull()
                                    ?: continue
                                val r = stampRect(shown, hitFit)
                                StampView(
                                    symbol = sym,
                                    // in fit units: the layer applies the scale, not the
                                    // stamp size (otherwise it is applied twice)
                                    sizePx = r.width,
                                    color = inkTint(shown.color, invert),
                                    modifier = Modifier
                                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                                        .requiredSize(
                                            with(density) { r.width.toDp() },
                                            with(density) { r.height.toDp() }
                                        )
                                )
                            }
                        }
                        // selection box ON TOP of everything: otherwise it ends up hidden by
                        // the very stamp being edited. Stamps get the frame only, no corner
                        // handles: their size is the slider (a corner drag resized them by
                        // accident); strokes still stretch from the corners.
                        val sel = selectionRect
                        if (showSelection && sel != null) {
                            val hr = handleRadiusPx(hitFit)
                            val frame = Color(0xFF00A0FF)
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                withTransform({
                                    translate(pageFit.left + offsetX(), pageFit.top + offsetY())
                                    scale(scale(), scale(), pivot = Offset.Zero)
                                }) {
                                    drawRect(
                                        color = frame,
                                        topLeft = Offset(sel.left, sel.top),
                                        size = Size(sel.width, sel.height),
                                        style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                    )
                                    if (strokeSelection) {
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
                }
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
