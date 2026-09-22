package com.reelscout.data

import com.reelscout.domain.MediaType
import com.reelscout.domain.Title
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/**
 * Talks to TMDB *through the Cloudflare Worker relay* at `/api/tmdb/…` — never directly,
 * so the TMDB key stays server-side. See edge/src/index.ts for the matching route.
 */
class TmdbRepository(private val client: HttpClient) {

    suspend fun searchTitles(query: String): List<Title> {
        val response: TmdbSearchResponse = client.get("${EdgeApiConfig.baseUrl}/api/tmdb/search/multi") {
            parameter("query", query)
        }.body()

        return response.results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .map { it.toDomain() }
    }

    /** TMDB's watch/providers endpoint — backed by JustWatch licensing data. */
    suspend fun getWatchProviders(tmdbId: Int, mediaType: MediaType, region: String): TmdbWatchProvidersResponse {
        val path = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        return client.get("${EdgeApiConfig.baseUrl}/api/tmdb/$path/$tmdbId/watch/providers").body()
    }
}

@Serializable
data class TmdbSearchResponse(val results: List<TmdbSearchResult>)

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String? = null,       // movies
    val name: String? = null,        // tv
    val overview: String = "",
    val mediaType: String? = null,
    val posterPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null
) {
    fun toDomain(): Title = Title(
        tmdbId = id,
        name = title ?: name ?: "Unknown title",
        mediaType = if (mediaType == "tv") com.reelscout.domain.MediaType.TV else com.reelscout.domain.MediaType.MOVIE,
        overview = overview,
        releaseYear = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(),
        posterPath = posterPath
    )
}

@Serializable
data class TmdbWatchProvidersResponse(
    val results: Map<String, TmdbRegionProviders> = emptyMap()
)

@Serializable
data class TmdbRegionProviders(
    val link: String? = null,
    val flatrate: List<TmdbProvider>? = null,
    val free: List<TmdbProvider>? = null,
    val ads: List<TmdbProvider>? = null
)

@Serializable
data class TmdbProvider(
    val providerName: String,
    val logoPath: String? = null
)
