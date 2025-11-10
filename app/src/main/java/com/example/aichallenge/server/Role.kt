package com.example.aichallenge.server

enum class Role(
    val roleDescription: String,
    val temperature: Double,
    val label: String,
    val isPermanentHistoryNeeded: Boolean = false,
) {
    DEFAULT(
        roleDescription = "Ты умный ИИ-ассистент",
        temperature = 0.3,
        label = "По умолчанию"
    ),
    T_0_1(
        roleDescription = "Ты ИИ-ассистент с параметром температуры 0.1. Перед ответом представляйся (обязательно с параметром температуры)",
        temperature = 0.1,
        label = "Температура 0.1"
    ),
    T_0_5(
        roleDescription = "Ты умный ИИ-ассистент с параметром температуры 0.5. Перед ответом представляйся (обязательно с параметром температуры)",
        temperature = 0.5,
        label = "Температура 0.5"
    ),
    T_1_0(
        roleDescription = "Ты умный ИИ-ассистент с параметром температуры 1. Перед ответом представляйся (обязательно с параметром температуры)",
        temperature = 1.0,
        label = "Температура 1.0"
    ),
    ANALYZER(
        roleDescription = "Ты аналитик чатов с ии-ботами.",
        temperature = 0.1,
        label = "Аналитик",
        isPermanentHistoryNeeded = true
    ),
}

