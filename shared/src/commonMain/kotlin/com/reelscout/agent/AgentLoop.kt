package com.reelscout.agent

import com.reelscout.data.commonJson
import com.reelscout.domain.Region
import com.reelscout.domain.Title
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One finished question and answer, carried into later questions so follow-ups work. */
data class Exchange(val question: String, val answer: String)

/**
 * Claude's answer, plus the titles the agent checked availability for while producing it
 * (what the answer is about - the app offers to save these as favorites).
 */
data class AgentAnswer(val text: String, val titles: List<Title> = emptyList())

/**
 * The actual "AI agent": a multi-turn tool-use loop, not a single prompt-and-response call.
 *
 * Each turn: send the conversation + tool schemas to Claude, execute whatever tools it
 * asks for against real data (TMDB/Watchmode/Archive.org via ToolExecutor), feed the
 * results back in, and repeat until Claude returns a final grounded text answer.
 *
 * This runs client-side (see ROADMAP.md) - the same Kotlin code executes identically on
 * Android, iOS, Desktop and Web, which is the point of keeping it in :shared.
 */
class AgentLoop(
    private val anthropic: AnthropicClient,
    private val toolExecutor: ToolExecutor,
    // The relay pins the model and caps max_tokens server-side (edge/src/index.ts); these
    // are sent to keep the request a valid Messages API call and should match the relay.
    private val model: String = "claude-sonnet-5",
    // Adaptive thinking counts toward this, so it has to leave room beyond the answer itself.
    private val maxTokens: Int = 8192,
    private val maxTurns: Int = 6
) {
    /**
     * Earlier exchanges go in as plain question/answer text: their tool calls and results
     * are dropped, which keeps requests small, and Claude re-checks availability anyway.
     */
    suspend fun run(
        userQuery: String,
        history: List<Exchange> = emptyList(),
        region: Region = Region.DEFAULT,
        onToolCall: suspend (toolName: String) -> Unit = {}
    ): AgentAnswer {
        val messages = history.takeLast(MAX_HISTORY_EXCHANGES).flatMap { exchange ->
            listOf(
                AnthropicMessage(role = "user", content = listOf(ContentBlock.Text(exchange.question))),
                AnthropicMessage(role = "assistant", content = listOf(ContentBlock.Text(exchange.answer)))
            )
        }.toMutableList()
        messages += AnthropicMessage(role = "user", content = listOf(ContentBlock.Text(userQuery)))

        // Titles from search_titles, and the ids availability was then checked for, in order.
        val foundTitles = mutableMapOf<Int, Title>()
        val checkedIds = linkedSetOf<Int>()

        repeat(maxTurns) {
            val response = anthropic.sendMessage(
                AnthropicRequest(
                    model = model,
                    maxTokens = maxTokens,
                    system = SystemPrompt.text(region),
                    messages = messages,
                    tools = Tools.all
                )
            )

            messages += AnthropicMessage(role = "assistant", content = response.content)

            val toolUses = response.content.filterIsInstance<ContentBlock.ToolUse>()
            if (toolUses.isEmpty() || response.stopReason != "tool_use") {
                return AgentAnswer(finalAnswer(response), checkedIds.mapNotNull { foundTitles[it] })
            }

            val toolResults = toolUses.map { toolUse ->
                onToolCall(toolUse.name)
                val result = runCatching { toolExecutor.execute(toolUse.name, toolUse.input, region.code) }
                result.onSuccess { noteTitles(toolUse, it, foundTitles, checkedIds) }
                ContentBlock.ToolResult(
                    toolUseId = toolUse.id,
                    content = result.getOrElse { "Error: ${it.message}" },
                    isError = result.isFailure
                )
            }

            messages += AnthropicMessage(role = "user", content = toolResults)
        }

        return AgentAnswer("That took more steps than expected - try narrowing the question.")
    }

    private fun noteTitles(toolUse: ContentBlock.ToolUse, result: String, found: MutableMap<Int, Title>, checked: MutableSet<Int>) {
        when (toolUse.name) {
            "search_titles" -> runCatching { commonJson.decodeFromString(ListSerializer(Title.serializer()), result) }
                .getOrNull()?.forEach { found.getOrPut(it.tmdbId) { it } }
            "get_watch_providers", "get_watchmode_sources" ->
                runCatching { toolUse.input.jsonObject["tmdbId"]?.jsonPrimitive?.int }.getOrNull()?.let { checked += it }
        }
    }

    private fun finalAnswer(response: AnthropicResponse): String {
        if (response.stopReason == "refusal") return "Sorry, I can't help with that one."

        val text = response.content.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { return "I couldn't find a grounded answer to that - try rephrasing." }
        return if (response.stopReason == "max_tokens") "$text\n\n_(This answer was cut short.)_" else text
    }

    companion object {
        // 10 exchanges (20 messages) plus the current question and up to 5 tool rounds
        // (10 messages) stays under the relay's 40-message cap (edge/src/index.ts).
        const val MAX_HISTORY_EXCHANGES = 10

        /** Turns a failed run() into something to show the user. */
        fun describeFailure(error: Throwable): String = when {
            error is AnthropicApiException && error.status == 429 ->
                "ReelScout is getting a lot of questions right now - try again in a minute."
            error is AnthropicApiException && error.status == 413 ->
                "This conversation has gotten too long - start a new chat."
            error is AnthropicApiException && error.isRetryable ->
                "Claude is busy right now - try again in a few seconds."
            error is AnthropicApiException -> "Something went wrong talking to Claude (${error.type})."
            error is kotlinx.io.IOException -> "Couldn't reach ReelScout - check your connection and try again."
            else -> "Something went wrong: ${error.message}"
        }
    }
}
