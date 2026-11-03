package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.viewer.ChromeStyle
import com.musigatto.fosscore.ui.viewer.LINE_MENU
import com.musigatto.fosscore.ui.viewer.LINE_MORE
import com.musigatto.fosscore.ui.viewer.LINE_SETTINGS
import com.musigatto.fosscore.ui.viewer.LineIcon

/**
 * The left of the header of a main view ([current]), in the look chosen in the preferences, as the
 * viewer's top bar: [ChromeStyle.MENU] is ☰ (the side menu) and the view's name;
 * [ChromeStyle.ICONS] is one icon per view, the one shown framed and the ones not built yet greyed
 * out, then the preferences and ⋯ for the rest of the side menu.
 */
@Composable
fun MainNavigation(
    current: MenuView,
    style: ChromeStyle,
    onOpenMenu: () -> Unit,
    onOpenView: (MenuView) -> Unit,
    onOpenPreferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (style) {
            ChromeStyle.MENU -> {
                val menuDescription = stringResource(R.string.menu_open)
                TextButton(onClick = onOpenMenu, modifier = Modifier.semantics { contentDescription = menuDescription }) {
                    LineIcon(LINE_MENU, size = 26.dp)
                }
                Text(
                    stringResource(current.label),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChromeStyle.ICONS -> {
                val soon = stringResource(R.string.library_menu_soon)
                MenuView.entries.forEach { view ->
                    MainIcon(view.icon, stringResource(view.label), selected = view == current, soon = soon.takeUnless { view.ready }) {
                        onOpenView(view)
                    }
                }
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                MainIcon(LINE_SETTINGS, stringResource(R.string.library_menu_settings), onClick = onOpenPreferences)
                MainIcon(LINE_MORE, stringResource(R.string.menu_open), onClick = onOpenMenu)
            }
        }
    }
}

// a view's icon: framed when it is the one shown, greyed out and inert when not built yet ([soon])
@Composable
private fun MainIcon(icon: String, label: String, selected: Boolean = false, soon: String? = null, onClick: () -> Unit) {
    val color = when {
        soon != null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .size(MAIN_ICON)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = soon == null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
                if (soon != null) stateDescription = soon
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { LineIcon(icon) }
    }
    Spacer(Modifier.width(2.dp))
}

// as the viewer's bar buttons: big enough for a finger, six of them still fit on a phone
private val MAIN_ICON = 44.dp
