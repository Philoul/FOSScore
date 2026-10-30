package com.musigatto.fosscore.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val LOG_TAG = "FOSScore-LivePlayer"

// frames rendered per write: about 6 ms at 44.1 kHz, the step of the events and of a new tempo
private const val BLOCK_FRAMES = 256

// the audio buffer, in blocks: enough for a slow tablet, short enough for a tempo to be heard soon
private const val BUFFER_BLOCKS = 12

// after the last note, the time its sound is left to ring before the end is called
private const val TAIL_QUARTERS = 2.0

// the blocks remembered to tell where the music heard is (the buffer and some more)
private const val HISTORY = BUFFER_BLOCKS * 8

// the blocks written before the track starts: it starts with music ready, not on an empty buffer
private const val PREFILL_BLOCKS = BUFFER_BLOCKS - 2

// the blocks a spare synth renders ahead (about a second): Android compiles the synth's code then,
// not while the first notes play (they came out cut on a slow tablet)
private const val COMPILE_BLOCKS = 200

// the MIDI channels
private const val CHANNELS = 16

// the metronome's clicks heard at once at most (a subdivision over the end of the beat before)
private const val CLICK_VOICES = 4

// 16-bit values to the -1..1 of the track
private const val CLICK_SCALE = 32768f

// the voices the synth holds at most, and at least when the tablet cannot keep up
private const val MAX_VOICES = 48
private const val MIN_VOICES = 16

/**
 * The live player: a [MidiSong] played by a [Synth] into an [AudioTrack], event after event, on
 * its own audio thread. The music advances in quarter notes at the song's tempo times
 * [tempoFactor], so a new tempo, a new song (other parts heard) or a jump apply at once, and the
 * loop wraps exactly at its end. [position] is the music being heard, read from the audio clock:
 * the cursor follows the sound, not what was written ahead into the buffer.
 */
class LivePlayer(private val font: SoundFont, val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)) {

    private val synth = Synth(font, sampleRate, MAX_VOICES)
    // the voices held, from how long the blocks take to render (a slow tablet, a dense piece)
    private val budget = VoiceBudget(MAX_VOICES, MIN_VOICES)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile var tempoFactor = 1.0

    /** No sound, the music going on: the playback bar follows it all the same. */
    @Volatile var muted = false

    /**
     * The metronome's clicks heard over the music (see [setClicks]); off, they are not played, and
     * the music's own sound does not change. [muted] silences the music, not the clicks.
     */
    @Volatile var clicking = false

    /** Called on the audio thread when the music ends (not while looping). */
    @Volatile var onEnd: (() -> Unit)? = null

    @Volatile var playing = false
        private set

    private var song = MidiSong.read(ByteArray(0))
    // the audio thread's music: where it is, and the next event to send
    private var at = 0.0
    private var next = 0
    private var loopOn = false
    private var loopStart = 0.0
    private var loopEnd: Double? = null
    // a note on: its channel, its key in the song and the key heard (transposed), its velocity and
    // where it started; to stop the ones a new song does not play, and move them to a new key
    private class Sounding(val channel: Int, val key: Int, var played: Int, val velocity: Int, val quarters: Double)

    private val sounding = ArrayList<Sounding>()

    // the metronome over the music: where each click is (in quarter notes) and which sound it
    // takes, the sounds as floats at the track's rate, their level, and the next click to play
    private var clickAt = DoubleArray(0)
    private var clickSound = IntArray(0)
    private var clickSounds: Array<FloatArray> = emptyArray()
    private var clickGain = 0f
    private var nextClick = 0
    // the count-in before the music: its clicks, the next one, and where the music starts (it
    // waits until then; NEGATIVE_INFINITY: no count-in)
    private var countInAt = DoubleArray(0)
    private var countInSound = IntArray(0)
    private var nextCountIn = 0
    private var countInEnd = Double.NEGATIVE_INFINITY
    // the clicks sounding: which sound (-1: none) and how far into it
    private val voiceSound = IntArray(CLICK_VOICES) { -1 }
    private val voiceAt = IntArray(CLICK_VOICES)
    // semitones the music is heard up (or down), the score shown staying as it is
    private var semitones = 0
    // the instrument chosen for each channel (null: the file's), read by [warm] off the audio thread
    @Volatile private var sounds: List<ChannelSound?> = emptyList()

