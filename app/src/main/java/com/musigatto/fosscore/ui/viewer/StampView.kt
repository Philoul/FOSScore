package com.musigatto.fosscore.ui.viewer

import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.musigatto.fosscore.R
import java.util.Locale

// label: score notation drawn on the page (Italian dynamics, never translated) or a stable
// identifier for glyph-only symbols. nameRes: translatable name, used for accessibility.
enum class StampSymbol(val label: String, @StringRes val nameRes: Int) {
    PP("pp", R.string.stamp_pp), P("p", R.string.stamp_p), MP("mp", R.string.stamp_mp),
    MF("mf", R.string.stamp_mf), F("f", R.string.stamp_f), FF("ff", R.string.stamp_ff),
    CRESC("cresc.", R.string.stamp_cresc), DECRESC("decresc.", R.string.stamp_decresc),
    UPBOW("upbow", R.string.stamp_upbow), DOWNBOW("downbow", R.string.stamp_downbow),
    ACCENT("accent", R.string.stamp_accent), STACCATO("staccato", R.string.stamp_staccato),
    TENUTO("tenuto", R.string.stamp_tenuto), FERMATA("fermata", R.string.stamp_fermata),
    // Accidentals: SMuFL glyphs accidentalSharp/Flat/Natural/DoubleSharp/DoubleFlat, served
    // by Noto Music at standard Unicode codepoints (verified against its cmap).
    SHARP("sharp", R.string.stamp_sharp), FLAT("flat", R.string.stamp_flat),
    NATURAL("natural", R.string.stamp_natural),
    DOUBLE_SHARP("double sharp", R.string.stamp_double_sharp),
    DOUBLE_FLAT("double flat", R.string.stamp_double_flat)
}

// Noto Music (OFL, third_party/NotoMusic-OFL.txt) has the accidentals at standard Unicode
// codepoints: verified in its cmap and "seen" by rendering them (♭ 266D, ♮ 266E, ♯ 266F, 𝄪 1D12A, 𝄫 1D12B).
// ponytail: if you ever want Bravura (pure SMuFL), its glyphs live in the private use area
// (U+E000…) and you need each one's exact codepoint; Noto Music avoids that mapping.
private val GLYPH_CODEPOINT = mapOf(
    StampSymbol.FLAT to 0x266D,
    StampSymbol.NATURAL to 0x266E,
    StampSymbol.SHARP to 0x266F,
    StampSymbol.DOUBLE_SHARP to 0x1D12A,
    StampSymbol.DOUBLE_FLAT to 0x1D12B
)

// Dynamics: in SMuFL dynamicPiano/Forte/MezzoPiano/MezzoForte/ForteFortissimo are literally
// italic serif letters (p, f, mp, mf, ff), which is what we draw with Text. Spectral Italic
// (OFL, third_party/Spectral-OFL.txt) looks closer to the gravity of a printed score than the
// system's Noto Serif Italic, which comes out heavy.
private val TEXT_SYMBOLS = setOf(
    StampSymbol.PP, StampSymbol.P, StampSymbol.MP,
    StampSymbol.MF, StampSymbol.F, StampSymbol.FF,
    StampSymbol.CRESC, StampSymbol.DECRESC
)

private val MUSIC_FONT = FontFamily(Font(R.font.spectral_italic))

// fraction of the box the label occupies (long words shrink)
private val TEXT_FILL = mapOf(
    StampSymbol.DECRESC to 0.34f,
    StampSymbol.CRESC to 0.42f,
    StampSymbol.MF to 0.5f,
    StampSymbol.FF to 0.55f,
    StampSymbol.MP to 0.58f,
    StampSymbol.PP to 0.58f
)

// tint colours readable in light and dark (forScore style)
val SWATCH_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF757575)
)

// null = theme colour; otherwise the stored ARGB
fun stampColor(color: Int?, fallback: Color): Color = color?.let(::Color) ?: fallback

// ponytail: resolved via getIdentifier so the user can drop `stamp_<name>.png` into res/drawable
// and the icons show up without touching code. Upgrade if it reaches >20 icons: static map.
@Composable
private fun stampDrawableId(symbol: StampSymbol): Int? {
    val context = LocalContext.current
    return remember(symbol) {
        val id = context.resources.getIdentifier(
            "stamp_" + symbol.name.lowercase(Locale.ROOT), "drawable", context.packageName
        )
        id.takeIf { it != 0 }
    }
}

@Composable
private fun musicTypeface(): Typeface? {
    val context = LocalContext.current
    // ResourcesCompat.getFont: Resources.getFont is API 26+ and minSdk is 23
    return remember {
        val tf: Typeface? = try {
            ResourcesCompat.getFont(context, R.font.noto_music)
        } catch (e: Exception) {
            null
        }
        tf
    }
}

