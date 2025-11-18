package com.example.aichallenge.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.delay

/**
 * Minimal MCP client wrapper to talk to the embedded MCP server and fetch its tool list.
 */
object McpClient {
    private const val TAG = "McpClient"

    suspend fun fetchTools(serverUrl: String = McpServerManager.endpoint): List<Tool> {
        // Ensure server is running
        if (!McpServerManager.isRunning()) {
            McpServerManager.start()
        }
        // Tiny delay to let Ktor bind the port before the first SSE attempt
        delay(50)

        val sseUrl = serverUrl // no trailing slash; baseUrl should be host root
        val httpClient = HttpClient(CIO) {
            install(SSE)
        }

        val client = Client(
            clientInfo = Implementation(
                name = "ai-challenge-mcp-client",
                version = "1.0.0",
            ),
        )
        var connected = false

        return try {
            val toolsResult = connectWithRetry(client, httpClient, sseUrl)
            connected = true
            toolsResult.tools
        } finally {
            if (connected) {
                runCatching { client.close() }.onFailure { Log.w(TAG, "Failed to close MCP client", it) }
            }
            runCatching { httpClient.close() }.onFailure { Log.w(TAG, "Failed to close HTTP client", it) }
        }
    }

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

    suspend fun callTool(toolName: String, arguments: Map<String, Any?> = emptyMap(), serverUrl: String = McpServerManager.endpoint): String {
        if (!McpServerManager.isRunning()) {
            McpServerManager.start()
        }
        delay(50)

        val httpClient = HttpClient(CIO) { install(SSE) }
        val client = Client(
            clientInfo = Implementation(name = "ai-challenge-mcp-tool-client", version = "1.0.0")
        )

        return try {
            val transport = SseClientTransport(client = httpClient, urlString = serverUrl)
            client.connect(transport)

            val result = client.callTool(toolName, arguments)
            val text = result?.content
                ?.mapNotNull { (it as? TextContent)?.text }
                ?.joinToString("\n")
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) text else "Инструмент $toolName вернул пустой результат"
        } finally {
            runCatching { client.close() }
            runCatching { httpClient.close() }
        }
    }
}
