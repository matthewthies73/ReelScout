package com.reelscout.domain

import kotlinx.serialization.Serializable

enum class MediaType { MOVIE, TV }

enum class Region(val code: String) {
    US("US")
    // Add more as region support expands (see ROADMAP.md, Phase 5).
}

@Serializable
data class Title(
    val tmdbId: Int,
    val name: String,
    val mediaType: MediaType,
    val overview: String,
    val releaseYear: Int?,
    val posterPath: String?
)

@Serializable
data class WatchOption(
    val providerName: String,
    val isFree: Boolean,
    val isAdSupported: Boolean,
    val deepLinkUrl: String?
)

@Serializable
data class TitleAvailability(
    val title: Title,
    val region: String,
    val freeOptions: List<WatchOption>,
    val subscriptionOptions: List<WatchOption>,
    val source: String // "tmdb" | "watchmode" | "archive.org"
)
