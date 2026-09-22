package com.reelscout.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelscout.agent.AgentLoop
import com.reelscout.agent.Exchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String, val isError: Boolean = false)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val statusText: String? = null
)

/**
 * Thin state holder around AgentLoop (see :shared > agent). This is the only place the
 * UI talks to the agent - everything downstream (tool-use loop, TMDB/Watchmode/Archive.org
 * calls, the Cloudflare relay) is the same shared Kotlin code on every platform.
 */
class ChatViewModel(private val agentLoop: AgentLoop) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    /** Sends [question] as if typed - used by the example questions on the empty screen. */
    fun ask(question: String) {
        onInputChange(question)
        send()
    }

    fun newChat() {
        if (!_uiState.value.isLoading) _uiState.value = ChatUiState()
    }

    fun send() {
        val query = _uiState.value.input.trim()
        if (query.isBlank() || _uiState.value.isLoading) return

        val history = answeredExchanges(_uiState.value.messages)
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = query),
                input = "",
                isLoading = true,
                statusText = "Thinking…"
            )
        }

        viewModelScope.launch {
            val reply = try {
                val answer = agentLoop.run(query, history) { toolName ->
                    _uiState.update { it.copy(statusText = statusFor(toolName)) }
                }
                ChatMessage(fromUser = false, text = answer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChatMessage(fromUser = false, text = AgentLoop.describeFailure(e), isError = true)
            }

            _uiState.update {
                it.copy(messages = it.messages + reply, isLoading = false, statusText = null)
            }
        }
    }

    // What the user sees while each tool runs (see Tools.kt for the tool names).
    private fun statusFor(toolName: String): String = when (toolName) {
        "search_titles" -> "Looking up the title on TMDB…"
        "get_watch_providers" -> "Checking where it's streaming…"
        "get_watchmode_sources" -> "Cross-checking with Watchmode…"
        "search_public_domain" -> "Searching Archive.org's public-domain films…"
        else -> "Working…"
    }

    // Questions that failed are left out, so Claude never sees an unanswered turn.
    private fun answeredExchanges(messages: List<ChatMessage>): List<Exchange> =
        messages.zipWithNext()
            .filter { (question, answer) -> question.fromUser && !answer.fromUser && !answer.isError }
            .map { (question, answer) -> Exchange(question.text, answer.text) }
}
