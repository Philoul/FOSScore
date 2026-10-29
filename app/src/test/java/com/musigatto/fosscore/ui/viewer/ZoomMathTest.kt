package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Matemática del zoom "1:1": anclaje con base de centrado, rango de paneo en coordenadas de
 * contenido y escalón de re-render. Estas funciones puras las consume el gesto del visor.
 */
class ZoomMathTest {

    // El anclaje debe dejar quieto el punto de la partitura que está bajo el dedo:
    // el mismo punto de contenido (en unidades de encaje) bajo la pantalla `anchor`
    // antes y después de multiplicar el zoom por k.
    @Test
    fun anchoredOffsetKeepsFitPointUnderAnchorFixed() {
        val base = Offset(40f, 60f)
        val offset = Offset(-10f, 20f)
        val anchor = Offset(500f, 300f)
        val s = 2.0f
        val k = 1.25f

        // punto de contenido bajo el dedo antes del zoom
        val pBefore = Offset(
            (anchor.x - base.x - offset.x) / s,
            (anchor.y - base.y - offset.y) / s
        )

        val offset2 = ZoomMath.anchoredOffset(offset, k, anchor, base)
        val s2 = s * k

        // el mismo punto de contenido debe seguir bajo el dedo tras el zoom
        val pAfter = Offset(
            (anchor.x - base.x - offset2.x) / s2,
            (anchor.y - base.y - offset2.y) / s2
        )
        assertEquals(pBefore.x, pAfter.x, 1e-2f)
        assertEquals(pBefore.y, pAfter.y, 1e-2f)
        // y el anclaje con base nula es la fórmula clásica k*offset + (1-k)*anchor
        val noBase = ZoomMath.anchoredOffset(offset, k, anchor, Offset.Zero)
        assertEquals(k * offset.x + (1f - k) * anchor.x, noBase.x, 1e-3f)
        assertEquals(k * offset.y + (1f - k) * anchor.y, noBase.y, 1e-3f)
    }

