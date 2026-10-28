package com.musigatto.fosscore.ui.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How the viewer shows the page. The same for every score; half page for each orientation, because
 * a stand is not held the same way in portrait and in landscape.
 */
data class DisplaySettings(
    val twoUp: Boolean = true,
    val halfPage: Boolean = false,
    val dimPct: Float = 0f,
    val invert: Boolean = false,
    val keepScreenOn: Boolean = false,
)

/** Where [DisplaySettings] are kept. */
interface DisplayStore {
    fun load(): DisplaySettings
    fun save(settings: DisplaySettings)
}

/** The app settings, half page for [landscape]. */
private class SettingsDisplayStore(private val context: Context, private val landscape: Boolean) : DisplayStore {
    override fun load() = DisplaySettings(
        twoUp = Settings.twoUp(context),
        halfPage = Settings.halfPage(context, landscape),
        dimPct = Settings.dimPct(context),
        invert = Settings.invert(context),
        keepScreenOn = Settings.keepScreenOn(context),
    )

    override fun save(settings: DisplaySettings) {
        Settings.setTwoUp(context, settings.twoUp)
        Settings.setHalfPage(context, landscape, settings.halfPage)
        Settings.setDimPct(context, settings.dimPct)
        Settings.setInvert(context, settings.invert)
        Settings.setKeepScreenOn(context, settings.keepScreenOn)
    }
}

/**
 * How the pages show, read and written once instead of [Settings] calls spread through the screen
 * (architecture review, finding A4). The viewer draws what this holds and sends the user's choices
 * back here; the page turning, the zoom and the annotations stay in the viewer.
 */
class ViewerViewModel(private val store: DisplayStore) : ViewModel() {

    private val _display = MutableStateFlow(store.load())
    val display: StateFlow<DisplaySettings> = _display.asStateFlow()

    fun setTwoUp(on: Boolean) = change { it.copy(twoUp = on) }

    fun setHalfPage(on: Boolean) = change { it.copy(halfPage = on) }

    /** The dim slider follows the finger: it is saved when it is let go, not on every frame. */
    fun setDimPct(pct: Float) {
        _display.update { it.copy(dimPct = pct) }
    }

    fun commitDimPct() = store.save(_display.value)

    fun setInvert(on: Boolean) = change { it.copy(invert = on) }

    fun setKeepScreenOn(on: Boolean) = change { it.copy(keepScreenOn = on) }

    /** Annotating goes back to a single page, without forgetting the reading mode. */
    fun singlePage() {
        _display.update { it.copy(twoUp = false, halfPage = false) }
    }

    /** Leaving edit mode: back to the pages as the user set them. */
    fun restoreReadingMode() {
        val saved = store.load()
        _display.update { it.copy(twoUp = saved.twoUp, halfPage = saved.halfPage) }
    }

    private fun change(edit: (DisplaySettings) -> DisplaySettings) {
        _display.update(edit)
        store.save(_display.value)
    }

    companion object {
        /** Half page is kept per orientation: a turned screen reads its own. */
        fun factory(context: Context, landscape: Boolean) = viewModelFactory {
            initializer { ViewerViewModel(SettingsDisplayStore(context.applicationContext, landscape)) }
        }
    }
}
