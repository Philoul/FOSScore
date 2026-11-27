package com.musigatto.fosscore.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.score.MusicPlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The bar's way over each page ([BarPath]), worked out away from the main thread and before a
 * turn shows the page. A way reads every note of the music played: made while the screen draws,
 * the two of a turned half page stopped the bar (and every frame) for some 400 ms on the Tab 3
 * at each turn (maintainer, 2026-11-26: the page refreshes, the bar goes on). Until a page's way
 * is ready the bar is not drawn there; it is never late, as the frames go on.
 *
 * Kept by page geometry (an identity: a new layout is new geometries), [build] makes one way.
 */
@Stable
internal class BarPaths(private val build: (PageGeometry) -> BarPath) {
    private val ready = mutableStateMapOf<PageGeometry, BarPath>()

    /** The way over the page laid out as [geometry], or null while it is being worked out. */
    fun of(geometry: PageGeometry?): BarPath? = geometry?.let { ready[it] }

    /**
     * Works out the ways of the pages the reader may see next, in that order: [page], the next two
     * (a turned half page shows the next one, a turn then needs the one after), the one before,
     * then all the others (a repeat back, a jump). [geometry] gives a page's layout, or null when
     * it is not known yet. Ways of layouts no longer shown are dropped.
     */
    suspend fun prepare(page: Int, pageCount: Int, geometry: (Int) -> PageGeometry?, work: CoroutineContext = Dispatchers.Default) {
        val order = (listOf(page, page + 1, page + 2, page - 1) + (0 until pageCount)).distinct().filter { it in 0 until pageCount }
        val current = HashSet<PageGeometry>()
        for (p in order) {
            val geo = geometry(p) ?: continue
            current.add(geo)
            if (ready.containsKey(geo)) continue
            ready[geo] = withContext(work) { build(geo) }
        }
        ready.keys.filterNot { it in current }.forEach { ready.remove(it) }
    }
}

/**
 * The ways of the bar over the pages of [places] for [playback], prepared around [page]. [version]
 * changes when a page's layout becomes known (MusicXML pages are laid out as they are drawn).
 */
@Composable
internal fun rememberBarPaths(places: MusicPlaces?, playback: Playback?, page: Int, pageCount: Int, version: Any?): BarPaths? {
    if (places == null || playback == null) return null
    val paths = remember(places, playback) { BarPaths { geo -> BarPath(playback, geo) } }
    LaunchedEffect(paths, page, pageCount, version) { paths.prepare(page, pageCount, places::geometry) }
    return paths
}
