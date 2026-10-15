package org.fosscore.spike.musicxml

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Thin Kotlin wrapper over the JNI bridge to vrv::Toolkit.
 * Not thread-safe: call it from a single thread.
 */
class Verovio(resourcePath: String) : AutoCloseable {
    private var handle: Long = nativeCreate(resourcePath)

    val version: String get() = nativeVersion(handle)
    val pageCount: Int get() = nativePageCount(handle)
    val log: String get() = nativeLog(handle)

    fun setOptions(json: String): Boolean = nativeSetOptions(handle, json)

    /** Loads MusicXML (plain or compressed .mxl) as given. */
    fun load(bytes: ByteArray): Boolean =
        if (isZip(bytes)) nativeLoadZip(handle, bytes) else nativeLoadData(handle, decodeXml(bytes))

    /** Loads MusicXML text (already unzipped and filtered). */
    fun loadXml(xml: String): Boolean = nativeLoadData(handle, xml)

    fun redoLayout(json: String = "") = nativeRedoLayout(handle, json)
    fun renderSvg(page: Int): String = nativeRenderSvg(handle, page)

    /** Base64-encoded standard MIDI file. */
    fun renderMidi(): String = nativeRenderMidi(handle)
    fun renderTimemap(): String = nativeRenderTimemap(handle)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("verovio")
            System.loadLibrary("verovio_jni")
        }

        private const val DATA_VERSION = "6.3.0-leipzig-bravura"

        /** Copies the font resources out of the APK once (Verovio needs real file paths). */
        fun prepareResources(context: Context): String {
            val root = File(context.filesDir, "verovio")
            val data = File(root, "data")
            val stamp = File(root, "version")
            if (!stamp.exists() || stamp.readText() != DATA_VERSION) {
                data.deleteRecursively()
                copyAssets(context.assets, "verovio/data", data)
                stamp.writeText(DATA_VERSION)
            }
            return data.absolutePath
        }

        private fun copyAssets(assets: AssetManager, from: String, to: File) {
            val children = assets.list(from).orEmpty()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } }
                return
            }
            to.mkdirs()
            for (child in children) copyAssets(assets, "$from/$child", File(to, child))
        }

        private fun isZip(b: ByteArray) = b.size > 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

        // MuseScore and others sometimes write UTF-16 MusicXML; honour the byte order mark.
        private fun decodeXml(b: ByteArray): String = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, Charsets.UTF_16LE).drop(1)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, Charsets.UTF_16BE).drop(1)
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                String(b, 3, b.size - 3, Charsets.UTF_8)
            else -> String(b, Charsets.UTF_8)
        }

        @JvmStatic private external fun nativeCreate(resourcePath: String): Long
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeVersion(h: Long): String
        @JvmStatic private external fun nativeSetOptions(h: Long, json: String): Boolean
        @JvmStatic private external fun nativeLoadData(h: Long, data: String): Boolean
        @JvmStatic private external fun nativeLoadZip(h: Long, data: ByteArray): Boolean
        @JvmStatic private external fun nativeRedoLayout(h: Long, json: String)
        @JvmStatic private external fun nativePageCount(h: Long): Int
        @JvmStatic private external fun nativeRenderSvg(h: Long, page: Int): String
        @JvmStatic private external fun nativeRenderMidi(h: Long): String
        @JvmStatic private external fun nativeRenderTimemap(h: Long): String
        @JvmStatic private external fun nativeLog(h: Long): String
    }
}
