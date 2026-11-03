package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import kotlinx.coroutines.launch

/**
 * The library's side menu, opened by the ☰ button of the library header or by swiping from the
 * left edge. It holds what concerns the whole app: the preferences (language, the export and
 * import of the library), then, greyed out until they are built, setlists, tags, practice
 * and "About" (roadmap 3.6).
 */
@Composable
fun LibraryMenuDrawer(
    drawerState: DrawerState,
    onOpenPreferences: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
                SoonItem(R.string.library_menu_setlists)
                SoonItem(R.string.library_menu_tags)
                SoonItem(R.string.library_menu_practice)
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                DrawerRow(stringResource(R.string.library_menu_settings)) {
                    scope.launch { drawerState.close() }
                    onOpenPreferences()
                }
                SoonItem(R.string.library_menu_about)
            }
        },
        content = content
    )
}

// an entry that is not built yet: greyed out ("Coming soon" for a screen reader), and a tap does nothing
@Composable
private fun SoonItem(label: Int) {
    DrawerRow(stringResource(label), soon = stringResource(R.string.library_menu_soon))
}

// An entry of the menu, on one line: the whole menu stays in sight on a phone. [soon]: not built
// yet, greyed out and not clickable.
@Composable
private fun DrawerRow(label: String, soon: String? = null, onClick: () -> Unit = {}) {
    val color = if (soon == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DRAWER_ROW_HEIGHT)
            .clickable(enabled = soon == null, role = Role.Button, onClick = onClick)
            .semantics { if (soon != null) stateDescription = soon }
            .padding(horizontal = 28.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

// a row of the menu: as the viewer's menu, low enough for the whole list, high enough for a finger
private val DRAWER_ROW_HEIGHT = 40.dp
