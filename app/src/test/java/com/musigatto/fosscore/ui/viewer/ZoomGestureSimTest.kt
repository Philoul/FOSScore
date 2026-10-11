package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * NUMERICALLY reproduces PdfViewerScreen's pinch loop with the production functions
 * (ZoomMath, fitRect).
 *
 * Contract of the gapless clamp: while an axis fits in the viewport it is centred
 * (the anchor does not rule there); once it overflows, the content under the midpoint
 * stays still. The sim reflects this: the midpoint drift is only measured while
 * overflowing, and the residual measures the accuracy of the zoom step BEFORE the
 * clamp (the anchoredOffset maths is always exact).
 *
 * The real gesture is SYMMETRIC: both fingers spread around a fixed midpoint
 * (see log 2026-09-06: d1 (645,1179) + d2 (954,1382), midpoint pinned at (800,1280)).
 */
class ZoomGestureSimTest {

    class Drift(val avg: Float, val max: Float, val res: Float)

    /**
     * Both fingers spread symmetrically around [mid]: f1 = mid - h(t),
     * f2 = mid + h(t) + [slide]*t, with h(t) = h0*(1 + 1.5*f). Same physics as the real gesture:
     * anchoring to the live centroid with ZoomMath.anchoredOffset, no follow, with clamp.
     */
    private fun runPinch(
        viewport: Size,
        pageAspect: Float,
        aim: Offset,
        away: Offset,
        frames: Int = 28,
        zoomTotal: Float = 2.5f,
        slide: Offset = Offset.Zero
    ): Drift {
        val fit = fitRect(viewport.width, viewport.height, pageAspect)
        val content = Size(fit.width, fit.height)
        val base = Offset(fit.left, fit.top)
        val mid = (aim + away) / 2f
        val h0 = (away - aim) / 2f

        var scale = 1f
        var offset = Offset.Zero
        var anchorDist = 0f
        var maxDrift = 0f
        var sumDrift = 0f
        var driftFrames = 0
        var maxResidual = 0f
        // from this zoom on both axes overflow and the anchor rules on both
        val sOverflow = maxOf(viewport.width / content.width, viewport.height / content.height, 1f)
        var pBase: Offset? = null   // content under the midpoint when overflow starts

        for (t in 1 until frames) {
            val f = t.toFloat() / (frames - 1)
            val h = Offset(
                h0.x * (1f + (zoomTotal - 1f) * f),
                h0.y * (1f + (zoomTotal - 1f) * f)
            )
            val f1 = mid - h
            val f2 = mid + h + Offset(slide.x * t, slide.y * t)
            val c = (f1 + f2) / 2f
            val d = (f2 - f1).getDistance()
            // point under the live centroid before updating: the zoom step must not
            // move it (the anchoring is exact); measured BEFORE the clamp so the centring
            // of an axis that still fits is not mixed with the accuracy of the maths
            val pC = (c - base - offset) / scale

            if (anchorDist == 0f) {                          // onScaleBegin
                anchorDist = d
            } else if (d > 0f) {
                val ratio = d / anchorDist
                anchorDist = d
                if (abs(ratio - 1f) > 1e-4f) {
                    val target = (scale * ratio).coerceIn(1f, 2.5f)
                    if (target != scale) {
                        val k = target / scale
                        // same anchoring as the real gesture: live centroid, no follow
                        offset = ZoomMath.anchoredOffset(offset, k, c, base)
                        scale = target
                    }
                }
            }
            maxResidual = maxOf(maxResidual, ((base + offset + pC * scale) - c).getDistance())
            offset = ZoomMath.clampOffset(offset, content, viewport, scale, base)

            // invariant while overflowing: the content under the fixed midpoint stays still
            if (scale >= sOverflow) {
                val pb = pBase ?: ((mid - base - offset) / scale).also { pBase = it }
                val screen = base + offset + pb * scale
                val dr = (screen - mid).getDistance()
                maxDrift = maxOf(maxDrift, dr)
                sumDrift += dr
                driftFrames++
            }
        }
        val avg = if (driftFrames > 0) sumDrift / driftFrames else 0f
        return Drift(avg, maxDrift, maxResidual)
    }

    private fun assertPinned(
        label: String,
        viewport: Size,
        pageAspect: Float,
        aim: Offset,
        away: Offset,
        frames: Int = 28,
        slide: Offset = Offset.Zero
    ) {
        val r = runPinch(viewport, pageAspect, aim, away, frames, slide = slide)
        println("$label: deriva del punto medio ${r.avg}px, máx ${r.max}px")
        assertTrue("$label deriva ${r.avg}px (máx ${r.max}px)", r.avg < 1f)
    }

    // S6 Lite: 1200x2000, partitura 1200x1569 @ (0,216)
    private val vp = Size(1200f, 2000f)
    private val partitura = 0.7648f

    @Test
    fun aimedTopLeft() {
        assertPinned("medio-arriba-izq", vp, partitura, Offset(150f, 300f), Offset(700f, 700f))
    }

    @Test
    fun aimedTopRight() {
        assertPinned("medio-arriba-der", vp, partitura, Offset(1050f, 300f), Offset(400f, 900f))
    }

    @Test
    fun aimedBottomRight() {
        assertPinned("medio-abajo-der", vp, partitura, Offset(1050f, 1600f), Offset(500f, 1100f))
    }

    @Test
    fun aimedBottomLeft() {
        assertPinned("medio-abajo-izq", vp, partitura, Offset(150f, 1600f), Offset(800f, 900f))
    }

    @Test
    fun symmetricPinchAroundTheTarget() {
        // mirror of the real gesture from the log: midpoint fixed at (800,1280)
        assertPinned("simetrico-log", vp, partitura, Offset(645f, 1179f), Offset(955f, 1381f))
    }

    @Test
    fun slidingSecondFingerKeepsMidpoint() {
        // the second finger spreads while SLIDING sideways (4,6 px/frame): the midpoint
        // travels with the hands by design (the zoom centres between the fingers, like Maps)
        // and the initial content under the midpoint travels with it. What is pinned here: the
        // zoom step by itself does not move the content under the live centroid (residual ~0) —
        // with follow that residual added the travel to the offset and summed ~30 px of drift.
        val r = runPinch(
            vp, partitura, Offset(150f, 300f), Offset(700f, 700f),
            frames = 24, slide = Offset(4f, 6f)
        )
        println("segundo-dedo-se-desliza: residuo máx ${r.res}px (deriva del medio ${r.avg}px)")
        assertTrue("desliza residuo ${r.res}px", r.res < 1f)
    }

    @Test
    fun landscapePageInPortraitViewport() {
        assertPinned("apaisada/en-retrato", Size(1428f, 2143f), 1.414f, Offset(300f, 400f), Offset(900f, 1000f), frames = 24)
    }

    @Test
    fun portraitPageInLandscapeViewport() {
        assertPinned("retrato/en-apaisado", Size(2143f, 1428f), 0.7071f, Offset(500f, 300f), Offset(1300f, 900f), frames = 24)
    }
}
