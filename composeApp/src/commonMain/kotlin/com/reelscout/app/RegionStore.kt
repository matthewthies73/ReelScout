package com.reelscout.app

import androidx.compose.ui.text.intl.Locale
import com.reelscout.domain.Region
import com.russhwolf.settings.Settings

/**
 * The user's picked region, remembered per device (SharedPreferences, NSUserDefaults,
 * java.util.prefs or localStorage, via multiplatform-settings). Until they pick one,
 * the device's own region if ReelScout supports it, otherwise the US.
 */
class RegionStore(private val settings: Settings = Settings()) {

    fun load(): Region =
        Region.fromCode(settings.getStringOrNull(KEY))
            ?: Region.fromCode(Locale.current.region)
            ?: Region.DEFAULT

    fun save(region: Region) = settings.putString(KEY, region.code)

    private companion object {
        const val KEY = "region"
    }
}
