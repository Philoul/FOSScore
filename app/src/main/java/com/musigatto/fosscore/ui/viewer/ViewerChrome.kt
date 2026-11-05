package com.musigatto.fosscore.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// a held − / + waits this long, then repeats at this pace
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 70L
// a typed number: its prefix or suffix ("♩ =", "%") and up to three digits
private val NUMBER_FIELD_WIDTH = 112.dp

/**
 * Title bar of the viewer, drawn over the page: the menu button on the left, the score and page in
 * the middle, and the edit toggle on the right (✎ to pick up the pen). While annotating the toggle
 * steps aside: ✓ lives at the end of the annotation bar, which is where the tools are (issue #50).
 * No state of its own: everything comes in as a parameter and goes out as a lambda.
 *
 * The row deliberately has NO clickable full-width background: it would eat the page taps in the gaps
 * between pills, which are the navigation (spec §3).
 */
/**
 * Whether the title bar offers ✎: not while annotating (✓ ends it), and not on a score that cannot
 * be annotated (no sheet in the library), where it would open nothing.
 */
internal fun editEntryShown(editing: Boolean, annotatable: Boolean): Boolean = !editing && annotatable

/** The score open is the [number]th of the [size] places of the setlist [name]. */
data class SetlistPlace(val name: String, val number: Int, val size: Int) {
    /** "2/8": the place, as the title bar shows it under the setlist's name. */
    val label: String get() = "$number/$size"
}

