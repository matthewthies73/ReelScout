package com.reelscout.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A hand-rolled model of the subset of Anthropic's Messages API this app needs, sent
 * through the Cloudflare Worker relay (see EdgeApiConfig). Deliberately not using the
 * Anthropic Java SDK here — it's JVM-only, which would break the iOS and Wasm/Web
 * targets. This is the multiplatform-safe way to speak the same wire protocol.
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
    val tools: List<ToolDefinition>
)

@Serializable
data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: List<ContentBlock>
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val role: String,
    val content: List<ContentBlock>,
    val model: String,
    val stopReason: String? = null
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonElement
)

@Serializable
sealed class ContentBlock {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    // Claude Sonnet 5 thinks adaptively by default. These have to be echoed back unchanged
    // in the assistant turn during the tool-use loop, so they're modeled rather than dropped.
    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String) : ContentBlock()

    @Serializable
    @SerialName("redacted_thinking")
    data class RedactedThinking(val data: String) : ContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonElement) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    ) : ContentBlock()
}
