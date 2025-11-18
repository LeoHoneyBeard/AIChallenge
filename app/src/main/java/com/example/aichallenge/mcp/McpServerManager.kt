package com.example.aichallenge.mcp

import android.util.Log
import com.example.aichallenge.BuildConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode as ClientStatusCode
import io.ktor.server.application.install
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role as McpRole
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.ok
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts and stops an MCP server (SSE transport) inside the Android app process.
 */
object McpServerManager {
    private const val TAG = "McpServer"
    private const val HOST = "127.0.0.1"
    private const val PORT = 11020

    private val running = AtomicBoolean(false)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var startedAt: Instant? = null
    private val sessions = ConcurrentHashMap<String, ServerSession>()

    // SSE entrypoint (no trailing slash) to match baseUrl logic of SseClientTransport.
    val endpoint: String get() = "http://$HOST:$PORT/mcp"

    fun isRunning(): Boolean = running.get()

    fun start(): Boolean {
        if (running.get()) return false

        val mcpServer = createMcpServer()
        return try {
            val ktorEngine = embeddedServer(CIO, host = HOST, port = PORT) {
                install(SSE)
                routing {
                    // SSE entrypoint: GET http://127.0.0.1:11020/mcp
                    sse("/mcp") {
                        val transport = SseServerTransport("/mcp/message", this)
                        val serverSession = mcpServer.createSession(transport)
                        sessions[transport.sessionId] = serverSession

                        serverSession.onClose {
                            sessions.remove(transport.sessionId)
                        }

                        // Keep the SSE connection alive unless cancelled
                        try {
                            awaitCancellation()
                        } finally {
                            transport.close()
                        }
                    }

                    // POST handler for messages from client
                    post("/mcp/message") {
                        val sessionId = call.request.queryParameters["sessionId"]
                        val transport = sessionId?.let { sessions[it]?.transport as? SseServerTransport }
                        if (sessionId == null || transport == null) {
                            call.respond(HttpStatusCode.NotFound, "Session not found")
                            return@post
                        }
                        transport.handlePostMessage(call)
                    }
                }
            }.apply {
                start(wait = false)
            }

            engine = ktorEngine
            startedAt = Instant.now()
            running.set(true)
            Log.i(TAG, "Started MCP SSE server at $endpoint (POST to the ?sessionId endpoint announced by the SSE stream)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start MCP server", t)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        startedAt = null
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        Log.i(TAG, "MCP server stopped")
    }

    private fun createMcpServer(): Server = Server(
        serverInfo = Implementation(
            name = "ai-challenge-mcp",
            version = BuildConfig.VERSION_NAME,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Connect via SSE (GET $endpoint). The SSE stream emits an `endpoint` event; POST all MCP JSON-RPC messages to that relative path."
    ) {
        addPrompt(
            name = "local_capabilities",
            description = "How to use the locally embedded MCP server",
        ) { _ ->
            GetPromptResult(
                description = "Local MCP server is running inside the Android app.",
                messages = listOf(
                    PromptMessage(
                        role = McpRole.assistant,
                        content = TextContent(
                            text = "SSE entrypoint: $endpoint\n" +
                                "Tools: github_repos\n" +
                                "Resources: local://ai-challenge/status\n" +
                                "Use the first `endpoint` event to know where to POST messages with the sessionId."
                        )
                    )
                ),
            )
        }

        addResource(
            uri = "local://ai-challenge/status",
            name = "ai-challenge-status",
            description = "Basic state of the embedded MCP server.",
            mimeType = "text/plain",
        ) { request ->
            val started = startedAt?.let { DateTimeFormatter.ISO_INSTANT.format(it) } ?: "unknown"
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = buildString {
                            appendLine("AIChallenge MCP server is running.")
                            appendLine("Endpoint: $endpoint")
                            appendLine("Started at: $started")
                            appendLine("Build: ${BuildConfig.VERSION_NAME}")
                        },
                        uri = request.uri,
                        mimeType = "text/plain",
                    )
                )
            )
        }

        // Tool: list GitHub repositories for a user
        addTool(
            name = "github_repos",
            description = "Возвращает список репозиториев GitHub по имени пользователя.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put(
                        "username",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Имя пользователя GitHub")
                        }
                    )
                    put(
                        "per_page",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Сколько репозиториев вернуть (по умолчанию 10, максимум 100).")
                        }
                    )
                },
                required = listOf("username"),
            ),
        ) { request ->
            val username = request.arguments["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (username.isBlank()) {
                return@addTool CallToolResult(
                    content = listOf(io.modelcontextprotocol.kotlin.sdk.TextContent("username is required")),
                    isError = true
                )
            }
            val perPage = request.arguments["per_page"]?.jsonPrimitive?.intOrNull ?: 10
            val result = fetchGithubRepos(username, perPage)
            CallToolResult.ok(result)
        }
    }

    private suspend fun fetchGithubRepos(username: String, perPage: Int): String {
        val token = BuildConfig.GITHUB_TOKEN
        if (token.isBlank()) {
            return "GITHUB_TOKEN не задан. Добавьте его в local.properties"
        }
        val client = HttpClient(ClientCIO)
        return client.use {
            val response = it.get("https://api.github.com/users/$username/repos") {
                header("Accept", "application/vnd.github+json")
                header("Authorization", "Bearer $token")
                header("X-GitHub-Api-Version", "2022-11-28")
                val safePerPage = perPage.coerceIn(1, 100)
                parameter("per_page", safePerPage.toString())
            }
            if (response.status.value != ClientStatusCode.OK.value) {
                return@use "Не удалось получить репозитории для $username: HTTP ${response.status.value}"
            }
            runCatching { response.bodyAsText() }.getOrElse { err ->
                "Не удалось прочитать ответ: ${err.message}"
            }
        }
    }
}
