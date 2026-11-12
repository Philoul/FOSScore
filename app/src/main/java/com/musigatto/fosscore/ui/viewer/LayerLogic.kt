package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Layer

object LayerLogic {
    /**
     * Whether a layer should be drawn. Layer 0 (marks from before layers existed) is always
     * visible. A layer not in the list (deleted, or list not loaded yet) is also visible to
     * avoid hiding annotations by a race condition.
     */
    fun showsLayer(layers: List<Layer>, layer: Long): Boolean {
        if (layer == 0L) return true
        val found = layers.firstOrNull { it.id == layer } ?: return true
        return found.visible
    }

    /** Only the last layer of a sheet cannot be deleted. */
    fun canDelete(layers: List<Layer>, id: Long): Boolean = layers.size > 1 && layers.any { it.id == id }
}