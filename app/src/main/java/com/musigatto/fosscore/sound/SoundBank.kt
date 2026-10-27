package com.musigatto.fosscore.sound

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

private const val LOG_TAG = "FOSScore-SoundBank"

// how long the decoder waits for a free buffer, in microseconds
private const val DECODE_WAIT_US = 10_000L

// a decoder that gives nothing for this long is stuck: the sample is given up (and silent)
private const val DECODE_STALL_MS = 2_000L

/**
 * The instrument chosen for a part: [program] (0-127) of [bank] (128 for drum kits) in sound bank
 * [bankId] ([SoundBank.BUILT_IN] or one the user added).
 */
data class PartSound(val bankId: String, val bank: Int, val program: Int)

/** A bank file found in a folder of the device, not added yet: its file [name], [size] and [uri]. */
data class BankFile(val name: String, val size: Long, val uri: Uri)

/** One sound bank the app can play: [BUILT_IN], or one the user added ([size] bytes). */
data class SoundBankEntry(val id: String, val name: String, val size: Long) {
    val builtIn: Boolean get() = id == SoundBank.BUILT_IN
}

/**
 * The sound banks of the app. The built-in one is GeneralUser GS by S. Christian Collins (its
 * licence is next to it in the assets), in SF3: its samples are Ogg Vorbis streams (9 MB instead
 * of 32). It is mapped from the APK, where it is stored uncompressed, so it is never copied into
 * the app's memory, and it cannot be deleted. The user adds SF2 or SF3 banks of their own: each is
 * copied into the app's files, then mapped the same way.
 *
 * A compressed sample is decoded by Android's own Vorbis decoder the first time a piece plays it,
 * then kept in the app's cache: the next pieces read it straight from there.
 */
object SoundBank {
    /** The id of the bank that comes with the app. */
    const val BUILT_IN = "builtin"

    private const val ASSET = "soundfont/GeneralUser-GS.sf3"
    private const val BUILT_IN_NAME = "GeneralUser GS"

    // the decoded samples of this version of the built-in bank; a bank added has its own folder
    private const val CACHE = "soundbank/GeneralUser-GS-2.0.3"
    private const val ADDED_CACHE = "soundbank/added-"

    // the banks the user added, with a text file holding the name shown for each
    private const val FOLDER = "soundbanks"
    private const val NAME_SUFFIX = ".name"

    // how deep [filesIn] looks into the sub-folders of the folder chosen
    private const val FOLDER_DEPTH = 3

    // the banks read so far, by id
    private val fonts = ConcurrentHashMap<String, SoundFont>()

    /** The built-in bank, read on the first call (a few tens of milliseconds): call it off the main thread. */
    fun load(context: Context): SoundFont = load(context, BUILT_IN)

    /**
     * Bank [id], read on the first call: call it off the main thread. A bank that is gone (deleted,
     * or a file that can no longer be read) gives the built-in one.
     */
    fun load(context: Context, id: String): SoundFont = fonts[id] ?: synchronized(this) {
        fonts[id] ?: (if (id == BUILT_IN) readBuiltIn(context) else readAdded(context, id))
            ?.also { fonts[id] = it }
            ?: load(context, BUILT_IN)
    }

    /** Whether bank [id] is there: the built-in one always is, one added until it is deleted. */
    fun has(context: Context, id: String): Boolean =
        id == BUILT_IN || folder(context).listFiles { f -> f.nameWithoutExtension == id && isBank(f.name) }.orEmpty().isNotEmpty()

    /** The banks the user can choose from: the built-in one first, then the ones added, by name. */
    fun list(context: Context): List<SoundBankEntry> {
        val added = folder(context).listFiles { f -> f.isFile && isBank(f.name) }.orEmpty().map { f ->
            val id = f.nameWithoutExtension
            SoundBankEntry(id, File(f.parentFile, id + NAME_SUFFIX).takeIf { it.isFile }?.readText()?.trim().orEmpty().ifBlank { f.name }, f.length())
        }.sortedBy { it.name.lowercase() }
        return listOf(SoundBankEntry(BUILT_IN, BUILT_IN_NAME, builtInSize(context))) + added
    }

