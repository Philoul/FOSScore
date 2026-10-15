package org.fosscore.spike.musicxml

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Runs every bundled sample through Verovio and both bitmap rasterizers, then writes
 * `report.csv`, `summary.txt` and page-1 PNGs to the app's external files dir
 * (Android/data/org.fosscore.spike.musicxml/files/bench), where `adb pull` can reach them.
 * Files dropped in `files/inbox` (for example with `adb push`) are measured too; they stay
 * on the device.
 */
class Benchmark(private val context: Context, private val engine: ScoreEngine) {

    suspend fun run(width: Int, height: Int, scale: Int, progress: (String) -> Unit): File {
        val out = File(context.getExternalFilesDir(null), "bench").apply {
            deleteRecursively()
            mkdirs()
        }
        val rows = mutableListOf(
            "file,kb,parts,parse_ms,load_ms,pages,svg_ms,svg_kb,mupdf_ms,androidsvg_ms,midi_ms,timemap_ms,warnings,error"
        )
        val bundled = context.assets.list("samples").orEmpty().sorted().map { name ->
            name to { context.assets.open("samples/$name").use { it.readBytes() } }
        }
        val inbox = File(context.getExternalFilesDir(null), "inbox").listFiles().orEmpty()
            .filter { it.isFile }.sortedBy { it.name }.map { f -> "inbox/${f.name}" to { f.readBytes() } }
        val all = bundled + inbox
        for ((i, entry) in all.withIndex()) {
            val (name, read) = entry
            progress("Bench ${i + 1}/${all.size}: $name")
            rows += try {
                measure(name.substringAfter('/'), read(), width, height, scale, out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "bench failed for $name", e)
                "$name,,,,,,,,,,,,,\"${e.javaClass.simpleName}: ${e.message?.replace('"', '\'')}\""
            }
        }
        File(out, "report.csv").writeText(rows.joinToString("\n") + "\n")
        File(out, "summary.txt").writeText(
            "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
                "verovio=${engine.version()} viewport=${width}x$height scale=$scale\n"
        )
        progress("Bench done: ${out.absolutePath}")
        return out
    }

    private suspend fun measure(name: String, bytes: ByteArray, width: Int, height: Int, scale: Int, out: File): String {
        val parts = engine.open(bytes).size
        val t0 = System.nanoTime()
        check(engine.layout(LayoutSpec(width, height, scale, 0))) { "load failed" }
        val loadMs = ms(t0)
        val parseMs = engine.lastTimings.prepMs
        val pages = engine.pageCount
        val svg = engine.svg(1)
        val svgMs = engine.lastTimings.svgMs

        val base = name.substringBeforeLast('.')
        val t1 = System.nanoTime()
        val mupdf = runCatching { Rasterizers.mupdf(svg, width, height) }
        val mupdfMs = ms(t1)
        mupdf.getOrNull()?.let { save(it, File(out, "$base.mupdf.png")) }

        val t2 = System.nanoTime()
        val asvg = runCatching { Rasterizers.androidSvg(svg, width, height) }
        val asvgMs = ms(t2)
        asvg.getOrNull()?.let { save(it, File(out, "$base.androidsvg.png")) }
        File(out, "$base.p1.svg").writeText(svg)

        val t3 = System.nanoTime()
        engine.midiFile()
        val midiMs = ms(t3)
        val timemapMs = engine.timemapMs()
        val log = engine.log()
        File(out, "$base.log.txt").writeText(log)
        val warnings = log.lines().count { it.isNotBlank() }
        val error = listOfNotNull(
            mupdf.exceptionOrNull()?.let { "mupdf: ${it.message}" },
            asvg.exceptionOrNull()?.let { "androidsvg: ${it.message}" }
        ).joinToString(" | ").replace('"', '\'')

        return "\"$name\",${bytes.size / 1024},$parts,$parseMs,$loadMs,$pages,$svgMs,${svg.length / 1024}," +
            "${if (mupdf.isSuccess) mupdfMs else ""},${if (asvg.isSuccess) asvgMs else ""}," +
            "$midiMs,$timemapMs,$warnings,\"$error\""
    }

    private fun save(bmp: Bitmap, file: File) {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    companion object {
        const val TAG = "M0Bench"
    }
}
