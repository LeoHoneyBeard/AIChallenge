package com.example.aichallenge.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ChatViewModel : ViewModel() {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "http://127.0.0.1:8080/chat"
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
                Log.d(TAG, "Local server request -> prompt='${prompt}'")
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

    private fun callLocalServer(prompt: String): String {
        val payload = JSONObject().put("prompt", prompt).toString()

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        Log.d(TAG, "HTTP POST $baseUrl payload=${payload}")
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "HTTP error code=${resp.code} body=${body}")
                throw IllegalStateException("HTTP ${resp.code} body=${body}")
            }
            val json = JSONObject(body)
            val answer = json.optString("answer", "")
            Log.d(TAG, "HTTP response <- answer='${answer}'")
            return answer
        }
    }
}

