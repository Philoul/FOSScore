package com.musigatto.fosscore.ocr

import android.content.Context
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.IOException

/** A gray picture: a byte per pixel (0 black, 255 white), rows top down. */
class GrayCrop(val width: Int, val height: Int, val pixels: ByteArray)

/** Reads the text of a picture of one line: a tempo mark, a title, a measure number. */
interface TextReader : AutoCloseable {
    /** The text [crop] shows (one line, drawn at [dpi]), cleaned for its [kind]; null when nothing is read. */
    fun readLine(crop: GrayCrop, dpi: Int, kind: TextKind = TextKind.LINE): String?
}

/**
 * [TextReader] by Tesseract, with tessdata_fast's English, French, Italian and German data (the
 * languages of the words over the music: "Allegro", "Langsam", "Rall."). Not thread-safe by
 * itself: one line at a time.
 */
class TesseractReader private constructor(private val api: TessBaseAPI) : TextReader {

    @Synchronized
    override fun readLine(crop: GrayCrop, dpi: Int, kind: TextKind): String? = try {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, if (kind == TextKind.DIGITS) DIGITS else "")
        api.setVariable("user_defined_dpi", dpi.toString())
        api.setImage(crop.pixels, crop.width, crop.height, 1, crop.width)
        api.utF8Text?.let { OcrText.clean(it, kind) }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "line not read: ${e.message}")
        null
    } finally {
        api.clear()
    }

    @Synchronized
    override fun close() = api.recycle()

    companion object {
        private const val LOG_TAG = "FOSScore-Ocr"
        private const val DIGITS = "0123456789"

        /** The languages read, as tessdata names them: the build ships their data. */
        const val LANGUAGES = "eng+fra+ita+deu"

        /**
         * The reader, its language data installed from the assets into [Context.getFilesDir] the
         * first time (Tesseract reads files); null when it cannot start. Blocking: off the main thread.
         */
        fun open(context: Context): TesseractReader? = try {
            val root = File(context.filesDir, "ocr")
            install(context, File(root, "tessdata"))
            val api = TessBaseAPI()
            if (api.init(root.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) {
                TesseractReader(api)
            } else {
                api.recycle()
                Log.e(LOG_TAG, "Tesseract did not start")
                null
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "language data not installed: ${e.message}", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            Log.e(LOG_TAG, "no Tesseract for this device: ${e.message}", e)
            null
        }

        /**
         * Copies each language's file from the assets, once for each version of the app (the data
         * may change with it): aside, then renamed, so a copy cut short is never taken for one.
         */
        private fun install(context: Context, dir: File) {
            dir.mkdirs()
            @Suppress("DEPRECATION")
            val app = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val done = File(dir, "installed")
            if (done.isFile && done.readText() == app.toString()) return
            for (name in context.assets.list("tessdata").orEmpty()) {
                val part = File(dir, "$name.part")
                context.assets.open("tessdata/$name").use { input -> part.outputStream().use { input.copyTo(it) } }
                if (!part.renameTo(File(dir, name))) throw IOException("could not install $name")
            }
            done.writeText(app.toString())
        }
    }
}
