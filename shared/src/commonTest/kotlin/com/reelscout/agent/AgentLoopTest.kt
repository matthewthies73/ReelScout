package com.reelscout.agent

import com.reelscout.data.ArchiveOrgRepository
import com.reelscout.data.TmdbRepository
import com.reelscout.data.WatchmodeRepository
import com.reelscout.data.installEdgeDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Drives AgentLoop against canned Claude responses served by a MockEngine standing in for the relay. */
class AgentLoopTest {

    /** Bodies of the requests sent to /api/anthropic/messages, in order. */
    private val claudeRequests = mutableListOf<JsonObject>()

    private fun agent(vararg claudeReplies: Pair<HttpStatusCode, String>): AgentLoop {
        val replies = ArrayDeque(claudeReplies.toList())
        val client = HttpClient(MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath == "/api/anthropic/messages") {
                claudeRequests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val (status, body) = replies.removeFirst()
                respond(body, status, json)
            } else if (request.url.encodedPath.endsWith("/watch/providers")) {
                respond("""{"results": {"GB": {}, "CA": {}}}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"results": [{"id": 10331, "title": "Night of the Living Dead", "media_type": "movie"}]}""", HttpStatusCode.OK, json)
            }
        }) { installEdgeDefaults() }
        return AgentLoop(
            AnthropicClient(client),
            ToolExecutor(TmdbRepository(client), WatchmodeRepository(client), ArchiveOrgRepository(client))
        )
    }

    private fun reply(stopReason: String, vararg blocks: String) = HttpStatusCode.OK to
        """{"id": "msg", "role": "assistant", "model": "claude-sonnet-5", "stop_reason": "$stopReason", "content": [${blocks.joinToString()}]}"""

    private fun error(status: HttpStatusCode, type: String) =
        status to """{"type": "error", "error": {"type": "$type", "message": "nope"}}"""

    private val thinking = """{"type": "thinking", "thinking": "", "signature": "sig-1"}"""
    private val searchCall = """{"type": "tool_use", "id": "toolu_1", "name": "search_titles", "input": {"query": "night of the living dead"}}"""
    private fun text(value: String) = """{"type": "text", "text": "$value"}"""

    private fun JsonObject.messages(): JsonArray = getValue("messages").jsonArray

    @Test
    fun `runs a tool and feeds the result back before answering`() = runTest {
        val loop = agent(reply("tool_use", thinking, searchCall), reply("end_turn", text("It's free on Tubi.")))
        val toolsCalled = mutableListOf<String>()

        val answer = loop.run("night of the living dead") { toolsCalled += it }

        assertEquals("It's free on Tubi.", answer.text)
        assertEquals(listOf("search_titles"), toolsCalled)

        val secondTurn = claudeRequests[1].messages()
        assertEquals(3, secondTurn.size) // question, assistant tool call, tool result
        val echoedAssistant = secondTurn[1].jsonObject.getValue("content").jsonArray
        assertEquals("thinking", echoedAssistant[0].jsonObject.getValue("type").jsonPrimitive.content, "thinking blocks go back unchanged")
        val toolResult = secondTurn[2].jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("toolu_1", toolResult.getValue("tool_use_id").jsonPrimitive.content)
        assertTrue("Night of the Living Dead" in toolResult.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `earlier exchanges are sent as plain question and answer turns`() = runTest {
        val loop = agent(reply("end_turn", text("The 1990 remake is on Pluto TV.")))

        loop.run(
            "what about the remake?",
            history = listOf(Exchange("night of the living dead", "It's free on Tubi."))
        )

        val roles = claudeRequests.single().messages().map { it.jsonObject.getValue("role").jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun `history is capped so requests stay under the relay's message limit`() = runTest {
        val loop = agent(reply("end_turn", text("ok")))

        loop.run("latest", history = List(15) { Exchange("q$it", "a$it") })

        val messages = claudeRequests.single().messages()
        assertEquals(AgentLoop.MAX_HISTORY_EXCHANGES * 2 + 1, messages.size)
        assertTrue("q5" in messages.first().toString(), "the oldest exchanges are the ones dropped")
    }

    @Test
    fun `overloaded responses are retried`() = runTest {
        val loop = agent(error(HttpStatusCode(529, "Overloaded"), "overloaded_error"), reply("end_turn", text("ok")))

        assertEquals("ok", loop.run("hi").text)
        assertEquals(2, claudeRequests.size)
    }

    @Test
    fun `rate limits are not retried and read as a try-again message`() = runTest {
        val loop = agent(error(HttpStatusCode.TooManyRequests, "rate_limit_error"))

        val failure = assertFailsWith<AnthropicApiException> { loop.run("hi") }

        assertEquals(1, claudeRequests.size)
        assertEquals("rate_limit_error", failure.type)
        assertTrue("minute" in AgentLoop.describeFailure(failure))
    }

    private fun providersCall(input: String) =
        """{"type": "tool_use", "id": "toolu_p", "name": "get_watch_providers", "input": $input}"""

    /** The get_watch_providers tool_result sent back in the second request. */
    private fun providersResult(): String =
        claudeRequests[1].messages().last().jsonObject.getValue("content").jsonArray.single().jsonObject
            .getValue("content").jsonPrimitive.content

    @Test
    fun `the picked region is in the system prompt and is the tools' default`() = runTest {
        val loop = agent(
            reply("tool_use", providersCall("""{"tmdbId": 10331, "mediaType": "movie"}""")),
            reply("end_turn", text("ok"))
        )

        loop.run("night of the living dead", region = com.reelscout.domain.Region.GB)

        assertTrue("United Kingdom" in claudeRequests[0].getValue("system").jsonPrimitive.content)
        assertTrue("\"region\":\"GB\"" in providersResult(), providersResult())
    }

    @Test
    fun `a country Claude names explicitly overrides the picked region`() = runTest {
        val loop = agent(
            reply("tool_use", providersCall("""{"tmdbId": 10331, "mediaType": "movie", "region": "CA"}""")),
            reply("end_turn", text("ok"))
        )

        loop.run("is it free in Canada?", region = com.reelscout.domain.Region.GB)

        assertTrue("\"region\":\"CA\"" in providersResult(), providersResult())
    }

    @Test
    fun `the answer lists the titles availability was checked for`() = runTest {
        val loop = agent(
            reply("tool_use", searchCall),
            reply("tool_use", providersCall("""{"tmdbId": 10331, "mediaType": "movie", "region": "GB"}""")),
            reply("end_turn", text("Free on Tubi."))
        )

        val answer = loop.run("night of the living dead")

        assertEquals(listOf(10331), answer.titles.map { it.tmdbId })
        assertEquals("Night of the Living Dead", answer.titles.single().name)
    }

    @Test
    fun `titles that were only searched, not checked, aren't offered`() = runTest {
        val loop = agent(reply("tool_use", searchCall), reply("end_turn", text("Which one did you mean?")))

        assertTrue(loop.run("batman").titles.isEmpty())
    }

    @Test
    fun `refusals and truncated answers are reported plainly`() = runTest {
        assertEquals("Sorry, I can't help with that one.", agent(reply("refusal")).run("hi").text)
        assertTrue(agent(reply("max_tokens", text("Partial"))).run("hi").text.endsWith("_(This answer was cut short.)_"))
    }
}
