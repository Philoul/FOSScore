package com.musigatto.fosscore.ui.viewer

import java.util.concurrent.ConcurrentHashMap

/**
 * The tempo each score plays at, as a factor of the tempo printed in it (maintainer's rule,
 * 2026-11-19): changed by hand, it holds for the session (while the app runs); set in the score's
 * settings, it is the score's default; else the tempo printed (factor 1).
 */
internal class SessionTempos {
    private val factors = ConcurrentHashMap<String, Float>()

    /** The factor [score] starts at, [scoreDefault] being the one its settings give (null: none). */
    fun start(score: String, scoreDefault: Float?): Float = factors[score] ?: scoreDefault ?: 1f

    /** A factor set by hand for [score]: it holds until the app stops. */
    fun set(score: String, factor: Float) {
        factors[score] = factor
    }

    companion object {
        /** The session's: one for the app's process. */
        val session = SessionTempos()
    }
}
