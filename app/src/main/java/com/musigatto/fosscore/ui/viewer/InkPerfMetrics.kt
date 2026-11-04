package com.musigatto.fosscore.ui.viewer

import android.os.SystemClock
import android.util.Log
import kotlin.math.ceil

/** Debug-only: one log line per stroke, so a lag report can say whether the frames were late.
 * Everything runs on the main thread (pointer input, draw, frame callbacks), so no lock. */
internal object InkPerfMetrics {
    private const val LOG_TAG = "FOSScore-InkPerf"

    @Volatile
    private var enabled = false

    @Volatile
    private var strokeActive = false

    private var startedAtNs = 0L
    private var frameBudgetNs = 16_666_667L
    private val frameNs = ArrayList<Long>()
    private var framesOverBudget = 0

    fun enable(refreshRateHz: Float) {
        frameBudgetNs = if (refreshRateHz > 0f) {
            (1_000_000_000.0 / refreshRateHz).toLong()
        } else {
            16_666_667L
        }
        enabled = true
        Log.i(LOG_TAG, "enabled refresh_hz=$refreshRateHz frame_budget_ms=${ms(frameBudgetNs)}")
    }

    fun beginStroke() {
        if (!enabled) return
        startedAtNs = SystemClock.elapsedRealtimeNanos()
        frameNs.clear()
        framesOverBudget = 0
        strokeActive = true
    }

    fun frame(totalNs: Long) {
        if (!enabled || !strokeActive || totalNs <= 0L) return
        frameNs.add(totalNs)
        if (totalNs > frameBudgetNs) framesOverBudget++
    }

    fun finishStroke(pointCount: Int, saved: Boolean) {
        if (!enabled || !strokeActive) return
        strokeActive = false
        val sorted = frameNs.sorted()
        val p95 = sorted.getOrNull((ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)) ?: 0L
        Log.i(
            LOG_TAG,
            "INK_STROKE duration_ms=${ms(SystemClock.elapsedRealtimeNanos() - startedAtNs)}" +
                " saved=$saved points=$pointCount frames=${frameNs.size}" +
                " frames_over_budget=$framesOverBudget" +
                " avg_frame_ms=${ms(frameNs.sum() / frameNs.size.coerceAtLeast(1))}" +
                " p95_frame_ms=${ms(p95)}" +
                " max_frame_ms=${ms(frameNs.maxOrNull() ?: 0L)}",
        )
    }

    fun cancelStroke() {
        strokeActive = false
    }

    private fun ms(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)
}
