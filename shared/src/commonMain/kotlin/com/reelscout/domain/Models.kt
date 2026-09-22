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

/** One place to watch a title. `adSupported` is null when the source doesn't say. */
@Serializable
data class WatchOption(
    val providerName: String,
    val adSupported: Boolean? = null,
    val url: String? = null
)

/**
 * Where a title can be watched in one region, according to one source. This is what the
 * availability tools hand back to Claude, so it's kept small - see ToolExecutor.
 */
@Serializable
data class RegionAvailability(
    val source: String, // "tmdb" | "watchmode"
    val region: String,
    val freeOptions: List<WatchOption>,
    val subscriptionOptions: List<WatchOption>,
    val moreInfoUrl: String? = null
)

@Serializable
data class PublicDomainFilm(
    val title: String,
    val year: Int?,
    val url: String
)
