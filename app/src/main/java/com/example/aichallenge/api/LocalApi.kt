package com.example.aichallenge.api

import retrofit2.http.Body
import retrofit2.http.POST

// DTO to match the updated local server contract
data class ChatPayload(
    val prompt: String,
)

interface LocalApi {
    @POST("chat")
    suspend fun chatWithRestrictions(@Body payload: ChatPayload): String
}
