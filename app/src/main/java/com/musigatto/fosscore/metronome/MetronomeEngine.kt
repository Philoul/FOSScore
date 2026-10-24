package com.musigatto.fosscore.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LOG_TAG = "FOSScore-Metronome"

// the audio written at a time: a tempo change is heard from the next beat at most this late
private const val CHUNK_FRAMES = Clicks.SAMPLE_RATE / 100
// how often the beat shown looks at the audio clock
private const val WATCH_MS = 10L

/** A beat: [beat] (0 = the first) of [bar], counted from 0; a bar below 0 is the count-in. */
data class Tick(val bar: Int, val beat: Int, val sub: Int = 0)

/**
 * When each click falls, in audio frames from the start, at the tempo, time signature and
 * subdivision of the moment: a change takes effect from the next click.
 */
class ClickSchedule(countInBars: Int) {
    private var bar = -countInBars
    private var beat = 0
    private var sub = 0
    private var at = 0.0

    /** The next click and the frame it falls on. */
    fun next(bpm: Int, meter: Meter, subdivision: Int): Pair<Long, Tick> {
        val click = at.roundToLong() to Tick(bar, beat, sub)
        val parts = subdivision.coerceAtLeast(1)
        at += Clicks.SAMPLE_RATE * 60.0 / bpm.coerceIn(MIN_BPM, MAX_BPM) / parts
        if (++sub >= parts) {
            sub = 0
            if (++beat >= meter.pulses) {
                beat = 0
                bar++
            }
        }
        return click
    }
}

/**
 * The metronome's sound: the clicks are written into an [AudioTrack] at exact sample positions,
 * so the timing comes from the audio clock and never drifts. [tick] is the beat being heard, read
 * from the same clock, for the beat dots, the flash and the page turns. The settings can change
 * while it runs: the next click takes them.
 */
class MetronomeEngine(private val scope: CoroutineScope) {
    @Volatile var bpm = DEFAULT_METRONOME_BPM
    @Volatile var meter = Meter()
    @Volatile var subdivision = 1
    @Volatile var accent = true
    @Volatile var sound = ClickSound.CLICK
    // visual only: the clicks are silent, the clock still runs
    @Volatile var audible = true
    // how loud, 0 to 1
    @Volatile var volume = 0.8f

    private val _tick = MutableStateFlow<Tick?>(null)
    val tick: StateFlow<Tick?> = _tick.asStateFlow()

    @Volatile private var running = false
    private var writer: Thread? = null
    private var watching: Job? = null

    val isRunning: Boolean get() = running

    fun start(countInBars: Int) {
        stop()
        val track = try {
            newTrack()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no audio track: ${e.message}", e)
            return
        }
        running = true
        val heard = ConcurrentLinkedQueue<Pair<Long, Tick>>()
        writer = Thread({ write(track, ClickSchedule(countInBars), heard) }, "metronome").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        watching = scope.launch {
            while (isActive && running) {
                val played = try {
                    track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                } catch (e: IllegalStateException) {
                    break
                }
                var last: Tick? = null
                while (true) {
                    val next = heard.peek() ?: break
                    if (next.first > played) break
                    heard.poll()
                    last = next.second
                }
                if (last != null) _tick.value = last
                delay(WATCH_MS)
            }
        }
    }

    fun stop() {
        running = false
        watching?.cancel()
        watching = null
        writer?.join(500)
        writer = null
        _tick.value = null
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(Clicks.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Clicks.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, CHUNK_FRAMES * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // a click being heard: its sound, starting at [offset] frames into the chunk being written
    private class Voice(val samples: ShortArray, var offset: Int)

    // On the writer thread: the clicks, chunk after chunk, until stopped. The track blocks the
    // writes once its buffer is full, which paces the loop on the audio clock.
    private fun write(track: AudioTrack, schedule: ClickSchedule, heard: ConcurrentLinkedQueue<Pair<Long, Tick>>) {
        val sounds = HashMap<Pair<ClickSound, ClickKind>, ShortArray>()
        val voices = ArrayList<Voice>()
        val chunk = ShortArray(CHUNK_FRAMES)
        var written = 0L
        var next = schedule.next(bpm, meter, subdivision)
        try {
            track.play()
            while (running) {
                chunk.fill(0)
                val end = written + CHUNK_FRAMES
                while (next.first < end) {
                    val (frame, tick) = next
                    val kind = when {
                        tick.sub > 0 -> ClickKind.SUBDIVISION
                        tick.beat == 0 && accent -> ClickKind.ACCENT
                        else -> ClickKind.BEAT
                    }
                    if (audible) {
                        val key = sound to kind
                        voices += Voice(sounds.getOrPut(key) { Clicks.of(key.first, key.second) }, (frame - written).toInt())
                    }
                    if (tick.sub == 0) heard.add(frame to tick)
                    next = schedule.next(bpm, meter, subdivision)
                }
                val gain = volume
                val each = voices.iterator()
                while (each.hasNext()) {
                    val v = each.next()
                    for (i in maxOf(0, v.offset) until CHUNK_FRAMES) {
                        val s = i - v.offset
                        if (s >= v.samples.size) break
                        chunk[i] = (chunk[i] + (v.samples[s] * gain).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    v.offset -= CHUNK_FRAMES
                    if (-v.offset >= v.samples.size) each.remove()
                }
                track.write(chunk, 0, CHUNK_FRAMES)
                written = end
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "metronome stopped: ${e.message}", e)
            running = false
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }
}
