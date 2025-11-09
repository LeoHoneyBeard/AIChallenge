package com.example.aichallenge.server

enum class Role(
    val roleDescription: String,
    val temperature: Double,
    val label: String
) {
    DEFAULT(
        roleDescription = "Ты умный ИИ-ассистент",
        temperature = 0.3,
        label = "По умолчанию"
    ),
    PROMT_GENERATOR(
        roleDescription = "Ты генератор промтов для других ии. Полученный запрос ты переделываешь в более краткий и чёткий, чтобы другому ии было проще дать ответ",
        temperature = 0.1,
        label = "Генератор промтов"
    ),
    MATH(
        roleDescription = "Ты профессор математики",
        temperature = 0.1,
        label = "Математик"
    ),
    PHILOSOPHIST(
        roleDescription = "Ты профессор философии",
        temperature = 0.5,
        label = "Философ"
    ),
    BIOLOGIST(
        roleDescription = "Ты профессор биологии",
        temperature = 0.3,
        label = "Биолог"
    ),
    KREST(
        roleDescription = "Ты крестьянин",
        temperature = 0.4,
        label = "Крестьянин"
    ),
    EXPERTS(
        roleDescription = "Нужно создать группу экспертов(профессор математики, профессор философии, профессор биологии и крестьянин), каждый из которых ответит на заданный вопрос. Верни ответы всех экспертов и дай по ним итоговое заключение с точки зрения аналитика, со сравнением правильности ответа.",
        temperature = 0.3,
        label = "Группа экспертов"
    )
}

