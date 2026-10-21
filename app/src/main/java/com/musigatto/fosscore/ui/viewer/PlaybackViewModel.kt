package com.musigatto.fosscore.ui.viewer

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.musicxml.SoundSpec
import com.musigatto.fosscore.score.MusicXmlPages
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "FOSScore-Playback"

// how often the cursor and the loop look at the player: under a sixteenth note at ♩ = 200
private const val FOLLOW_MS = 40L

// the musicxml module accepts tempo factors in this range (VerovioOptions)
const val MIN_TEMPO_FACTOR = 0.2f
const val MAX_TEMPO_FACTOR = 4.0f

// MIDI's own default, when the score writes no tempo
const val DEFAULT_BPM = 120.0

// one tap on − / + of the tempo
const val TEMPO_STEP_BPM = 4

// quick taps on − / + (or on several parts) settle before the music starts again with them
private const val SETTLE_MS = 400L

enum class PlayState { STOPPED, PREPARING, PLAYING, PAUSED }

/**
 * The A–B loop. Its one button cycles: the first tap marks A where the music is, the second marks
 * B and loops between them, the third turns the loop off. Positions are in quarter notes.
 */
sealed interface LoopState {
    data object Off : LoopState
    data class FromA(val a: Double) : LoopState
    data class Looping(val a: Double, val b: Double) : LoopState
}

/** One tap on the loop button with the music at [quarters]. A B before A swaps them. */
fun LoopState.tap(quarters: Double): LoopState = when (this) {
    LoopState.Off -> LoopState.FromA(quarters)
    // a second tap on the same spot would make an empty loop: wait for the music to move
    is LoopState.FromA -> if (quarters == a) this else LoopState.Looping(minOf(a, quarters), maxOf(a, quarters))
    is LoopState.Looping -> LoopState.Off
}

/** Where ⏮ goes: back to A when there is one, else to the start. */
fun LoopState.restartAt(): Double = when (this) {
    LoopState.Off -> 0.0
    is LoopState.FromA -> a
    is LoopState.Looping -> a
}

/** Beats per minute shown for a tempo [factor] of the score's [writtenBpm]. */
fun bpmOf(factor: Float, writtenBpm: Double): Int = (writtenBpm * factor).roundToInt()

/** The tempo factor for [bpm], within what the module accepts. */
fun factorOf(bpm: Int, writtenBpm: Double): Float =
    (bpm / writtenBpm).toFloat().coerceIn(MIN_TEMPO_FACTOR, MAX_TEMPO_FACTOR)

/**
 * The parts heard: the ones picked by the user, or the ones shown until the user picks. Never
 * empty while the score has parts, so the music never goes silent by accident.
 */
fun heardParts(picked: Set<String>?, shown: Set<String>, parts: List<Part>): Set<String> {
    val ids = parts.map { it.id }.toSet()
    return (picked?.filter { it in ids }?.toSet() ?: shown).ifEmpty { ids }
}

/**
 * MusicXML playback: the MIDI the musicxml module writes for the score, played by Android's own
 * MIDI player, and the notes being played for the viewer's cursor and page turns. Tempo and
 * parts heard are saved per score.
 *
 * ponytail: tempo and parts heard live in the app settings by file, like the layout
 * (ScoreLayoutStore), until the per-score configurations table exists (roadmap 3.4).
 */
