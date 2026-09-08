package com.reelscout.agent

import com.reelscout.data.ArchiveOrgRepository
import com.reelscout.data.ArchiveOrgSearchResponse
import com.reelscout.data.TmdbRepository
import com.reelscout.data.TmdbWatchProvidersResponse
import com.reelscout.data.WatchmodeRepository
import com.reelscout.data.commonJson
import com.reelscout.domain.MediaType
import com.reelscout.domain.Title
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
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
            val results = tmdb.searchTitles(query)
            commonJson.encodeToString(ListSerializer(Title.serializer()), results)
        }

        "get_watch_providers" -> {
            val obj = input.jsonObject
            val tmdbId = obj["tmdbId"]?.jsonPrimitive?.int ?: error("tmdbId is required")
            val mediaType = if (obj["mediaType"]?.jsonPrimitive?.content == "tv") MediaType.TV else MediaType.MOVIE
            val region = obj["region"]?.jsonPrimitive?.content ?: "US"
            val response = tmdb.getWatchProviders(tmdbId, mediaType, region)
            commonJson.encodeToString(TmdbWatchProvidersResponse.serializer(), response)
        }

        "search_public_domain" -> {
            val title = input.jsonObject["title"]?.jsonPrimitive?.content.orEmpty()
            val response = archive.searchPublicDomainFilm(title)
            commonJson.encodeToString(ArchiveOrgSearchResponse.serializer(), response)
        }

        else -> """{"error":"unknown tool: $name"}"""
    }
}
