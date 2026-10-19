package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "1:1" zoom math: anchoring with a centring base, pan range in content coordinates and
 * re-render step. These pure functions are consumed by the viewer's gesture.
 */
class ZoomMathTest {

    // Anchoring must keep the point of the score under the finger still:
    // the same content point (in fit units) under the screen point `anchor`
    // before and after multiplying the zoom by k.
    @Test
    fun anchoredOffsetKeepsFitPointUnderAnchorFixed() {
        val base = Offset(40f, 60f)
        val offset = Offset(-10f, 20f)
        val anchor = Offset(500f, 300f)
        val s = 2.0f
        val k = 1.25f

        // content point under the finger before the zoom
        val pBefore = Offset(
            (anchor.x - base.x - offset.x) / s,
            (anchor.y - base.y - offset.y) / s
        )

        val offset2 = ZoomMath.anchoredOffset(offset, k, anchor, base)
        val s2 = s * k

        // the same content point must still be under the finger after the zoom
        val pAfter = Offset(
            (anchor.x - base.x - offset2.x) / s2,
            (anchor.y - base.y - offset2.y) / s2
        )
        assertEquals(pBefore.x, pAfter.x, 1e-2f)
        assertEquals(pBefore.y, pAfter.y, 1e-2f)
        // and anchoring with a zero base is the classic formula k*offset + (1-k)*anchor
        val noBase = ZoomMath.anchoredOffset(offset, k, anchor, Offset.Zero)
        assertEquals(k * offset.x + (1f - k) * anchor.x, noBase.x, 1e-3f)
        assertEquals(k * offset.y + (1f - k) * anchor.y, noBase.y, 1e-3f)
    }

    // when zoomed, the page may slide within a SINGLE continuous range (always touching the
    // viewport): the pinch anchor is honoured at every scale — no jump when going from "fits" to
    // "overflows" (previously the page ran off to the corner in the range where it fit).
    // Without zoom (scale == 1) the view is the full page: always centred.
    @Test
    fun clampOffsetKeepsAnchorSlideAcrossAllScalesAndCentersAtScaleOne() {
        val viewport = Size(1000f, 700f)
        val content = Size(400f, 560f)   // fit <= viewport
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val s = 1.2f   // 400*1.2=480 <= 1000 and 560*1.2=672 <= 700: both axes fit

        // an offset within range is not re-centred (the page slides after anchoring)
        val kept = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, s, base)
        assertEquals(50f, kept.x, 1e-3f)
        assertEquals(50f, kept.y, 1e-3f)

        // out of range: the page is clamped so it keeps touching the viewport
        val tooFar = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(-480f - 300f, tooFar.x, 1e-3f)      // -780
        assertEquals(-672f - 70f, tooFar.y, 1e-3f)       // -742

        // scale == 1: full-page view, centred
        val atOne = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, 1f, base)
        assertEquals(0f, atOne.x, 1e-3f)
        assertEquals(0f, atOne.y, 1e-3f)
    }

    // content larger than the viewport: the pan is clamped so the page never fully detaches
    // (it keeps touching the viewport along one edge at the end of the range)
    @Test
    fun clampOffsetClampsPanWhenContentOverflows() {
        val viewport = Size(600f, 800f)
        val content = Size(900f, 1200f)
        val base = Offset((600f - 900f) / 2f, (800f - 1200f) / 2f)   // (-150, -200)
        val s = 2f   // effective content 1800x2400
        val minX = -content.width * s - base.x                       // -1650
        val maxX = viewport.width - base.x                           // 750
        val minY = -content.height * s - base.y                      // -2200
        val maxY = viewport.height - base.y                          // 1000

        val tooLeft = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(minX, tooLeft.x, 1e-3f)
        assertEquals(minY, tooLeft.y, 1e-3f)

        val tooRight = ZoomMath.clampOffset(Offset(5000f, 5000f), content, viewport, s, base)
        assertEquals(maxX, tooRight.x, 1e-3f)
        assertEquals(maxY, tooRight.y, 1e-3f)

        // an offset within range is left untouched
        val mid = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val kept = ZoomMath.clampOffset(mid, content, viewport, s, base)
        assertEquals(mid.x, kept.x, 1e-3f)
        assertEquals(mid.y, kept.y, 1e-3f)
    }

    @Test
    fun renderBucketRoundsUpToTenthsAndNeverBelowOne() {
        assertEquals(1.0f, ZoomMath.renderBucket(1.0f), 1e-4f)
        assertEquals(1.1f, ZoomMath.renderBucket(1.04f), 1e-4f)
        assertEquals(2.0f, ZoomMath.renderBucket(1.999f), 1e-4f)
        assertEquals(2.5f, ZoomMath.renderBucket(2.499f), 1e-4f)
        assertEquals(1.0f, ZoomMath.renderBucket(0.9f), 1e-4f)
        assertEquals(1.0f, ZoomMath.renderBucket(0.0f), 1e-4f)
    }
}