/**
 * Music glyphs: Noto Music for the accidentals (real glyphs, measured and centred by their ink
 * box) and Canvas for the rest. A PNG in res/drawable takes priority over everything.
 */
@Composable
fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val side = with(density) { sizePx.toDp() }
    val typeface = musicTypeface()
    Box(modifier, contentAlignment = Alignment.Center) {
        val png = stampDrawableId(symbol)
        if (png != null) {
            Image(
                painter = painterResource(png),
                contentDescription = stringResource(symbol.nameRes),
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier.fillMaxSize()
            )
        } else if (symbol in TEXT_SYMBOLS) {
            // ponytail: the label shrinks by a fixed per-label factor (eyeballed against the box)
            // instead of measuring the text at runtime. Upgrade: Canvas.drawText, truly measured.
            // `?:` rather than getOrDefault, which is API 24+.
            val maxSp = with(density) { (sizePx * (TEXT_FILL[symbol] ?: 0.7f) / density.density).sp }
            androidx.compose.material3.Text(
                text = symbol.label,
                maxLines = 1,
                softWrap = false,
                fontSize = maxSp,
                fontFamily = MUSIC_FONT,
                fontStyle = FontStyle.Italic,
                color = color,
                textAlign = TextAlign.Center
            )
        } else {
            Canvas(Modifier.size(side)) {
                val s = size.minDimension
                val stroke = s * 0.08f
                val p = Path()
                val cp = GLYPH_CODEPOINT[symbol]

                if (cp != null && typeface != null) {
                    // we measure the INK (not the em box) to scale it to the page's size and
                    // truly centre it; Compose's text box off-centres symbol glyphs and also
                    // clips them to the line height
                    val text = String(Character.toChars(cp))
                    val paint = android.graphics.Paint().apply {
                        // CAREFUL: without "this." the left side resolves to the outer val (same
                        // name) and Kotlin says "'val' cannot be reassigned"
                        this.typeface = typeface
                        isAntiAlias = true
                        this.color = color.toArgb()
                    }
                    val b = android.graphics.Rect()
                    val REF = 200f
                    paint.textSize = REF
                    paint.getTextBounds(text, 0, text.length, b)
                    val k = if (b.width() > 0 && b.height() > 0) {
                        minOf(s * 0.92f / b.width(), s * 0.92f / b.height())
                    } else 1f
                    paint.textSize = REF * k
                    paint.getTextBounds(text, 0, text.length, b)
                    // centred on the ink box; the baseline sits below the centre
                    val x = s / 2f - (b.left + b.right) / 2f
                    val y = s / 2f - (b.top + b.bottom) / 2f + b.bottom
                    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
                } else when (symbol) {
                    StampSymbol.UPBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.28f); lineTo(s * 0.5f, s * 0.78f); lineTo(s * 0.92f, s * 0.28f)
                    }
                    StampSymbol.DOWNBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.78f); lineTo(s * 0.5f, s * 0.18f); lineTo(s * 0.92f, s * 0.78f)
                    }
                    StampSymbol.ACCENT -> p.apply {
                        moveTo(s * 0.05f, s * 0.12f); lineTo(s * 0.88f, s * 0.5f); lineTo(s * 0.05f, s * 0.88f)
                    }
                    StampSymbol.STACCATO ->
                        drawCircle(color, radius = s * 0.14f, center = Offset(s / 2f, s / 2f))
                    StampSymbol.TENUTO -> drawLine(
                        color, Offset(s * 0.05f, s / 2f), Offset(s * 0.95f, s / 2f),
                        strokeWidth = s * 0.15f, cap = StrokeCap.Round
                    )
                    StampSymbol.FERMATA -> {
                        // dome (upper semicircle) + dot BELOW: on Android 0° is 3 o'clock and
                        // angles run clockwise, so 180→360 is the top half. With 0→180 the dome
                        // came out at the bottom.
                        drawArc(
                            color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                            topLeft = Offset(s * 0.18f, s * 0.16f),
                            size = androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.64f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = stroke, cap = StrokeCap.Round
                            )
                        )
                        drawCircle(color, radius = s * 0.11f, center = Offset(s / 2f, s * 0.72f))
                    }
                    else -> {}
                }
                // the Path symbols are those drawn with neither a glyph nor primitives
                val usesPath = symbol == StampSymbol.UPBOW ||
                    symbol == StampSymbol.DOWNBOW || symbol == StampSymbol.ACCENT
                if (usesPath) {
                    drawPath(
                        p, color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke, cap = StrokeCap.Round
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun StampPaletteButton(symbol: StampSymbol, selected: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) color else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
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
