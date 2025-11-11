package com.example.aichallenge.server

enum class Model(
    val model: String,
    val label: String,
    val isPermanentHistoryNeeded: Boolean = false,
) {
    LLAMA_3_1_8B(
    model = "meta-llama/Llama-3.1-8B-Instruct",
    label = "Llama 3.1 8B",
    ),
    ARCH_ROUTER_1_5B(
        model = "katanemo/Arch-Router-1.5B",
        label = "Arch-Router-1.5B",
    ),
    L3_8B_STHENO_V3_2(
        model = "Sao10K/L3-8B-Stheno-v3.2",
        label = "L3-8B-Stheno-v3.2",
    ),
    ANALYZER_HISTORY(
        model = "openai/gpt-oss-120b:fastest",
        label = "Analyzer (keep history)",
        isPermanentHistoryNeeded = true,
    ),
}

