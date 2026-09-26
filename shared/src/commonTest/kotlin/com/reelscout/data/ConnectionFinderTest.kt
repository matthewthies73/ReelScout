package com.reelscout.data

import com.reelscout.domain.Connection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs ConnectionFinder over a small film graph served by a MockEngine standing in for the relay. */
class ConnectionFinderTest {

    private data class Role(val personId: Int, val personName: String, val character: String)
    private data class Film(val id: Int, val title: String, val year: Int, val cast: List<Role>, val genreIds: List<Int> = emptyList())

    private var calls = 0

    private fun finder(films: List<Film>, maxCalls: Int = 40, failWith: HttpStatusCode? = null): ConnectionFinder {
        val client = HttpClient(MockEngine { request ->
            calls++
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (failWith != null) return@MockEngine respond("""{"type": "error"}""", failWith, json)
            val path = request.url.encodedPath
            val body = Regex("""/api/tmdb/person/(\d+)/combined_credits""").find(path)?.let { match ->
                val personId = match.groupValues[1].toInt()
                val credits = films.filter { film -> film.cast.any { it.personId == personId } }.joinToString(",") { film ->
                    val character = film.cast.first { it.personId == personId }.character
                    """{"id": ${film.id}, "title": "${film.title}", "media_type": "movie", "character": "$character",
                        "release_date": "${film.year}-01-01", "popularity": ${film.id}, "genre_ids": ${film.genreIds}}"""
                }
                """{"cast": [$credits]}"""
            } ?: Regex("""/api/tmdb/movie/(\d+)/credits""").find(path)?.let { match ->
                val film = films.first { it.id == match.groupValues[1].toInt() }
                """{"cast": [${film.cast.joinToString(",") { """{"id": ${it.personId}, "name": "${it.personName}", "character": "${it.character}"}""" }}]}"""
            } ?: error("unexpected request $path")
            respond(body, HttpStatusCode.OK, json)
        }) { installEdgeDefaults() }
        return ConnectionFinder(TmdbRepository(client), maxCalls = maxCalls)
    }

    private val elvis = Role(1, "Elvis Presley", "Dr. John Carpenter")
    private val asner = Role(2, "Edward Asner", "Lt. Moroney")
    private val bacon = Role(3, "Kevin Bacon", "Willie O'Keefe")

    private val changeOfHabit = Film(10, "Change of Habit", 1969, listOf(elvis, asner.copy(character = "Lt. Moroney")))
    private val jfk = Film(20, "JFK", 1991, listOf(asner.copy(character = "Guy Banister"), bacon))

    private fun Connection.chain() = links.map { "${it.from} > ${it.movie.name} (${it.movie.releaseYear}) > ${it.to}" }

    @Test
    fun `two people in the same film are one degree apart`() = runTest {
        val connection = finder(listOf(jfk)).find(2, "Edward Asner", 3, "Kevin Bacon")!!

        assertEquals(1, connection.degrees)
        assertEquals(listOf("Edward Asner > JFK (1991) > Kevin Bacon"), connection.chain())
        assertEquals("Guy Banister", connection.links.single().fromCharacter)
        assertEquals("Willie O'Keefe", connection.links.single().toCharacter)
    }

    @Test
    fun `a chain through a co-star is walked from one person to the other`() = runTest {
        val connection = finder(listOf(changeOfHabit, jfk)).find(1, "Elvis Presley", 3, "Kevin Bacon")!!

        assertEquals(2, connection.degrees)
        assertEquals(
            listOf("Elvis Presley > Change of Habit (1969) > Edward Asner", "Edward Asner > JFK (1991) > Kevin Bacon"),
            connection.chain()
        )
        assertEquals("Dr. John Carpenter", connection.links.first().fromCharacter)
    }

    @Test
    fun `documentaries don't count as a link`() = runTest {
        val documentary = Film(30, "Hollywood Stories", 2000, listOf(elvis, bacon), genreIds = listOf(99))

        assertNull(finder(listOf(documentary)).find(1, "Elvis Presley", 3, "Kevin Bacon"))
    }

    @Test
    fun `archive footage doesn't count as a link`() = runTest {
        val forrestGump = Film(40, "Forrest Gump", 1994, listOf(elvis.copy(character = "Self (archive footage) (uncredited)"), bacon))

        assertNull(finder(listOf(forrestGump)).find(1, "Elvis Presley", 3, "Kevin Bacon"))
    }

    @Test
    fun `the search stops at its call budget`() = runTest {
        // A long chain of films, each sharing one person with the next.
        val films = (1..30).map { i -> Film(100 + i, "Film $i", 2000, listOf(Role(i, "P$i", "A"), Role(i + 1, "P${i + 1}", "B"))) }

        assertNull(finder(films, maxCalls = 6).find(1, "P1", 31, "P31"))
        assertEquals(6, calls)
    }

    @Test
    fun `a failed lookup is an error, not an empty filmography`() = runTest {
        assertFailsWith<IllegalStateException> {
            finder(listOf(jfk), failWith = HttpStatusCode.TooManyRequests).find(2, "Edward Asner", 3, "Kevin Bacon")
        }
    }

    @Test
    fun `a person is zero degrees from themselves`() = runTest {
        val connection = finder(emptyList()).find(3, "Kevin Bacon", 3, "Kevin Bacon")!!

        assertEquals(0, connection.degrees)
        assertTrue(connection.links.isEmpty())
        assertEquals(0, calls)
    }
}
