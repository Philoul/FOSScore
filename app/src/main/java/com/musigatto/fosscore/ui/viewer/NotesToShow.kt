package com.musigatto.fosscore.ui.viewer

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The notes the page must show, each time they are asked for: ⏮ asks for the first notes even
 * when they are already the cursor's, after the pages were turned by hand. The cursor, a state,
 * did not change then, and the page stayed where the hand had left it.
 */
class NotesToShow {
    private val flow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<List<String>> = flow.asSharedFlow()

    fun show(notes: List<String>) {
        flow.tryEmit(notes)
    }
}
