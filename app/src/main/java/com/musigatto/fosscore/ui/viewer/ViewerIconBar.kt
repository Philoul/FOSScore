package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R

/**
 * How the top of the viewer looks, chosen in the preferences:
 * - [MENU]: as before, the ☰ menu, the title pill and ✎;
 * - [ICONS]: a row of icons, ✎ among them in Annotate's place; each panel unfolds from the bar
 *   like a tab.
 * Annotating is the same with both: ✎ opens the annotation bar, which takes the top of the screen
 * until its ✕ or ✓.
 */
enum class ChromeStyle {
    MENU, ICONS;

    companion object {
        /** The style saved under [name], the menu when none (or an unknown one) was. */
        fun of(name: String?): ChromeStyle = entries.firstOrNull { it.name == name } ?: MENU
    }
}

/** The look of the top bar, for the panels: with [ChromeStyle.ICONS] they unfold from it. */
val LocalChromeStyle = compositionLocalOf { ChromeStyle.MENU }

// the entries that get an icon in the bar, in this order; the others, not built yet, stay in the
// full menu behind ⋯
private val BAR_ITEMS = listOf(
    ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY, ViewerMenuItem.LAYOUT,
    ViewerMenuItem.METRONOME, ViewerMenuItem.PLAYBACK, ViewerMenuItem.SETTINGS,
)

private val BAR_BUTTON = 36.dp
// the icon inside its button, and the margin it keeps from the button's edge
private val BAR_ICON = 24.dp
private val BAR_ICON_MARGIN = 12.dp
// the smallest an icon button gets on a narrow screen
private val BAR_BUTTON_MIN = 28.dp
// the share of the bar the title keeps beside the icons
private const val TITLE_SHARE = 0.2f
// the thin line between two groups of entries, as in the menu
private val SEPARATOR_HEIGHT = 24.dp
private val SEPARATOR_PADDING = 4.dp
private val SEPARATOR_WIDTH = 1.dp

/**
 * The entries of the bar for this score, in the menu's order: those that work for it, and ✎ in
 * Annotate's place (beside the pages) when it can be annotated.
 */
internal fun barItems(isMusicXml: Boolean, annotatable: Boolean): List<ViewerMenuItem> =
    (BAR_ITEMS.filter { menuItemState(it, isMusicXml) == MenuItemState.READY } +
        listOfNotNull(ViewerMenuItem.ANNOTATE.takeIf { annotatable })).sorted()

/**
 * The entries shown when only [fits] icons fit (⋯ apart): the first ones, ✎ always among them;
 * the others stay behind ⋯.
 */
internal fun itemsThatFit(items: List<ViewerMenuItem>, fits: Int): List<ViewerMenuItem> {
    if (items.size <= fits) return items
    val annotate = listOfNotNull(ViewerMenuItem.ANNOTATE.takeIf { it in items })
    return (items.filter { it != ViewerMenuItem.ANNOTATE }.take((fits - annotate.size).coerceAtLeast(0)) + annotate).sorted()
}

/**
 * The top of the viewer as a row of icons, no text: the entries of the menu that work for this
 * score in groups as in the menu, a thin line between them, ✎ in Annotate's place when the score
 * can be annotated ([annotatable]), ⋯ for the full menu right after the icons, the title and the
 * page in small type in the room left. [open] is the entry whose panel shows (its icon is framed);
 * with a panel open, the bar's lower corners square off so the panel reads as its continuation.
 * ✎ opens the annotation bar, which takes the place of this one while annotating.
 *
 * On a screen too narrow for every icon (a phone), the first ones show, ✎ always among them, and
 * ⋯ opens the menu with the others.
 */
@Composable
fun ViewerIconBar(
    title: String,
    pageLabel: String,
    isMusicXml: Boolean,
    annotatable: Boolean,
    open: ViewerMenuItem?,
    onSelect: (ViewerMenuItem) -> Unit,
    onMore: () -> Unit,
    onAnnotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val joined = open != null
    val items = barItems(isMusicXml, annotatable)
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
            // the icons that fit at their smallest, ⋯ included
            val fits = ((room - gap * separatorsOf(items)) / BAR_BUTTON_MIN).toInt() - 1
            val shown = itemsThatFit(items, fits)
            val button = ((room - gap * separatorsOf(shown)) / (shown.size + 1)).coerceIn(BAR_BUTTON_MIN, BAR_BUTTON)
            Row(
                modifier = Modifier.height(48.dp + if (joined) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEachIndexed { i, item ->
                    if (i > 0 && item.group != shown[i - 1].group) GroupSeparator()
                    if (item == ViewerMenuItem.ANNOTATE) {
                        BarIcon(LINE_EDIT, stringResource(R.string.action_edit), panelOpen = false, size = button, onClick = onAnnotate)
                    } else {
                        BarIcon(item.icon, item.label(), panelOpen = item == open, size = button) { onSelect(item) }
                    }
                }
                BarIcon(LINE_MORE, stringResource(R.string.menu_label), panelOpen = false, size = button, onClick = onMore)
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

// one icon of the bar, [size] wide; framed while its panel shows, as an annotation tool is while its
// settings show: a frame means a panel open, everywhere
@Composable
private fun BarIcon(icon: String, label: String, panelOpen: Boolean, size: Dp, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(size - 4.dp)
                .clip(CircleShape)
                .then(if (panelOpen) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { LineIcon(icon, size = minOf(BAR_ICON, size - BAR_ICON_MARGIN)) }
        }
    }
}
