package com.musigatto.fosscore.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * The viewer's zoom and pan as ONE immutable value.
 *
 * A plain state holder, not a ViewModel: the zoom is UI logic and it goes back to the whole page
 * on every turn and reading-mode change, so there is nothing for it to survive.
 *
 * The gesture reads [transform] inside its pointer loop and the canvas inside its draw lambdas,
 * never in composition: a per-frame value read in composition recomposes the whole screen on
 * every pinch frame. Every write goes through [ZoomMath], which clamps, so no caller can leave
 * the page off screen — and nobody has to remember to clamp afterwards.
 */
@Stable
class ZoomState {
    var transform by mutableStateOf(ZoomTransform())
        private set

    /** Pinch by [k] around [anchor] (screen px). */
    fun pinch(frame: ZoomFrame, k: Float, anchor: Offset) {
        transform = ZoomMath.pinch(transform, k, anchor, frame)
    }

    /** One-finger pan by [delta] px. */
    fun pan(frame: ZoomFrame, delta: Offset) {
        transform = ZoomMath.pan(transform, delta, frame)
    }

    /** The double-tap animation interpolates one transform per frame; still clamped. */
    fun set(frame: ZoomFrame, next: ZoomTransform) {
        transform = ZoomMath.clamped(next, frame)
    }

    /** Back to the whole page: a turn or a reading-mode change. */
    fun reset() {
        transform = ZoomTransform()
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }
