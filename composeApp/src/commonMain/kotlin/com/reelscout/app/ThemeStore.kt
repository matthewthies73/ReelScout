package com.reelscout.app

import com.russhwolf.settings.Settings

/** Whether the user switched to dark mode, remembered per device like [RegionStore]. Light by default. */
class ThemeStore(private val settings: Settings = Settings()) {

    fun load(): Boolean = settings.getBoolean(KEY, false)

    fun save(darkMode: Boolean) = settings.putBoolean(KEY, darkMode)

    private companion object {
        const val KEY = "dark_mode"
    }
}
