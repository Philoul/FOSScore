package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import com.musigatto.fosscore.metronome.BarPages
import com.musigatto.fosscore.metronome.ClickSound
import com.musigatto.fosscore.metronome.Meter
import com.musigatto.fosscore.metronome.MetronomeMode
import com.musigatto.fosscore.metronome.MetronomeSettings
import androidx.core.content.edit
import java.util.Locale

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    fun darkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    @Composable
    fun label(): String = when (this) {
        SYSTEM -> stringResource(R.string.theme_auto)
        LIGHT -> stringResource(R.string.theme_light)
        DARK -> stringResource(R.string.theme_dark)
    }
}

/**
 * The language of the UI. [SYSTEM] follows the device, which is what a fresh installation does. The
 * names of the languages are their autonyms, so they read the same whatever the UI language is.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    SPANISH("es"),
    FRENCH("fr");

    /** The name to list; empty for [SYSTEM], whose name is translated. */
    val displayName: String
        get() = when (this) {
            SYSTEM -> ""
            ENGLISH -> "English"
            SPANISH -> "Español"
            FRENCH -> "Français"
        }

    /** [base] with this language, for `attachBaseContext`; [SYSTEM] hands it back untouched. */
    fun wrap(base: Context): Context {
        val code = tag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(code))
        return base.createConfigurationContext(configuration)
    }

    companion object {
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM

        /** The device language, read from the system so our own override is not picked up. */
        fun deviceLocale(): Locale {
            val configuration = Resources.getSystem().configuration
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) configuration.locales[0]
            else @Suppress("DEPRECATION") configuration.locale
        }
    }
}

object Settings {
    private const val PREFS = "fosscore_settings"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_THEME = "theme"
    private const val KEY_DIM = "dim"
    private const val KEY_INVERT = "invert"
    private const val KEY_HALF_PAGE = "half_page_"
    private const val KEY_TWO_UP = "two_up"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_CHROME_STYLE = "chrome_style"
    private const val KEY_ALL_ICONS = "all_icons"
    private const val KEY_LAST_PAGE = "last_page_"
    private const val KEY_OPENED_AT = "opened_at_"
    private const val KEY_LAST_PAGE_AT = "last_page_at_"
    // a score left longer ago than this opens at its first page again
    const val LAST_PAGE_KEPT_MS = 30 * 60 * 1000L
    private const val KEY_STAMP_PX = "stamp_px"
    private const val KEY_MUSIC_SIZE = "music_size_"
    private const val KEY_TRANSPOSE = "transpose_"
    private const val KEY_PARTS = "parts_"
    private const val KEY_LYRICS = "lyrics_"
    private const val KEY_HIDDEN_LYRICS = "hidden_lyrics_"
    private const val KEY_TEMPO = "tempo_"
    private const val KEY_HEARD = "heard_"
    private const val KEY_MET_BPM = "metronome_bpm_"
    private const val KEY_MET_METER = "metronome_meter_"
    private const val KEY_MET_SUBDIVISION = "metronome_subdivision_"
    private const val KEY_MET_COUNT_IN = "metronome_count_in_"
    private const val KEY_MET_AUTO_TURN = "metronome_auto_turn_"
    private const val KEY_MET_BARS = "metronome_bars_"
    private const val KEY_MET_MODE = "metronome_mode"
    private const val KEY_MET_SOUND = "metronome_sound"
    private const val KEY_MET_ACCENT = "metronome_accent"
    private const val KEY_MET_VOLUME = "metronome_volume"
    private const val KEY_TURN_AHEAD = "turn_ahead"

    fun language(context: Context): AppLanguage =
        AppLanguage.of(context.prefs().getString(KEY_LANGUAGE, null))

    fun setLanguage(context: Context, language: AppLanguage) {
        context.prefs().edit {
            if (language.tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, language.tag)
        }
    }

