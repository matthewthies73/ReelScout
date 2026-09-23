package com.reelscout.app

import com.reelscout.domain.Title
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Saved titles, per device, as a JSON list in the same settings store as the region
 * (multiplatform-settings: SharedPreferences, NSUserDefaults, java.util.prefs, localStorage).
 * A favorites list stays small, so a key-value entry is enough - see ROADMAP.md, Phase 5.
 */
class FavoritesStore(private val settings: Settings = Settings()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Title.serializer())

    fun load(): List<Title> =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    fun save(favorites: List<Title>) = settings.putString(KEY, json.encodeToString(serializer, favorites.take(MAX_FAVORITES)))

    private companion object {
        const val KEY = "favorites"
        // Newest first; the oldest drop off past this.
        const val MAX_FAVORITES = 200
    }
}
