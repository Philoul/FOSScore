package com.musigatto.fosscore.pdf

import android.content.Context as AndroidContext
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.musigatto.fosscore.R
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.sqrt

private const val LOG_TAG = "FOSScore-MuPDF"

// Memory ceiling per bitmap (4M px = 16 MB). This caps zoom quality: above it the process eats
// the RAM and the system kills it. Upgrade path: render only the visible rectangle (tiles)
// instead of the whole page, as serious viewers do.
const val MAX_RENDER_PIXELS = 4_000_000L

// display lists kept in memory: they allow re-rendering at another resolution without re-parsing
private const val MAX_CACHED_LISTS = 3

// already-rendered bitmaps keyed by (page, width in px)
private const val MAX_CACHED_BITMAPS = 4

// pages whose size is preloaded on open, so layout can happen without waiting for the first render
private const val PRELOAD_SIZES = 4

// below this (in points) the display list is considered to have "no usable bounds"
private const val MIN_PAGE_DIM = 0.05f

/** Page box in PDF points. */
data class PageBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/**
 * PDF document opened with MuPDF.
 *
 * The whole API is **synchronous and not thread-safe** (MuPDF's `Context` is global): the caller
 * must serialize access with a mutex, just as was done with `PdfRenderer`.
 */
class MuPdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val stream: FdSeekable,
    private val doc: Document
) {
    val pageCount: Int = doc.countPages()

    // read from the main thread (pageSize) and written from the render dispatcher
    private val sizes = ConcurrentHashMap<Int, PageBox>()
    private val lists = object : LinkedHashMap<Int, DisplayList>(MAX_CACHED_LISTS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DisplayList>?): Boolean {
            if (size <= MAX_CACHED_LISTS) return false
            eldest?.value?.destroy()
            return true
        }
    }
    private val bitmaps = object : LinkedHashMap<Long, Bitmap>(MAX_CACHED_BITMAPS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
            size > MAX_CACHED_BITMAPS
    }

    /**
     * Page size in points. **Pure memory read** (safe from the main thread): only returns
     * something if we already preloaded it on open or if it was rendered.
     */
    fun pageSize(index: Int): PageBox? = sizes[index]

    /**
     * Same as [pageSize] but, if the size is not loaded yet (pages beyond the preload), loads it
     * now. Since it touches MuPDF ([loadSize]), call it inside the viewer's render Mutex.
     */
    fun ensurePageSize(index: Int): PageBox? = sizes[index] ?: loadSize(index)

    /**
     * Rasterizes the page at the requested size (px), honouring [MAX_RENDER_PIXELS].
     * Returns the cached bitmap if that resolution was already rendered.
     */
    /**
     * Rasterizes the page so that it fills [slotW]x[slotH] px (the slot it is fitted into), with
     * [quality] = 1 to 3 (multiplier for zoom). Fits to the box internally, so the caller does
     * not need to know the page size.
     */
    fun render(index: Int, slotW: Int, slotH: Int, quality: Int): Bitmap? {
        val list = lists[index] ?: displayList(index) ?: return null
        // CAREFUL: the size comes from the DISPLAY LIST, not page.getBounds(): toDisplayList()
        // applies the page transform (/Rotate rotation and Y-axis flip), so its bounds are those
        // of the already-upright page in y-down space.
        val dl = list.getBounds()
        val fromList = PageBox(dl.x0, dl.y0, dl.x1, dl.y1)
        // a blank page gives a display list with INFINITE bounds: if one slips by, the scale is
        // w/inf = 0 and the pixmap is born empty. Fall back to the page dictionary's size.
        val box = if (fromList.width.isFinite() && fromList.height.isFinite() &&
            fromList.width > MIN_PAGE_DIM && fromList.height > MIN_PAGE_DIM
        ) {
            sizes[index] = fromList
            fromList
        } else {
            Log.w(LOG_TAG, "página $index: display list sin bounds (¿en blanco?), uso MediaBox")
            sizes[index] ?: loadSize(index) ?: return null
        }
        if (slotW <= 0 || slotH <= 0) return null
        val pw = box.width
        val ph = box.height
        if (!pw.isFinite() || !ph.isFinite() || pw <= 0f || ph <= 0f) return null

        // fit into the slot (same rule as fitRect in the UI) times the quality multiplier
        val fitW = min(slotW.toFloat(), slotH * (pw / ph))
        var w = (fitW * quality).toInt().coerceAtLeast(1)
        // memory budget: the actual pixmap will be w x (w*ph/pw)
        val realH = w * ph / pw
        if (w.toLong() * realH.toLong() > MAX_RENDER_PIXELS) {
            w = sqrt(MAX_RENDER_PIXELS.toDouble() * pw.toDouble() / ph.toDouble())
                .toInt().coerceIn(1, w)
        }
        val key = index.toLong() * 1_000_000L + w
        bitmaps[key]?.let { return it }

        // y-down on both sides: positive scale, without the flip the viewer uses
        val scale = w / pw
        val m = Matrix(scale, 0f, 0f, scale, -box.x0 * scale, -box.y0 * scale)
        val t0 = System.nanoTime()
        var pix: Pixmap? = null
        try {
            // alpha=true: output is RGBA and the buffer is copied as-is into an ARGB_8888
            pix = list.toPixmap(m, ColorSpace.DeviceRGB, true)
            val rgba = Bitmap.createBitmap(pix.getWidth(), pix.getHeight(), Bitmap.Config.ARGB_8888)
            val samples = pix.getSamples()
            val stride = pix.getStride()
            val rowBytes = pix.getWidth() * 4
            if (stride == rowBytes) {
                rgba.copyPixelsFromBuffer(ByteBuffer.wrap(samples))
            } else {
                for (y in 0 until pix.getHeight()) {
                    rgba.copyPixelsFromBuffer(ByteBuffer.wrap(samples, y * stride, rowBytes))
                }
            }
            // the pixmap comes out with a TRANSPARENT paper: composite it onto white so the page is
            // opaque. Otherwise the "Neg" filter inverts the ink but not the background (whatever
            // is behind shows through, white in light theme) and the score ends up white on white.
            val bmp = Bitmap.createBitmap(rgba.width, rgba.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(bmp).drawBitmap(rgba, 0f, 0f, null)
            bmp.setHasAlpha(false)
            rgba.recycle()
            bitmaps[key] = bmp
            Log.d(
                LOG_TAG,
                "render p$index ${pix.getWidth()}x${pix.getHeight()} " +
                    "en ${(System.nanoTime() - t0) / 1_000_000}ms (hueco ${slotW}x$slotH)"
            )
            return bmp
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render falló en la página $index: ${e.message}", e)
            return null
        } finally {
            pix?.destroy()
        }
    }

    // Cheap size (parses only the page dictionary, does not run the content): enough to lay out
    // before the first render. On pages with /Rotate 90/270 the aspect comes out rotated until the
    // first render corrects it in sizes (one frame of off-centre overlay, nothing more).
    private fun loadSize(index: Int): PageBox? {
        if (index !in 0 until pageCount) return null
        val page = loadPage(index) ?: return null
        val b = try {
            page.getBounds()
        } finally {
            page.destroy()
        }
        return PageBox(b.x0, b.y0, b.x1, b.y1).also { sizes[index] = it }
    }

    // The display list includes the page content and annotations; it allows re-rasterizing at
    // another resolution without parsing again (this is what gives sharp zoom).
    private fun displayList(index: Int): DisplayList? {
        val page = loadPage(index) ?: return null
        return try {
            page.toDisplayList().also { lists[index] = it }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo preparar la página $index: ${e.message}", e)
            null
        } finally {
            page.destroy()
        }
    }

    private fun loadPage(index: Int): Page? =
        if (index in 0 until pageCount) {
            try {
                doc.loadPage(index)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "no se pudo cargar la página $index: ${e.message}", e)
                null
            }
        } else null

    /**
     * Forgets what is cached for a page (display list + bitmaps) so it is parsed and rendered
     * again. For after the PDF was edited outside (annotation export): otherwise the old bitmap
     * would hide the ink just saved. Call it under the viewer's mutex.
     */
    fun forgetPage(index: Int) {
        lists.remove(index)?.destroy()
        bitmaps.keys.filter { it / 1_000_000L == index.toLong() }.forEach { bitmaps.remove(it) }
    }

    /** Frees all. **Blocking**: call off the main thread (see FOSScoreApp.applicationScope). */
    fun close() {
        lists.values.forEach { runCatching { it.destroy() } }
        lists.clear()
        bitmaps.values.forEach { runCatching { it.recycle() } }
        bitmaps.clear()
        runCatching { doc.destroy() }
        runCatching { stream.close() }
        runCatching { fd.close() }
    }

    companion object {
        private var initialized = false

        /** Opens a PDF from a SAF Uri. Blocking: call from a background dispatcher. */
        fun open(context: AndroidContext, uri: Uri): MuPdfDoc {
            if (!initialized) {
                Context.init()
                initialized = true
            }
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException(context.getString(R.string.error_file_open))
            val stream = FdSeekable(fd)
            val doc = try {
                Document.openDocument(stream, "application/pdf")
            } catch (e: Exception) {
                runCatching { stream.close() }
                runCatching { fd.close() }
                throw e
            }
            return MuPdfDoc(fd, stream, doc).also { mupdf ->
                // sizes up front: the UI lays out without having to wait for the first render
                for (i in 0 until PRELOAD_SIZES) {
                    if (i >= mupdf.pageCount) break
                    runCatching { mupdf.loadSize(i) }
                }
            }
        }
    }
}

/**
 * MuPDF needs a seekable stream; the SAF PFD provides one. It stays open for the document's
 * whole lifetime because MuPDF reads lazily.
 * ponytail: `RandomAccessFile(FileDescriptor, ...)` does not exist in android.jar (it is desktop
 * only), so we read with FileInputStream and seek with its FileChannel: both share the offset of
 * the same descriptor, so reads and seeks stay consistent.
 */
private class FdSeekable(private val fd: ParcelFileDescriptor) : SeekableInputStream {
    private val fis = FileInputStream(fd.fileDescriptor)
    private val channel = fis.channel
    private var closed = false

    override fun read(buf: ByteArray): Int = fis.read(buf)

    override fun seek(pos: Long, whence: Int): Long {
        val target = when (whence) {
            SeekableStream.SEEK_CUR -> channel.position() + pos
            SeekableStream.SEEK_END -> channel.size() + pos
            else -> pos
        }
        channel.position(target)
        return channel.position()
    }

    override fun position(): Long = channel.position()

    fun close() {
        if (closed) return
        closed = true
        runCatching { fis.close() }
    }
}
