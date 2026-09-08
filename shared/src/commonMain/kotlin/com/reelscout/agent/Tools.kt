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

    val getWatchProviders = ToolDefinition(
        name = "get_watch_providers",
        description = "Look up where a specific title (by TMDB id) can be streamed in a given " +
            "region, including free/ad-supported options.",
        inputSchema = buildJsonObject {
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

    val all = listOf(searchTitles, getWatchProviders, searchPublicDomain)
}
