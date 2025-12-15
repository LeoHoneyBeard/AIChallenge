package com.example.aichallenge.api

import com.example.aichallenge.user.UserProfile
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class HistoryMessageDto(
    val role: String,
    val text: String,
)

data class HistoryResponse(
    val messages: List<HistoryMessageDto> = emptyList(),
)

// DTO to match the updated local server contract
data class ChatPayload(
    val prompt: String,
    val userProfile: UserProfile? = null,
)

interface LocalApi {
    @POST("chat")
    suspend fun chatWithRestrictions(@Body payload: ChatPayload): String

    @GET("history")
    suspend fun loadHistory(): HistoryResponse

    @POST("clear")
    suspend fun clearHistory()
}
