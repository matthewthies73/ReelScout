package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Six Degrees of Matthew Thies: people the app's creator has met in person, from the relay's
 * GET /api/personal-links (edge/src/personalLinks.ts, where new ones are added). Fetched once
 * per run. It's a joke, so if the fetch fails a connection search just goes without them.
 */
class PersonalLinksRepository(private val client: HttpClient) {
    private var cached: PersonalLinks? = null

    /** Null when the relay couldn't be reached; tried again next time. */
    suspend fun get(): PersonalLinks? {
        cached?.let { return it }
        return try {
            val response = client.get("${EdgeApiConfig.baseUrl}/api/personal-links")
            if (response.status.isSuccess()) response.body<PersonalLinks>().also { cached = it } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** [person] and the people they've met, each one degree from them. */
@Serializable
data class PersonalLinks(val person: LinkedPerson, val links: List<PersonalLink>)

@Serializable
data class LinkedPerson(val tmdbId: Int, val name: String)

/** Someone [PersonalLinks.person] has met, by TMDB person id, and how they met. */
@Serializable
data class PersonalLink(val tmdbId: Int, val name: String, val how: String)
