package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

private val BAR_BUTTON = 36.dp
// the icon inside its button, and the margin it keeps from the button's edge
private val BAR_ICON = 24.dp
private val BAR_ICON_MARGIN = 12.dp
// the smallest an icon button gets when every entry has to fit in the bar
private val BAR_BUTTON_MIN = 28.dp
// the share of the bar the title keeps beside the icons
private const val TITLE_SHARE = 0.2f
// the thin line between two groups of entries, as in the menu
private val SEPARATOR_HEIGHT = 24.dp
private val SEPARATOR_PADDING = 4.dp
private val SEPARATOR_WIDTH = 1.dp

/**
 * The top of the viewer as a row of icons, no text: the entries of the menu that work for this
 * score in groups as in the menu, a thin line between them, the edit toggle in Annotate's place,
 * the title and the page in small type in the room left, ⋯ for the full menu on the right. [open] is the entry whose panel shows (its icon is lit); [onSelect] gets
 * the middle of the icon tapped, where its panel hangs. With [tab] and a panel open, the bar's
 * lower corners square off so the panel reads as its continuation.
 *
 * With [allIcons] (a preview of the finished app), every entry of the menu that fits the file has
 * its icon, greyed out while it is not built, and there is no ⋯. The buttons shrink to leave the title some room;
 * on a screen too narrow for them all, the first ones show and ⋯ opens the menu.
 */
@Composable
fun ViewerIconBar(
    title: String,
    pageLabel: String,
    isMusicXml: Boolean,
    editing: Boolean,
    open: ViewerMenuItem?,
    tab: Boolean,
    allIcons: Boolean,
    onSelect: (ViewerMenuItem, Dp) -> Unit,
    onMore: (Dp) -> Unit,
    onToggleEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val joined = tab && open != null
    // in the menu's order; the edit toggle stands for Annotate, in its place (beside the pages)
    val items = if (allIcons) ViewerMenuItem.entries.filter { fitsFile(it, isMusicXml) }
    else (BAR_ITEMS.filter { menuItemState(it, isMusicXml) == MenuItemState.READY } + ViewerMenuItem.ANNOTATE).sorted()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (joined) 0.dp else 8.dp),
        shape = if (joined) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (joined) 0.dp else 4.dp,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 6.dp)) {
            val room = maxWidth * (1 - TITLE_SHARE)
            val gap = SEPARATOR_WIDTH + SEPARATOR_PADDING * 2
            fun separatorsOf(list: List<ViewerMenuItem>) = list.zipWithNext().count { (a, b) -> a.group != b.group }
            // on a narrow screen (a phone), the icons that fit at their smallest, the edit toggle
            // always among them, and the others behind ⋯
            val fits = ((room - gap * separatorsOf(items)) / BAR_BUTTON_MIN).toInt()
            val all = items.size + (if (allIcons) 0 else 1) <= fits
            val shown = if (all) items
            else (items.filter { it != ViewerMenuItem.ANNOTATE }.take((fits - 2).coerceAtLeast(0)) + ViewerMenuItem.ANNOTATE).sorted()
            val more = !allIcons || !all
            val button = ((room - gap * separatorsOf(shown)) / (shown.size + if (more) 1 else 0))
                .coerceIn(BAR_BUTTON_MIN, BAR_BUTTON)
            Row(
                modifier = Modifier.height(48.dp + if (joined) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEachIndexed { i, item ->
                    if (i > 0 && item.group != shown[i - 1].group) GroupSeparator()
                    if (item == ViewerMenuItem.ANNOTATE) {
                        BarIcon(
                            if (editing) LINE_DONE else LINE_EDIT,
                            stringResource(if (editing) R.string.annotate_done else R.string.action_edit),
                            lit = editing,
                            size = button,
                        ) { onToggleEditing() }
                    } else {
                        val ready = menuItemState(item, isMusicXml) == MenuItemState.READY
                        BarIcon(item.icon, item.label(), lit = item == open, size = button, enabled = ready) { x -> onSelect(item, x) }
                    }
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
                if (more) BarIcon(LINE_MORE, stringResource(R.string.menu_label), lit = false, size = button, onClick = onMore)
            }
        }
    }
}

// the thin line between two groups of entries
@Composable
private fun GroupSeparator() {
    Box(
        Modifier
            .padding(horizontal = SEPARATOR_PADDING)
            .width(SEPARATOR_WIDTH)
            .height(SEPARATOR_HEIGHT)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// one icon of the bar, [size] wide; [lit] while its panel shows, greyed out unless [enabled].
// Reports the middle of the icon, in dp from the left of the screen, with the tap.
@Composable
private fun BarIcon(icon: String, label: String, lit: Boolean, size: Dp, enabled: Boolean = true, onClick: (Dp) -> Unit) {
    val density = LocalDensity.current
    var middle by remember { mutableStateOf(0.dp) }
    val tint = when {
        lit -> MaterialTheme.colorScheme.onPrimaryContainer
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    IconButton(
        onClick = { onClick(middle) },
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .onGloballyPositioned { middle = with(density) { it.boundsInRoot().center.x.toDp() } }
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(size - 4.dp)
                .clip(CircleShape)
                .background(if (lit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides tint) { LineIcon(icon, size = minOf(BAR_ICON, size - BAR_ICON_MARGIN)) }
        }
    }
}
