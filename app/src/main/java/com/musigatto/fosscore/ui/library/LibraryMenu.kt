package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import kotlinx.coroutines.launch

/**
 * The side menu, opened by the ☰ button of the library or setlists header or by swiping from the
 * left edge. It holds what concerns the whole app: the views (the library, the setlists, then,
 * greyed out until they are built, tags and practice), the preferences (language, keyboard and
 * pedals, MIDI, the export and import of the library) and "About" (roadmap 3.6). [current] is the
 * view shown, marked in the menu.
 */
@Composable
fun LibraryMenuDrawer(
    drawerState: DrawerState,
    current: MenuView,
    onOpenPreferences: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSetlists: () -> Unit,
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
                DrawerRow(stringResource(R.string.library_title), selected = current == MenuView.LIBRARY) {
                    scope.launch { drawerState.close() }
                    onOpenLibrary()
                }
                DrawerRow(stringResource(R.string.library_menu_setlists), selected = current == MenuView.SETLISTS) {
                    scope.launch { drawerState.close() }
                    onOpenSetlists()
                }
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

/** The views the side menu goes between. */
enum class MenuView { LIBRARY, SETLISTS }

// an entry that is not built yet: greyed out ("Coming soon" for a screen reader), and a tap does nothing
@Composable
private fun SoonItem(label: Int) {
    DrawerRow(stringResource(label), soon = stringResource(R.string.library_menu_soon))
}

// An entry of the menu, on one line: the whole menu stays in sight on a phone. [soon]: not built
// yet, greyed out and not clickable. [selected]: the view shown, marked as in the viewer's menu.
@Composable
private fun DrawerRow(label: String, soon: String? = null, selected: Boolean = false, onClick: () -> Unit = {}) {
    val color = when {
        soon != null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .heightIn(min = DRAWER_ROW_HEIGHT)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = soon == null, role = Role.Button, onClick = onClick)
            .semantics {
                if (soon != null) stateDescription = soon
                this.selected = selected
            }
            .padding(horizontal = 16.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            fontWeight = if (selected) FontWeight.Bold else null,
        )
    }
}

// a row of the menu: as the viewer's menu, low enough for the whole list, high enough for a finger
private val DRAWER_ROW_HEIGHT = 40.dp
