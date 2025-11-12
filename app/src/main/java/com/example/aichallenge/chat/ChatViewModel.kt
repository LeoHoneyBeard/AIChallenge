package com.example.aichallenge.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import com.example.aichallenge.api.ApiModule
import com.example.aichallenge.api.ChatPayload

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

    fun sendLongText(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isSending) return

        val userMsg = ChatMessage(System.nanoTime(), ChatMessage.Role.USER, prompt)
        messages = messages + userMsg
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
                    val msg = "Local server error: ${e.message}"
                    Log.e(TAG, "Local server error: $msg", e)
                    _errors.tryEmit(msg)
                    isSending = false
                }
            }
        }
    }

    private suspend fun callLocalServer(prompt: String): String {
        val payload = ChatPayload(prompt = prompt)
        val ans = api.chatWithRestrictions(payload)
        return ans
    }
}
