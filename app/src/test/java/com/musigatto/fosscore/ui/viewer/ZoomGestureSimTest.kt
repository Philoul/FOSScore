package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reproduce NUMÉRICAMENTE el bucle del pellizco de PdfViewerScreen con las funciones de
 * producción (ZoomMath, fitRect).
 *
 * Contrato del clamp sin huecos: mientras un eje cabe en el viewport va centrado
 * (el anclaje no manda ahí); cuando desborda, el contenido bajo el punto medio se
 * queda quieto. El sim lo refleja: la deriva del punto medio solo se mide en
 * régimen de desborde, y el residuo mide la exactitud del paso de zoom ANTES del
 * clamp (la matemática de anchoredOffset es exacta siempre).
 *
 * El gesto real es SIMÉTRICO: los dos dedos se abren alrededor de un punto medio fijo
 * (ver log 2026-09-06: d1 (645,1179) + d2 (954,1382), medio clavado en (800,1280)).
 */
class ZoomGestureSimTest {

    class Drift(val avg: Float, val max: Float, val res: Float)

    /**
     * Los dos dedos se abren simétricamente alrededor de [mid]: f1 = mid - h(t),
     * f2 = mid + h(t) + [slide]*t, con h(t) = h0*(1 + 1.5*f). Misma física que el gesto real:
     * anclaje al centroide vivo con ZoomMath.anchoredOffset, sin follow, con clamp.
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
        // a partir de este zoom ambos ejes desbordan y el anclaje manda en los dos
        val sOverflow = maxOf(viewport.width / content.width, viewport.height / content.height, 1f)
        var pBase: Offset? = null   // contenido bajo el punto medio al entrar en desborde

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
            // punto bajo el centroide vivo antes de actualizar: el paso de zoom no debe
            // moverlo (el anclaje es exacto); se mide ANTES del clamp para no mezclar
            // el centrado del eje que aún cabe con la exactitud de la matemática
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
                        // mismo anclaje que el gesto real: centroide vivo, sin follow
                        offset = ZoomMath.anchoredOffset(offset, k, c, base)
                        scale = target
                    }
                }
            }
            maxResidual = maxOf(maxResidual, ((base + offset + pC * scale) - c).getDistance())
            offset = ZoomMath.clampOffset(offset, content, viewport, scale, base)

            // invariante en desborde: el contenido bajo el punto medio fijo se queda quieto
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
        // espejo del gesto real del log: medio fijo en (800,1280)
        assertPinned("simetrico-log", vp, partitura, Offset(645f, 1179f), Offset(955f, 1381f))
    }

    @Test
    fun slidingSecondFingerKeepsMidpoint() {
        // el segundo dedo abre DESLIZÁNDOSE de lado (4,6 px/frame): el punto medio viaja
        // con las manos por diseño (el zoom centra entre los dedos, como Maps) y el
        // contenido inicial del medio viaja con él. Lo que se fija aquí: el paso de zoom
        // no mueve por sí mismo el contenido bajo el centroide vivo (residuo ~0) — con
        // follow ese residuo integraba el viaje en el offset y sumaba ~30 px de deriva.
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
