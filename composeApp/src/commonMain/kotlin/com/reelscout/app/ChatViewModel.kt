package com.reelscout.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelscout.agent.AgentLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String)

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

    fun send() {
        val query = _uiState.value.input.trim()
        if (query.isBlank() || _uiState.value.isLoading) return

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = query),
                input = "",
                isLoading = true,
                statusText = "Thinking..."
            )
        }

        viewModelScope.launch {
            val answer = runCatching {
                agentLoop.run(query) { toolName ->
                    _uiState.update { it.copy(statusText = "Calling $toolName...") }
                }
            }.getOrElse { "Something went wrong: ${it.message}" }

            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(fromUser = false, text = answer),
                    isLoading = false,
                    statusText = null
                )
            }
        }
    }
}
