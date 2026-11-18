package com.musigatto.fosscore.pdf

import kotlinx.coroutines.sync.Mutex

/**
 * The one lock every MuPDF call is made under, whatever the document: MuPDF keeps process-wide
 * state, so the viewer's draws, an export writing its own PDF and the music read in the background
 * from another document take turns. Not reentrant: never take it inside itself.
 */
object MuPdfLock {
    val mutex = Mutex()
}
