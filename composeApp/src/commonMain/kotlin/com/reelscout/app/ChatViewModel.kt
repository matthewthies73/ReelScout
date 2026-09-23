package com.reelscout.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelscout.agent.AgentLoop
import com.reelscout.agent.Exchange
import com.reelscout.data.SearchStats
import com.reelscout.data.StatsRepository
import com.reelscout.domain.Region
import com.reelscout.domain.Title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    // For answers: the titles the agent checked availability for, offered as "Save" chips.
    val titles: List<Title> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isLoading: Boolean = false,
    val statusText: String? = null,
    // Null until the first load succeeds; the footer stays hidden rather than showing 0.
    val stats: SearchStats? = null,
    val region: Region = Region.DEFAULT,
    val favorites: List<Title> = emptyList(),
    val showFavorites: Boolean = false
)

/**
 * Thin state holder around AgentLoop (see :shared > agent). This is the only place the
 * UI talks to the agent - everything downstream (tool-use loop, TMDB/Watchmode/Archive.org
 * calls, the Cloudflare relay) is the same shared Kotlin code on every platform.
 */
class ChatViewModel(
    private val agentLoop: AgentLoop,
    private val statsRepository: StatsRepository,
    private val regionStore: RegionStore,
    private val favoritesStore: FavoritesStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(region = regionStore.load(), favorites = favoritesStore.load())
    )
    val uiState: StateFlow<ChatUiState> = _uiState

    init {
        refreshStats()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    /** Sends [question] as if typed - used by the example questions on the empty screen. */
    fun ask(question: String) {
        onInputChange(question)
        send()
    }

    fun newChat() {
        if (!_uiState.value.isLoading) {
            _uiState.update { ChatUiState(stats = it.stats, region = it.region, favorites = it.favorites) }
        }
    }

    /** Saves [title], or removes it if it's already saved. Newest first. */
    fun toggleFavorite(title: Title) {
        _uiState.update { state ->
            val saved = state.favorites.any { it.tmdbId == title.tmdbId }
            val favorites = if (saved) state.favorites.filterNot { it.tmdbId == title.tmdbId } else listOf(title) + state.favorites
            favoritesStore.save(favorites)
            state.copy(favorites = favorites)
        }
    }

    fun showFavorites(show: Boolean) {
        _uiState.update { it.copy(showFavorites = show) }
    }

    /** Re-checks a saved title - availability changes, so a favorite is a question to ask again. */
    fun askAbout(title: Title) {
        _uiState.update { it.copy(showFavorites = false) }
        ask(title.name + (title.releaseYear?.let { " ($it)" } ?: ""))
    }

    /** Applies from the next question on; earlier answers stay as they were. */
    fun setRegion(region: Region) {
        regionStore.save(region)
        _uiState.update { it.copy(region = region) }
    }

    fun send() {
        val query = _uiState.value.input.trim()
        if (query.isBlank() || _uiState.value.isLoading) return

        val history = answeredExchanges(_uiState.value.messages)
        val region = _uiState.value.region
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
                val answer = agentLoop.run(query, history, region) { toolName ->
                    _uiState.update { it.copy(statusText = statusFor(toolName)) }
                }
                ChatMessage(fromUser = false, text = answer.text, titles = answer.titles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChatMessage(fromUser = false, text = AgentLoop.describeFailure(e), isError = true)
            }

            _uiState.update {
                it.copy(messages = it.messages + reply, isLoading = false, statusText = null)
            }
            // The relay has just recorded this search, so the count moves on screen.
            refreshStats()
        }
    }

    // Analytics are a nice-to-have: a failed load keeps whatever was shown before.
    private fun refreshStats() {
        viewModelScope.launch {
            try {
                val stats = statsRepository.getStats()
                _uiState.update { it.copy(stats = stats) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the footer as it was.
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
