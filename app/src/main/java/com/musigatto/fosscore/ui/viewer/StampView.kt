package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.musigatto.fosscore.R
import kotlin.math.roundToInt

// Stamps are SMuFL code points (U+E000…) drawn from Bravura, the reference font for the standard,
// so the marks come out the same shape as the ones in the scores.
// descRes = the name a screen reader speaks, translated in strings.xml; null = say the number.
enum class StampSymbol(val codepoint: Int, @param:StringRes val descRes: Int?) {
    PPP(0xE52A, R.string.stamp_ppp),
    PP(0xE52B, R.string.stamp_pp),
    P(0xE520, R.string.stamp_p),
    MP(0xE52C, R.string.stamp_mp),
    MF(0xE52D, R.string.stamp_mf),
    F(0xE522, R.string.stamp_f),
    FF(0xE52F, R.string.stamp_ff),
    FFF(0xE530, R.string.stamp_fff),
    // E524 (dynamicSforzando) is only the "s": the whole sfz is dynamicSforzato
    SFZ(0xE539, R.string.stamp_sfz),
    CRESC(0xE53E, R.string.stamp_cresc),
    DECRESC(0xE53F, R.string.stamp_decresc),
    UPBOW(0xE612, R.string.stamp_upbow),
    DOWNBOW(0xE610, R.string.stamp_downbow),
    ARPEGGIO_UP(0xE634, R.string.stamp_arpeggio_up),
    ARPEGGIO_DOWN(0xE635, R.string.stamp_arpeggio_down),
    ACCENT(0xE4A0, R.string.stamp_accent),
    STACCATO(0xE4A2, R.string.stamp_staccato),
    TENUTO(0xE4A4, R.string.stamp_tenuto),
    // breath mark (comma) lives with the fermata in SMuFL's "holds and pauses" range
    FERMATA(0xE4C0, R.string.stamp_fermata),
    BREATH_COMMA(0xE4CE, R.string.stamp_breath_comma),
    SHARP(0xE262, R.string.stamp_sharp),
    FLAT(0xE260, R.string.stamp_flat),
    NATURAL(0xE261, R.string.stamp_natural),
    DOUBLE_SHARP(0xE263, R.string.stamp_double_sharp),
    DOUBLE_FLAT(0xE264, R.string.stamp_double_flat),
    TRILL(0xE566, R.string.stamp_trill),
    // SMuFL has no "inverted mordent": the upper one is ornamentShortTrill (E56C) and the lower
    // one is ornamentMordent (E56D, the wavy line with the stroke through it)
    MORDENT_UP(0xE56C, R.string.stamp_mordent_up),
    MORDENT_DOWN(0xE56D, R.string.stamp_mordent_down),
    TURN(0xE567, R.string.stamp_turn),
    ACCIACCATURA(0xE560, R.string.stamp_acciaccatura),
    APPOGGIATURA(0xE562, R.string.stamp_appoggiatura),
    // piano pedaling: keyboardPedalPed = "Ped." mark, keyboardPedalUp = the release mark
    PEDAL(0xE650, R.string.stamp_pedal),
    PEDAL_RELEASE(0xE655, R.string.stamp_pedal_release),
    // fingering 6-9 sit apart from 0-5 in SMuFL: 0-5 are ED10-ED15, 6-9 are ED24-ED27
    FINGERING_0(0xED10, null),
    FINGERING_1(0xED11, null),
    FINGERING_2(0xED12, null),
    FINGERING_3(0xED13, null),
    FINGERING_4(0xED14, null),
    FINGERING_5(0xED15, null),
    FINGERING_6(0xED24, null),
    FINGERING_7(0xED25, null),
    FINGERING_8(0xED26, null),
    FINGERING_9(0xED27, null)
}

// tint colors that read well on light and dark backgrounds. White stores as white; in negative
// mode inkTint renders it BLACK, good for painting over the page's own (now white) notes. On a
// plain white page with negative off it blends in — that is the nature of a white ink.
// the stamps' panel: eight symbols a row (and the eight colours on one), so the 43 symbols take
// six rows, and the panel fits above or below the bar on a phone upright
private const val STAMP_COLUMNS = 8

val SWATCH_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF757575),
    Color(0xFFFFFFFF),
)

// null = theme color; otherwise the ARGB that was saved
fun stampColor(color: Int?, fallback: Color): Color = color?.let(::Color) ?: fallback

/** Font size in sp for a text stamp (#35): the font px measured against the page's fit, converted by
 * density. Draw (here) and measure (the shared textRects) both pass THROUGH this, or they diverge. */
fun textFontSp(fontPx: Float, density: Density): TextUnit = (fontPx / density.density).sp

/**
 * The style of a text stamp's words, from [base] (the theme's): its font at [fontPx], each line
 * [TEXT_LINE_EM] of that font high. The theme's line height is a fixed 24 sp: a big text's lines
 * ran into each other and a small one's were far apart. Draw, measure and typing all take it.
 */
fun textStampStyle(base: TextStyle, fontPx: Float, density: Density, color: Color = Color.Unspecified): TextStyle =
    base.copy(fontSize = textFontSp(fontPx, density), lineHeight = TEXT_LINE_EM.em, color = color)

