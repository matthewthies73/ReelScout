package com.reelscout.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.reelscout.data.SearchStats
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import org.koin.compose.viewmodel.koinViewModel

// Keeps lines readable on desktop and web instead of stretching across a wide window.
private val MaxContentWidth = 760.dp

private val ExampleQuestions = listOf(
    "Night of the Living Dead",
    "Sci-fi movies like Interstellar I can watch free",
    "Is His Girl Friday in the public domain?"
)

/**
 * The chat screen - talks to ChatViewModel, which wraps AgentLoop (see :shared > agent).
 * Same UI code, same agent, on all four targets.
 */
@Composable
fun App() {
    MaterialTheme {
        val viewModel: ChatViewModel = koinViewModel()
        val state by viewModel.uiState.collectAsState()

        ChatScreen(
            state = state,
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::send,
            onAsk = viewModel::ask,
            onNewChat = viewModel::newChat
        )
    }
}

/** Stateless, so it can be rendered with any ChatUiState (previews, screenshots). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAsk: (String) -> Unit,
    onNewChat: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("ReelScout") })
        },
        bottomBar = {
            ChatInput(
                input = state.input,
                statusText = state.statusText,
                isLoading = state.isLoading,
                stats = state.stats,
                onInputChange = onInputChange,
                onSend = onSend,
                onAsk = onAsk
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = MaxContentWidth).fillMaxSize().padding(horizontal = 16.dp)) {
                if (state.messages.isEmpty()) {
                    EmptyState(onExampleClick = onAsk, enabled = !state.isLoading)
                } else {
                    MessageList(state.messages)
                }
                // Floats just above the input bar. Inside the width-capped column, so it
                // lines up with the Ask button on wide windows too.
                if (state.messages.isNotEmpty() && !state.isLoading) {
                    ExtendedFloatingActionButton(
                        onClick = onNewChat,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp)
                    ) { Text("New chat") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onExampleClick: (String) -> Unit, enabled: Boolean) {
    // Centered when there's room; scrolls when there isn't (e.g. a phone with the keyboard up).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Find something free to watch", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Ask about any movie or show. ReelScout checks live streaming data and public-domain " +
                    "archives for free, legal ways to watch it in the US.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            ExampleQuestions.forEach { question ->
                SuggestionChip(onClick = { onExampleClick(question) }, label = { Text(question) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun ChatInput(
    input: String,
    statusText: String?,
    isLoading: Boolean,
    stats: SearchStats?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAsk: (String) -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        // safeDrawing covers the navigation bar / home indicator and, when it's open, the
        // on-screen keyboard - so the input sits just above whichever is taller.
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.Center
        ) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (statusText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        enabled = !isLoading,
                        singleLine = true,
                        placeholder = { Text("Ask about a movie or show…") },
                        // A single-line field runs its IME action on Enter too, so this covers
                        // the Send key on phone keyboards and Enter on desktop and web.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onSend,
                        enabled = !isLoading && input.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Ask") }
                }
                if (stats != null) {
                    SearchFooter(stats, enabled = !isLoading, onAsk = onAsk)
                }
            }
        }
    }
}

/** Global search count plus the week's most-asked questions (edge/src/analytics.ts). */
@Composable
private fun SearchFooter(stats: SearchStats, enabled: Boolean, onAsk: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "${formatCount(stats.totalSearches)} ${if (stats.totalSearches == 1L) "search" else "searches"} so far · logged anonymously",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (stats.topSearches.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Text("Popular:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(stats.topSearches) { top ->
                    AssistChip(
                        onClick = { onAsk(top.query) },
                        label = { Text(top.query, style = MaterialTheme.typography.labelSmall) },
                        enabled = enabled
                    )
                }
            }
        }
    }
}

/** 1234567 -> "1,234,567" (no String.format in common Kotlin). */
internal fun formatCount(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(",").reversed()

@Composable
internal fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()

    // New messages are added at the bottom, below a possibly long answer. Keep the latest
    // question at the top of the list so it and the start of its answer are on screen.
    LaunchedEffect(messages.size) {
        val latestQuestion = messages.indexOfLast { it.fromUser }
        if (latestQuestion >= 0) listState.animateScrollToItem(latestQuestion)
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Room to scroll the end of the last answer clear of the floating New chat button.
        contentPadding = PaddingValues(bottom = 88.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(messages) { message ->
            when {
                message.fromUser -> UserMessage(message.text)
                message.isError -> Text(message.text, color = MaterialTheme.colorScheme.error)
                else -> Column {
                    AssistantMessage(message.text)
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

/** The user's question, as a right-aligned bubble so it stands apart from the answers. */
@Composable
private fun UserMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
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