    // what was written, block by block: the frame (since the track started) and the music there
    private val historyFrame = LongArray(HISTORY)
    private val historyAt = DoubleArray(HISTORY)
    private val historyStep = DoubleArray(HISTORY)
    @Volatile private var historyCount = 0
    @Volatile private var pausedAt = 0.0
    private var written = 0L
    // the track's head when the frames written started counting (a flush may not reset it)
    @Volatile private var headBase = 0L

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** The voices sounding: a load gauge. */
    val voices: Int get() = synth.activeVoices

    /** The music being heard now, in quarter notes. */
    val position: Double
        get() {
            if (!playing) return pausedAt
            val t = track ?: return pausedAt
            val head = try {
                t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (e: IllegalStateException) {
                return pausedAt
            }
            return heardAt(head - headBase)
        }

    /**
     * Gets [song] ready to play: its samples read, and the synth's code run once by a spare synth.
     * Call it off the main thread before [setSong].
     */
    fun warm(song: MidiSong, chosen: List<ChannelSound?> = sounds, cancelled: () -> Boolean = { false }) {
        val started = System.nanoTime()
        val read = synth.warm(song, chosen, cancelled)
        SoundBank.releaseDecoders()
        Log.d(LOG_TAG, "warm: $read samples in ${(System.nanoTime() - started) / 1_000_000} ms")
        if (cancelled()) return
        val spare = Synth(font, sampleRate)
        song.events.firstOrNull { it.type == MidiSong.NOTE_ON && it.data2 > 0 }?.let { spare.noteOn(it.channel, it.data1, it.data2) }
        val scratch = FloatArray(BLOCK_FRAMES * 2)
        repeat(COMPILE_BLOCKS) { spare.render(scratch, 0, BLOCK_FRAMES) }
    }

    /** Plays [newSong] from where the music is: the notes it no longer has stop. */
    fun setSong(newSong: MidiSong) = run {
        val wanted = newSong.events.filter { it.type == MidiSong.NOTE_ON && it.data2 > 0 }
            .map { Triple(it.channel, it.data1, it.quarters) }.toHashSet()
        for (n in sounding.toList()) {
            if (Triple(n.channel, n.key, n.quarters) !in wanted) {
                synth.noteOff(n.channel, n.played)
                sounding.remove(n)
            }
        }
        song = newSong
        next = song.firstEventAt(at)
    }

    /**
     * The instrument of each channel, by channel (null, or a channel left out: the file's own),
     * from the next notes on. Call [warm] with them first, off the main thread: a sample not read
     * yet is not played.
     */
    fun setSounds(chosen: List<ChannelSound?>) {
        sounds = chosen.toList()
        run { for (c in 0 until CHANNELS) synth.setSound(c, chosen.getOrNull(c)) }
    }

    /**
     * The metronome's clicks: where they are in the music ([at], in quarter notes, in time order),
     * which of [sounds] each one plays ([which]), at [gain]. [sounds] are 16-bit values at
     * [sampleRate]. Heard while [clicking].
     */
    fun setClicks(at: DoubleArray, which: IntArray, sounds: List<ShortArray>, gain: Float) {
        // made here, not on the audio thread
        val floats = Array(sounds.size) { k -> FloatArray(sounds[k].size) { sounds[k][it] / CLICK_SCALE } }
        run {
            clickAt = at
            clickSound = which
            clickSounds = floats
            clickGain = gain
            nextClick = firstClickAt(this.at)
        }
    }

    /**
     * Plays the music [by] semitones up (down when negative), the drums excepted; the notes held
     * move to their new key at once.
     */
    fun transpose(by: Int) = run {
        if (by != semitones) {
            semitones = by
            for (n in sounding) {
                if (n.channel == Synth.DRUM_CHANNEL) continue
                synth.noteOff(n.channel, n.played)
                n.played = heard(n.channel, n.key)
                synth.noteOn(n.channel, n.played, n.velocity)
            }
        }
    }

    /** The loop: [on], from [start] to [end] (null: the end of the song). */
    fun setLoop(on: Boolean, start: Double, end: Double?) = run {
        loopOn = on
        loopStart = start
        loopEnd = end
    }

    /**
     * Plays from [from], quarter notes into the song, after a count-in of [countIn] quarter notes:
     * its clicks at [countInAt] (before [from], in time order) play [countInWhich] of the click
     * sounds, heard while [clicking]. [position] runs through the count-in, below [from].
     */
    fun play(from: Double, countIn: Double = 0.0, countInAt: DoubleArray = DoubleArray(0), countInWhich: IntArray = IntArray(0)) {
        if (playing) return seek(from)
        // the song ended by itself: its thread is done, its track still to stop
        pause()
        // one piece at a time: another score's player stops
        if (active !== this) active?.pause()
        active = this
        val t = track ?: newTrack().also { track = it }
        jump(from)
        this.countInAt = countInAt
        countInSound = countInWhich
        nextCountIn = 0
        if (countIn > 0) {
            at = from - countIn
            countInEnd = from
        }
        pausedAt = at
        historyCount = 0
        written = 0L
        headBase = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        playing = true
        // the audio thread starts the track once it has written the first blocks
        thread = Thread({ loop(t) }, "live-player").apply { start() }
    }

    /** Stops where the music is heard; [play] goes on from [position]. */
    fun pause() {
        // the end of the song stops the audio thread by itself: the track is still to stop
        if (thread == null) return
        val heard = position
        playing = false
        thread?.join(500)
        thread = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        // paused while counting in: ▶ goes on from where the music starts
        pausedAt = max(heard, countInEnd)
        countInEnd = Double.NEGATIVE_INFINITY
        synth.silence()
        sounding.clear()
    }

    /** Jumps to [quarters]: playing, the music goes on from there at once. */
    fun seek(quarters: Double) {
        if (!playing) {
            pausedAt = quarters
            return
        }
        run {
            jump(quarters)
            // what was written ahead is dropped, so the jump is heard now
            track?.let { t ->
                runCatching { t.pause() }
                runCatching { t.flush() }
                runCatching { t.play() }
                headBase = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }
            historyCount = 0
            written = 0L
        }
    }

    fun release() {
        pause()
        if (active === this) active = null
        track?.release()
        track = null
    }

    // a change made on the audio thread while it plays, at once when it does not
    private fun run(change: () -> Unit) {
        if (playing) commands.add(change) else change()
    }

    // where the music heard is: the block written at [head], and how far into it
    private fun heardAt(head: Long): Double {
        val count = historyCount
        if (count == 0) return pausedAt
        var best = -1
        val first = max(0, count - HISTORY)
        for (i in count - 1 downTo first) {
            if (historyFrame[i % HISTORY] <= head) {
                best = i % HISTORY
                break
            }
        }
        if (best < 0) return historyAt[first % HISTORY]
        return historyAt[best] + (head - historyFrame[best]) * historyStep[best]
    }

    private fun jump(quarters: Double) {
        synth.silence()
        sounding.clear()
        voiceSound.fill(-1)
        at = quarters
        next = song.firstEventAt(quarters)
        nextClick = firstClickAt(quarters)
        nextCountIn = countInAt.size
        countInEnd = Double.NEGATIVE_INFINITY
        // programs and controllers as the music has set them by then
        synth.reset()
        for (i in 0 until next) {
            val e = song.events[i]
            when (e.type) {
                MidiSong.PROGRAM -> synth.programChange(e.channel, e.data1)
                MidiSong.CONTROL -> synth.controlChange(e.channel, e.data1, e.data2)
            }
        }
    }

    private fun send(e: MidiSong.Event) {
        when (e.type) {
            MidiSong.NOTE_ON -> if (e.data2 > 0) {
                val played = heard(e.channel, e.data1)
                synth.noteOn(e.channel, played, e.data2)
                sounding += Sounding(e.channel, e.data1, played, e.data2, e.quarters)
            } else {
                noteOff(e.channel, e.data1)
            }
            MidiSong.NOTE_OFF -> noteOff(e.channel, e.data1)
            MidiSong.PROGRAM -> synth.programChange(e.channel, e.data1)
            MidiSong.CONTROL -> synth.controlChange(e.channel, e.data1, e.data2)
        }
    }

    private fun noteOff(channel: Int, key: Int) {
        sounding.removeAll { n ->
            (n.channel == channel && n.key == key).also { if (it) synth.noteOff(channel, n.played) }
        }
    }

    // the index of the first click at or after [quarters]
    private fun firstClickAt(quarters: Double): Int {
        var lo = 0
        var hi = clickAt.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (clickAt[mid] < quarters - 1e-9) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // a click starts with the frames rendered next: in a free voice, else in the first one
    private fun startClick(sound: Int) {
        if (sound !in clickSounds.indices) return
        val v = voiceSound.indexOf(-1).takeIf { it >= 0 } ?: 0
        voiceSound[v] = sound
        voiceAt[v] = 0
    }

    // the clicks sounding, added to [frames] frames of [block] from frame [from]
    private fun mixClicks(block: FloatArray, from: Int, frames: Int) {
        for (v in 0 until CLICK_VOICES) {
            val s = voiceSound[v]
            if (s < 0) continue
            val samples = clickSounds[s]
            var p = voiceAt[v]
            for (i in from until from + frames) {
                if (p >= samples.size) break
                val x = samples[p++] * clickGain
                block[i * 2] += x
                block[i * 2 + 1] += x
            }
            voiceAt[v] = p
            if (p >= samples.size) voiceSound[v] = -1
        }
    }

    // the key heard for [key] of the song
    private fun heard(channel: Int, key: Int) =
        if (channel == Synth.DRUM_CHANNEL) key else (key + semitones).coerceIn(0, 127)

    // On the audio thread: block after block until paused. The track blocks the writes once its
    // buffer is full, which paces the loop on the audio clock.
    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(BLOCK_FRAMES * 2)
        var blocks = 0
        try {
            while (playing) {
                if (blocks++ == PREFILL_BLOCKS) t.play()
                while (true) commands.poll()?.invoke() ?: break
                var done = 0
                val started = System.nanoTime()
                while (done < BLOCK_FRAMES) {
                    // the count-in at the tempo the music starts with
                    val step = 1_000_000.0 / song.microsPerQuarter(max(at, countInEnd)) * tempoFactor / sampleRate
                    // the next thing that happens: an event, a tempo change, the loop's or the song's end
                    while (next < song.events.size && song.events[next].quarters <= at + 1e-9) send(song.events[next++])
                    while (nextClick < clickAt.size && clickAt[nextClick] <= at + 1e-9) {
                        if (clicking) startClick(clickSound[nextClick])
                        nextClick++
                    }
                    while (nextCountIn < countInAt.size && countInAt[nextCountIn] <= at + 1e-9) {
                        if (clicking) startClick(countInSound[nextCountIn])
                        nextCountIn++
                    }
                    val end = if (loopOn) loopEnd ?: song.length else song.length + TAIL_QUARTERS
                    if (at >= end - 1e-9) {
                        if (loopOn) {
                            jump(loopStart)
                            continue
                        }
                        playing = false
                        onEnd?.invoke()
                        break
                    }
                    var until = end
                    if (next < song.events.size) until = min(until, song.events[next].quarters)
                    if (nextClick < clickAt.size) until = min(until, clickAt[nextClick])
                    if (nextCountIn < countInAt.size) until = min(until, countInAt[nextCountIn])
                    if (at < countInEnd) until = min(until, countInEnd)
                    song.nextTempoChange(at)?.let { until = min(until, it) }
                    val frames = ceil((until - at) / step).toInt().coerceIn(1, BLOCK_FRAMES - done)
                    val slot = historyCount % HISTORY
                    historyFrame[slot] = written + done
                    historyAt[slot] = at
                    historyStep[slot] = step
                    historyCount++
                    synth.render(block, done * 2, frames)
                    if (muted) block.fill(0f, done * 2, (done + frames) * 2)
                    mixClicks(block, done, frames)
                    at += frames * step
                    done += frames
                }
                // the block's render time over its own time: past the time, the music would lag
                if (done > 0) {
                    budget.update((System.nanoTime() - started) / (done * 1e9f / sampleRate))
                    synth.voiceLimit = budget.limit
                }
                if (done > 0) t.write(block, 0, done * 2, AudioTrack.WRITE_BLOCKING)
                written += done
            }
            // a piece shorter than the first blocks still starts
            if (blocks <= PREFILL_BLOCKS) t.play()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "live player stopped: ${e.message}", e)
            playing = false
        }
    }

    private companion object {
        // the player playing, or the last one that played (on the main thread)
        var active: LivePlayer? = null
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(max(min, BLOCK_FRAMES * BUFFER_BLOCKS * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
