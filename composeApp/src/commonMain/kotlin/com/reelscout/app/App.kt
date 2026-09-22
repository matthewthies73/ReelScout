package com.reelscout.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import org.koin.compose.viewmodel.koinViewModel

/**
 * The chat screen - talks to ChatViewModel, which wraps AgentLoop (see :shared > agent).
 * Same UI code, same agent, on all four targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ReelScout") }) }
        ) { padding ->
            val viewModel: ChatViewModel = koinViewModel()
            val state by viewModel.uiState.collectAsState()

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

                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.messages) { message ->
                        if (message.fromUser) {
                            Text(message.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            AssistantMessage(message.text)
                        }
                    }
                }
            }
        }
    }
}

/** Claude answers in Markdown; links open in the platform browser. */
@Composable
internal fun AssistantMessage(markdown: String) {
    Markdown(
        content = markdown,
        typography = markdownTypography(
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
