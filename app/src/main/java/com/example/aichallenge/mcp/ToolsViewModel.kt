package com.example.aichallenge.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ToolsState(
    val isLoading: Boolean = false,
    val servers: List<ServerToolsState> = emptyList(),
    val error: String? = null,
)

data class ServerToolsState(
    val serverId: String,
    val displayName: String,
    val description: String,
    val endpoint: String,
    val tools: List<Tool>,
)

class ToolsViewModel : ViewModel() {
    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    fun loadTools() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val toolsByServer = McpClient.fetchAllTools()
                McpServers.list().map { entry ->
                    ServerToolsState(
                        serverId = entry.id,
                        displayName = entry.displayName,
                        description = entry.description,
                        endpoint = entry.endpoint,
                        tools = toolsByServer[entry.id].orEmpty()
                    )
                }
            }.onSuccess { serverStates ->
                _state.value = _state.value.copy(isLoading = false, servers = serverStates, error = null)
            }.onFailure { t ->
                _state.value = _state.value.copy(isLoading = false, error = t.message ?: "Unknown error")
            }
        }
    }
}
