package com.musigatto.fosscore.ui.viewer

import android.os.SystemClock
import android.util.Log
import kotlin.math.ceil

internal object InkPerfMetrics {
    private const val LOG_TAG = "FOSScore-InkPerf"

    @Volatile
    private var enabled = false

    @Volatile
    private var strokeActive = false

    private val lock = Any()
    private var startedAtNs = 0L
    private var lastPointerAtNs = 0L
    private var pointerEvents = 0
    private var maxPointerGapNs = 0L
    private var previewDraws = 0
    private var previewDrawNs = 0L
    private var maxPreviewDrawNs = 0L
    private var previewUpdates = 0
    private var maxPreviewPoints = 0
    private var frameBudgetNs = 16_666_667L
    private val frameNs = ArrayList<Long>()
    private var framesOverBudget = 0
    private var framesOverTwoBudgets = 0
    private var frameDrawNs = 0L
    private var maxFrameDrawNs = 0L

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
        synchronized(lock) {
            startedAtNs = SystemClock.elapsedRealtimeNanos()
            lastPointerAtNs = startedAtNs
            pointerEvents = 0
            maxPointerGapNs = 0L
            previewDraws = 0
            previewDrawNs = 0L
            maxPreviewDrawNs = 0L
            previewUpdates = 0
            maxPreviewPoints = 0
            frameNs.clear()
            framesOverBudget = 0
            framesOverTwoBudgets = 0
            frameDrawNs = 0L
            maxFrameDrawNs = 0L
            strokeActive = true
        }
    }

    fun pointerEvent() {
        if (!enabled || !strokeActive) return
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            if (!strokeActive) return
            maxPointerGapNs = maxOf(maxPointerGapNs, now - lastPointerAtNs)
            lastPointerAtNs = now
            pointerEvents++
        }
    }

    fun previewDrawStarted(): Long =
        if (enabled && strokeActive) System.nanoTime() else 0L

    fun previewDrawFinished(startedAt: Long) {
        if (startedAt == 0L) return
        val elapsed = System.nanoTime() - startedAt
        synchronized(lock) {
            if (!strokeActive) return
            previewDraws++
            previewDrawNs += elapsed
            maxPreviewDrawNs = maxOf(maxPreviewDrawNs, elapsed)
        }
    }

    fun previewPublished(pointCount: Int) {
        if (!enabled || !strokeActive) return
        synchronized(lock) {
            if (!strokeActive) return
            previewUpdates++
            maxPreviewPoints = maxOf(maxPreviewPoints, pointCount)
        }
    }

    fun frame(totalNs: Long, drawNs: Long) {
        if (!enabled || !strokeActive || totalNs <= 0L) return
        synchronized(lock) {
            if (!strokeActive) return
            frameNs.add(totalNs)
            if (totalNs > frameBudgetNs) framesOverBudget++
            if (totalNs > frameBudgetNs * 2) framesOverTwoBudgets++
            frameDrawNs += drawNs.coerceAtLeast(0L)
            maxFrameDrawNs = maxOf(maxFrameDrawNs, drawNs)
        }
    }

    fun finishStroke(pointCount: Int, saved: Boolean) {
        if (!enabled) return
        val summary = synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            val sortedFrames = frameNs.sorted()
            val p95FrameNs = sortedFrames
                .getOrNull((ceil(sortedFrames.size * 0.95).toInt() - 1).coerceAtLeast(0))
                ?: 0L
            listOf(
                "duration_ms=${ms(SystemClock.elapsedRealtimeNanos() - startedAtNs)}",
                "saved=$saved",
                "points=$pointCount",
                "pointer_events=$pointerEvents",
                "max_pointer_gap_ms=${ms(maxPointerGapNs)}",
                "frames=${frameNs.size}",
                "frames_over_budget=$framesOverBudget",
                "frames_over_2x_budget=$framesOverTwoBudgets",
                "avg_frame_ms=${ms(frameNs.sum() / frameNs.size.coerceAtLeast(1))}",
                "p95_frame_ms=${ms(p95FrameNs)}",
                "max_frame_ms=${ms(frameNs.maxOrNull() ?: 0L)}",
                "avg_frame_draw_ms=${ms(frameDrawNs / frameNs.size.coerceAtLeast(1))}",
                "max_frame_draw_ms=${ms(maxFrameDrawNs)}",
                "preview_draws=$previewDraws",
                "preview_updates=$previewUpdates",
                "max_preview_points=$maxPreviewPoints",
                "avg_preview_draw_ms=${ms(previewDrawNs / previewDraws.coerceAtLeast(1))}",
                "max_preview_draw_ms=${ms(maxPreviewDrawNs)}",
            ).joinToString(" ")
        }
        Log.i(LOG_TAG, "INK_STROKE $summary")
    }

    fun databaseInsertStarted(): Long =
        if (enabled) SystemClock.elapsedRealtimeNanos() else 0L

    fun databaseInsertFinished(startedAt: Long, id: Long) {
        if (startedAt == 0L) return
        val duration = SystemClock.elapsedRealtimeNanos() - startedAt
        Log.i(
            LOG_TAG,
            "INK_DB_INSERT duration_ms=${ms(duration)} inserted=${id > 0L}",
        )
    }

    fun cancelStroke() {
        if (!enabled) return
        synchronized(lock) { strokeActive = false }
    }

    private fun ms(nanos: Long): String = "%.2f".format(nanos / 1_000_000.0)
}
