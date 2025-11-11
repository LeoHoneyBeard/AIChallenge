package com.example.aichallenge.server

interface HuggingLocalServer {
    fun setModel(newModel: Model)
    fun getModel(): Model
}

