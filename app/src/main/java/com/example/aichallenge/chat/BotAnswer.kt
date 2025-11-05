package com.example.aichallenge.chat

data class BotAnswer(
    val title: String,
    val rating: String,
    val description: String,
) {
    fun toDisplayString(): String =
        "Название: $title\n" +
        "Рейтинг: $rating\n" +
        "Описание: $description"
}
