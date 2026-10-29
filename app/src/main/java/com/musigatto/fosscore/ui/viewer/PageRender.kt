package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import com.musigatto.fosscore.R
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.pdf.MuPdfDoc
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.PageSource
import com.musigatto.fosscore.score.ScorePrecompute
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

// the same tag the viewer logs its draws with: one grep finds the document's whole story
private const val LOG_TAG = "FOSScore-PDF"

// Re-draw during a pinch: minimum gap between draws (150 ms) and a settling wait for the final
// draw (250 ms with no zoom change). Reference viewers also draw during the gesture; kept to a
// minimum, the bitmap follows the zoom and the GPU never rescales the texture.
private const val RENDER_DURING_ZOOM_MS = 150L
private const val RENDER_SETTLE_MS = 250L

/**
 * The paper on screen: the document, its bitmaps at the current zoom step, the last one drawn (so a
 * page turn does not flash a spinner), the cut of a half page, and the mutex MuPDF needs.
 *
 * The mutex is not optional: MuPDF is not thread-safe either (it shares a global Context), so it
 * serializes draw/close, and a cancelled draw (not cancellable mid-page) cannot clash with the next
 * one or with close().
 *
 * ponytail: one mutex per document is enough; if there is ever more than one document at a time,
 * move it to FOSScoreApp. The bitmap cache lives in MuPdfDoc.
 */
internal class PagePaper(private val pdfUri: Uri) {

    private val lock = Mutex()

    /** Why the page is not there: shown in place of it. */
    var error by mutableStateOf<Int?>(null)

    var pageCount by mutableIntStateOf(0)
        private set

    // SnapshotStateMap: changing one bitmap only redraws whoever reads THAT key. With a plain
    // Map, changing a bitmap invalidated the Image + the ink Canvas + all the stamps at once
    // (that was the jolt felt when the sharpness changed mid-pinch).
    val bitmaps = mutableStateMapOf<Int, Bitmap>()

    // bitmap draw step: the bitmap is drawn at fit × renderScale (1:1 on screen, no GPU
    // rescaling). The gesture feeds it through ZoomMath.renderBucket.
    var renderScale by mutableFloatStateOf(1f)

    // last page that was actually drawn: while the next one renders (~300 ms, plus the display
    // list parse) the old page stays on screen instead of a spinner flash.
    var lastShown by mutableStateOf<Bitmap?>(null)

    // in half page, the cut of the next turn: the dotted line shows it and the turn makes it, so
    // both always agree
    var halfCut by mutableStateOf<HalfCut?>(null)

    // the blank bands of a page, read from its picture once
    private val blankBands = WeakHashMap<Bitmap, List<Band>>()

    // guards against an older, non-cancellable render landing after a newer one
    private var generation = 0

    /**
     * Opens the document and works out its page count, off the main thread: opening parses the PDF
     * index. Null when it could not be opened ([error] then says why).
     */
    suspend fun open(context: Context, format: SheetFormat): PageSource? {
        try {
            // NonCancellable: if the user leaves while we are opening, the document is created
            // anyway, and we close it below. Cancelling here would leave the native doc hanging
            // (and its memory too).
            val doc: PageSource = withContext(NonCancellable + Dispatchers.IO) {
                if (format.isMusicXml) MusicXmlPages.open(context, File(pdfUri.path.orEmpty()))
                else MuPdfDoc.open(context, pdfUri)
            }
            if (coroutineContext[Job]?.isActive == false) {
                withContext(NonCancellable + Dispatchers.IO) { doc.close() }
                return null
            }
            pageCount = doc.pageCount
            ScorePrecompute.opened(context, pdfUri.toString())
            return doc
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(LOG_TAG, "no se pudo abrir el archivo: ${e.message}", e)
            error = R.string.err_open_file
            return null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo abrir: ${e::class.simpleName}: ${e.message}", e)
            error = if (format.isMusicXml) R.string.err_open_file else R.string.err_invalid_pdf
            return null
        }
    }

    /** The score was laid out again: its pages changed under the same numbers. */
    fun pagesRelaidOut(count: Int) {
        pageCount = count
        bitmaps.clear()
    }

    /**
     * Lays the music out again under the same lock as the draws: it is the same document, and a
     * layout landing in the middle of a draw is the kind of clash the mutex is there for.
     */
    suspend fun layoutPages(pages: MusicXmlPages, spec: LayoutSpec): Boolean =
        lock.withLock { withContext(Dispatchers.Default) { pages.layout(spec) } }

