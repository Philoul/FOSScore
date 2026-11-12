package com.musigatto.fosscore.ui.viewer

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.view.inputmethod.InputMethodManager
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A text being typed on the page (#35): written where it shows, at its size and in its colour,
 * with no dialog in between. [origin] is the text stamp it changes, null for a new one. Its place
 * is the top left corner of its box ([left], [top], page fractions): the first line stays where it
 * was tapped and the text grows to the right and down, as on paper. Nothing is written to the
 * library until the typing is over ([typingOutcome]).
 */
internal data class TextDraft(
    val page: Int,
    val origin: Stamp?,
    val text: String,
    val size: Float,
    val color: Int?,
    val left: Float,
    val top: Float,
    // where the box was when the typing started: moving it from there is what the handle changed
    val originLeft: Float = left,
    val originTop: Float = top,
    // wrapping width as a share of the page width; null = a text from before widths existed
    val width: Float? = null,
    // null = the stamp keeps the font it had (old texts stored none)
    val font: TextFont? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** What the end of a typing does to the library. */
internal sealed interface TypingOutcome {
    /** A new text, typed and not left empty. */
    data class Insert(val stamp: Stamp) : TypingOutcome
    /** A text changed: the same row, its words, size, colour or place new. */
    data class Update(val origin: Stamp, val stamp: Stamp) : TypingOutcome
    /** A text emptied: it goes. */
    data class Delete(val origin: Stamp) : TypingOutcome
    /** Nothing typed, or nothing changed. */
    data object Nothing : TypingOutcome
}

/**
 * The stamp of [draft] once typed: its box ([width] x [height], measured as the page draws it)
 * with its top left corner at the draft's place, kept inside the page, and the stamp's point at
 * the box's centre (the convention of every stamp, [textBoxRect]).
 */
internal fun draftStamp(draft: TextDraft, sheetId: Long, width: Float, height: Float, fit: RectPx): Stamp {
    val w = width.coerceIn(1f, fit.width.coerceAtLeast(1f))
    val h = height.coerceIn(1f, fit.height.coerceAtLeast(1f))
    val left = (draft.left * fit.width).coerceIn(0f, (fit.width - w).coerceAtLeast(0f))
    val top = (draft.top * fit.height).coerceIn(0f, (fit.height - h).coerceAtLeast(0f))
    val centre = pxToNormalized(Offset(left + w / 2f, top + h / 2f), fit)
    val base = draft.origin
        ?: Stamp(sheetId = sheetId, page = draft.page, symbol = Stamp.TEXT_SYMBOL, x = 0f, y = 0f, size = draft.size)
    return base
        .copy(
            x = centre.x,
            y = centre.y,
            size = draft.size,
            color = draft.color,
            text = draft.text,
            textWidth = draft.width,
            font = draft.font?.name,
            bold = draft.bold,
            italic = draft.italic,
        )
}

/** What the typing of [draft] leaves: [typed] is [draftStamp]. The spaces and empty lines left at
 * the end take room for nothing and go; a text left empty is no text at all.
 *
 * The place and the width count too: the handles move the box and the right one changes its width,
 * so a text left where it was found writes nothing while one moved or resized does.
 */
internal fun typingOutcome(draft: TextDraft, typed: Stamp): TypingOutcome {
    val text = typed.text.orEmpty().trimEnd()
    val origin = draft.origin
    val moved = origin != null &&
        (abs(draft.left - draft.originLeft) > TEXT_MOVE_EPSILON ||
            abs(draft.top - draft.originTop) > TEXT_MOVE_EPSILON)
    return when {
        origin == null && text.isBlank() -> TypingOutcome.Nothing
        origin == null -> TypingOutcome.Insert(typed.copy(text = text))
        text.isBlank() -> TypingOutcome.Delete(origin)
        origin.text == text && origin.size == typed.size && origin.color == typed.color &&
            origin.textWidth == typed.textWidth && origin.font == typed.font &&
            origin.bold == typed.bold && origin.italic == typed.italic && !moved ->
            TypingOutcome.Nothing
        else -> TypingOutcome.Update(origin, typed.copy(text = text))
    }
}

// how far a text may move before it counts as a change (page shares; the handles do not jitter)
internal const val TEXT_MOVE_EPSILON = 1e-4f

/**
 * The top-left corner after dragging the left handle by [dx]/[dy] px: the whole box moves and stops
 * at the page's edge.
 */
internal fun movedTextCorner(
    draft: TextDraft,
    dx: Float,
    dy: Float,
    boxW: Float,
    boxH: Float,
    fit: RectPx,
): Offset {
    val w = fit.width.coerceAtLeast(1f)
    val h = fit.height.coerceAtLeast(1f)
    val left = (draft.left * w + dx).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
    val top = (draft.top * h + dy).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
    return Offset(left / w, top / h)
}

/**
 * The width (page share) after dragging the right handle by [dx] px: the left edge does not move,
 * and the box neither leaves the page nor goes under [MIN_TEXT_WIDTH].
 */
internal fun resizedTextWidth(draft: TextDraft, boxW: Float, dx: Float, fit: RectPx): Float {
    val w = fit.width.coerceAtLeast(1f)
    val left = draft.left * w
    val max = (w - left).coerceAtMost(w * MAX_TEXT_WIDTH)
    val min = (w * MIN_TEXT_WIDTH).coerceAtMost(max)
    return ((boxW + dx).coerceIn(min, max) / w).coerceIn(MIN_TEXT_WIDTH, MAX_TEXT_WIDTH)
}

/** The size (px at zoom 1), the colour and the typeface the text panel shows. */
internal data class TextPanelValues(
    val px: Float,
    val color: Int?,
    val font: TextFont,
    val bold: Boolean,
    val italic: Boolean,
)

/**
 * What the text panel shows: the size, colour and typeface of the text being typed, so that editing
 * a text shows its own; [nextPx], [nextColor], [nextFont], [nextBold] and [nextItalic] (those of the
 * next new text) the rest of the time.
 */
internal fun textPanelValues(
    typing: TextDraft?,
    nextPx: Float,
    nextColor: Int?,
    nextFont: TextFont,
    nextBold: Boolean,
    nextItalic: Boolean,
    fit: RectPx,
): TextPanelValues = typing?.let {
    TextPanelValues(textFontPx(it.size, fit), it.color, it.font ?: TextFont.SANS, it.bold, it.italic)
} ?: TextPanelValues(nextPx, nextColor, nextFont, nextBold, nextItalic)

/**
 * The top left corner where a new text starts, from the point tapped ([tap], page fractions): the
 * middle of its first line on the point, as a pen would start there. [lineHeight] is the height of
 * a line in px.
 */
internal fun newTextCorner(tap: Offset, lineHeight: Float, fit: RectPx): Offset =
    Offset(tap.x, tap.y - lineHeight / 2f / fit.height.coerceAtLeast(1f))

/**
 * The bottom of what the keyboard leaves visible, in px from the top of the window: the window's
 * own bottom while there is no keyboard. Read from the window frame, which every Android version
 * reports (the keyboard insets are not there on Android 7).
 */
@Composable
internal fun rememberVisibleBottom(): State<Int> {
    val view = LocalView.current
    val bottom = remember { mutableIntStateOf(Int.MAX_VALUE) }
    DisposableEffect(view) {
        val frame = Rect()
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(frame)
            bottom.intValue = frame.bottom
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return bottom
}

/**
 * The keyboard's way onto the page: the text being typed, drawn by the field itself at the place,
 * the size and in the colour the page will draw it ([TextStampView]: the same style, the same
 * wrapping width). It lives in the page's coordinates (the caller puts it in the layer that zooms
 * the stamps), so it stays on its text at any zoom. Every key goes to [onText]; Enter is a new line. Losing the keys is [onDone].
 * [key] names the text: another one starts with its own [initial] text, the cursor after it.
 */
@Composable
internal fun TypingField(
    key: Any,
    initial: String,
    topLeft: Offset,
    fontPx: Float,
    maxWidthPx: Float,
    color: Color,
    onText: (String) -> Unit,
    onDone: () -> Unit,
    font: TextFont = TextFont.SANS,
    bold: Boolean = false,
    italic: Boolean = false,
) {
    var value by remember(key) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    // the field had the keys: losing them (Back once the keyboard is down, a tap on the bar) ends
    // the typing, as a tap elsewhere on the page does
    var focused by remember(key) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val density = LocalDensity.current
    val label = stringResource(R.string.tool_text)
    BasicTextField(
        value = value,
        onValueChange = {
            val changed = it.text != value.text
            value = it
            if (changed) onText(it.text)
        },
        textStyle = textStampStyle(LocalTextStyle.current, fontPx, density, color, font, bold, italic),
        cursorBrush = SolidColor(color),
        // an empty new text shows its size and colour, faintly, until the first letter
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.text_placeholder),
                        style = textStampStyle(
                            LocalTextStyle.current, fontPx, density, color.copy(alpha = 0.4f),
                            font, bold, italic,
                        ),
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .widthIn(max = with(density) { maxWidthPx.coerceAtLeast(1f).toDp() })
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) onDone()
                focused = it.isFocused
            }
            .semantics { contentDescription = label },
    )
    // the keyboard comes up at once: the tool was taken to type
    LaunchedEffect(key) {
        focus.requestFocus()
        keyboard?.show()
    }
    // and goes with the field: on Android 7 it stayed over the page once nothing had the focus
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.hide()
            view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}

/**
 * The two white circles of a text being typed, as the reference implementation shows them: the left one moves the whole
 * box, the right one changes its width with the left edge fixed (the height follows the wrapping).
 * They sit in the page's coordinates, in the same layer as the field, so they stay on the box at any
 * zoom.
 */
@Composable
internal fun TextBoxHandles(
    box: RectPx,
    onMove: (Offset) -> Unit,
    onResize: (Float) -> Unit,
) {
    val move = rememberUpdatedState(onMove)
    val resize = rememberUpdatedState(onResize)
    val moveLabel = stringResource(R.string.text_move_handle)
    val widthLabel = stringResource(R.string.text_width_handle)

    val gap = with(LocalDensity.current) { 14.dp.toPx() }

    HandleDot(Offset(box.left - gap, box.center().y), moveLabel) { move.value(it) }
    HandleDot(Offset(box.right, box.center().y), widthLabel) { resize.value(it.x) }
}

@Composable
private fun HandleDot(center: Offset, label: String, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val radius = with(density) { 10.dp.toPx() }
    val drag = rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()) }
            .size(20.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag.value(amount)
                }
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White)
            drawCircle(Color.Black, style = DrawStroke(width = 2.dp.toPx()))
        }
    }
}
