package com.reelscout.data

import com.reelscout.domain.Credit
import com.reelscout.domain.MediaType
import com.reelscout.domain.Person
import com.reelscout.domain.RegionAvailability
import com.reelscout.domain.Title
import com.reelscout.domain.WatchOption
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
    suspend fun getWatchProviders(tmdbId: Int, mediaType: MediaType, region: String): RegionAvailability {
        val path = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val response: TmdbWatchProvidersResponse =
            client.get("${EdgeApiConfig.baseUrl}/api/tmdb/$path/$tmdbId/watch/providers").body()

        // TMDB returns every country at once (~150 KB); only the requested one goes to Claude.
        return response.results[region.uppercase()].toAvailability(region.uppercase())
    }

    suspend fun searchPeople(query: String): List<Person> {
        val response: TmdbPersonSearchResponse = client.get("${EdgeApiConfig.baseUrl}/api/tmdb/search/person") {
            parameter("query", query)
        }.body()
        return response.results.map { it.toDomain() }
    }

    /**
     * A person's acting credits, most popular first. Talk, news and reality shows are dropped:
     * they're guest appearances as themselves, and would crowd out the actual roles.
     */
    suspend fun getPersonCredits(personId: Int): List<Credit> {
        val response: TmdbCombinedCredits =
            client.get("${EdgeApiConfig.baseUrl}/api/tmdb/person/$personId/combined_credits").body()

        return response.cast
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            .filter { credit -> credit.genreIds.none { it in NON_ACTING_GENRES } }
            // An actor can be credited twice on one title (two characters); keep the first.
            .distinctBy { it.mediaType to it.id }
            .sortedByDescending { it.popularity }
            .map { it.toDomain() }
    }

    private companion object {
        // TMDB TV genre ids: News, Reality, Talk.
        val NON_ACTING_GENRES = setOf(10763, 10764, 10767)
    }
}

@Serializable
data class TmdbPersonSearchResponse(val results: List<TmdbPersonResult>)

@Serializable
data class TmdbPersonResult(
    val id: Int,
    val name: String,
    val knownForDepartment: String? = null,
    val knownFor: List<TmdbSearchResult> = emptyList()
) {
    fun toDomain(): Person = Person(
        tmdbId = id,
        name = name,
        knownForDepartment = knownForDepartment,
        knownFor = knownFor.mapNotNull { it.title ?: it.name }
    )
}

@Serializable
data class TmdbCombinedCredits(val cast: List<TmdbCastCredit> = emptyList())

@Serializable
data class TmdbCastCredit(
    val id: Int,
    val title: String? = null,       // movies
    val name: String? = null,        // tv
    val mediaType: String? = null,
    val character: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val popularity: Double = 0.0,
    val genreIds: List<Int> = emptyList()
) {
    fun toDomain(): Credit = Credit(
        tmdbId = id,
        name = title ?: name ?: "Unknown title",
        mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
        releaseYear = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(),
        character = character?.takeIf { it.isNotBlank() },
        posterPath = posterPath
    )
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

internal fun TmdbRegionProviders?.toAvailability(region: String): RegionAvailability {
    val free = this?.free.orEmpty().map { WatchOption(it.providerName, adSupported = false) }
    val ads = this?.ads.orEmpty().map { WatchOption(it.providerName, adSupported = true) }
    val (freeForAnyone, withLibraryCard) = FreeSourceRules.split((free + ads).distinctBy { it.providerName })
    return RegionAvailability(
        source = "tmdb",
        region = region,
        freeOptions = freeForAnyone,
        subscriptionOptions = this?.flatrate.orEmpty().map { WatchOption(it.providerName) },
        libraryCardOptions = withLibraryCard,
        moreInfoUrl = this?.link
    )
}

@Serializable
data class TmdbProvider(
    val providerName: String,
    val logoPath: String? = null
)
