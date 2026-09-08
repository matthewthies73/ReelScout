package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/** Cross-checks TMDB and supplies explicit is_free / ad-supported flags TMDB doesn't give directly. */
class WatchmodeRepository(private val client: HttpClient) {

    suspend fun getSources(watchmodeTitleId: Int, region: String): List<WatchmodeSource> =
        client.get("${EdgeApiConfig.baseUrl}/api/watchmode/title/$watchmodeTitleId/sources") {
            parameter("regions", region)
        }.body()
}

@Serializable
data class WatchmodeSource(
    val name: String,
    val type: String,       // "free" | "sub" | "rent" | "buy"
    val region: String,
    val webUrl: String? = null
)
