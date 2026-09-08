package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Base URL of the Cloudflare Worker relay. It is the ONLY thing this app talks to
 * directly — the Worker holds the real Anthropic/TMDB/Watchmode secrets and forwards
 * requests, so no API key ever ships inside a client binary.
 *
 * See ROADMAP.md > "Backend on Cloudflare" for why the relay is deliberately dumb.
 */
object EdgeApiConfig {
    // TODO: replace with the deployed Worker URL once Phase 2 is live
    // (e.g. https://reelscout-relay.<your-subdomain>.workers.dev).
    var baseUrl: String = "http://localhost:8787"
}

/** Platform HTTP engine — see the androidMain/iosMain/desktopMain/wasmJsMain actuals. */
expect fun platformHttpClient(): HttpClient

fun HttpClient.withEdgeDefaults(): HttpClient = this

// TMDB/Watchmode/Archive.org all use snake_case JSON; this lets @Serializable data
// classes stay camelCase (idiomatic Kotlin) without a @SerialName on every field.
internal val commonJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

internal fun io.ktor.client.HttpClientConfig<*>.installEdgeDefaults() {
    install(ContentNegotiation) { json(commonJson) }
    install(Logging) { level = LogLevel.INFO }
}
