package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.locale.LocaleHelper

@Composable
fun LibraryScreen(
    repository: LibraryRepository,
    onOpen: (Sheet) -> Unit,
    onEdit: (Sheet) -> Unit,
    onImport: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    val sheets by repository.sheets().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val visible = filterSheets(sheets, query)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            LanguageMenu(onLanguageChange)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onImport) { Text(stringResource(R.string.action_import)) }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.library_search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (sheets.isEmpty()) {
            Text(
                stringResource(R.string.library_empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else if (visible.isEmpty()) {
            Text(
                stringResource(R.string.library_no_results, query),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { sheet ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
                            if (sheet.composer.isNotBlank()) {
                                Text(sheet.composer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(onClick = { onEdit(sheet) }) { Text(stringResource(R.string.action_edit)) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onOpen(sheet) }) { Text(stringResource(R.string.action_open)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// Shows the current choice; picking an entry stores it and recreates the activity
@Composable
private fun LanguageMenu(onLanguageChange: (String) -> Unit) {
    val context = LocalContext.current
    val selected = remember { LocaleHelper.selectedLanguage(context) }
    var expanded by remember { mutableStateOf(false) }
    val systemDefault = stringResource(R.string.language_system_default)
    fun nameOf(language: String) =
        if (language == LocaleHelper.FOLLOW_DEVICE) systemDefault else LocaleHelper.displayName(language)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.language_current, nameOf(selected)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(LocaleHelper.FOLLOW_DEVICE) + LocaleHelper.SUPPORTED).forEach { language ->
                DropdownMenuItem(
                    text = { Text(nameOf(language)) },
                    onClick = {
                        expanded = false
                        onLanguageChange(language)
                    }
                )
            }
        }
    }
}
