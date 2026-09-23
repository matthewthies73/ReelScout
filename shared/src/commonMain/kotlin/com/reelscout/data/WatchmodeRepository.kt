package com.reelscout.data

import com.reelscout.domain.MediaType
import com.reelscout.domain.RegionAvailability
import com.reelscout.domain.WatchOption
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** Cross-checks TMDB and supplies explicit free flags plus a direct link to each source. */
class WatchmodeRepository(private val client: HttpClient) {

    suspend fun getAvailability(tmdbId: Int, mediaType: MediaType, region: String): RegionAvailability {
        // Watchmode accepts TMDB ids directly as "movie-<id>" / "tv-<id>", so no id lookup is needed.
        val titleId = "${if (mediaType == MediaType.TV) "tv" else "movie"}-$tmdbId"
        val response = client.get("${EdgeApiConfig.baseUrl}/api/watchmode/title/$titleId/sources") {
            parameter("regions", region.uppercase())
        }

        // Titles Watchmode doesn't know come back as an error object, not an empty list.
        val sources: List<WatchmodeSource> = if (response.status.isSuccess()) response.body() else emptyList()
        return sources.toAvailability(region.uppercase())
    }
}

@Serializable
data class WatchmodeSource(
    val name: String,
    val type: String,       // "free" | "sub" | "rent" | "buy" | "tve"
    val region: String,
    val webUrl: String? = null
)

// Watchmode lists each service once per format (SD/HD/4K), so collapse to one entry per name.
internal fun List<WatchmodeSource>.toAvailability(region: String): RegionAvailability {
    val inRegion = filter { it.region.equals(region, ignoreCase = true) }
    fun optionsOfType(type: String) = inRegion.filter { it.type == type }
        .groupBy { it.name }
        .map { (name, rows) -> WatchOption(name, url = rows.firstNotNullOfOrNull { it.webUrl }) }

    val (freeForAnyone, withLibraryCard) = FreeSourceRules.split(optionsOfType("free"))
    return RegionAvailability(
        source = "watchmode",
        region = region,
        freeOptions = freeForAnyone,
        subscriptionOptions = optionsOfType("sub").map { it.copy(url = null) },
        libraryCardOptions = withLibraryCard
    )
}
