package com.musigatto.fosscore.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import androidx.core.content.edit
import com.musigatto.fosscore.locale.LocaleHelper

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

object Settings {
    private const val PREFS = "fosscore_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_DIM = "dim"
    private const val KEY_INVERT = "invert"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LAST_PAGE = "last_page_"
    private const val KEY_MUSIC_SIZE = "music_size_"
    private const val KEY_TRANSPOSE = "transpose_"
    private const val KEY_PARTS = "parts_"
    private const val KEY_LYRICS = "lyrics_"
    private const val KEY_LANGUAGE = "language"

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

    // Keeps the screen awake while a score is displayed, so it does not sleep mid-performance.
    fun keepScreenOn(context: Context): Boolean =
        context.prefs().getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.prefs().edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }

    fun lastPage(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_LAST_PAGE + uri, 0)

    fun setLastPage(context: Context, uri: String, page: Int) {
        context.prefs().edit { putInt(KEY_LAST_PAGE + uri, page) }
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

    /** MusicXML note size, in percent of the size chosen for the screen (100). Per score. */
    fun musicSize(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_MUSIC_SIZE + uri, 100)

    fun setMusicSize(context: Context, uri: String, percent: Int) {
        context.prefs().edit { putInt(KEY_MUSIC_SIZE + uri, percent) }
    }

    // LocaleHelper.FOLLOW_DEVICE or a language code from LocaleHelper.SUPPORTED
    fun language(context: Context): String =
        context.prefs().getString(KEY_LANGUAGE, null) ?: LocaleHelper.FOLLOW_DEVICE

    fun setLanguage(context: Context, language: String) {
        context.prefs().edit().putString(KEY_LANGUAGE, language).apply()
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}