    /**
     * Draws [needed] (one page, or the two of a spread) at the current zoom step. A draw that fails
     * leaves on screen what was already there: faint beats a hole.
     */
    suspend fun draw(
        doc: PageSource,
        needed: List<Int>,
        page: Int,
        viewport: IntSize,
        showTwoUp: Boolean,
        spread: Boolean,
    ) {
        val gen = ++generation
        // read Compose state BEFORE going into the draw dispatcher
        val scale = renderScale
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        val fallback = needed.associateWith { bitmaps[it] }
        try {
            val session = lock.withLock {
                withContext(Dispatchers.Default) {
                    // Single page: the draw slot is the fit × zoom step (the bitmap is shown 1:1 on
                    // screen, the GPU rescales nothing). Reading modes (two-up, half page): the slot
                    // is the viewport and the layer keeps scaling.
                    val slot = if (spread) {
                        Pair(if (showTwoUp) viewport.width / 2 else viewport.width, viewport.height)
                    } else {
                        // the real page size may not be loaded yet (pages past the ones loaded up
                        // front): it is resolved inside the lock with loadSize (a cheap read of the
                        // page dictionary), the same path the draw takes for its fit
                        val box = doc.ensurePageSize(page)
                        if (box == null || box.height <= 0f) return@withContext emptyMap()
                        ZoomMath.renderSlot(fitRect(vpW, vpH, box.width / box.height), scale)
                    }
                    val out = mutableMapOf<Int, Bitmap>()
                    for (idx in needed) out[idx] = doc.render(idx, slot.first, slot.second) ?: fallback[idx] ?: continue
                    out
                }
            }
            if (gen != generation) return
            for ((idx, bmp) in session) bitmaps[idx] = bmp
            // keep the page on screen while the next one renders (see lastShown)
            bitmaps[page]?.let { lastShown = it }
            // we do not pile up bitmaps of pages that are no longer on screen (16 MB each when sharp)
            bitmaps.keys.filterNot { it in needed }.forEach { bitmaps.remove(it) }
            // no bitmap for this page means nothing to show: say so instead of leaving the spinner
            // turning forever (it happened with a blank page). Only for a render made for this page:
            // a MusicXML score laid out again (two pages -> one) changes the page numbers while an
            // older render finishes, and the newer one is on its way.
            if (page in needed && bitmaps[page] == null) {
                Log.e(LOG_TAG, "la página $page no se pudo renderizar")
                error = R.string.err_render_page
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render failed: ${e::class.simpleName}: ${e.message}", e)
            if (gen == generation) error = R.string.err_render_page
        }
    }

    /**
     * Prefetches both neighbours, so a turn is a cache hit in MuPdfDoc (same page, same width key),
     * and works out the cut of the next half page from this page and the next one just drawn.
     * Returns at once when a turn supersedes it, instead of holding the lock for nothing.
     */
    suspend fun prefetch(
        doc: PageSource,
        page: Int,
        viewport: IntSize,
        showHalf: Boolean,
        layoutVersion: Int,
    ) {
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        val shown = bitmaps[page]
        halfCut = lock.withLock {
            withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                fun draw(index: Int) = doc.ensurePageSize(index)?.takeIf { it.height > 0f }?.let { box ->
                    val slot = ZoomMath.renderSlot(fitRect(vpW, vpH, box.width / box.height), 1f)
                    doc.render(index, slot.first, slot.second)
                }
                for (next in listOf(page + 1, page - 1)) {
                    if (next !in 0 until pageCount) continue
                    // a turn supersedes this prefetch: drop the rest instead of holding the lock
                    if (job?.isActive == false) return@withContext null
                    draw(next)
                }
                // in half page, the cut of the next turn, from this page and the next one just drawn
                shown?.takeIf { showHalf && page + 1 < pageCount }?.let { here ->
                    draw(page + 1)?.let { next ->
                        val bands = here.blankBands()
                        val split = HalfSplit.of(bands, next.blankBands())
                        // the line goes through the middle of the blank band the cut is in, clear of
                        // the staves above and below: anywhere in it, the turn hides only paper
                        val mark = bands.firstOrNull { split.lower in it.start..it.end }?.let { (it.start + it.end) / 2 }
                        HalfCut(page, layoutVersion, split, mark ?: split.lower)
                    }
                }
            }
        }
    }

