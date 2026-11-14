package com.musigatto.fosscore.pdfscore.music

/** How a barline is drawn: one thin line, two (a section's end), or a thin and a thick one (the end). */
enum class BarStyle(val musicXml: String) {
    REGULAR("regular"),
    LIGHT_LIGHT("light-light"),
    LIGHT_HEAVY("light-heavy"),
    HEAVY_LIGHT("heavy-light"),
}