/** The words of a text stamp, wrapped at the page's width and tinted like the other ink. */
@Composable
internal fun TextStampView(
    text: String,
    fontPx: Float,
    maxWidthPx: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Text(
        text = text,
        style = textStampStyle(LocalTextStyle.current, fontPx, density, color),
        modifier = modifier.widthIn(max = with(density) { maxWidthPx.toDp() })
    )
}

// Typeface.createFromAsset builds a fresh Typeface on every call and the palette asks for one per
// symbol (29 of them), so one per process is what keeps the panel from taking a few ms to open.
private const val BRAVURA_ASSET = "fonts/bravura.otf"
private var bravuraTypeface: Typeface? = null
private var bravuraLoaded = false

private fun bravura(context: Context): Typeface? {
    if (!bravuraLoaded) {
        bravuraLoaded = true
        bravuraTypeface = runCatching {
            Typeface.createFromAsset(context.assets, BRAVURA_ASSET)
        }.getOrNull()
    }
    return bravuraTypeface
}

// A glyph much smaller than the em (the staccato dot is 0.08 em) would balloon to fill the box.
private const val MAX_ENLARGE = 2f

/**
 * Draws a stamp: the glyph from Bravura, measured by its ink box so it lands centred, and grown to
 * fill the requested size up to [MAX_ENLARGE] times its natural size.
 */
@Composable
fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier) {
    val typeface = bravura(LocalContext.current)
    val side = with(LocalDensity.current) { sizePx.toDp() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(side)) {
            val s = size.minDimension
            if (typeface == null || s <= 0f) return@Canvas
            val text = String(Character.toChars(symbol.codepoint))
            val paint = Paint().apply {
                // NOTE: without "this." the typeface falls back to the outer name
                this.typeface = typeface
                isAntiAlias = true
                this.color = color.toArgb()
            }
            val box = Rect()
            // natural size: one em = the stamp box
            paint.textSize = s
            paint.getTextBounds(text, 0, text.length, box)
            val fit = if (box.width() > 0 && box.height() > 0) {
                minOf(s * 0.92f / box.width(), s * 0.92f / box.height())
            } else 1f
            paint.textSize = s * fit.coerceAtMost(MAX_ENLARGE)
            paint.getTextBounds(text, 0, text.length, box)
            // centred on the ink box (getTextBounds is relative to the baseline: top is negative)
            val x = s / 2f - (box.left + box.right) / 2f
            val y = s / 2f - (box.top + box.bottom) / 2f
            drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
        }
    }
}

/** One symbol of the stamps panel: framed when it is the one the stamp tool will place. */
@Composable
private fun StampChoiceButton(symbol: StampSymbol, selected: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) color else MaterialTheme.colorScheme.outline
    val desc = symbol.descRes?.let { stringResource(it) } ?: symbol.name.substringAfter('_')
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc }
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            val palettePx = with(LocalDensity.current) { 24.dp.toPx() }
            StampView(symbol, sizePx = palettePx, color = color)
        }
    }
}

@Composable
fun ColorSwatchButton(color: Color, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = color,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.size(28.dp))
    }
}

/**
 * Stamps palette (spec §5): the symbols in a grid, the tint and the placement size below — the
 * settings of the stamp tool (issue #56). The bar only carries the tool button; the panel drops
 * from it, stays a compact block (4 columns, see-through) instead of a band across the page, and
 * steps aside while a finger is on the page. The grid scrolls once there are more symbols than fit;
 * the colour and size controls stay.
 */
@Composable
fun StampPalettePanel(
    active: StampSymbol,
    sizePx: Float,
    color: Int?,
    invert: Boolean,
    onColor: (Int?) -> Unit,
    onSymbol: (StampSymbol) -> Unit,
    onSize: (Float) -> Unit,
    onSaveDefault: () -> Unit,
    isDefault: Boolean,
) {
    ToolBlock {
        Column(
            modifier = Modifier
                // three rows of eight (24 symbols), the rest a scroll away: the panel was half a
                // phone's height
                .heightIn(max = 112.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StampSymbol.entries.chunked(STAMP_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { sym ->
                        StampChoiceButton(
                            symbol = sym,
                            selected = sym == active,
                            onClick = { onSymbol(sym) }
                        )
                    }
                }
            }
        }
        ColorChoices(active = color, invert = invert, onPick = onColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${stringResource(R.string.size_label)} · ${sizePx.roundToInt()} px",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            // persists in Settings: the next session starts with this size
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
        // the slider is the coarse control; the arrows step 1 px for the last few
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepArrow(label = "−", onClick = { onSize((sizePx - 1f).coerceIn(MIN_STAMP_PX, MAX_STAMP_PX)) })
            Slider(
                value = sizePx,
                onValueChange = { onSize(wholePx(it)) },
                valueRange = MIN_STAMP_PX..MAX_STAMP_PX,
                modifier = Modifier
                    .weight(1f)
                    .height(TOOL_SLIDER_DP.dp),
            )
            StepArrow(label = "+", onClick = { onSize((sizePx + 1f).coerceIn(MIN_STAMP_PX, MAX_STAMP_PX)) })
        }
    }
}

/** 1 px step button beside the slider: 32 dp of chrome, no ripple ring stealing the tap.
 * Shared with the other size panels (pen, eraser; issue #59: every size control is − slider +). */
@Composable
internal fun StepArrow(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(32.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
