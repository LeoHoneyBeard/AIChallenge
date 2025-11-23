package com.example.aichallenge.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ToolsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ToolsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadTools()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MCP Tools", style = MaterialTheme.typography.headlineSmall)

            Button(onClick = { viewModel.loadTools() }) {
                Text("Reload")
            }

            when {
                state.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text("Loading tools...")
                    }
                }
                state.error != null -> {
                    Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
                }
                state.servers.isEmpty() -> Text("No tools available.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.servers) { server ->
                        ServerSection(server)
                    }
                }
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ToolRow(name: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ServerSection(server: ServerToolsState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text("${server.displayName} (${server.serverId})", style = MaterialTheme.typography.titleMedium)
        Text("Endpoint: ${server.endpoint}", style = MaterialTheme.typography.bodySmall)
        Text(server.description, style = MaterialTheme.typography.bodySmall)
        if (server.tools.isEmpty()) {
            Text("No tools exposed by this server.", style = MaterialTheme.typography.bodyMedium)
        } else {
            server.tools.forEach { tool ->
                ToolRow(tool.name, tool.description ?: "No description")
            }
        }
    }
}
