package com.reelscout.agent

import com.reelscout.data.EdgeApiConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Posts to the relay's /api/anthropic/messages route, which forwards to api.anthropic.com. */
class AnthropicClient(private val client: HttpClient) {
    suspend fun sendMessage(request: AnthropicRequest): AnthropicResponse =
        client.post("${EdgeApiConfig.baseUrl}/api/anthropic/messages") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
}