@Composable
fun ViewerTitleBar(
    title: String,
    composer: String,
    pageLabel: String,
    editing: Boolean,
    onOpenMenu: () -> Unit,
    onToggleEditing: () -> Unit,
    modifier: Modifier = Modifier,
    annotatable: Boolean = true,
    setlistPlace: SetlistPlace? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // rule 7 of the spec: the extra room depends on the width, not on the device. On a phone the
        // pills keep only their glyphs, or the centre zone is left with no room for the title (#19).
        val compact = maxWidth < 480.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // one entry for everything else (library, display, layout, annotate...): ViewerMenuPanel
            Pill {
                PillEntry(LINE_MENU, stringResource(R.string.menu_label), compact = compact, onClick = onOpenMenu)
            }
            Spacer(Modifier.width(8.dp))
            // center pill: "what am I reading". It takes the leftover width and the darker tone, so it
            // reads as the main zone.
            Pill(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // in a setlist: its name, and the score's place in it, before the title
                    setlistPlace?.let { place ->
                        Column(
                            modifier = Modifier.widthIn(max = if (compact) 72.dp else 160.dp).padding(start = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            Text(place.label, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        }
                        VerticalDivider(Modifier.fillMaxHeight().padding(start = 12.dp, top = 6.dp, bottom = 6.dp))
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
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
            }
            // the pen is picked up here and put down with ✓ at the end of the annotation bar (spec
            // §5 row 1): while annotating the toggle steps aside instead of meaning something else
            // (issue #50). forScore does the same: its own row 1 replaces the title bar.
            if (editEntryShown(editing, annotatable)) {
                Spacer(Modifier.width(8.dp))
                Pill {
                    PillEntry(
                        LINE_EDIT,
                        stringResource(R.string.action_edit),
                        compact = compact,
                        onClick = onToggleEditing,
                    )
                }
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
    /** While annotating, the page modes are frozen: they turn canEdit off and leave the pen inert. */
    pageModes: Boolean,
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
        OptionRow(stringResource(R.string.half_page_label), halfEnabled, Role.RadioButton, enabled = pageModes) {
            onSetHalf(true)
        }
        OptionRow(stringResource(R.string.one_page_label), !halfEnabled && !twoUpChecked, Role.RadioButton, enabled = pageModes) {
            onSetHalf(false)
            onSetTwoUp(false)
        }
        if (twoUpVisible) {
            OptionRow(stringResource(R.string.two_pages_label), twoUpChecked, Role.RadioButton, enabled = pageModes) {
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
/** Where the panels start: under the bar at the top of the viewer while it shows. */
val LocalPanelTop = compositionLocalOf { 0.dp }

@Composable
internal fun PanelOverlay(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // at the top: the bottom of the page stays readable (the metronome counts its bars there)
    alignment: Alignment = Alignment.TopCenter,
    maxWidth: Dp = 420.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // with the icon bar, the panel unfolds from it like a tab, as wide as it, and the scrim starts
    // under it: the bar stays lit, so one tap goes from a panel to the next
    val icons = LocalChromeStyle.current == ChromeStyle.ICONS
    // under the status bar at least, when the bar at the top is hidden
    val top = maxOf(LocalPanelTop.current, WindowInsets.systemBars.asPaddingValues().calculateTopPadding())
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (icons) {
                    Modifier
                        .padding(top = top)
                        .background(Color.Black.copy(alpha = 0.2f))
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        // the panel clear of a navigation bar on the side or at the bottom
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                } else {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.4f))
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                }
            ),
        contentAlignment = if (icons) Alignment.TopStart else alignment,
    ) {
        Surface(
            shape = if (icons) RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp) else RoundedCornerShape(16.dp),
            shadowElevation = if (icons) 6.dp else 0.dp,
            // swallow taps on the panel: without this they reach the scrim behind and dismiss
            modifier = (
                if (icons) Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                else Modifier.padding(top = LocalPanelTop.current).widthIn(max = maxWidth).padding(16.dp)
            ).pointerInput(Unit) { detectTapGestures { } },
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
                        LineIcon(LINE_CLOSE)
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
            // greyed out, not just unresponsive: the control says why it does nothing
            .alpha(if (enabled) 1f else 0.38f)
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

// the pause in the typing after which a number typed applies
private const val TYPED_PAUSE_MS = 800

/**
 * − [value] +, as AndroidAPS's NumberInputRow: the value is typed in place (kept with Done or when
 * the field loses focus, and brought into [range], shown under the field while typing; a value in
 * [range] also applies once the typing pauses), and − / +
 * move it to the next multiple of [step] on each press, at once (not on release), repeating while
 * held after a short wait.
 */
@Composable
internal fun NumberFieldRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    lessLabel: String,
    moreLabel: String,
    rangeText: String,
    onValue: (Int) -> Unit,
    prefix: String? = null,
    suffix: String? = null,
) {
    // the held button reads the value it changes, not the one it saw when the press began
    val current by rememberUpdatedState(value)
    val apply by rememberUpdatedState(onValue)
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(TextFieldValue(value.toString())) }
    var focused by remember { mutableStateOf(false) }
    val typed = text.text.toIntOrNull()
    val wrong = typed == null || typed !in range
    // − / + and other controls change the value from outside the field
    LaunchedEffect(value) { if (typed != value) text = TextFieldValue(value.toString()) }
    // a value typed is laid out once the typing pauses, the field still open: its result shows
    // without leaving the panel (the keyboard closed with Back kept the focus, so nothing changed)
    LaunchedEffect(text.text) {
        val live = typedToApply(text.text, range, current) ?: return@LaunchedEffect
        delay(TYPED_PAUSE_MS.milliseconds)
        apply(live)
    }
    fun keep() {
        val kept = (typed ?: current).coerceIn(range)
        text = TextFieldValue(kept.toString())
        if (kept != current) onValue(kept)
    }
    // the panel may close while the field still has the focus
    val keepLatest by rememberUpdatedState(::keep)
    val focusedLatest by rememberUpdatedState(focused)
    DisposableEffect(Unit) { onDispose { if (focusedLatest) keepLatest() } }
    fun stepBy(direction: Int) {
        focus.clearFocus()
        onValue(stepped(current, step, direction).coerceIn(range))
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RepeatButton("−", lessLabel, enabled = value > range.first) { stepBy(-1) }
        OutlinedTextField(
            value = text,
            onValueChange = { new -> text = new.copy(text = new.text.filter(Char::isDigit).take(4)) },
            prefix = prefix?.let { { Text(it) } },
            suffix = suffix?.let { { Text(it) } },
            singleLine = true,
            isError = wrong,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .width(NUMBER_FIELD_WIDTH)
                .onFocusChanged { state ->
                    // leaving the field keeps what was typed; entering it selects the value, to type over it
                    if (focused && !state.isFocused) keep()
                    if (!focused && state.isFocused) text = text.copy(selection = TextRange(0, text.text.length))
                    focused = state.isFocused
                },
        )
        RepeatButton("+", moreLabel, enabled = value < range.last) { stepBy(1) }
    }
    if (focused) {
        Text(
            rangeText,
            style = MaterialTheme.typography.bodySmall,
            color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The value typed in a number field to lay out now, the field still open: a whole number in
 * [range] other than [current]; null while it is not one (still being typed, out of range).
 */
internal fun typedToApply(text: String, range: IntRange, current: Int): Int? =
    text.toIntOrNull()?.takeIf { it in range && it != current }

/** The next multiple of [step] above [value] (direction 1) or below it (-1): 87 › 90 or 85 by 5. */
internal fun stepped(value: Int, step: Int, direction: Int): Int =
    if (direction > 0) Math.floorDiv(value, step) * step + step
    else Math.floorDiv(value + step - 1, step) * step - step

/** A button that acts on press, then again and again while held. */
@Composable
internal fun RepeatButton(text: String, label: String, enabled: Boolean, size: Dp = 48.dp, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val color = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier = Modifier
            .size(size)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { step(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    step()
                    val repeating = scope.launch {
                        delay(REPEAT_DELAY_MS.milliseconds)
                        while (true) {
                            step()
                            delay(REPEAT_EVERY_MS.milliseconds)
                        }
                    }
                    waitForUpOrCancellation()
                    repeating.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = color)
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
 * Icon + label entry. The icon (a LineIcons path) takes the accent, the label the plain content
 * color, so the pill reads as a Material component instead of a gray slab; [selected] switches both
 * to the accent container's content color. The label is what a screen reader announces.
 */
@Composable
private fun PillEntry(
    icon: String,
    label: String,
    compact: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val glyphColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.primary
    val labelColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        // without its label the glyph has no name of its own: keep the label spoken
        modifier = if (compact) Modifier.semantics { contentDescription = label } else Modifier,
    ) {
        CompositionLocalProvider(LocalContentColor provides glyphColor) { LineIcon(icon, size = 20.dp) }
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = labelColor)
        }
    }
}

/**
 * Tap of a tool button (issue #56): a tool is chosen, never turned off. Tapping the one in hand
 * opens its options, tapping it again closes them and leaves it in hand. Returns whether its panel
 * should be open.
 */
fun nextToolTap(active: Boolean, panelOpen: Boolean): Boolean = active && !panelOpen

/**
 * Compact block that drops from the annotation bar: the shape every tool panel shares (stamps, pen,
 * eraser, colour). It floats over the page instead of painting a band across it (spec §5), and the
 * fixed width keeps all of them the same so the bar never reshuffles: wide enough for a row of
 * eight stamps or eight colours, so the panels take width rather than half a phone's height.
 */
/**
 * A size slider's value, to the pixel. The sliders used to step by 1 px through `steps`, which
 * Material draws as one tick per step: 18 dots on the pen's 1-20 px, a dashed line on the stamps'
 * 8-100 px. Rounded here instead, every size slider looks the same.
 */
internal fun wholePx(value: Float): Float = value.roundToInt().toFloat()

// every tool panel: eight stamps or the eight colours a row, and a slider no taller than its thumb
internal const val TOOL_PANEL_DP = 320
internal const val SWATCHES_PER_ROW = 8
internal const val TOOL_SLIDER_DP = 32

@Composable
fun ToolBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        // ponytail: fixed alpha; make it a setting if it hides the music you are working on
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .padding(start = 12.dp, bottom = 4.dp)
            .width(TOOL_PANEL_DP.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

/**
 * Tool panel with one labelled slider: pen thickness, eraser size (issue #59: every size control
 * is − slider +, the pattern the stamp's size follows). [lead] sits above it, for the settings a
 * tool shares a panel with (the pen's colour, issue #56).
 */
@Composable
fun ToolSizePanel(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    lead: (@Composable ColumnScope.() -> Unit)? = null,
    // the size the tool starts with next time: saved by the button beside the label
    onSaveDefault: (() -> Unit)? = null,
    isDefault: Boolean = true,
) {
    ToolBlock {
        lead?.invoke(this)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$label · ${value.roundToInt()} px",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onSaveDefault != null) {
                TextButton(
                    onClick = onSaveDefault,
                    enabled = !isDefault,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(TOOL_SLIDER_DP.dp),
                ) {
                    Text(
                        stringResource(R.string.stamp_default_save),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepArrow(label = "-") { onValue((value - 1f).coerceIn(range.start, range.endInclusive)) }
            Slider(
                value = value,
                onValueChange = { onValue(wholePx(it)) },
                valueRange = range,
                modifier = Modifier
                    .weight(1f)
                    .height(TOOL_SLIDER_DP.dp)
            )
            StepArrow(label = "+") { onValue((value + 1f).coerceIn(range.start, range.endInclusive)) }
        }
    }
}

/**
 * The tint swatches, the same grid wherever a tool has a colour (issue #56: the colour lives in the
 * panel of the tool that uses it). The null entry is the default colour. Every swatch shows what
 * really lands on the page: the same inkTint the ink takes, so in negative mode the swatches are
 * shown inverted (the white swatch shows black, the default shows white) — the square never lies
 * about the ink (issue #35).
 * [defaultColor] is the ARGB to show for the "default" (null) swatch; if null, uses theme color.
 */
@Composable
fun ColorChoices(
    active: Int?,
    invert: Boolean,
    onPick: (Int?) -> Unit,
    perRow: Int = SWATCHES_PER_ROW,
    defaultColor: Int? = null,
) {
    val defaultSwatch = defaultColor ?: inkTint(null, invert).toArgb()
    (listOf<Int?>(defaultColor) + SWATCH_COLORS.map { it.toArgb() }).chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { argb ->
                val swatchColor = if (argb == null) Color(defaultSwatch) else inkTint(argb, invert)
                ColorSwatchButton(
                    color = swatchColor,
                    selected = active == argb,
                    onClick = { onPick(argb) }
                )
            }
        }
    }
}
