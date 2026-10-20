package com.musigatto.fosscore.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R

/**
 * Title bar of the viewer, drawn over the page: the menu button on the left, the score and page in
 * the middle, and while annotating a "Done" pill on the right. No state of its own: everything comes
 * in as a parameter and goes out as a lambda.
 *
 * The row deliberately has NO clickable full-width background: it would eat the page taps in the gaps
 * between pills, which are the navigation (spec §3).
 */
@Composable
fun ViewerTitleBar(
    title: String,
    composer: String,
    pageLabel: String,
    editing: Boolean,
    onOpenMenu: () -> Unit,
    onStopEditing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // one entry for everything else (library, display, layout, annotate...): ViewerMenuPanel
        Pill {
            PillEntry("☰", stringResource(R.string.menu_label), onClick = onOpenMenu)
        }
        Spacer(Modifier.width(8.dp))
        // center pill: "what am I reading". It takes the leftover width and the darker tone, so it
        // reads as the main zone.
        Pill(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    listOf(title, composer).filter(String::isNotBlank).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (pageLabel.isNotEmpty()) {
                    Text(pageLabel, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // while annotating, the way out stays in sight: the accent container is the M3 "active"
        // tone, and it reads from across the room
        if (editing) {
            Spacer(Modifier.width(8.dp))
            Pill(color = MaterialTheme.colorScheme.primaryContainer) {
                PillEntry("✓", stringResource(R.string.annotate_done), selected = true, onClick = onStopEditing)
            }
        }
    }
}

/**
 * Display panel, as a checklist: one row per option, the control in a fixed left slot, so the rows
 * never change width with their label.
 */
@Composable
fun DisplayOptionsOverlay(
    halfEnabled: Boolean,
    onSetHalf: (Boolean) -> Unit,
    twoUp: Boolean,
    onSetTwoUp: (Boolean) -> Unit,
    /** Two-up only means anything in landscape (`PageFlow.showTwoUp`); in portrait the row is not drawn. */
    twoUpVisible: Boolean,
    themeLabel: String,
    onToggleTheme: () -> Unit,
    invert: Boolean,
    onToggleInvert: () -> Unit,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    dimPct: Float,
    onDimChange: (Float) -> Unit,
    onDimChangeFinished: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelOverlay(stringResource(R.string.display_options_title), onDismiss, modifier) {
        // the page modes are exclusive, and what is ticked is what the viewer is showing:
        // half wins over two-up (PageFlow.showTwoUp requires !halfEnabled)
        val twoUpChecked = twoUpVisible && twoUp && !halfEnabled
        SectionLabel(stringResource(R.string.page_section_label))
        OptionRow(stringResource(R.string.half_page_label), halfEnabled, Role.RadioButton) {
            onSetHalf(true)
        }
        OptionRow(stringResource(R.string.one_page_label), !halfEnabled && !twoUpChecked, Role.RadioButton) {
            onSetHalf(false)
            onSetTwoUp(false)
        }
        if (twoUpVisible) {
            OptionRow(stringResource(R.string.two_pages_label), twoUpChecked, Role.RadioButton) {
                onSetHalf(false)
                onSetTwoUp(true)
            }
        }
        SectionLabel(stringResource(R.string.screen_section_label))
        OptionRow(stringResource(R.string.neg_label), invert, Role.Checkbox, onClick = onToggleInvert)
        OptionRow(stringResource(R.string.keep_screen_on_label), keepScreenOn, Role.Checkbox, onClick = onToggleKeepScreenOn)
        ValueRow(stringResource(R.string.theme_row_label), themeLabel, onToggleTheme)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dim_label), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = dimPct,
                onValueChange = onDimChange,
                onValueChangeFinished = onDimChangeFinished,
                valueRange = 0f..80f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text("${dimPct.toInt()}%", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The frame of every settings panel of the viewer: a scrim that closes on tap, and a centered card
 * with a title, a close button and [content]. The card scrolls when the screen is short (a phone in
 * landscape). Closes with back too.
 *
 * ponytail: it does not carry spec §7's modal ✕/✓ chrome — it is an overlay, not an inspector, and it
 * holds nothing that can be discarded: every change applies at once. If a panel ever holds unapplied
 * changes, that chrome becomes mandatory.
 */
@Composable
internal fun PanelOverlay(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            // swallow taps on the panel: without this they reach the scrim behind and dismiss
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(16.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    val closeLabel = stringResource(R.string.action_close)
                    TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                        Text("✕", style = MaterialTheme.typography.titleMedium)
                    }
                }
                content()
            }
        }
    }
}

/** Group header of the checklist. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One option row: the control sits in a fixed left slot, so a longer label only grows to the right
 * (and may wrap) — the row never changes width under the finger.
 */
@Composable
internal fun OptionRow(
    label: String,
    checked: Boolean,
    role: Role,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, enabled = enabled, role = role, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (role == Role.RadioButton) RadioButton(selected = checked, onClick = null, enabled = enabled)
        else Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** − value + on the right of the label; each tap is one step, so the value never jumps under the finger. */
@Composable
internal fun StepperRow(
    label: String,
    value: String,
    lessLabel: String,
    moreLabel: String,
    canLess: Boolean,
    canMore: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onLess, enabled = canLess, modifier = Modifier.semantics { contentDescription = lessLabel }) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(value, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onMore, enabled = canMore, modifier = Modifier.semantics { contentDescription = moreLabel }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Cycles on tap. The value sits on the right, so the row's width does not follow the label either. */
@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$value ▸", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Rounded container for a stack of entries. [color] is the pill's own tone. */
@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = color,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            content = content,
        )
    }
}

/**
 * Glyph + label entry. The glyph takes the accent, the label the plain content color, so the pill
 * reads as a Material component instead of a gray slab; [selected] switches both to the accent
 * container's content color. The label is what a screen reader announces.
 */
@Composable
private fun PillEntry(icon: String, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val glyphColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.primary
    val labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(icon, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = glyphColor)
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = labelColor)
    }
}
