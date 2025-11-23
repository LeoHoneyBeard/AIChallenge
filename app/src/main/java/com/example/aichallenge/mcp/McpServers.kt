package com.example.aichallenge.mcp

import android.content.Context

/**
 * Keeps track of every embedded MCP server exposed by the app.
 */
object McpServers {
    data class Entry(
        val id: String,
        val displayName: String,
        val description: String,
        val endpointProvider: () -> String,
        val start: () -> Boolean,
        val stop: () -> Unit,
        val isRunning: () -> Boolean,
        val setContext: ((Context) -> Unit)? = null,
    ) {
        val endpoint: String get() = endpointProvider()
    }

    private val entries: List<Entry> = listOf(
        Entry(
            id = "core",
            displayName = "Campaigns data",
            description = "Primary MCP server with dnd campaigns and masters data.",
            endpointProvider = { McpServerManager.endpoint },
            start = { McpServerManager.start() },
            stop = { McpServerManager.stop() },
            isRunning = { McpServerManager.isRunning() },
            setContext = { ctx -> McpServerManager.setAppContext(ctx) },
        ),
        Entry(
            id = "knowledge",
            displayName = "DnD compendium",
            description = "Local D&D characters and spells.",
            endpointProvider = { LocalKnowledgeMcpServerManager.endpoint },
            start = { LocalKnowledgeMcpServerManager.start() },
            stop = { LocalKnowledgeMcpServerManager.stop() },
            isRunning = { LocalKnowledgeMcpServerManager.isRunning() },
            setContext = { ctx -> LocalKnowledgeMcpServerManager.setAppContext(ctx) },
        ),
    )

    private val entriesById = entries.associateBy { it.id }

    val primaryServerId: String get() = entries.first().id

    fun list(): List<Entry> = entries

    fun requireEntry(id: String): Entry =
        entriesById[id] ?: throw IllegalArgumentException("Unknown MCP server id: $id")

    fun ensureRunning(id: String) {
        val entry = requireEntry(id)
        if (!entry.isRunning()) {
            entry.start()
        }
    }

    fun setAppContext(context: Context) {
        entries.forEach { entry -> entry.setContext?.invoke(context) }
    }

    fun isRunning(): Boolean = entries.all { it.isRunning() }

    fun startAll(): Boolean {
        entries.forEach { entry ->
            if (!entry.isRunning()) {
                entry.start()
            }
        }
        return entries.all { it.isRunning() }
    }

    fun stopAll() {
        entries.forEach { entry ->
            if (entry.isRunning()) {
                entry.stop()
            }
        }
    }
}
