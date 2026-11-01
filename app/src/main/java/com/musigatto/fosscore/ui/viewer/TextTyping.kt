package com.musigatto.fosscore.ui.viewer

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import android.view.inputmethod.InputMethodManager
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Stamp
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
        .copy(x = centre.x, y = centre.y, size = draft.size, color = draft.color, text = draft.text)
}

/**
 * What the typing of [draft] leaves: [typed] is [draftStamp]. The spaces and empty lines left at
 * the end take room for nothing and go; a text left empty is no text at all.
 */
internal fun typingOutcome(draft: TextDraft, typed: Stamp): TypingOutcome {
    val text = typed.text.orEmpty().trimEnd()
    val origin = draft.origin
    return when {
        origin == null && text.isBlank() -> TypingOutcome.Nothing
        origin == null -> TypingOutcome.Insert(typed.copy(text = text))
        text.isBlank() -> TypingOutcome.Delete(origin)
        origin.text == text && origin.size == typed.size && origin.color == typed.color -> TypingOutcome.Nothing
        else -> TypingOutcome.Update(origin, typed.copy(text = text))
    }
}

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
        textStyle = LocalTextStyle.current.copy(fontSize = textFontSp(fontPx, density), color = color),
        cursorBrush = SolidColor(color),
        // an empty new text shows its size and colour, faintly, until the first letter
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.text_placeholder),
                        style = LocalTextStyle.current.copy(fontSize = textFontSp(fontPx, density), color = color.copy(alpha = 0.4f)),
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
