package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.library.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerLogicTest {

    private val layers = listOf(
        Layer(1L, 1L, "Capa 1", true),
        Layer(2L, 1L, "Capa 2", false),
        Layer(3L, 1L, "Capa 3", true),
    )

    @Test
    fun showsLayer_layerZero_alwaysTrue() {
        assertTrue("layer 0 always visible", LayerLogic.showsLayer(layers, 0L))
        assertTrue("layer 0 visible even with empty list", LayerLogic.showsLayer(emptyList(), 0L))
    }

    @Test
    fun showsLayer_visibleLayer_true() {
        assertTrue("layer 1 is visible", LayerLogic.showsLayer(layers, 1L))
        assertTrue("layer 3 is visible", LayerLogic.showsLayer(layers, 3L))
    }

    @Test
    fun showsLayer_hiddenLayer_false() {
        assertFalse("layer 2 is hidden", LayerLogic.showsLayer(layers, 2L))
    }

    @Test
    fun showsLayer_absentLayer_true() {
        // layer not in list (deleted, or list not loaded yet) → visible to avoid hiding by race
        assertTrue("layer 99 not in list → visible", LayerLogic.showsLayer(layers, 99L))
    }

    @Test
    fun showsLayer_emptyList_nonZeroLayer_true() {
        // empty list = not loaded yet, so don't hide
        assertTrue("empty list → layer 1 visible", LayerLogic.showsLayer(emptyList(), 1L))
    }

    @Test
    fun canDelete_singleLayer_false() {
        val single = listOf(Layer(1L, 1L, "Capa 1", true))
        assertFalse("cannot delete the only layer", LayerLogic.canDelete(single, 1L))
    }

    @Test
    fun canDelete_twoLayers_true() {
        val two = listOf(Layer(1L, 1L, "Capa 1", true), Layer(2L, 1L, "Capa 2", true))
        assertTrue("can delete layer 1 of 2", LayerLogic.canDelete(two, 1L))
        assertTrue("can delete layer 2 of 2", LayerLogic.canDelete(two, 2L))
    }

    @Test
    fun canDelete_foreignId_false() {
        val two = listOf(Layer(1L, 1L, "Capa 1", true), Layer(2L, 1L, "Capa 2", true))
        assertFalse("cannot delete layer 99 (not in list)", LayerLogic.canDelete(two, 99L))
    }
}