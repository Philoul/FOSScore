package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * NUMERICALLY reproduces PdfViewerScreen's pinch loop (anchor → clamp) with the production
 * functions (ZoomMath, fitRect) and measures the anchor residual: how far the page point that was
 * under the aiming finger has moved on screen.
 *
 * The focus is FIXED (the first finger, not the midpoint of the two) and does not follow sliding,
 * so the residual must be ~0 even if the other finger moves. Previously the anchor was the live
 * centroid plus a 2-finger pan: the aimed point escaped as the fingers travelled. The clamp may
 * force the offset at the edges: that is why the threshold is 5 px and not 0.
 */
class ZoomGestureSimTest {

    class Drift(val avg: Float, val max: Float)

    /**
     * Two fingers spreading [zoomTotal]×: the first (the aiming one) stays at [fingers.x] and the
     * second spreads towards [fingers.y] and also slides [drift] px/frame, as in a real pinch
     * over a bar.
     */
    private fun runPinch(
        viewport: Size,
        pageAspect: Float,
        fingers: Pair<Offset, Offset>,
        frames: Int = 28,
        zoomTotal: Float = 2.5f,
        drift: Offset = Offset.Zero
    ): Drift {
        val fit = fitRect(viewport.width, viewport.height, pageAspect)
        val content = Size(fit.width, fit.height)
        val base = Offset(fit.left, fit.top)
        val (aim, open) = fingers
        val aim0 = aim
        val open0 = open

        var scale = 1f
        var offset = Offset.Zero
        // focus: the aiming finger, fixed for the whole gesture
        val anchor = aim0
        val f0 = Offset((aim0.x - base.x) / scale, (aim0.y - base.y) / scale)
        var anchorDist = 0f
        var prevD = -1f
        var maxDrift = 0f
        var sumDrift = 0f

        for (t in 1 until frames) {
            val f = t.toFloat() / (frames - 1)
            // the spreading finger moves away and slides; the aiming one stays still
            val openNow = Offset(
                open0.x + (open0.x - aim0.x) * (zoomTotal - 1f) * f + drift.x * t,
                open0.y + (open0.y - aim0.y) * (zoomTotal - 1f) * f + drift.y * t
            )
            val d = (openNow - aim0).getDistance()

            // same order and formulas as the real gesture
            if (prevD < 0f) {
                anchorDist = d
            } else {
                val ratio = d / anchorDist
                anchorDist = d
                if (abs(ratio - 1f) > 1e-4f) {
                    val target = (scale * ratio).coerceIn(1f, 2.5f)
                    if (target != scale) {
                        val k = target / scale
                        // anchor to the fixed focus (1st finger), NOT the midpoint or live centroid
                        offset = ZoomMath.anchoredOffset(offset, k, anchor, base)
                        scale = target
                    }
                }
            }
            // no 2-finger pan: the focus does not follow the sliding
            offset = ZoomMath.clampOffset(offset, content, viewport, scale, base)
            prevD = d

            val screen = Offset(base.x + offset.x + f0.x * scale, base.y + offset.y + f0.y * scale)
            val dr = (screen - anchor).getDistance()
            maxDrift = maxOf(maxDrift, dr)
            sumDrift += dr
        }
        return Drift(sumDrift / (frames - 1), maxDrift)
    }

    private fun assertPinned(
        label: String,
        viewport: Size,
        pageAspect: Float,
        fingers: Pair<Offset, Offset>,
        frames: Int = 28,
        drift: Offset = Offset.Zero
    ) {
        val r = runPinch(viewport, pageAspect, fingers, frames = frames, drift = drift)
        println("$label: deriva del foco medio ${r.avg}px, máx ${r.max}px")
        assertTrue("$label deriva ${r.avg}px (máx ${r.max}px)", r.avg < 5f)
    }

    // landscape score on the S6 Lite (1200x2000, fit 1200x1569 @ (0,216)): the user aims with
    // one finger and spreads with the other
    private val vp = Size(1200f, 2000f)
    private val partitura = 0.7648f

    @Test
    fun aimedTopLeftStaysPinned() {
        // one finger at (150,300) (top left) and the other spreading towards (700,700)
        assertPinned("apunta-arriba-izq", vp, partitura, Offset(150f, 300f) to Offset(700f, 700f))
    }

    @Test
    fun aimedBottomRightStaysPinned() {
        assertPinned("apunta-abajo-der", vp, partitura, Offset(1050f, 1600f) to Offset(500f, 1100f))
    }

    @Test
    fun aimedTopRightStaysPinned() {
        assertPinned("apunta-arriba-der", vp, partitura, Offset(1050f, 300f) to Offset(400f, 900f))
    }

    @Test
    fun aimedBottomLeftStaysPinned() {
        assertPinned("apunta-abajo-izq", vp, partitura, Offset(150f, 1600f) to Offset(800f, 900f))
    }

    /**
     * Both fingers together (symmetric case): the focus is the first finger's, so the other end
     * spreads. This is the requested behaviour change: the midpoint would leave the anchor halfway
     * across the gap between the fingers.
     */
    @Test
    fun bothFingersTogetherStayPinnedOnTheFirstOne() {
        assertPinned("dos-dedos-juntos", vp, partitura, Offset(500f, 900f) to Offset(560f, 905f), frames = 24)
    }

    @Test
    fun slidingSecondFingerKeepsFocus() {
        // the second finger slides a lot: the aimed bar must not move
        assertPinned("segundo-dedo-se-desliza", vp, partitura, Offset(150f, 300f) to Offset(700f, 700f), frames = 24, drift = Offset(4f, 6f))
    }

    @Test
    fun landscapePageInPortraitViewport() {
        assertPinned("apaisada/en-retrato", Size(1428f, 2143f), 1.414f, Offset(300f, 400f) to Offset(900f, 1000f), frames = 24)
    }

    @Test
    fun portraitPageInLandscapeViewport() {
        assertPinned("retrato/en-apaisado", Size(2143f, 1428f), 0.7071f, Offset(500f, 300f) to Offset(1300f, 900f), frames = 24)
    }
}
