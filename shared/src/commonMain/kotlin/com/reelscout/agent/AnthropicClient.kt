package com.reelscout.agent

import com.reelscout.data.EdgeApiConfig
import com.reelscout.data.commonJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** Posts to the relay's /api/anthropic/messages route, which forwards to api.anthropic.com. */
class AnthropicClient(private val client: HttpClient) {

    suspend fun sendMessage(request: AnthropicRequest): AnthropicResponse {
        var attempt = 0
        while (true) {
            val response = client.post("${EdgeApiConfig.baseUrl}/api/anthropic/messages") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) return response.body()

            val error = response.toApiException()
            if (!error.isRetryable || attempt >= MAX_RETRIES) throw error
            delay(RETRY_BASE_DELAY_MS shl attempt)
            attempt++
        }
    }

    private suspend fun HttpResponse.toApiException(): AnthropicApiException {
        val text = bodyAsText()
        // Both Anthropic and the relay use {"type":"error","error":{"type":...,"message":...}}.
        val error = runCatching { commonJson.decodeFromString(AnthropicErrorResponse.serializer(), text).error }.getOrNull()
        return AnthropicApiException(
            status = status.value,
            type = error?.type ?: "api_error",
            message = error?.message ?: text.take(200)
        )
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_BASE_DELAY_MS = 1_000L
    }
}

class AnthropicApiException(val status: Int, val type: String, message: String) : Exception(message) {
    // Overloaded (529) and other 5xx errors are usually gone within seconds. 429 isn't
    // retried: the relay's rate-limit window is a full minute (edge/wrangler.toml).
    val isRetryable: Boolean get() = status >= 500
}

@Serializable
internal data class AnthropicErrorResponse(val error: AnthropicError)

@Serializable
internal data class AnthropicError(val type: String, val message: String)
