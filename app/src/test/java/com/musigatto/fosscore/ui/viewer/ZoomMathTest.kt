package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

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

    // Sin huecos: si el contenido cabe se centra (sin paneo); si desborda, el borde
    // queda pegado al viewport. Sin zoom (scale == 1) la vista es página completa.
    @Test
    fun clampOffsetKeepsAnchorSlideAcrossAllScalesAndCentersAtScaleOne() {
        val viewport = Size(1000f, 700f)
        val content = Size(400f, 560f)   // fit <= viewport
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val s = 1.2f   // 400*1.2=480 <= 1000 y 560*1.2=672 <= 700: ambos ejes caben -> centrado

        // cabe: siempre al centro, el paneo no mueve
        val centered = Offset((viewport.width - 480f) / 2f - base.x, (viewport.height - 672f) / 2f - base.y)
        val kept = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, s, base)
        assertEquals(centered.x, kept.x, 1e-3f)
        assertEquals(centered.y, kept.y, 1e-3f)

        // fuera del rango: igual, al centro
        val tooFar = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(centered.x, tooFar.x, 1e-3f)
        assertEquals(centered.y, tooFar.y, 1e-3f)

        // scale == 1: full-page view, centred
        val atOne = ZoomMath.clampOffset(Offset(50f, 50f), content, viewport, 1f, base)
        assertEquals(0f, atOne.x, 1e-3f)
        assertEquals(0f, atOne.y, 1e-3f)
    }

    // contenido más grande que el viewport: bordes pegados, sin huecos (nada de
    // medio viewport vacío: el paneo posterior al zoom no puede dejar la página
    // en la esquina).
    @Test
    fun clampOffsetClampsPanWhenContentOverflows() {
        val viewport = Size(600f, 800f)
        val content = Size(900f, 1200f)
        val base = Offset((600f - 900f) / 2f, (800f - 1200f) / 2f)   // (-150, -200)
        val s = 2f   // contenido eficaz 1800x2400

        val tooLeft = ZoomMath.clampOffset(Offset(-5000f, -5000f), content, viewport, s, base)
        assertEquals(-1050f, tooLeft.x, 1e-3f)
        assertEquals(-1400f, tooLeft.y, 1e-3f)

        val tooRight = ZoomMath.clampOffset(Offset(5000f, 5000f), content, viewport, s, base)
        assertEquals(150f, tooRight.x, 1e-3f)
        assertEquals(200f, tooRight.y, 1e-3f)

        // un offset dentro del rango no se toca
        val mid = Offset((-1050f + 150f) / 2f, (-1400f + 200f) / 2f)
        val kept = ZoomMath.clampOffset(mid, content, viewport, s, base)
        assertEquals(mid.x, kept.x, 1e-3f)
        assertEquals(mid.y, kept.y, 1e-3f)
    }

    // regresión del escape: con el contenido desbordando, el rect visible tras el clamp
    // siempre cubre al menos la mitad del viewport en cada eje (la página no se pierde).
    @Test
    fun clampOffsetNeverLetsPageEscapeViewport() {
        val viewport = Size(1600f, 2560f)
        val content = Size(1600f, 2044f)   // encaje a pantalla completa
        val base = Offset(0f, (2560f - 2044f) / 2f)
        val s = 2f
        for (off in listOf(Offset(-5000f, -5000f), Offset(5000f, 5000f))) {
            val o = ZoomMath.clampOffset(off, content, viewport, s, base)
            val left = base.x + o.x
            val top = base.y + o.y
            val right = left + content.width * s
            val bottom = top + content.height * s
            val visX = min(right, viewport.width) - max(left, 0f)
            val visY = min(bottom, viewport.height) - max(top, 0f)
            assertTrue("eje X visible $visX", visX >= viewport.width / 2f)
            assertTrue("eje Y visible $visY", visY >= viewport.height / 2f)
        }
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