    // Sin huecos: si el contenido cabe se centra (sin paneo); si desborda, el borde
    // queda pegado al viewport. Sin zoom (scale == 1) la vista es página completa.
    @Test
    fun clampOffsetKeepsAnchorSlideAcrossAllScalesAndCentersAtScaleOne() {
        val viewport = Size(1000f, 700f)
        val content = Size(400f, 560f)   // encaje <= viewport
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

        // scale == 1: vista de página completa, centrada
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

    // el pellizco deja el punto de la partitura que hay bajo el ancla en su sitio, y además
    // devuelve el transform ya clampeado (aquí ambos ejes desbordan, así que el clamp no
    // recentra y no tapa el anclaje)
    @Test
    fun pinchKeepsThePointUnderTheAnchorAndReturnsItClamped() {
        val viewport = Size(600f, 800f)
        val content = Size(400f, 560f)
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val frame = ZoomFrame(content, viewport, base)
        val before = ZoomTransform(scale = 2f, offset = Offset(-200f, -200f))
        val anchor = Offset(300f, 400f)
        val k = 1.2f

        val pBefore = (anchor - base - before.offset) / before.scale
        val after = ZoomMath.pinch(before, k, anchor, frame)
        val pAfter = (anchor - base - after.offset) / after.scale

        assertEquals(2.4f, after.scale, 1e-4f)
        assertEquals(pBefore.x, pAfter.x, 1e-2f)
        assertEquals(pBefore.y, pAfter.y, 1e-2f)
        // y ya viene clampeado: repetir el clamp no lo mueve
        assertEquals(after.offset.x, ZoomMath.clamped(after, frame).offset.x, 1e-4f)
        assertEquals(after.offset.y, ZoomMath.clamped(after, frame).offset.y, 1e-4f)
    }

    // en los bordes el pellizco no puede sacar la página: el clamp manda sobre el anclaje
    @Test
    fun pinchStopsAtTheEdges() {
        val viewport = Size(600f, 800f)
        val content = Size(400f, 560f)
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val frame = ZoomFrame(content, viewport, base)
        val fit = ZoomTransform()   // página entera, centrada

        // 2x anclado en la esquina superior izquierda de la pantalla: el anclaje mandaría el
        // contenido a (+100, +120), fuera del rango [-100..] / [-120..]; manda el clamp
        val raw = ZoomMath.anchoredOffset(Offset.Zero, 2f, Offset.Zero, base)
        val corner = ZoomMath.pinch(fit, 2f, Offset.Zero, frame)
        assertEquals(2f, corner.scale, 1e-4f)
        assertEquals(base.x * -1f, corner.offset.x, 1e-3f)
        assertEquals(base.y * -1f, corner.offset.y, 1e-3f)
        assertTrue("el anclaje no llegó a salirse", raw.x > corner.offset.x)
        assertEquals(ZoomMath.clampOffset(raw, content, viewport, 2f, base), corner.offset)
    }

    @Test
    fun panKeepsTheScaleAndStopsAtTheEdges() {
        val viewport = Size(600f, 800f)
        val content = Size(400f, 560f)
        val base = Offset((viewport.width - content.width) / 2f, (viewport.height - content.height) / 2f)
        val frame = ZoomFrame(content, viewport, base)
        val t = ZoomTransform(scale = 2f, offset = Offset(-200f, -200f))

        val panned = ZoomMath.pan(t, Offset(10f, 20f), frame)
        assertEquals(2f, panned.scale, 0f)
        assertEquals(-190f, panned.offset.x, 1e-4f)
        assertEquals(-180f, panned.offset.y, 1e-4f)

        // del tirón al borde: mismo tope que el clamp suelto, sin dejar hueco
        val far = ZoomMath.pan(t, Offset(5000f, 5000f), frame)
        assertEquals(ZoomMath.clampOffset(Offset(-200f + 5000f, -200f + 5000f), content, viewport, 2f, base), far.offset)
        assertEquals(-base.x, far.offset.x, 1e-3f)
        assertEquals(-base.y, far.offset.y, 1e-3f)
    }

    // el paso de "cabe" a "desborda" no da un salto: en el umbral exacto el rango del eje se
    // reduce a un punto y ese punto es el mismo al que se pega el borde al desbordar
    @Test
    fun clampIsContinuousAcrossTheFitToOverflowBoundary() {
        val far = Offset(-1e6f, -1e6f)
        // eje X: 400*s = 800, el umbral está en scale 2
        val frameX = ZoomFrame(Size(400f, 560f), Size(800f, 560f), Offset(200f, 0f))
        val atX = ZoomMath.clampOffset(far, frameX.content, frameX.viewport, 2f, frameX.base).x
        val belowX = ZoomMath.clampOffset(far, frameX.content, frameX.viewport, 2f * 0.9999f, frameX.base).x
        val aboveX = ZoomMath.clampOffset(far, frameX.content, frameX.viewport, 2f * 1.0001f, frameX.base).x
        assertEquals(-200f, atX, 1e-3f)
        // el cambio permitido dentro del propio paso (1.5 * 800 px * 0.0001) es 0.12 px: un
        // salto real sería de decenas
        assertTrue("salto en X ${abs(aboveX - belowX)}", abs(aboveX - belowX) <= 0.2f)

        // eje Y: 560*s = 800, el umbral está en 800/560
        val frameY = ZoomFrame(Size(400f, 560f), Size(800f, 800f), Offset(200f, 120f))
        val edge = 800f / 560f
        val atY = ZoomMath.clampOffset(far, frameY.content, frameY.viewport, edge, frameY.base).y
        val belowY = ZoomMath.clampOffset(far, frameY.content, frameY.viewport, edge * 0.9999f, frameY.base).y
        val aboveY = ZoomMath.clampOffset(far, frameY.content, frameY.viewport, edge * 1.0001f, frameY.base).y
        assertEquals(-120f, atY, 1e-2f)
        assertTrue("salto en Y ${abs(aboveY - belowY)}", abs(aboveY - belowY) <= 0.2f)
    }

    // lo que quedaba sin cubrir del gesto: el ratio incremental, la zona muerta y el cap
    @Test
    fun nextScaleHasADeadZoneAndStopsAtTheCap() {
        val dead = 0.004f
        val max = 2.5f

        // dedo quieto (1 px sobre 400): la zona muerta se come el ruido
        assertEquals(1.5f, ZoomMath.nextScale(1.5f, 400f, 401f, dead, max), 0f)
        // primera referencia del pellizco y dedos coincidentes: ni zoom ni NaN
        assertEquals(1f, ZoomMath.nextScale(1f, 0f, 400f, dead, max), 0f)
        assertEquals(1f, ZoomMath.nextScale(1f, 400f, 0f, dead, max), 0f)
        assertEquals(1f, ZoomMath.nextScale(1f, 400f, Float.NaN, dead, max), 0f)
        // 2x llega a 2x, y el cap corta
        assertEquals(2f, ZoomMath.nextScale(1f, 400f, 800f, dead, max), 1e-4f)
        assertEquals(2.5f, ZoomMath.nextScale(2f, 400f, 800f, dead, max), 1e-4f)
        // ni por debajo del encaje ni por encima del cap, venga el ratio que venga
        assertEquals(1f, ZoomMath.nextScale(1f, 400f, 200f, dead, max), 1e-4f)
        assertEquals(max, ZoomMath.nextScale(1f, 1f, 10_000f, dead, max), 1e-4f)
    }
}