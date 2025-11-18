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
    val tools: List<Tool> = emptyList(),
    val error: String? = null,
)

class ToolsViewModel : ViewModel() {
    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    fun loadTools() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                McpClient.fetchTools()
            }.onSuccess { tools ->
                _state.value = _state.value.copy(isLoading = false, tools = tools, error = null)
            }.onFailure { t ->
                _state.value = _state.value.copy(isLoading = false, error = t.message ?: "Unknown error")
            }
        }
    }
}
