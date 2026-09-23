package com.reelscout.agent

import com.reelscout.data.ArchiveOrgRepository
import com.reelscout.data.TmdbRepository
import com.reelscout.data.WatchmodeRepository
import com.reelscout.data.commonJson
import com.reelscout.data.installEdgeDefaults
import com.reelscout.domain.PublicDomainFilm
import com.reelscout.domain.RegionAvailability
import com.reelscout.domain.Title
import com.reelscout.domain.WatchOption
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs each tool against a MockEngine standing in for the relay, and checks what Claude would see. */
class ToolExecutorTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun executor(vararg routes: Pair<String, Pair<HttpStatusCode, String>>): ToolExecutor {
        val byPath = routes.toMap()
        val client = HttpClient(MockEngine { request ->
            requests += request
            val (status, body) = byPath[request.url.encodedPath] ?: (HttpStatusCode.NotFound to "{}")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { installEdgeDefaults() }
        return ToolExecutor(TmdbRepository(client), WatchmodeRepository(client), ArchiveOrgRepository(client))
    }

    private fun input(json: String) = Json.parseToJsonElement(json)

    private val movieInUs = input("""{"tmdbId": 10331, "mediaType": "movie", "region": "US"}""")

    private val tmdbProviders = """
        {"id": 10331, "results": {
          "US": {
            "link": "https://www.themoviedb.org/movie/10331/watch?locale=US",
            "free": [{"provider_name": "Plex", "logo_path": "/a.png"}, {"provider_name": "Kanopy", "logo_path": "/k.png"}],
            "ads": [{"provider_name": "Plex", "logo_path": "/a.png"}, {"provider_name": "Pluto TV", "logo_path": "/b.png"}],
            "flatrate": [{"provider_name": "Peacock", "logo_path": "/c.png"}]
          },
          "GB": {"flatrate": [{"provider_name": "BBC iPlayer", "logo_path": "/d.png"}]}
        }}
    """.trimIndent()

    @Test
    fun `get_watch_providers returns only the requested region`() = runTest {
        val tools = executor("/api/tmdb/movie/10331/watch/providers" to (HttpStatusCode.OK to tmdbProviders))

        val result = tools.execute("get_watch_providers", movieInUs)
        val availability = commonJson.decodeFromString(RegionAvailability.serializer(), result)

        assertFalse("BBC iPlayer" in result, "other regions must not reach Claude")
        assertEquals("US", availability.region)
        assertEquals(
            listOf(WatchOption("Plex", adSupported = false), WatchOption("Pluto TV", adSupported = true)),
            availability.freeOptions
        )
        assertEquals(listOf(WatchOption("Peacock")), availability.subscriptionOptions)
        assertEquals(listOf(WatchOption("Kanopy", adSupported = false)), availability.libraryCardOptions)
        assertEquals("https://www.themoviedb.org/movie/10331/watch?locale=US", availability.moreInfoUrl)
    }

    @Test
    fun `get_watch_providers is empty for a region TMDB has no data for`() = runTest {
        val tools = executor("/api/tmdb/movie/10331/watch/providers" to (HttpStatusCode.OK to tmdbProviders))

        val result = tools.execute("get_watch_providers", input("""{"tmdbId": 10331, "mediaType": "movie", "region": "fr"}"""))
        val availability = commonJson.decodeFromString(RegionAvailability.serializer(), result)

        assertEquals("FR", availability.region)
        assertTrue(availability.freeOptions.isEmpty() && availability.subscriptionOptions.isEmpty())
    }

    @Test
    fun `get_watchmode_sources collapses formats and keeps links for free sources`() = runTest {
        val sources = """
            [
              {"name": "Pluto TV", "type": "free", "region": "US", "web_url": "https://pluto.tv/notld", "format": "SD"},
              {"name": "Pluto TV", "type": "free", "region": "US", "web_url": null, "format": "HD"},
              {"name": "Tubi TV", "type": "free", "region": "US", "web_url": "https://tubitv.com/notld"},
              {"name": "Hoopla", "type": "free", "region": "US", "web_url": "https://www.hoopladigital.com/title/1"},
              {"name": "Peacock", "type": "sub", "region": "US", "web_url": "https://peacock.com/notld", "format": "HD"},
              {"name": "Peacock", "type": "sub", "region": "US", "web_url": "https://peacock.com/notld", "format": "4K"},
              {"name": "Amazon", "type": "rent", "region": "US", "web_url": "https://amazon.com/notld", "price": 3.99},
              {"name": "BBC iPlayer", "type": "free", "region": "GB", "web_url": "https://bbc.co.uk/notld"}
            ]
        """.trimIndent()
        val tools = executor("/api/watchmode/title/movie-10331/sources" to (HttpStatusCode.OK to sources))

        val result = tools.execute("get_watchmode_sources", movieInUs)
        val availability = commonJson.decodeFromString(RegionAvailability.serializer(), result)

        assertEquals("US", requests.single().url.parameters["regions"])
        assertEquals(
            listOf(WatchOption("Pluto TV", url = "https://pluto.tv/notld"), WatchOption("Tubi TV", url = "https://tubitv.com/notld")),
            availability.freeOptions
        )
        assertEquals(listOf(WatchOption("Peacock")), availability.subscriptionOptions)
        assertEquals(listOf(WatchOption("Hoopla", url = "https://www.hoopladigital.com/title/1")), availability.libraryCardOptions)
    }

    @Test
    fun `get_watchmode_sources is empty when Watchmode doesn't know the title`() = runTest {
        val tools = executor(
            "/api/watchmode/title/tv-1396/sources" to
                (HttpStatusCode.NotFound to """{"success": false, "statusCode": 404, "statusMessage": "Not found"}""")
        )

        val result = tools.execute("get_watchmode_sources", input("""{"tmdbId": 1396, "mediaType": "tv", "region": "US"}"""))
        val availability = commonJson.decodeFromString(RegionAvailability.serializer(), result)

        assertTrue(availability.freeOptions.isEmpty() && availability.subscriptionOptions.isEmpty())
    }

    @Test
    fun `search_public_domain builds a feature-film query and returns watch links`() = runTest {
        val docs = """
            {"response": {"numFound": 3, "docs": [
              {"identifier": "Night.Of.The.Living.Dead_1080p", "title": "Night of the Living Dead", "year": 1968},
              {"identifier": "his_girl_friday", "title": "His Girl Friday", "year": "1940"},
              {"identifier": "untitled_reel"}
            ]}}
        """.trimIndent()
        val tools = executor("/api/archive/advancedsearch.php" to (HttpStatusCode.OK to docs))

        val result = tools.execute("search_public_domain", input("""{"title": "Night of the \"Living\" Dead"}"""))
        val films = commonJson.decodeFromString(ListSerializer(PublicDomainFilm.serializer()), result)

        val params = requests.single().url.parameters
        assertEquals(
            "title:(\"Night of the Living Dead\") AND mediatype:(movies) AND collection:(feature_films)",
            params["q"]
        )
        assertEquals(listOf("identifier", "title", "year"), params.getAll("fl[]"))
        assertEquals(
            listOf(
                PublicDomainFilm("Night of the Living Dead", 1968, "https://archive.org/details/Night.Of.The.Living.Dead_1080p"),
                PublicDomainFilm("His Girl Friday", 1940, "https://archive.org/details/his_girl_friday"),
                PublicDomainFilm("untitled_reel", null, "https://archive.org/details/untitled_reel")
            ),
            films
        )
    }

    @Test
    fun `search_titles caps result count and synopsis length`() = runTest {
        val longOverview = "x".repeat(500)
        val results = (1..12).joinToString(",") {
            """{"id": $it, "title": "Movie $it", "media_type": "movie", "overview": "$longOverview"}"""
        }
        val tools = executor("/api/tmdb/search/multi" to (HttpStatusCode.OK to """{"results": [$results]}"""))

        val result = tools.execute("search_titles", input("""{"query": "movie"}"""))
        val titles = commonJson.decodeFromString(ListSerializer(Title.serializer()), result)

        assertEquals(8, titles.size)
        assertTrue(titles.all { it.overview.length == 200 })
    }
}
