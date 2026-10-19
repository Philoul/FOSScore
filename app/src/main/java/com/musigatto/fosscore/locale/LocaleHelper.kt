package com.musigatto.fosscore.locale

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import com.musigatto.fosscore.ui.viewer.Settings
import java.util.Locale

/**
 * In-app language selection, modelled on AndroidAPS' `LocaleHelper`.
 *
 * The stored preference is either [FOLLOW_DEVICE] or a language code from [SUPPORTED]. Each
 * activity passes its base context through [wrap] in `attachBaseContext`, so every resource lookup
 * (including Compose `stringResource`) resolves against the chosen locale. Changing the preference
 * takes effect when the activity is recreated.
 *
 * Not connected at the moment: the temporary language menu left the library header, and the app
 * follows the device language. The choice comes back in the app settings screen, which calls
 * [wrap] from `attachBaseContext` again.
 */
object LocaleHelper {

    /** Preference value meaning "use the phone's language". */
    const val FOLLOW_DEVICE = "default"

    /**
     * Languages the app ships, as BCP 47 tags (`pt-BR` style for regional variants). English is the
     * default `values/` folder; each other code needs a matching `values-<code>/strings.xml`
     * (`values-b+pt+BR` for a regional variant). `LanguageResourcesTest` checks that the two agree.
     */
    val SUPPORTED = listOf("en", "es", "fr")

    fun selectedLanguage(context: Context): String {
        val stored = Settings.language(context)
        return if (stored in SUPPORTED) stored else FOLLOW_DEVICE
    }

    fun setSelectedLanguage(context: Context, language: String) {
        Settings.setLanguage(context, if (language in SUPPORTED) language else FOLLOW_DEVICE)
    }

    /** The locale resources resolve against: the chosen language, or the device's own. */
    fun currentLocale(context: Context): Locale {
        val language = selectedLanguage(context)
        return if (language == FOLLOW_DEVICE) deviceLocale() else Locale.forLanguageTag(language)
    }

    /** A language's name written in that language ("Español", "Français"), for the picker. */
    fun displayName(language: String): String {
        val locale = Locale.forLanguageTag(language)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    fun wrap(context: Context): Context {
        val locale = currentLocale(context)
        // Keeps String.format, dates and numbers consistent with the UI language. Also undoes an
        // earlier choice when the user switches back to the device language in the same process.
        Locale.setDefault(locale)
        if (selectedLanguage(context) == FOLLOW_DEVICE) return context

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return ContextWrapper(context.createConfigurationContext(configuration))
    }

    // Read from the system resources, not Locale.getDefault(): the latter is overwritten by wrap().
    private fun deviceLocale(): Locale =
        ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0] ?: Locale.getDefault()
}
