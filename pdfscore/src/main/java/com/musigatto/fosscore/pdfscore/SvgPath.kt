package com.musigatto.fosscore.pdfscore

/**
 * Reads an SVG path's `d` attribute into polygons (curves flattened into short straight segments),
 * the way music fonts' outlines are given as text.
 */
object SvgPath {
    private val TOKEN = Regex("""[MmLlHhVvCcSsQqTtZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    /** Straight segments per curve. */
    private const val CURVE_STEPS = 8

    /** The path's subpaths as polygons, each closed implicitly; null when it uses a command not read here (arcs). */
    fun parse(d: String): List<List<Point>>? {
        val tokens = TOKEN.findAll(d).map { it.value }.toList()
        if (TOKEN.replace(d, "").any { !it.isWhitespace() && it != ',' }) return null
        val polygons = mutableListOf<List<Point>>()
        var current = mutableListOf<Point>()
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        // the last control point, mirrored by S and T
        var controlX = 0.0
        var controlY = 0.0
        var command = ' '
        var i = 0

        fun number(): Double? = tokens.getOrNull(i)?.toDoubleOrNull()?.also { i++ }

        fun close() {
            if (current.size > 2) polygons += current
            current = mutableListOf()
        }

        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
            for (step in 1..CURVE_STEPS) {
                val t = step.toDouble() / CURVE_STEPS
                val u = 1 - t
                current += Point(
                    u * u * u * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                    u * u * u * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3,
                )
            }
            controlX = x2
            controlY = y2
            x = x3
            y = y3
        }

        fun quadratic(x1: Double, y1: Double, x2: Double, y2: Double) {
            cubic(x + 2.0 / 3 * (x1 - x), y + 2.0 / 3 * (y1 - y), x2 + 2.0 / 3 * (x1 - x2), y2 + 2.0 / 3 * (y1 - y2), x2, y2)
            controlX = x1
            controlY = y1
        }

        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length == 1 && token[0].isLetter()) {
                command = token[0]
                i++
                if (command == 'Z' || command == 'z') {
                    close()
                    x = startX
                    y = startY
                    controlX = x
                    controlY = y
                    continue
                }
            } else if (command == ' ') {
                return null
            }
            val relative = command.isLowerCase()
            val dx = if (relative) x else 0.0
            val dy = if (relative) y else 0.0
            when (command.uppercaseChar()) {
                'M' -> {
                    val nx = (number() ?: return null) + dx
                    val ny = (number() ?: return null) + dy
                    close()
                    x = nx
                    y = ny
                    startX = x
                    startY = y
                    current += Point(x, y)
                    // more pairs after a moveto are linetos
                    command = if (relative) 'l' else 'L'
                    controlX = x
                    controlY = y
                }
                'L' -> {
                    x = (number() ?: return null) + dx
                    y = (number() ?: return null) + dy
                    current += Point(x, y)
                    controlX = x
                    controlY = y
                }
                'H' -> {
                    x = (number() ?: return null) + dx
                    current += Point(x, y)
                    controlX = x
                    controlY = y
                }
                'V' -> {
                    y = (number() ?: return null) + dy
                    current += Point(x, y)
                    controlX = x
                    controlY = y
                }
                'C' -> cubic(
                    (number() ?: return null) + dx, (number() ?: return null) + dy,
                    (number() ?: return null) + dx, (number() ?: return null) + dy,
                    (number() ?: return null) + dx, (number() ?: return null) + dy,
                )
                'S' -> {
                    val x1 = 2 * x - controlX
                    val y1 = 2 * y - controlY
                    cubic(
                        x1, y1,
                        (number() ?: return null) + dx, (number() ?: return null) + dy,
                        (number() ?: return null) + dx, (number() ?: return null) + dy,
                    )
                }
                'Q' -> quadratic(
                    (number() ?: return null) + dx, (number() ?: return null) + dy,
                    (number() ?: return null) + dx, (number() ?: return null) + dy,
                )
                'T' -> quadratic(2 * x - controlX, 2 * y - controlY, (number() ?: return null) + dx, (number() ?: return null) + dy)
                else -> return null
            }
        }
        close()
        return polygons
    }
}
