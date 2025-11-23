package com.example.aichallenge.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import kotlinx.coroutines.delay

/**
 * Minimal MCP client wrapper to talk to the embedded MCP servers and fetch their tool lists.
 */
object McpClient {
    private const val TAG = "McpClient"

    suspend fun fetchTools(serverId: String = McpServers.primaryServerId): List<Tool> {
        val entry = McpServers.requireEntry(serverId)
        McpServers.ensureRunning(serverId)
        delay(50)

        val httpClient = HttpClient(CIO) { install(SSE) }
        val client = Client(
            clientInfo = Implementation(
                name = "ai-challenge-mcp-client",
                version = "1.0.0",
            ),
        )
        var connected = false

        return try {
            val toolsResult = connectWithRetry(client, httpClient, entry.endpoint)
            connected = true
            toolsResult.tools
        } finally {
            if (connected) {
                runCatching { client.close() }.onFailure { Log.w(TAG, "Failed to close MCP client", it) }
            }
            runCatching { httpClient.close() }.onFailure { Log.w(TAG, "Failed to close HTTP client", it) }
        }
    }

    suspend fun fetchAllTools(): Map<String, List<Tool>> {
        val result = linkedMapOf<String, List<Tool>>()
        for (entry in McpServers.list()) {
            val tools = runCatching { fetchTools(entry.id) }
                .onFailure { Log.w(TAG, "Failed to fetch tools for server ${entry.id}", it) }
                .getOrDefault(emptyList())
            result[entry.id] = tools
        }
        return result
    }

    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?> = emptyMap(),
        serverId: String = McpServers.primaryServerId,
    ): String = withClient(serverId) { client, transport ->
        client.connect(transport)
        val result = client.callTool(toolName, arguments)
        val text = result?.content
            ?.mapNotNull { (it as? TextContent)?.text }
            ?.joinToString("\n")
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) text else "Инструмент $toolName вернул пустой результат"
    }

    private suspend fun <T> withClient(
        serverId: String,
        block: suspend (client: Client, transport: SseClientTransport) -> T,
    ): T {
        val entry = McpServers.requireEntry(serverId)
        McpServers.ensureRunning(serverId)
        delay(50)

        val httpClient = HttpClient(CIO) { install(SSE) }
        val client = Client(
            clientInfo = Implementation(
                name = "ai-challenge-mcp-client",
                version = "1.0.0",
            ),
        )

        return try {
            val transport = SseClientTransport(client = httpClient, urlString = entry.endpoint)
            block(client, transport)
        } finally {
            runCatching { client.close() }.onFailure { Log.w(TAG, "Failed to close MCP client", it) }
            runCatching { httpClient.close() }.onFailure { Log.w(TAG, "Failed to close HTTP client", it) }
        }
    }

    @Suppress("SameParameterValue")
    private suspend fun connectWithRetry(
        client: Client,
        httpClient: HttpClient,
        sseUrl: String,
        retries: Int = 3,
        delayMs: Long = 120,
    ): ListToolsResult {
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < retries) {
            attempt++
            try {
                val transport = SseClientTransport(client = httpClient, urlString = sseUrl)
                client.connect(transport)
                return client.listTools()
            } catch (e: SSEClientException) {
                lastError = e
                Log.w(TAG, "SSE connect attempt $attempt/$retries failed: ${e.message}")
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "MCP connect attempt $attempt/$retries failed: ${e.message}")
            }
            delay(delayMs)
        }
        throw lastError ?: IllegalStateException("Failed to connect to MCP server at $sseUrl")
    }
}
