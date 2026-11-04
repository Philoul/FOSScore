package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Glyphs of the annotation bar, drawn in code. The bar used to show emoji: 🩹 needs Unicode 11 and
 * came out as an empty box on Android 7 (issue #21), and emoji also change shape from one brand to
 * the next. These take the button's content color (so they grey out when the button is disabled)
 * and look the same from API 23 up.
 *
 * TODO: placeholder for a future .PNG
 */
private const val ICON_UNITS = 24f
private const val ICON_DP = 20

// Material's own 24 dp paths (edit / undo / redo / delete), so the shapes are the familiar ones.
internal const val ICON_PENCIL =
    "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39,0.39,-1.02,0,-1.41" +
        "l-2.34,-2.34c-0.39,-0.39,-1.02,-0.39,-1.41,0l-1.83,1.83,3.75,3.75,1.83,-1.83z"

internal const val ICON_UNDO =
    "M12.5,8c-2.65,0,-5.05,0.99,-6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16,3.16,-1.88,5.12,-1.88" +
        ",3.54,0,6.55,2.31,7.6,5.5l2.37,-0.78C21.08,11.03,17.15,8,12.5,8z"

internal const val ICON_REDO =
    "M18.4,10.6C16.55,8.99,14.15,8,11.5,8c-4.65,0,-8.58,3.03,-9.96,7.22L3.9,16c1.05,-3.19" +
        ",4.05,-5.5,7.6,-5.5,1.95,0,3.73,0.72,5.12,1.88L13,16h9V7l-3.6,3.6z"

internal const val ICON_TRASH =
    "M6,19c0,1.1,0.9,2,2,2h8c1.1,0,2,-0.9,2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"

internal const val ICON_CHECK =
    "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"

internal const val ICON_CLOSE =
    "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 " +
        "13.41,12z"

// the selection tool of the annotation bar: a pointer, as in every drawing app (issue #50)
internal const val ICON_SELECT =
    "M6,2 L6,20.5 L10.4,16.3 L13.2,22 L16.2,20.5 L13.4,15 L19.5,15 Z"

// the text tool (issue #35): Material's text_fields glyph, a big T with a small f
internal const val ICON_TEXT =
    "M2.5,4v3h5v12h3V7h5V4h-13zm21,7h-8v3h2v7h3v-7h3v-3z"

/**
 * The grip of the annotation bar: two lines, Material's drag_handle. Three lines would be the ☰ of
 * a menu, which the viewer has at the same height.
 */
@Composable
fun GripIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        listOf(9f, 15f).forEach { y ->
            drawLine(
                color = color,
                start = Offset(5f * u, y * u),
                end = Offset(19f * u, y * u),
                strokeWidth = 1.8f * u,
            )
        }
    }
}

/** One of the 24 dp paths above, scaled to the button's text size. */
@Composable
fun ToolIcon(path: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val shape = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        withTransform({ scale(u, u, pivot = Offset.Zero) }) { drawPath(shape, color) }
    }
}

/**
 * Eraser: a slanted block with its sleeve line, over the line it is rubbing out. Material has no
 * eraser glyph, so it is two primitives instead of a path.
 */
@Composable
fun EraserIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        withTransform({ rotate(-45f, pivot = center) }) {
            drawRoundRect(
                color = color,
                topLeft = Offset(5f * u, 8f * u),
                size = Size(14f * u, 8f * u),
                cornerRadius = CornerRadius(2f * u),
                style = Stroke(width = 1.8f * u),
            )
            // sleeve: the mark that separates rubber from holder
            drawLine(
                color = color,
                start = Offset(10f * u, 8f * u),
                end = Offset(10f * u, 16f * u),
                strokeWidth = 1.5f * u,
            )
        }
        // the stroke being erased, under the block
        drawLine(
            color = color,
            start = Offset(4f * u, 21f * u),
            end = Offset(20f * u, 21f * u),
            strokeWidth = 1.5f * u,
        )
    }
}

/**
 * The Shapes tool: the glyph is the shape itself, so like the stamp button, the bar shows what is
 * armed. Line, rectangle and ellipse are primitives; the arrow is shaft + tip + wings, the same
 * geometry the page draws; the staff is its five lines.
 */
@Composable
fun ShapeGlyph(kind: ShapeKind, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        val sw = 1.8f * u
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            ShapeKind.LINE -> drawLine(color, Offset(4f * u, 17f * u), Offset(20f * u, 7f * u), strokeWidth = sw)
            ShapeKind.RECT -> drawRect(color, topLeft = Offset(4f * u, 5f * u), size = Size(16f * u, 14f * u), style = stroke)
            ShapeKind.ELLIPSE -> drawOval(color, topLeft = Offset(3f * u, 6f * u), size = Size(18f * u, 12f * u), style = stroke)
            ShapeKind.ARROW -> {
                val tip = Offset(20f * u, 7f * u)
                drawLine(color, Offset(4f * u, 17f * u), tip, strokeWidth = sw)
                // the wings trail behind the tip, the way the drawn arrow opens
                drawLine(color, tip, Offset(16f * u, 13.7f * u), strokeWidth = sw)
                drawLine(color, tip, Offset(12.2f * u, 7.9f * u), strokeWidth = sw)
            }
            // the same bow on the page: a slur deep, a tie barely off the line
            ShapeKind.SLUR, ShapeKind.TIE -> {
                val deep = kind == ShapeKind.SLUR
                drawArc(
                    color,
                    startAngle = 180f,
                    sweepAngle = -180f,
                    useCenter = false,
                    topLeft = Offset(3f * u, (if (deep) 7f else 11f) * u),
                    size = Size(18f * u, (if (deep) 10f else 5f) * u),
                    style = stroke
                )
            }
            // a box hairpin: shut on the left, open on the right (drag left→right)
            ShapeKind.CRESCENDO -> {
                drawLine(color, Offset(4f * u, 6f * u), Offset(20f * u, 3f * u), strokeWidth = sw)
                drawLine(color, Offset(4f * u, 6f * u), Offset(20f * u, 9f * u), strokeWidth = sw)
                drawLine(color, Offset(20f * u, 3f * u), Offset(20f * u, 9f * u), strokeWidth = sw)
            }
            // the bracket over the notes: a line with a tick down at each end
            ShapeKind.TUPLET -> {
                drawLine(color, Offset(4f * u, 9f * u), Offset(20f * u, 9f * u), strokeWidth = sw)
                drawLine(color, Offset(4f * u, 9f * u), Offset(4f * u, 14f * u), strokeWidth = sw)
                drawLine(color, Offset(20f * u, 9f * u), Offset(20f * u, 14f * u), strokeWidth = sw)
            }
            ShapeKind.STAFF -> (0 until STAFF_LINES).forEach { i ->
                val y = (6f + 3f * i) * u
                drawLine(color, Offset(3f * u, y), Offset(21f * u, y), strokeWidth = sw)
            }
        }
    }
}
