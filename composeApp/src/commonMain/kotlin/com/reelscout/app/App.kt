package com.reelscout.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 0/1 placeholder UI: a single search box wired to nothing yet. This becomes the
 * chat screen in Phase 4 once AgentLoop (see :shared > agent) is wired in - see
 * ROADMAP.md for the phased plan.
 */
@Composable
fun App() {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ReelScout") }) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var query by remember { mutableStateOf("") }
                var messages by remember { mutableStateOf(listOf<String>()) }

                Text("Find something free to watch")

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(onClick = {
                    // TODO(Phase 4): call AgentLoop.run(query) via a ViewModel/Koin-injected
                    // instance once the Cloudflare Worker relay (edge/) is deployed.
                    if (query.isNotBlank()) {
                        messages = messages + "You asked: $query"
                        query = ""
                    }
                }) {
                    Text("Ask ReelScout")
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages) { message -> Text(message) }
                }
            }
        }
    }
}
