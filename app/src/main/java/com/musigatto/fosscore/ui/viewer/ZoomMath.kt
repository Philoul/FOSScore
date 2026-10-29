package com.musigatto.fosscore.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Where the page sits on screen at the current zoom: `scale`, and `offset` px from its centred
 * base. Immutable, and every function here returns it already clamped (see [ZoomMath.pinch] and
 * [ZoomMath.pan]), so a transform that escapes the screen cannot be built.
 */
@Immutable
data class ZoomTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * The frame a zoom is clamped against: the content at scale 1 ([content]), the viewport it is
 * shown in, and where the content is centred on screen ([base]). A single page scales its own
 * content; the reading modes (two-up, half page) scale the whole viewport.
 */
@Immutable
data class ZoomFrame(val content: Size, val viewport: Size, val base: Offset)

/**
 * The maths behind the "1:1" zoom (see ZoomMathTest and plan 2026-09-30-zoom-render-1to1).
 *
 * The page is drawn WITHOUT a scaled layer: content the size of `fit*scale` placed at
 * `base + offset` (screen), and the vector overlay scaled as it is drawn. `base` is where the
 * page sits centred in the viewport at scale 1 (pageFit.left/top). Everything is in px.
 */
object ZoomMath {

    /** [k] is the factor this frame applies to the scale. Anchored, then clamped. */
    fun pinch(t: ZoomTransform, k: Float, anchor: Offset, frame: ZoomFrame): ZoomTransform =
        clamped(ZoomTransform(t.scale * k, anchoredOffset(t.offset, k, anchor, frame.base)), frame)

    fun pan(t: ZoomTransform, delta: Offset, frame: ZoomFrame): ZoomTransform =
        clamped(t.copy(offset = t.offset + delta), frame)

    fun clamped(t: ZoomTransform, frame: ZoomFrame): ZoomTransform =
        t.copy(offset = clampOffset(t.offset, frame.content, frame.viewport, t.scale, frame.base))

    /**
     * Zoom after one pinch frame: how much the finger distance changed ([previous] -> [distance]),
     * with a dead zone against touch noise and the cap. [previous] is 0 on the first frame of a
     * pinch: no zoom yet, that frame only takes the reference.
     */
    fun nextScale(
        scale: Float,
        previous: Float,
        distance: Float,
        deadZone: Float,
        max: Float,
    ): Float {
        // no ratio to take: the first frame of a pinch (no reference yet), or two fingers
        // reported on top of each other. Written as negated comparisons so a NaN stays out too.
        if (!(previous > 0f) || !(distance > 0f)) return scale
        val ratio = distance / previous
        if (abs(ratio - 1f) <= deadZone) return scale
        return (scale * ratio).coerceIn(1f, max)
    }

    /**
     * Pinch anchor: multiplying the zoom by [k] keeps the content under [anchor] (screen) still.
     * It is the usual `k*offset + (1-k)*anchor`, with the centring base taken off the anchor
     * point (the content no longer starts at the viewport corner: the page is centred on `base`).
     */
    fun anchoredOffset(offset: Offset, k: Float, anchor: Offset, base: Offset): Offset =
        Offset(
            offset.x * k + (1f - k) * (anchor.x - base.x),
            offset.y * k + (1f - k) * (anchor.y - base.y)
        )

    /**
     * Pan range: the content ([content] in screen px at scale 1) enlarged by [scale] so it still
     * touches the viewport — one single, continuous range (no jump when it goes from fitting the
     * screen to overflowing it). At scale 1 (whole page in view) it stays centred.
     */
    fun clampOffset(offset: Offset, content: Size, viewport: Size, scale: Float, base: Offset): Offset =
        Offset(
            clampAxis(offset.x, content.width, viewport.width, scale, base.x),
            clampAxis(offset.y, content.height, viewport.height, scale, base.y)
        )

    private fun clampAxis(o: Float, content: Float, viewport: Float, scale: Float, base: Float): Float {
        // With no zoom the view is the whole page: always centred.
        if (scale <= 1f) return 0f
        val scaled = content * scale
        // ponytail: clamp with no gaps — if the content fits it is centred (no panning); if it
        // overflows, the edge sticks to the border (no half-empty viewport).
        return if (scaled <= viewport) {
            (viewport - scaled) / 2f - base
        } else {
            o.coerceIn(viewport - scaled - base, -base)
        }
    }

    /**
     * Re-draw step: the bitmap is drawn at the next 10% of the fit (1.0, 1.1, ...). During a
     * gesture the drawing requests land on the same steps and MuPdfDoc's cache reuses them; when
     * the gesture ends, the final draw matches the step already on screen.
     */
    fun renderBucket(scale: Float): Float {
        val bucket = ceil(scale * 10f) / 10f
        return if (bucket < 1f) 1f else bucket
    }

    /** Pixel slot a single page is drawn into: the viewer and the prefetch must agree on it,
     *  because MuPdfDoc caches bitmaps by (page, width). */
    fun renderSlot(fit: RectPx, renderScale: Float): Pair<Int, Int> =
        Pair((fit.width * renderScale).toInt(), (fit.height * renderScale).toInt())
}