    fun themeMode(context: Context): ThemeMode =
        ThemeMode.entries[context.prefs().getInt(KEY_THEME, ThemeMode.SYSTEM.ordinal)]

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.prefs().edit { putInt(KEY_THEME, mode.ordinal) }
    }

    fun dimPct(context: Context): Float = context.prefs().getFloat(KEY_DIM, 0f)

    fun setDimPct(context: Context, pct: Float) {
        context.prefs().edit { putFloat(KEY_DIM, pct.coerceIn(0f, 80f)) }
    }

    fun invert(context: Context): Boolean = context.prefs().getBoolean(KEY_INVERT, false)

    fun setInvert(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_INVERT, value) }
    }

    // how the pages show, the same for every score: half page for each orientation (the stand is
    // not held the same way), two pages side by side in landscape
    fun halfPage(context: Context, landscape: Boolean): Boolean =
        context.prefs().getBoolean(KEY_HALF_PAGE + if (landscape) "landscape" else "portrait", false)

    fun setHalfPage(context: Context, landscape: Boolean, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_HALF_PAGE + if (landscape) "landscape" else "portrait", value) }
    }

    fun twoUp(context: Context): Boolean = context.prefs().getBoolean(KEY_TWO_UP, true)

    fun setTwoUp(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_TWO_UP, value) }
    }

    // the screen stays awake while a score is on screen, so it does not sleep mid-performance
    fun keepScreenOn(context: Context): Boolean =
        context.prefs().getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
    }

    // the look of the viewer's top bar, while the variants are tried; the icon bar by default
    fun chromeStyle(context: Context): ChromeStyle =
        context.prefs().getString(KEY_CHROME_STYLE, null)
            ?.let { name -> ChromeStyle.entries.firstOrNull { it.name == name } }
            ?: ChromeStyle.ICONS_POPUP

    fun setChromeStyle(context: Context, value: ChromeStyle) {
        context.prefs().edit { putString(KEY_CHROME_STYLE, value.name) }
    }

    // the icon bar with an icon for every entry of the menu, a preview of the finished app
    fun allIcons(context: Context): Boolean = context.prefs().getBoolean(KEY_ALL_ICONS, false)

    fun setAllIcons(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_ALL_ICONS, value) }
    }

    /** When the score at [uri] was opened last (ms since 1970), 0 if never. */
    fun openedAt(context: Context, uri: String): Long = context.prefs().getLong(KEY_OPENED_AT + uri, 0L)

    fun setOpenedAt(context: Context, uri: String, at: Long) {
        context.prefs().edit { putLong(KEY_OPENED_AT + uri, at) }
    }

    /**
     * The page a score opens at: the one it was left at, when that was a short while ago (back from
     * the library, a call...), otherwise the first one: a score worked on days ago starts again.
     */
    fun lastPage(context: Context, uri: String, now: Long = System.currentTimeMillis()): Int {
        val prefs = context.prefs()
        return if (keepsLastPage(prefs.getLong(KEY_LAST_PAGE_AT + uri, 0L), now)) prefs.getInt(KEY_LAST_PAGE + uri, 0) else 0
    }

    fun setLastPage(context: Context, uri: String, page: Int, now: Long = System.currentTimeMillis()) {
        context.prefs().edit {
            putInt(KEY_LAST_PAGE + uri, page)
            putLong(KEY_LAST_PAGE_AT + uri, now)
        }
    }

    internal fun keepsLastPage(savedAt: Long, now: Long): Boolean = now - savedAt in 0 until LAST_PAGE_KEPT_MS

    // size the stamp tool starts with, saved from the stamps panel
    fun stampPx(context: Context): Float =
        context.prefs().getFloat(KEY_STAMP_PX, DEFAULT_STAMP_PX)

    fun setStampPx(context: Context, px: Float) {
        context.prefs().edit { putFloat(KEY_STAMP_PX, px.coerceIn(MIN_STAMP_PX, MAX_STAMP_PX)) }
    }

    /** MusicXML transposition in semitones. Per score. */
    fun transpose(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_TRANSPOSE + uri, 0)

    fun setTranspose(context: Context, uri: String, semitones: Int) {
        context.prefs().edit { putInt(KEY_TRANSPOSE + uri, semitones) }
    }

    /** Ids of the MusicXML parts shown, or null for the parts the author left visible. Per score. */
    fun shownParts(context: Context, uri: String): Set<String>? =
        context.prefs().getStringSet(KEY_PARTS + uri, null)?.toSet()

    fun setShownParts(context: Context, uri: String, ids: Set<String>?) {
        context.prefs().edit {
            if (ids == null) remove(KEY_PARTS + uri) else putStringSet(KEY_PARTS + uri, ids)
        }
    }

    /** Name of a LyricsPlacement, or null for the file's own placement. Per score. */
    fun lyrics(context: Context, uri: String): String? =
        context.prefs().getString(KEY_LYRICS + uri, null)

    fun setLyrics(context: Context, uri: String, placement: String) {
        context.prefs().edit { putString(KEY_LYRICS + uri, placement) }
    }

    /** Whether the lyrics of the MusicXML parts hidden stay, as a line of lyrics. Per score. */
    fun keepHiddenLyrics(context: Context, uri: String): Boolean =
        context.prefs().getBoolean(KEY_HIDDEN_LYRICS + uri, true)

    fun setKeepHiddenLyrics(context: Context, uri: String, keep: Boolean) {
        context.prefs().edit { putBoolean(KEY_HIDDEN_LYRICS + uri, keep) }
    }

    /** Playback tempo, as a factor of the tempo written in the score (1 = as written). Per score. */
    fun tempo(context: Context, uri: String): Float =
        context.prefs().getFloat(KEY_TEMPO + uri, 1f)

    fun setTempo(context: Context, uri: String, factor: Float) {
        context.prefs().edit { putFloat(KEY_TEMPO + uri, factor) }
    }

    /** Ids of the MusicXML parts heard, or null for the parts shown. Per score. */
    fun heardParts(context: Context, uri: String): Set<String>? =
        context.prefs().getStringSet(KEY_HEARD + uri, null)?.toSet()

    fun setHeardParts(context: Context, uri: String, ids: Set<String>?) {
        context.prefs().edit {
            if (ids == null) remove(KEY_HEARD + uri) else putStringSet(KEY_HEARD + uri, ids)
        }
    }

    /** MusicXML note size, in percent of the size chosen for the screen (100). Per score. */
    fun musicSize(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_MUSIC_SIZE + uri, 100)

    fun setMusicSize(context: Context, uri: String, percent: Int) {
        context.prefs().edit { putInt(KEY_MUSIC_SIZE + uri, percent) }
    }

    /** The metronome of [uri]: its own settings, and the ones shared by every score. */
    fun metronome(context: Context, uri: String): MetronomeSettings {
        val p = context.prefs()
        val d = MetronomeSettings()
        return MetronomeSettings(
            bpm = p.getInt(KEY_MET_BPM + uri, d.bpm),
            meter = Meter.parse(p.getString(KEY_MET_METER + uri, null)) ?: d.meter,
            subdivision = p.getInt(KEY_MET_SUBDIVISION + uri, d.subdivision),
            countIn = p.getInt(KEY_MET_COUNT_IN + uri, d.countIn),
            autoTurn = p.getBoolean(KEY_MET_AUTO_TURN + uri, d.autoTurn),
            barCounts = BarPages.parse(p.getString(KEY_MET_BARS + uri, null)),
            mode = p.getString(KEY_MET_MODE, null)?.let { n -> MetronomeMode.entries.firstOrNull { it.name == n } } ?: d.mode,
            sound = p.getString(KEY_MET_SOUND, null)?.let { n -> ClickSound.entries.firstOrNull { it.name == n } } ?: d.sound,
            accent = p.getBoolean(KEY_MET_ACCENT, d.accent),
            volume = p.getInt(KEY_MET_VOLUME, d.volume),
            turnAhead = p.getInt(KEY_TURN_AHEAD, d.turnAhead),
        )
    }

    fun setMetronome(context: Context, uri: String, s: MetronomeSettings) {
        context.prefs().edit {
            putInt(KEY_MET_BPM + uri, s.bpm)
            putString(KEY_MET_METER + uri, s.meter.toString())
            putInt(KEY_MET_SUBDIVISION + uri, s.subdivision)
            putInt(KEY_MET_COUNT_IN + uri, s.countIn)
            putBoolean(KEY_MET_AUTO_TURN + uri, s.autoTurn)
            putString(KEY_MET_BARS + uri, BarPages.format(s.barCounts))
            putString(KEY_MET_MODE, s.mode.name)
            putString(KEY_MET_SOUND, s.sound.name)
            putBoolean(KEY_MET_ACCENT, s.accent)
            putInt(KEY_MET_VOLUME, s.volume)
            putInt(KEY_TURN_AHEAD, s.turnAhead)
        }
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}