class PlaybackViewModel(app: Application, private val score: String) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val _state = MutableStateFlow(PlayState.STOPPED)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val _loop = MutableStateFlow<LoopState>(LoopState.Off)
    val loop: StateFlow<LoopState> = _loop.asStateFlow()

    // ids of the notes that started last: the cursor, and what the page turns follow
    private val _cursor = MutableStateFlow<List<String>>(emptyList())
    val cursor: StateFlow<List<String>> = _cursor.asStateFlow()

    private val _tempo = MutableStateFlow(Settings.tempo(context, score))
    val tempo: StateFlow<Float> = _tempo.asStateFlow()

    // null: the parts shown are the parts heard
    private val _heard = MutableStateFlow(Settings.heardParts(context, score))
    val heard: StateFlow<Set<String>?> = _heard.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** Set by a page turned by hand while playing: the pages stop following until the music is on screen again. */
    var followPaused = false
        private set

    private var pages: MusicXmlPages? = null
    private var shownParts: Set<String> = emptySet()
    private var attachedFor: Any? = null
    private var player: MediaPlayer? = null
    private var playback: Playback? = null
    private var starting: Job? = null
    private var settling: Job? = null
    private var following: Job? = null
    private var position = 0.0
    // where ▶ starts next, when it is not "the page on screen": after ⏮, or a change while paused
    private var resumeAt: Double? = null

    /**
     * The score to play, with the [shown] parts. [loaded] stands for what the score was loaded
     * with (transposition, parts, lyrics): a new one has new note ids, so the music stops. A new
     * size or a turned screen keeps the ids, and the music goes on.
     */
    fun attach(pages: MusicXmlPages?, shown: Set<String>, loaded: Any) {
        if (pages !== this.pages || loaded != attachedFor) stop()
        this.pages = pages
        shownParts = shown
        attachedFor = loaded
    }

    /**
     * ▶ / ⏸. From a pause it goes on; otherwise it starts at A, or at [onScreen], the first
     * notes of the page shown, so the music starts where the eyes are.
     */
    fun playPause(onScreen: Set<String>) {
        when (_state.value) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> resume()
            PlayState.PREPARING -> Unit
            PlayState.STOPPED -> start(fromStart = null, onScreen = onScreen)
        }
    }

    /** ⏮: back to A, or to the start. Keeps playing if it was. */
    fun restart() {
        val at = _loop.value.restartAt()
        val p = player
        val pb = playback
        if (p != null && pb != null) {
            p.seekTo(pb.msAt(at).toInt())
            position = at
            followPaused = false
        } else {
            position = at
            resumeAt = at
        }
    }

    fun tapLoop() {
        _loop.value = _loop.value.tap(position)
    }

    fun setTempo(factor: Float) {
        val f = factor.coerceIn(MIN_TEMPO_FACTOR, MAX_TEMPO_FACTOR)
        if (f == _tempo.value) return
        _tempo.value = f
        Settings.setTempo(context, score, f)
        restartWhereItIs()
    }

    fun setHeard(id: String, heard: Boolean, parts: List<Part>) {
        val now = heardParts(_heard.value, shownParts, parts)
        val next = if (heard) now + id else now - id
        if (next.isEmpty()) return
        _heard.value = next
        Settings.setHeardParts(context, score, next)
        restartWhereItIs()
    }

    /** A page turned by hand during playback: let the reader look ahead or back. */
    fun pageTurnedByHand() {
        if (_state.value == PlayState.PLAYING) followPaused = true
    }

    /** The music reached the page on screen again: the pages follow it again. */
    fun musicOnScreen() {
        followPaused = false
    }

    fun stop() {
        starting?.cancel()
        starting = null
        following?.cancel()
        following = null
        player?.release()
        player = null
        playback = null
        followPaused = false
        _cursor.value = emptyList()
        _state.value = PlayState.STOPPED
    }

    override fun onCleared() = stop()

    private fun pause() {
        player?.pause()
        _state.value = PlayState.PAUSED
    }

    private fun resume() {
        player?.start()
        _state.value = PlayState.PLAYING
        follow()
    }

    // A new tempo or new parts heard need a new MIDI: start again at the same place, once the
    // taps settle. The music goes on meanwhile with the old one.
    private fun restartWhereItIs() {
        if (_state.value == PlayState.STOPPED && settling == null) return
        settling?.cancel()
        settling = viewModelScope.launch {
            delay(SETTLE_MS)
            settling = null
            val state = _state.value
            if (state == PlayState.STOPPED) return@launch
            val at = position
            stop()
            if (state == PlayState.PAUSED) resumeAt = at else start(fromStart = at, onScreen = emptySet())
        }
    }

    private fun start(fromStart: Double?, onScreen: Set<String>) {
        val pages = pages ?: return
        // only one start at a time: two quick taps must not end up with two players
        stop()
        _state.value = PlayState.PREPARING
        _failed.value = false
        starting = viewModelScope.launch {
            try {
                val sound = SoundSpec(audible = _heard.value, tempo = _tempo.value.toDouble())
                val pb = pages.playback(sound)
                val midi = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "playback.mid").apply { writeBytes(pb.midi) }
                }
                val from = fromStart
                    ?: resumeAt
                    ?: (_loop.value as? LoopState.Looping)?.a
                    ?: pb.onsets.firstOrNull { o -> o.notes.any { it in onScreen } }?.quarters
                    ?: 0.0
                if (!isActive) return@launch
                player = MediaPlayer().apply {
                    setDataSource(midi.absolutePath)
                    prepare()
                    setOnCompletionListener { onEnd() }
                    if (from > 0) seekTo(pb.msAt(from).toInt())
                    start()
                }
                playback = pb
                position = from
                resumeAt = null
                followPaused = false
                _state.value = PlayState.PLAYING
                follow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "playback could not start: ${e::class.simpleName}: ${e.message}", e)
                stop()
                _failed.value = true
            }
        }
    }

    // the end of the music: around the loop again, or stop
    private fun onEnd() {
        val loop = _loop.value
        val p = player
        val pb = playback
        if (loop is LoopState.Looping && p != null && pb != null) {
            p.seekTo(pb.msAt(loop.a).toInt())
            p.start()
        } else {
            stop()
        }
    }

    private fun follow() {
        following?.cancel()
        following = viewModelScope.launch {
            var last = -2
            while (isActive) {
                val p = player ?: break
                val pb = playback ?: break
                if (!p.isPlaying) break
                val q = pb.quartersAt(p.currentPosition.toLong())
                position = q
                val loop = _loop.value
                if (loop is LoopState.Looping && q >= loop.b) {
                    p.seekTo(pb.msAt(loop.a).toInt())
                    position = loop.a
                }
                val index = pb.onsetIndexAt(position)
                if (index != last && index >= 0) {
                    last = index
                    _cursor.value = pb.onsets[index].notes
                }
                delay(FOLLOW_MS)
            }
        }
    }

    companion object {
        /** One ViewModel per score: [score] keys both the ViewModel and its saved settings. */
        fun factory(context: Context, score: String) = viewModelFactory {
            initializer { PlaybackViewModel(context.applicationContext as Application, score) }
        }
    }
}
