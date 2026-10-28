package com.musigatto.fosscore.ui.viewer

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musigatto.fosscore.metronome.ClickSound
import com.musigatto.fosscore.metronome.MAX_BEATS
import com.musigatto.fosscore.metronome.MAX_BPM
import com.musigatto.fosscore.metronome.MAX_COUNT_IN
import com.musigatto.fosscore.metronome.MAX_SUBDIVISION
import com.musigatto.fosscore.metronome.MAX_TURN_AHEAD
import com.musigatto.fosscore.metronome.MIN_BEATS
import com.musigatto.fosscore.metronome.MIN_BPM
import com.musigatto.fosscore.metronome.Meter
import com.musigatto.fosscore.metronome.MetronomeEngine
import com.musigatto.fosscore.metronome.MetronomeMode
import com.musigatto.fosscore.metronome.MetronomeSettings
import com.musigatto.fosscore.metronome.TapTempo
import com.musigatto.fosscore.metronome.Tick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The metronome of a score: its settings, saved with the score, and the clicks while it runs.
 * [tick] is the beat being heard; the viewer turns the pages from [barHeard].
 *
 * A MusicXML score has its own tempo and time signature: the viewer hands them over with
 * [followScore], and the tempo stays the one of playback.
 */
class MetronomeViewModel(app: Application, private val score: String) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val engine = MetronomeEngine(viewModelScope)
    val tick: StateFlow<Tick?> = engine.tick

    private val _settings = MutableStateFlow(Settings.metronome(context, score))
    val settings: StateFlow<MetronomeSettings> = _settings.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // the band in the bottom bar, from "Metronome" in the menu until closed
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    // the time signature comes from the score, not from the user
    private val _meterFromScore = MutableStateFlow(false)
    val meterFromScore: StateFlow<Boolean> = _meterFromScore.asStateFlow()

    private val tapTempo = TapTempo()

    // the bar of the piece where the counting started: the first bar of the page on screen
    private var startBar = 0

    fun show(shown: Boolean) {
        _shown.value = shown
        if (!shown) stop()
    }

    /** ▶ / ⏸; [fromBar] is the bar of the piece where the music on screen starts. */
    fun startStop(fromBar: Int) = if (_running.value) stop() else start(fromBar)

    fun start(fromBar: Int) {
        startBar = fromBar
        apply(_settings.value)
        engine.start(_settings.value.countIn)
        _running.value = engine.isRunning
    }

    fun stop() {
        engine.stop()
        _running.value = false
    }

    /** The bar of the piece being heard, or null when stopped or counting in. */
    fun barHeard(tick: Tick?): Int? = tick?.takeIf { it.bar >= 0 }?.let { startBar + it.bar }

    /** A MusicXML score: its tempo (playback's) and the time signature it starts with. */
    fun followScore(bpm: Int, meter: Meter?) {
        _meterFromScore.value = meter != null
        _settings.value = _settings.value.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM), meter = meter ?: _settings.value.meter)
        apply(_settings.value)
    }

    fun setBpm(bpm: Int) = change { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM)) }

    /** A tap of TAP: once there are two, the tempo of the taps, returned too. */
    fun tap(): Int? = tapTempo.tap(SystemClock.uptimeMillis())?.also(::setBpm)

    fun setMeter(meter: Meter) = change { it.copy(meter = Meter(meter.beats.coerceIn(MIN_BEATS, MAX_BEATS), meter.unit)) }

    fun setSubdivision(n: Int) = change { it.copy(subdivision = n.coerceIn(1, MAX_SUBDIVISION)) }

    fun setCountIn(bars: Int) = change { it.copy(countIn = bars.coerceIn(0, MAX_COUNT_IN)) }

    fun setAutoTurn(on: Boolean) = change { it.copy(autoTurn = on) }

    fun setMode(mode: MetronomeMode) = change { it.copy(mode = mode) }

    fun setSound(sound: ClickSound) = change { it.copy(sound = sound) }

    fun setAccent(on: Boolean) = change { it.copy(accent = on) }

    fun setVolume(percent: Int) = change { it.copy(volume = percent.coerceIn(0, 100)) }

    fun setTurnAhead(bars: Int) = change { it.copy(turnAhead = bars.coerceIn(0, MAX_TURN_AHEAD)) }

    /** The bars of page [page] (0-based); the pages before it without a count get the same one. */
    fun setBars(page: Int, bars: Int) = change { s ->
        val counts = s.barCounts.toMutableList()
        val fill = counts.lastOrNull() ?: bars
        while (counts.size <= page) counts += fill
        counts[page] = bars.coerceIn(1, 99)
        s.copy(barCounts = counts)
    }

    /** "Copy to all": every page after [page] gets its count. */
    fun copyBarsToFollowing(page: Int) = change { s ->
        // a page without a count of its own has the count of the last page before it (BarPages)
        if (s.barCounts.getOrNull(page) == null) s else s.copy(barCounts = s.barCounts.take(page + 1))
    }

    private fun change(edit: (MetronomeSettings) -> MetronomeSettings) {
        _settings.value = edit(_settings.value)
        Settings.setMetronome(context, score, _settings.value)
        apply(_settings.value)
    }

    // the engine takes the new settings from its next click
    private fun apply(s: MetronomeSettings) {
        engine.bpm = s.bpm
        engine.meter = s.meter
        engine.subdivision = s.subdivision
        engine.accent = s.accent
        engine.sound = s.sound
        engine.audible = s.mode != MetronomeMode.VISUAL
        engine.volume = s.volume / 100f
    }

    override fun onCleared() = engine.stop()

    companion object {
        /** One ViewModel per score: [score] keys both the ViewModel and its saved settings. */
        fun factory(context: Context, score: String) = viewModelFactory {
            initializer { MetronomeViewModel(context.applicationContext as Application, score) }
        }
    }
}
