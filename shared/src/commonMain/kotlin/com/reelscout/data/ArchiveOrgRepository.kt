package com.reelscout.data

import com.reelscout.domain.PublicDomainFilm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/** Public-domain film fallback for titles too old to be covered by modern streaming catalogs. */
class ArchiveOrgRepository(private val client: HttpClient) {

    suspend fun searchPublicDomainFilm(title: String): List<PublicDomainFilm> {
        // A quote inside the title would end the phrase query early.
        val phrase = title.replace("\"", "")
        val response: ArchiveOrgSearchResponse =
            client.get("${EdgeApiConfig.baseUrl}/api/archive/advancedsearch.php") {
                // feature_films keeps out trailers and clips; most-downloaded copies first.
                parameter("q", "title:(\"$phrase\") AND mediatype:(movies) AND collection:(feature_films)")
                listOf("identifier", "title", "year").forEach { parameter("fl[]", it) }
                parameter("sort[]", "downloads desc")
                parameter("rows", 5)
                parameter("output", "json")
            }.body()

        return response.response.docs.map { doc ->
            PublicDomainFilm(
                title = doc.title ?: doc.identifier,
                year = doc.year?.take(4)?.toIntOrNull(),
                url = "https://archive.org/details/${doc.identifier}"
            )
        }
    }
}

@Serializable
data class ArchiveOrgSearchResponse(val response: ArchiveOrgDocsWrapper)

@Serializable
data class ArchiveOrgDocsWrapper(val docs: List<ArchiveOrgDoc> = emptyList())

@Serializable
data class ArchiveOrgDoc(
    val identifier: String,
    val title: String? = null,
    val year: String? = null // a number in most docs, a string in some; commonJson is lenient
)
