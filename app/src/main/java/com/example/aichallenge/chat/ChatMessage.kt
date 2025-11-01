package com.example.aichallenge.chat

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
) {
    enum class Role { USER, BOT }
}

