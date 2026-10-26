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
import kotlin.test.assertNull

class PersonalLinksRepositoryTest {

    private var calls = 0

    private fun repository(vararg statuses: HttpStatusCode): PersonalLinksRepository {
        val replies = ArrayDeque(statuses.toList())
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/personal-links", request.url.encodedPath)
            respond(body, replies.removeFirst(), headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installEdgeDefaults() }
        return PersonalLinksRepository(client)
    }

    private val body = """
        {"person": {"tmdb_id": 1740657, "name": "Matthew Thies"},
         "links": [{"tmdb_id": 2390, "name": "LeVar Burton", "how": "met at parties"}]}
    """.trimIndent()

    @Test
    fun `reads the relay's list once per run`() = runTest {
        val repository = repository(HttpStatusCode.OK)

        val expected = PersonalLinks(LinkedPerson(1740657, "Matthew Thies"), listOf(PersonalLink(2390, "LeVar Burton", "met at parties")))
        assertEquals(expected, repository.get())
        assertEquals(expected, repository.get())
        assertEquals(1, calls)
    }

    @Test
    fun `a failed fetch means no personal links, and is tried again next time`() = runTest {
        val repository = repository(HttpStatusCode.TooManyRequests, HttpStatusCode.OK)

        assertNull(repository.get())
        assertEquals("LeVar Burton", repository.get()?.links?.single()?.name)
        assertEquals(2, calls)
    }
}
