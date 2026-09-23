package com.reelscout.domain

import kotlinx.serialization.Serializable

enum class MediaType { MOVIE, TV }

/**
 * Countries the region picker offers (ROADMAP.md, Phase 5). Codes are ISO 3166-1 alpha-2, as
 * TMDB's watch/providers and Watchmode's regions= expect. TMDB covers all of these; Watchmode's
 * coverage is thinner outside the US, CA, GB and AU, where TMDB carries more of the answer.
 */
enum class Region(
    val code: String,
    val displayName: String,
    // For use mid-sentence: "in the United States", "in Canada".
    val inSentence: String = displayName
) {
    US("US", "United States", "the United States"),
    CA("CA", "Canada"),
    GB("GB", "United Kingdom", "the United Kingdom"),
    IE("IE", "Ireland"),
    AU("AU", "Australia"),
    NZ("NZ", "New Zealand"),
    DE("DE", "Germany"),
    FR("FR", "France"),
    ES("ES", "Spain"),
    IT("IT", "Italy"),
    NL("NL", "Netherlands", "the Netherlands"),
    SE("SE", "Sweden"),
    BR("BR", "Brazil"),
    MX("MX", "Mexico"),
    IN("IN", "India");

    companion object {
        val DEFAULT = US

        fun fromCode(code: String?): Region? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
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
    // Free, but only with a participating public library card (see FreeSourceRules).
    val libraryCardOptions: List<WatchOption> = emptyList(),
    val moreInfoUrl: String? = null
)

@Serializable
data class PublicDomainFilm(
    val title: String,
    val year: Int?,
    val url: String
)
