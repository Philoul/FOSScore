package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil

/**
 * "1:1" zoom math (see ZoomMathTest and plan 2026-09-30-zoom-render-1to1).
 *
 * The page is drawn WITHOUT a scaled layer: content of size `fit*scale` placed at
 * `base + offset` (screen), vector overlay scaled in the drawing itself. `base` is the
 * centring of the page in the viewport at scale 1 (pageFit.left/top). All in px.
 */
object ZoomMath {

    /**
     * Pinch anchoring: when the zoom is multiplied by [k], the content point under [anchor]
     * (screen) stays still. It is the classic formula `k*offset + (1-k)*anchor` with the
     * centring base subtracted from the anchor point (the content origin is no longer the
     * viewport's: the page is centred at `base`).
     */
    fun anchoredOffset(offset: Offset, k: Float, anchor: Offset, base: Offset): Offset =
        Offset(
            offset.x * k + (1f - k) * (anchor.x - base.x),
            offset.y * k + (1f - k) * (anchor.y - base.y)
        )

    /**
     * Pan range: the content (of on-screen size [content] at scale 1) magnified by [scale]
     * so that it keeps touching the viewport — a single, continuous range (no jumps when going
     * from not overflowing to overflowing). At scale 1 (full view) it stays centred.
     */
    fun clampOffset(offset: Offset, content: Size, viewport: Size, scale: Float, base: Offset): Offset =
        Offset(
            clampAxis(offset.x, content.width, viewport.width, scale, base.x),
            clampAxis(offset.y, content.height, viewport.height, scale, base.y)
        )

    private fun clampAxis(o: Float, content: Float, viewport: Float, scale: Float, base: Float): Float {
        // Without zoom the view is "full page": always centred.
        if (scale <= 1f) return 0f
        // Single, CONTINUOUS range (no jump when going from not overflowing to overflowing): the
        // page may slide as long as it keeps touching the viewport. The pinch anchor is never
        // betrayed (if the axis fits and centring is forced, the page grows from the centre and
        // the point under the fingers runs off to the corner). The cost: when overflowing, a pan
        // can leave the page slightly off on one side — if it bothers, upgrade: a full-coverage
        // clamp on gesture release.
        val scaled = content * scale
        return o.coerceIn(-scaled - base, viewport - base)
    }

    /**
     * Re-render step: the bitmap is rendered at the next 10% of the fit (1.0, 1.1, ...).
     * Within a gesture, render requests fall on the same steps and MuPdfDoc's cache reuses
     * them; at the end, the final render matches the visual step.
     */
    fun renderBucket(scale: Float): Float {
        val bucket = ceil(scale * 10f) / 10f
        return if (bucket < 1f) 1f else bucket
    }
}