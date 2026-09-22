package com.reelscout.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** Tool schemas handed to Claude in every request. Keep names/params in sync with ToolExecutor. */
object Tools {

    val searchTitles = ToolDefinition(
        name = "search_titles",
        description = "Search for movies and TV shows by name. Returns candidate titles with their TMDB id.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Free-text title or description to search for.")
                }
            }
            putJsonArray("required") { add("query") }
        }
    )

    // Both availability tools take the same title-in-region input.
    private val titleInRegionSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tmdbId") { put("type", "integer") }
            putJsonObject("mediaType") {
                put("type", "string")
                putJsonArray("enum") { add("movie"); add("tv") }
            }
            putJsonObject("region") {
                put("type", "string")
                put("description", "ISO 3166-1 region code, e.g. US.")
            }
        }
        putJsonArray("required") { add("tmdbId"); add("mediaType"); add("region") }
    }

    val getWatchProviders = ToolDefinition(
        name = "get_watch_providers",
        description = "Look up where a specific title (by TMDB id) can be streamed in a given " +
            "region, from TMDB/JustWatch data: free, ad-supported and subscription options.",
        inputSchema = titleInRegionSchema
    )

    val getWatchmodeSources = ToolDefinition(
        name = "get_watchmode_sources",
        description = "Cross-check a title's (by TMDB id) streaming availability in a region using " +
            "Watchmode, which also returns a direct link for each free source.",
        inputSchema = titleInRegionSchema
    )

    val searchPublicDomain = ToolDefinition(
        name = "search_public_domain",
        description = "Search Archive.org for a public-domain film matching a title. Use as a " +
            "fallback when a title has no modern streaming availability.",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
            }
            putJsonArray("required") { add("title") }
        }
    )

    val all = listOf(searchTitles, getWatchProviders, getWatchmodeSources, searchPublicDomain)
}
