package com.musigatto.fosscore.library

/** The list with the element at [from] taken out and put back at [to]. Out of range: unchanged. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Where a row dragged [offset] pixels from its place goes, among neighbours [height] high: one place
 * down (+1) or up (-1) once it covers half of the next one, else it stays (0).
 */
fun dragStep(offset: Float, height: Float): Int = when {
    height <= 0f -> 0
    offset > height / 2 -> 1
    offset < -height / 2 -> -1
    else -> 0
}

/**
 * The place [step] places away from the place [itemId] in [itemIds] (the setlist's order): the
 * next score is +1, the one before -1. Null past either end, or when the place is gone (removed
 * from the setlist while its score was open).
 */
fun neighbour(itemIds: List<Long>, itemId: Long, step: Int): Long? {
    val at = itemIds.indexOf(itemId)
    if (at < 0) return null
    return itemIds.getOrNull(at + step)
}
