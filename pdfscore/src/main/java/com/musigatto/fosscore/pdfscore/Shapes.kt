package com.musigatto.fosscore.pdfscore

import kotlin.math.abs

/**
 * The line a thin filled rectangle draws (a staff line, stem or barline that a program fills
 * instead of stroking), else null: lines are read as lines whichever way they were drawn.
 */
fun DrawnShape.asLine(): DrawnLine? {
    if (curved) return null
    // The corners, without the repeats that closing a path back on its first point leaves.
    val corners = points.filterIndexed { i, p -> i == 0 || !p.near(points[i - 1]) }
        .let { if (it.size > 1 && it.last().near(it.first())) it.dropLast(1) else it }
    if (corners.size != 4) return null
    if (!(corners + corners[0]).zipWithNext().all { (a, b) -> abs(a.x - b.x) < 0.01 || abs(a.y - b.y) < 0.01 }) return null
    val box = box
    return when {
        box.height < 0.25 * box.width -> DrawnLine(box.left, box.centerY, box.right, box.centerY, box.height)
        box.width < 0.25 * box.height -> DrawnLine(box.centerX, box.top, box.centerX, box.bottom, box.width)
        else -> null
    }
}

private fun Point.near(other: Point): Boolean = abs(x - other.x) < 0.01 && abs(y - other.y) < 0.01
