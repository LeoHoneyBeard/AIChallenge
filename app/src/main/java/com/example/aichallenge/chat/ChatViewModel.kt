package com.example.aichallenge.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichallenge.api.ApiModule
import com.example.aichallenge.api.ChatPayload
import com.example.aichallenge.api.HistoryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel : ViewModel() {
    private val api = ApiModule.localApi
    private val TAG = "ChatViewModel"

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set

    var input by mutableStateOf("")
        private set

    var isSending by mutableStateOf(false)
        private set

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors

    init {
        loadHistory()
    }

    fun updateInput(value: String) {
        input = value
    }

    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty() || isSending) return

        val userMsg = ChatMessage(System.nanoTime(), ChatMessage.Role.USER, prompt)
        messages = messages + userMsg
        input = ""
        isSending = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resultText = callLocalServer(prompt)
                withContext(Dispatchers.Main) {
                    val botMsg = ChatMessage(System.nanoTime(), ChatMessage.Role.BOT, resultText)
                    messages = messages + botMsg
                    isSending = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = "Ошибка: ${e.message ?: "неизвестная"}"
                    Log.e(TAG, "Local server error: $msg", e)
                    _errors.tryEmit(msg)
                    isSending = false
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.clearHistory() }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        messages = emptyList()
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to clear history", e)
                    _errors.tryEmit("Не удалось очистить историю: ${e.message}")
                }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.loadHistory() }
                .onSuccess { resp ->
                    val mapped = mapHistory(resp)
                    withContext(Dispatchers.Main) {
                        messages = mapped
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load saved history", e)
                }
        }
    }

    private fun mapHistory(resp: HistoryResponse): List<ChatMessage> =
        resp.messages.mapIndexed { index, msg ->
            val role = if (msg.role.equals("user", ignoreCase = true)) {
                ChatMessage.Role.USER
            } else {
                ChatMessage.Role.BOT
            }
            ChatMessage(index.toLong(), role, msg.text)
        }

    private suspend fun callLocalServer(prompt: String): String {
        val payload = ChatPayload(prompt = prompt)
        val ans = api.chatWithRestrictions(payload)
        return ans
    }
}
