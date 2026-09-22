package com.reelscout.agent

import com.reelscout.data.ArchiveOrgRepository
import com.reelscout.data.TmdbRepository
import com.reelscout.data.WatchmodeRepository
import com.reelscout.data.commonJson
import com.reelscout.domain.MediaType
import com.reelscout.domain.PublicDomainFilm
import com.reelscout.domain.RegionAvailability
import com.reelscout.domain.Title
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Executes a tool_use block against the real repositories and returns tool_result content. */
class ToolExecutor(
    private val tmdb: TmdbRepository,
    private val watchmode: WatchmodeRepository,
    private val archive: ArchiveOrgRepository
) {
    suspend fun execute(name: String, input: JsonElement): String = when (name) {
        "search_titles" -> {
            val query = input.jsonObject["query"]?.jsonPrimitive?.content.orEmpty()
            // TMDB returns up to 20 matches with full synopses; the top few are enough to pick from.
            val results = tmdb.searchTitles(query).take(MAX_SEARCH_RESULTS)
                .map { it.copy(overview = it.overview.take(MAX_OVERVIEW_CHARS)) }
            commonJson.encodeToString(ListSerializer(Title.serializer()), results)
        }

        "get_watch_providers" -> {
            val (tmdbId, mediaType, region) = titleInRegion(input)
            commonJson.encodeToString(RegionAvailability.serializer(), tmdb.getWatchProviders(tmdbId, mediaType, region))
        }

        "get_watchmode_sources" -> {
            val (tmdbId, mediaType, region) = titleInRegion(input)
            commonJson.encodeToString(RegionAvailability.serializer(), watchmode.getAvailability(tmdbId, mediaType, region))
        }

        "search_public_domain" -> {
            val title = input.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
            commonJson.encodeToString(ListSerializer(PublicDomainFilm.serializer()), archive.searchPublicDomainFilm(title))
        }

        else -> """{"error":"unknown tool: $name"}"""
    }

    /** The tmdbId/mediaType/region inputs shared by both availability tools. */
    private fun titleInRegion(input: JsonElement): Triple<Int, MediaType, String> {
        val obj = input.jsonObject
        val tmdbId = obj["tmdbId"]?.jsonPrimitive?.int ?: error("tmdbId is required")
        val mediaType = if (obj["mediaType"]?.jsonPrimitive?.content == "tv") MediaType.TV else MediaType.MOVIE
        val region = obj["region"]?.jsonPrimitive?.content ?: "US"
        return Triple(tmdbId, mediaType, region)
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 8
        const val MAX_OVERVIEW_CHARS = 200
    }
}
