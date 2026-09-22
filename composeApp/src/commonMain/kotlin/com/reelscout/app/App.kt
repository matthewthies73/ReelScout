package com.reelscout.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import org.koin.compose.viewmodel.koinViewModel

/**
 * The chat screen - talks to ChatViewModel, which wraps AgentLoop (see :shared > agent).
 * Same UI code, same agent, on all four targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val viewModel: ChatViewModel = koinViewModel()
        val state by viewModel.uiState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("ReelScout") },
                    actions = {
                        TextButton(
                            onClick = viewModel::newChat,
                            enabled = state.messages.isNotEmpty() && !state.isLoading
                        ) { Text("New chat") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Find something free to watch")

                TextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(onClick = viewModel::send, enabled = !state.isLoading) {
                    Text(if (state.isLoading) "Asking..." else "Ask ReelScout")
                }

                state.statusText?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }

                MessageList(state.messages)
            }
        }
    }
}

@Composable
internal fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()

    // New messages are added at the bottom, below a possibly long answer. Keep the latest
    // question at the top of the list so it and the start of its answer are on screen.
    LaunchedEffect(messages.size) {
        val latestQuestion = messages.indexOfLast { it.fromUser }
        if (latestQuestion >= 0) listState.animateScrollToItem(latestQuestion)
    }

    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(messages) { message ->
            if (message.fromUser) {
                Text(message.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else if (message.isError) {
                Text(message.text, color = MaterialTheme.colorScheme.error)
            } else {
                AssistantMessage(message.text)
            }
        }
    }
}

/** Claude answers in Markdown; links open in the platform browser. */
@Composable
internal fun AssistantMessage(markdown: String) {
    Markdown(
        // Parse during composition rather than in the background, so the answer has its
        // full height on the first frame and MessageList can scroll to it.
        markdownState = rememberMarkdownState(markdown, immediate = true),
        typography = markdownTypography(
            // The library's heading defaults are display-sized; in a chat answer a heading
            // should read as a section label, not dwarf the text around it.
            h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            h4 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            h5 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            h6 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            textLink = TextLinkStyles(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline
                )
            )
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
