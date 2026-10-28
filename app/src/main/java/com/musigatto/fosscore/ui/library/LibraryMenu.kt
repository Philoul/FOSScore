package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.library_menu_settings)) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenPreferences()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                SoonItem(R.string.library_menu_about)
            }
        },
        content = content
    )
}

// an entry that is not built yet: greyed out, "Coming soon" under it, and a tap does nothing
@Composable
private fun SoonItem(label: Int) {
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    NavigationDrawerItem(
        label = {
            Column {
                Text(stringResource(label), color = grey)
                Text(stringResource(R.string.library_menu_soon), color = grey, style = MaterialTheme.typography.labelSmall)
            }
        },
        selected = false,
        onClick = {},
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}
