package com.reelscout.agent

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
    suspend fun run(userQuery: String, onToolCall: suspend (toolName: String) -> Unit = {}): String {
        val messages = mutableListOf(
            AnthropicMessage(role = "user", content = listOf(ContentBlock.Text(userQuery)))
        )

        repeat(maxTurns) {
            val response = anthropic.sendMessage(
                AnthropicRequest(
                    model = model,
                    maxTokens = maxTokens,
                    system = SystemPrompt.text,
                    messages = messages,
                    tools = Tools.all
                )
            )

            messages += AnthropicMessage(role = "assistant", content = response.content)

            val toolUses = response.content.filterIsInstance<ContentBlock.ToolUse>()
            if (toolUses.isEmpty() || response.stopReason != "tool_use") {
                return response.content.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text }
                    .ifBlank { "I couldn't find a grounded answer to that - try rephrasing." }
            }

            val toolResults = toolUses.map { toolUse ->
                onToolCall(toolUse.name)
                val result = runCatching { toolExecutor.execute(toolUse.name, toolUse.input) }
                ContentBlock.ToolResult(
                    toolUseId = toolUse.id,
                    content = result.getOrElse { "Error: ${it.message}" },
                    isError = result.isFailure
                )
            }

            messages += AnthropicMessage(role = "user", content = toolResults)
        }

        return "That took more steps than expected - try narrowing the question."
    }
}
