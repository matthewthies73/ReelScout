package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable

/** Public search analytics from the relay's GET /api/stats (edge/src/analytics.ts). */
class StatsRepository(private val client: HttpClient) {
    suspend fun getStats(): SearchStats = client.get("${EdgeApiConfig.baseUrl}/api/stats").body()
}

@Serializable
data class SearchStats(
    val totalSearches: Long,
    val topSearches: List<TopSearch> = emptyList()
)

@Serializable
data class TopSearch(val query: String, val count: Int)
