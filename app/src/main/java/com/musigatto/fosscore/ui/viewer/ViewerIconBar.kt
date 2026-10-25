package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R

/**
 * How the top of the viewer looks, while the variants are tried side by side (temporary: one of
 * them stays once chosen):
 * - [MENU]: the ☰ menu and the title pill;
 * - [ICONS_POPUP]: a row of icons, each opening its panel as a card under the icon;
 * - [ICONS_TAB]: the same row, each panel unfolding from the bar like a tab, full width.
 */
enum class ChromeStyle { MENU, ICONS_POPUP, ICONS_TAB }

/** Where the panels hang: [x] is the middle of the icon that opened them, from the left of the screen. */
data class PanelAnchor(val style: ChromeStyle = ChromeStyle.MENU, val x: Dp = 0.dp)

val LocalPanelAnchor = compositionLocalOf { PanelAnchor() }

// the entries that get an icon in the bar, in this order; the others, not built yet, stay in the
// full menu behind ⋯
private val BAR_ITEMS = listOf(
    ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.LAYOUT,
    ViewerMenuItem.METRONOME, ViewerMenuItem.PLAYBACK,
)

private val BAR_BUTTON = 44.dp

/**
 * The top of the viewer as a row of icons, no text: the entries of the menu that work for this
 * score, the title and the page in small type in the room left, ⋯ for the full menu and the edit
 * toggle on the right. [open] is the entry whose panel shows (its icon is lit); [onSelect] gets
 * the middle of the icon tapped, where its panel hangs. With [tab] and a panel open, the bar's
 * lower corners square off so the panel reads as its continuation.
 */
@Composable
fun ViewerIconBar(
    title: String,
    pageLabel: String,
    isMusicXml: Boolean,
    editing: Boolean,
    open: ViewerMenuItem?,
    tab: Boolean,
    onSelect: (ViewerMenuItem, Dp) -> Unit,
    onMore: (Dp) -> Unit,
    onToggleEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val joined = tab && open != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (joined) 0.dp else 8.dp),
        shape = if (joined) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (joined) 0.dp else 4.dp,
    ) {
        Row(
            modifier = Modifier
                .height(48.dp + if (joined) 8.dp else 0.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BAR_ITEMS.filter { menuItemState(it, isMusicXml) == MenuItemState.READY }.forEach { item ->
                BarIcon(item.icon, item.label(), lit = item == open) { x -> onSelect(item, x) }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                if (pageLabel.isNotEmpty()) {
                    Text(pageLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
            BarIcon(LINE_MORE, stringResource(R.string.menu_label), lit = false, onClick = onMore)
            BarIcon(
                if (editing) LINE_DONE else LINE_EDIT,
                stringResource(if (editing) R.string.annotate_done else R.string.action_edit),
                lit = editing,
            ) { onToggleEditing() }
        }
    }
}

// one icon of the bar; [lit] while its panel shows. Reports the middle of the icon, in dp from the
// left of the screen, with the tap.
@Composable
private fun BarIcon(icon: String, label: String, lit: Boolean, onClick: (Dp) -> Unit) {
    val density = LocalDensity.current
    var middle by remember { mutableStateOf(0.dp) }
    val tint = if (lit) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    IconButton(
        onClick = { onClick(middle) },
        modifier = Modifier
            .size(BAR_BUTTON)
            .onGloballyPositioned { middle = with(density) { it.boundsInRoot().center.x.toDp() } }
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (lit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) { LineIcon(icon, size = 28.dp) }
        }
    }
}
