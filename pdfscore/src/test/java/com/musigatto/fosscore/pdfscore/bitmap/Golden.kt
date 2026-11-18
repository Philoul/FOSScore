package com.musigatto.fosscore.pdfscore.bitmap

import java.io.File
import javax.imageio.ImageIO

/** The golden files of the bitmap reading (free synthetic data only), from the test resources. */
object Golden {
    /** A golden file's text, lines split. */
    fun lines(name: String): List<String> =
        Golden::class.java.getResourceAsStream("/bitmap/$name")?.bufferedReader()?.readLines().orEmpty()

    /** An 8-bit gray PNG as the reader takes it (its raw samples, no color conversion). */
    fun gray(name: String): GrayImage? = Golden::class.java.getResourceAsStream("/bitmap/$name")?.use { stream ->
        ImageIO.read(stream)?.let { image -> grayOf(image) }
    }

    /** A gray image of a PNG file (8-bit gray, or color turned gray as SPEC 1 recommends). */
    fun gray(file: File): GrayImage? = ImageIO.read(file)?.let { grayOf(it) }

    private fun grayOf(image: java.awt.image.BufferedImage): GrayImage {
        val w = image.width
        val h = image.height
        val pixels = ByteArray(w * h)
        val raster = image.raster
        if (raster.numBands == 1) {
            val samples = raster.getSamples(0, 0, w, h, 0, IntArray(w * h))
            for (i in samples.indices) pixels[i] = samples[i].toByte()
        } else {
            for (y in 0 until h) for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                val g = (77 * ((argb shr 16) and 0xFF) + 150 * ((argb shr 8) and 0xFF) + 29 * (argb and 0xFF) + 128) shr 8
                pixels[y * w + x] = g.toByte()
            }
        }
        return GrayImage(w, h, pixels)
    }

    /** The model shipped in the module's resources, read once. */
    val model: OmrModel? by lazy { OmrModel.load() }
}
