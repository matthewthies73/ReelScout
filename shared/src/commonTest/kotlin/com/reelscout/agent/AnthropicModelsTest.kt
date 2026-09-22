package com.reelscout.agent

import com.reelscout.data.commonJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnthropicModelsTest {

    // Shape of a Sonnet 5 tool-use turn with adaptive thinking on (display defaults to omitted).
    private val responseJson = """
        {
          "id": "msg_1",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-5",
          "stop_reason": "tool_use",
          "content": [
            {"type": "thinking", "thinking": "", "signature": "sig-abc"},
            {"type": "redacted_thinking", "data": "opaque"},
            {"type": "tool_use", "id": "toolu_1", "name": "search_titles", "input": {"query": "Nosferatu"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `response with thinking blocks deserializes`() {
        val response = commonJson.decodeFromString(AnthropicResponse.serializer(), responseJson)

        assertEquals("tool_use", response.stopReason)
        assertIs<ContentBlock.Thinking>(response.content[0])
        assertIs<ContentBlock.RedactedThinking>(response.content[1])
        assertIs<ContentBlock.ToolUse>(response.content[2])
    }

    @Test
    fun `thinking blocks are echoed back unchanged`() {
        val original = Json.parseToJsonElement(responseJson) as JsonObject
        val response = commonJson.decodeFromString(AnthropicResponse.serializer(), responseJson)

        val echoed = commonJson.encodeToJsonElement(
            AnthropicMessage.serializer(),
            AnthropicMessage(role = "assistant", content = response.content)
        ) as JsonObject

        assertEquals(original["content"], echoed["content"])
    }
}
