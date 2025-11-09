package com.example.aichallenge.api

import com.example.aichallenge.chat.BotAnswer
import retrofit2.http.Body
import retrofit2.http.POST

// DTO to match the updated local server contract
data class ChatPayload(
    val prompt: String,
)

interface LocalApi {
    @POST("chat")
    suspend fun chat(@Body payload: ChatPayload): BotAnswer

    @POST("chat_with_restrictions")
    suspend fun chatWithRestrictions(@Body payload: ChatPayload): String
}