    /**
     * Copies the bank the user picked at [uri] into the app, after reading it once to check it is
     * a SoundFont 2 or SF3 bank. Off the main thread. Null when it is not one.
     */
    fun add(context: Context, uri: Uri): SoundBankEntry? {
        val dir = folder(context).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val part = File(dir, "$id.part")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } } ?: return null
            val font = map(part)
            val shown = font.name.ifBlank { displayName(context, uri)?.substringBeforeLast('.').orEmpty() }.ifBlank { id }
            val ext = if (font.compressed) "sf3" else "sf2"
            val file = File(dir, "$id.$ext")
            if (!part.renameTo(file)) return null
            File(dir, id + NAME_SUFFIX).writeText(shown)
            SoundBankEntry(id, shown, file.length())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "not a sound bank: ${e.message}")
            null
        } finally {
            part.delete()
        }
    }

    /**
     * The SF2 and SF3 files in the folder the user gave access to ([tree], from the system's folder
     * picker) and in its sub-folders, by name; nothing else is listed. Off the main thread.
     */
    fun filesIn(context: Context, tree: Uri): List<BankFile> {
        val found = ArrayList<BankFile>()
        fun walk(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            )
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < FOLDER_DEPTH) walk(id, depth + 1)
                    } else if (isBank(name)) {
                        found += BankFile(name, c.getLong(3), DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
        runCatching { walk(DocumentsContract.getTreeDocumentId(tree), 0) }
            .onFailure { Log.w(LOG_TAG, "folder not read: ${it.message}") }
        return found.sortedBy { it.name.lowercase() }
    }

    /** The name of the folder the user gave access to ([tree]), to show it. */
    fun folderName(context: Context, tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()

    /** Removes bank [id] the user added, with its decoded samples. The built-in bank stays. */
    fun delete(context: Context, id: String) {
        if (id == BUILT_IN) return
        fonts.remove(id)
        folder(context).listFiles { f -> f.nameWithoutExtension == id }.orEmpty().forEach { it.delete() }
        File(context.cacheDir, ADDED_CACHE + id).deleteRecursively()
    }

    private fun folder(context: Context) = File(context.filesDir, FOLDER)

    private fun isBank(name: String) = name.endsWith(".sf2", ignoreCase = true) || name.endsWith(".sf3", ignoreCase = true)

    private fun builtInSize(context: Context): Long = runCatching { context.assets.openFd(ASSET).use { it.length } }.getOrDefault(0L)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun readBuiltIn(context: Context): SoundFont = context.assets.openFd(ASSET).use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            SoundFont.read(channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length))
        }
    }.also { it.decoder = decoderFor(File(context.cacheDir, CACHE)) }

    private fun readAdded(context: Context, id: String): SoundFont? =
        folder(context).listFiles { f -> f.nameWithoutExtension == id && isBank(f.name) }?.firstOrNull()?.let { file ->
            runCatching { map(file) }.onFailure { Log.w(LOG_TAG, "bank $id not read: ${it.message}") }.getOrNull()
        }?.also { it.decoder = decoderFor(File(context.cacheDir, ADDED_CACHE + id)) }

    // the whole file mapped: its samples are read in place
    private fun map(file: File): SoundFont = RandomAccessFile(file, "r").use { f ->
        SoundFont.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()))
    }

    private fun decoderFor(cache: File): (Int, ByteBuffer) -> ShortArray =
        { index, ogg -> cached(cache, index) ?: decode(ogg).also { keep(cache, index, it) } }

    private fun cached(dir: File, index: Int): ShortArray? = runCatching {
        val file = File(dir, "$index.pcm").takeIf { it.isFile } ?: return null
        val bytes = file.readBytes()
        ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }.getOrNull()

    // written aside first then renamed: a reader never gets half a file
    private fun keep(dir: File, index: Int, pcm: ShortArray) {
        runCatching {
            dir.mkdirs()
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            val part = File(dir, "$index.part")
            part.writeBytes(bytes.array())
            part.renameTo(File(dir, "$index.pcm"))
        }
    }

    // One Ogg Vorbis stream into 16-bit values, by Android's decoder (no code of our own).
    private fun decode(ogg: ByteBuffer): ShortArray {
        val started = System.nanoTime()
        val bytes = ByteArray(ogg.remaining()).also { ogg.duplicate().get(it) }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = min(size, bytes.size - position.toInt())
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()

                override fun close() = Unit
            })
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            val out = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastProgress = System.nanoTime()
            while (true) {
                if ((System.nanoTime() - lastProgress) / 1_000_000 > DECODE_STALL_MS) {
                    Log.w(LOG_TAG, "decoder stuck after ${total} values of ${bytes.size} bytes: sample given up")
                    break
                }
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(DECODE_WAIT_US)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i) ?: continue
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, DECODE_WAIT_US)
                if (o >= 0) {
                    lastProgress = System.nanoTime()
                    decoder.getOutputBuffer(o)?.let { buffer ->
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val part = ShortArray(info.size / 2)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(part)
                        out += part
                        total += part.size
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            Log.d(LOG_TAG, "decoded ${bytes.size} bytes into $total values in ${(System.nanoTime() - started) / 1_000_000} ms")
            val pcm = ShortArray(total)
            var at = 0
            for (part in out) {
                part.copyInto(pcm, at)
                at += part.size
            }
            return pcm
        } catch (e: Exception) {
            // a sample that cannot be decoded is silent: better than no music at all
            Log.e(LOG_TAG, "sample not decoded: ${e.message}", e)
            return ShortArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