    /** Where a turned half page cuts: the cut worked out for this layout, or read from the pictures. */
    fun halfSplit(page: Int, layoutVersion: Int): HalfSplit {
        fun bands(index: Int) = bitmaps[index]?.let { bmp -> blankBands.getOrPut(bmp) { bmp.blankBands() } }.orEmpty()
        return halfCut?.takeIf { it.page == page && it.layout == layoutVersion }?.split
            ?: HalfSplit.of(bands(page), bands(page + 1))
    }

    /**
     * Frees the document, after whatever draw is in flight. Never on the main thread: a draw in
     * flight is not cancellable, and waiting for it there is an ANR.
     */
    fun close(doc: PageSource, scope: CoroutineScope) {
        scope.launch { lock.withLock { doc.close() } }
    }
}

@Composable
internal fun rememberPagePaper(pdfUri: Uri): PagePaper = remember(pdfUri) { PagePaper(pdfUri) }

/**
 * The paper's effects: the redraw at the current zoom step, the settling one of a pinch, the
 * neighbours drawn ahead, and the cut of a half page. [paper] holds what they produce.
 */
@Composable
internal fun PageRenderEffects(
    paper: PagePaper,
    doc: PageSource?,
    page: Int,
    viewport: IntSize,
    layoutVersion: Int,
    showTwoUp: Boolean,
    showHalf: Boolean,
    halfTurned: Boolean,
    spread: Boolean,
    zoom: ZoomState,
) {
    // zoom back to zero when the page or the reading mode changes (the draw step too: otherwise
    // paging would draw every page zoomed in)
    LaunchedEffect(page, showTwoUp, showHalf, halfTurned) {
        zoom.reset()
        paper.renderScale = 1f
    }

    // Redraw at the current zoom (steps of 10% of the fit, ZoomMath.renderBucket): like reference
    // viewers, the bitmap follows the zoom so the GPU never rescales it. During a pinch it only
    // goes UP a step, with a 150 ms minimum between draws; once it settles (no scale change for
    // 250 ms) the final step is tuned in, which can also go DOWN after zooming out.
    LaunchedEffect(doc, viewport) {
        var lastUpgradeMs = 0L
        snapshotFlow { zoom.transform.scale }.collectLatest { s ->
            val target = ZoomMath.renderBucket(s)
            val now = System.currentTimeMillis()
            if (target > paper.renderScale && now - lastUpgradeMs >= RENDER_DURING_ZOOM_MS) {
                lastUpgradeMs = now
                paper.renderScale = target
                return@collectLatest
            }
            delay(RENDER_SETTLE_MS.milliseconds)
            // if the scale kept changing while we waited, the next emission picks it up
            if (target >= ZoomMath.renderBucket(zoom.transform.scale)) paper.renderScale = target
        }
    }

    // viewport matters: on the first frame it is still IntSize.Zero, and with no size there is no draw
    LaunchedEffect(
        doc, page, showTwoUp, showHalf, halfTurned, paper.renderScale, viewport, layoutVersion,
    ) {
        val r = doc ?: return@LaunchedEffect
        // a MusicXML score has no pages until it is laid out (the screen's effect)
        if (r is MusicXmlPages && r.pageCount == 0) return@LaunchedEffect
        val needed = pagesToDraw(showTwoUp, showHalf, halfTurned, page, paper.pageCount)
        paper.draw(r, needed, page, viewport, showTwoUp, spread)
    }

    // The delay gives a quick turn the lock: turning before it fires cancels this effect. Only at
    // 1x: a turn resets the zoom, so a draw at a zoomed slot would never be reused. It waits for
    // this page's own picture, which the cut of a half page is worked out from.
    LaunchedEffect(
        doc, page, viewport, paper.renderScale, showTwoUp, showHalf, layoutVersion,
        paper.bitmaps[page] != null,
    ) {
        val r = doc ?: return@LaunchedEffect
        if (spread) return@LaunchedEffect
        if (paper.renderScale != 1f || viewport == IntSize.Zero) return@LaunchedEffect
        delay(RENDER_SETTLE_MS.milliseconds)
        paper.prefetch(r, page, viewport, showHalf, layoutVersion)
    }
}
