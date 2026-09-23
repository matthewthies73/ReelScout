package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsRepositoryTest {

    @Test
    fun `reads the relay's stats response`() = runTest {
        val body = """{"total_searches": 1234, "top_searches": [{"query": "Nosferatu", "count": 3}]}"""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/stats", request.url.encodedPath)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installEdgeDefaults() }

        assertEquals(SearchStats(1234, listOf(TopSearch("Nosferatu", 3))), StatsRepository(client).getStats())
    }
}
