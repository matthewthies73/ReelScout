package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/** Public-domain film fallback for titles too old to be covered by modern streaming catalogs. */
class ArchiveOrgRepository(private val client: HttpClient) {

    suspend fun searchPublicDomainFilm(title: String): ArchiveOrgSearchResponse =
        client.get("${EdgeApiConfig.baseUrl}/api/archive/advancedsearch.php") {
            parameter("q", "title:(\"$title\") AND mediatype:(movies)")
            parameter("output", "json")
        }.body()
}

@Serializable
data class ArchiveOrgSearchResponse(val response: ArchiveOrgDocsWrapper)

@Serializable
data class ArchiveOrgDocsWrapper(val docs: List<ArchiveOrgDoc> = emptyList())

@Serializable
data class ArchiveOrgDoc(
    val identifier: String,
    val title: String? = null
) {
    val watchUrl: String get() = "https://archive.org/details/$identifier"
}
