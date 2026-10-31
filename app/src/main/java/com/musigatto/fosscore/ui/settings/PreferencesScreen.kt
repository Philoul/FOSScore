package com.musigatto.fosscore.ui.settings

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.viewer.AppLanguage
import com.musigatto.fosscore.ui.viewer.Settings

// the widest the preferences get on a tablet: lines longer than this are hard to read
private const val MAX_WIDTH_DP = 640

/** The sections of the preferences, in their order. Each one opens under its title. */
private enum class Section { LANGUAGE, KEYBOARD, LIBRARY }

/**
 * The app's preferences, over the screen it was opened from: what applies to every score. A list
 * of sections, each opened under its title (one at a time), so new ones join without changing the
 * others: the language, the keyboard and the pedals, and the library (export and import).
 */
@Composable
fun PreferencesScreen(onDone: () -> Unit) {
    // the section open, by name (kept when the screen turns, and when a new language recreates it)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDone)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = MAX_WIDTH_DP.dp).fillMaxWidth()) {
            Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            Section.entries.forEach { section ->
                val isOpen = open == section.name
                SectionTitle(section.title(), section.summary(), isOpen) { open = if (isOpen) null else section.name }
                if (isOpen) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (section) {
                            Section.LANGUAGE -> LanguageSection()
                            Section.KEYBOARD -> KeyboardSection()
                            Section.LIBRARY -> LibraryBackupSection()
                        }
                    }
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.action_back)) }
        }
    }
}

@Composable
private fun Section.title(): String = stringResource(
    when (this) {
        Section.LANGUAGE -> R.string.language_label
        Section.KEYBOARD -> R.string.prefs_keyboard
        Section.LIBRARY -> R.string.prefs_library
    }
)

@Composable
private fun Section.summary(): String = when (this) {
    Section.LANGUAGE -> Settings.language(LocalContext.current).displayName.ifEmpty { stringResource(R.string.language_system) }
    Section.KEYBOARD -> stringResource(R.string.prefs_keyboard_summary)
    Section.LIBRARY -> stringResource(R.string.prefs_library_summary)
}

// a section's title, what it holds under it, and ▸ / ▾ for closed / open; a tap opens or closes it
@Composable
private fun SectionTitle(title: String, summary: String, open: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The app's language: the device's, or one the app ships, each named in itself. The app restarts its screen in it. */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val current = Settings.language(context)
    AppLanguage.entries.forEach { language ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = language == current, role = Role.RadioButton) {
                    if (language == current) return@selectable
                    Settings.setLanguage(context, language)
                    // every string is resolved again in the new language
                    (context as? Activity)?.recreate()
                },
        ) {
            RadioButton(selected = language == current, onClick = null)
            Text(language.displayName.ifEmpty { stringResource(R.string.language_system) }, modifier = Modifier.padding(start = 12.dp))
        }
    }
}
