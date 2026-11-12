package com.musigatto.fosscore.ui.viewer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export writes annotations a PC's PDF reader lists as comments, into a copy: the score the
 * app reads must come out of it byte for byte.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** One blank A4 page, written by MuPDF itself so the source is a real PDF. */
    private fun blankPdf(name: String): File {
        val file = File(context.cacheDir, name)
        PDFDocument().apply {
            // addPage only builds the page: it enters the document with insertPage
            insertPage(0, addPage(Rect(0f, 0f, 612f, 792f), 0, null, ""))
            save(file.path, "")
            destroy()
        }
        return file
    }

    @Test
    fun exportWritesAnnotationsAndLeavesTheSourceUntouched() {
        val src = blankPdf("export-src.pdf")
        val before = src.readBytes()
        val dest = File(context.cacheDir, "export-dest.pdf")
        dest.delete()

        runBlocking {
            AnnotationExport.export(
                context = context,
                source = Uri.fromFile(src),
                destination = Uri.fromFile(dest),
                stamps = listOf(
                    Stamp(sheetId = 1, page = 0, symbol = "FERMATA", x = .5f, y = .5f, size = .05f),
                    Stamp(
                        sheetId = 1, page = 0, symbol = Stamp.TEXT_SYMBOL, text = "rit.", x = .3f, y = .7f,
                        size = .03f, font = "SERIF", bold = true,
                    ),
                ),
                strokes = listOf(
                    Stroke(
                        sheetId = 1, page = 0, points = "0.1;0.1|0.5;0.5",
                        width = 0.004f, color = 0xFFFF0000.toInt(),
                    )
                ),
                layerName = { "Capa 1" },
                layerId = null,
            )
        }

        assertArrayEquals("the original changed", before, src.readBytes())

        val doc = Document.openDocument(dest.path)
        try {
            assertEquals(
                "src ${src.length()} bytes / dest ${dest.length()} bytes",
                1, doc.countPages(),
            )
            val all = (doc.loadPage(0) as PDFPage).annotations
            // MuPDF does not list the popups, but they are there: the PC's panel hangs the comment
            // from them
            assertEquals("annotations", 3, all.size)
            for (mark in all) {
                assertTrue("no /Contents: the panel would not list it", !mark.contents.isNullOrEmpty())
                assertEquals("the layer goes in /Subj", "Capa 1", mark.subject)
                assertEquals("FOSScore", mark.author)
                assertTrue("no popup", mark.hasPopup())
                val popup = mark.getObject().get("Popup")
                assertTrue("the popup is not a dictionary", popup?.isDictionary() == true)
                assertNotNull("the popup does not point back at its mark", popup?.get("Parent"))
                assertFalse("the mark has no box", mark.bounds.isEmpty())
            }
            assertEquals(1, all.count { it.type == PDFAnnotation.TYPE_INK })
            assertEquals(1, all.count { it.type == PDFAnnotation.TYPE_STAMP })
            assertEquals(1, all.count { it.type == PDFAnnotation.TYPE_FREE_TEXT })

            // the points are a share of the page as it is seen; the PDF counts from the bottom left
            val ink = all.single { it.type == PDFAnnotation.TYPE_INK }.inkList.single()
            assertEquals("ink start x", 61.2f, ink.first().x, 0.1f)
            assertEquals("ink start y", 712.8f, ink.first().y, 0.1f)
            assertEquals("ink end x", 306f, ink.last().x, 0.1f)
            assertEquals("ink end y", 396f, ink.last().y, 0.1f)

            // the stamp is a square of 0.05 of the page height centred on (0.5, 0.5)
            val glyph = all.single { it.type == PDFAnnotation.TYPE_STAMP }
            val square = glyph.rect
            assertEquals("stamp left", 286.2f, square.x0, 2f)
            assertEquals("stamp right", 325.8f, square.x1, 2f)
            assertEquals("stamp bottom", 376.2f, square.y0, 2f)
            assertEquals("stamp top", 415.8f, square.y1, 2f)
            assertNotNull("the glyph never reached the PDF", glyph.getObject().get("AP"))

            val text = all.single { it.type == PDFAnnotation.TYPE_FREE_TEXT }
            assertEquals("rit.", text.contents)
            assertNotNull("no /DA: the reader would not know the font", text.getObject().get("DA"))
        } finally {
            doc.destroy()
        }
    }

    @Test
    fun noMarksIsReportedBeforeAnythingIsWritten() {
        val src = blankPdf("export-empty-src.pdf")
        val dest = File(context.cacheDir, "export-empty-dest.pdf")
        dest.delete()

        val error = runCatching {
            runBlocking {
                AnnotationExport.export(
                    context = context,
                    source = Uri.fromFile(src),
                    destination = Uri.fromFile(dest),
                    stamps = emptyList(),
                    strokes = emptyList(),
                    layerName = { "Capa 1" },
                    layerId = null,
                )
            }
        }.exceptionOrNull()

        assertTrue("expected NoMarks, got $error", error is ExportError.NoMarks)
        assertFalse("a share with no marks writes no file", dest.exists())
    }
}
