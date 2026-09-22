package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
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
    // Points at the Worker route (reelscout.bitterinfantproductions.com/api/* - see
    // edge/wrangler.toml). Override to http://localhost:8787 for local `wrangler dev`.
    var baseUrl: String = "https://reelscout.bitterinfantproductions.com"
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
    // Engine defaults are 10-15s, but one Claude turn (thinking + a long answer) can take
    // longer than that. The cap still bounds a hung connection.
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
    }
    install(Logging) { level = LogLevel.INFO }
}
