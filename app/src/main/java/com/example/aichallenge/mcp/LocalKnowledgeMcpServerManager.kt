package com.example.aichallenge.mcp

import android.content.Context
import android.util.Log
import com.example.aichallenge.BuildConfig
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.ok
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight MCP server that hosts the local D&D compendium tools.
 */
object LocalKnowledgeMcpServerManager {
    private const val TAG = "KnowledgeMcpServer"
    private const val HOST = "127.0.0.1"
    private const val PORT = 11030
    private const val DND_CHARACTERS_ASSET = "mcp/dnd_characters.json"
    private const val DND_SPELLS_ASSET = "mcp/dnd_spells.json"

    private val running = AtomicBoolean(false)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val sessions = ConcurrentHashMap<String, ServerSession>()
    @Volatile private var appContext: Context? = null

    private val dndCharactersData: JSONArray by lazy { loadJsonArrayAsset(DND_CHARACTERS_ASSET) }
    private val dndSpellsData: JSONArray by lazy { loadJsonArrayAsset(DND_SPELLS_ASSET) }

    val endpoint: String get() = "http://$HOST:$PORT/mcp"

    fun isRunning(): Boolean = running.get()

    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }

    fun start(): Boolean {
        if (running.get()) return false

        val mcpServer = createMcpServer()
        return try {
            val ktorEngine = embeddedServer(CIO, host = HOST, port = PORT) {
                install(SSE)
                routing {
                    sse("/mcp") {
                        val transport = SseServerTransport("/mcp/message", this)
                        val serverSession = mcpServer.createSession(transport)
                        sessions[transport.sessionId] = serverSession

                        serverSession.onClose {
                            sessions.remove(transport.sessionId)
                        }

                        try {
                            awaitCancellation()
                        } finally {
                            transport.close()
                        }
                    }

                    post("/mcp/message") {
                        val sessionId = call.request.queryParameters["sessionId"]
                        val transport = sessionId?.let { sessions[it]?.transport as? SseServerTransport }
                        if (sessionId == null || transport == null) {
                            call.respond(io.ktor.http.HttpStatusCode.NotFound, "Session not found")
                            return@post
                        }
                        transport.handlePostMessage(call)
                    }
                }
            }.apply { start(wait = false) }

            engine = ktorEngine
            running.set(true)
            Log.i(TAG, "Started MCP knowledge server at $endpoint")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start knowledge MCP server", t)
            stop()
            false
        }
    }

    fun stop() {
        running.set(false)
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
        Log.i(TAG, "Knowledge MCP server stopped")
    }

    private fun createMcpServer(): Server = Server(
        serverInfo = Implementation(
            name = "ai-challenge-knowledge-mcp",
            version = BuildConfig.VERSION_NAME,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                prompts = ServerCapabilities.Prompts(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Embedded DnD MCP server. Connect via SSE (GET $endpoint)."
    ) {
        addTool(
            name = "dnd_characters",
            description = "Returns a JSON object with a `characters` array describing the heroes that appear in the `dnd_campaigns` tool; each record includes id, name, race, class, level, and STR/DEX/CON/WIS/INT/CHA stats.",
            inputSchema = io.modelcontextprotocol.kotlin.sdk.Tool.Input(
                properties = buildJsonObject { },
                required = emptyList()
            )
        ) {
            val result = runCatching { dndCharactersJson() }
                .getOrElse { error ->
                    return@addTool CallToolResult(
                        content = listOf(TextContent("Failed to load characters: ${error.message}")),
                        isError = true
                    )
                }
            CallToolResult.ok(result)
        }

        addTool(
            name = "dnd_spells_for_class",
            description = "Returns the spells available to one or more D&D classes and their level requirements.",
            inputSchema = io.modelcontextprotocol.kotlin.sdk.Tool.Input(
                properties = buildJsonObject {
                    put(
                        "class_names",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Class name such as Wizard, Cleric, Paladin.")
                                }
                            )
                            put("description", "List of class names; spells for any of them will be returned.")
                        }
                    )
                    put(
                        "class_name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "(Deprecated) Single class name. Use class_names instead.")
                        }
                    )
                },
                required = emptyList()
            )
        ) { request ->
            val classesFromArray = request.arguments["class_names"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val legacySingle = request.arguments["class_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val classNames = when {
                classesFromArray.isNotEmpty() -> classesFromArray
                legacySingle.isNotBlank() -> listOf(legacySingle)
                else -> emptyList()
            }
            if (classNames.isEmpty()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Provide at least one class via class_names.")),
                    isError = true
                )
            }
            val result = runCatching { spellsForClassesJson(classNames) }
                .getOrElse { error ->
                    return@addTool CallToolResult(
                        content = listOf(TextContent("Failed to load spells: ${error.message}")),
                        isError = true
                    )
                }
            CallToolResult.ok(result)
        }
    }

    private fun requireAppContext(): Context =
        appContext ?: throw IllegalStateException("Call LocalKnowledgeMcpServerManager.setAppContext() before using DnD tools.")

    private fun loadJsonArrayAsset(assetName: String): JSONArray {
        val text = requireAppContext().assets.open(assetName).bufferedReader().use { it.readText() }
        return JSONArray(text)
    }

    private fun dndCharactersJson(): String = JSONObject()
        .put("characters", dndCharactersData)
        .toString()

    private fun spellsForClassesJson(classNames: List<String>): String {
        val normalized = classNames.mapNotNull { it.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
        if (normalized.isEmpty()) {
            return JSONObject()
                .put("message", "No valid class names provided.")
                .toString()
        }
        val normalizedLookup = normalized.map { it.lowercase() }.toSet()
        val filtered = JSONArray()
        for (i in 0 until dndSpellsData.length()) {
            val spell = dndSpellsData.optJSONObject(i) ?: continue
            val spellClasses = spell.optJSONArray("classes") ?: continue
            for (j in 0 until spellClasses.length()) {
                if (normalizedLookup.contains(spellClasses.optString(j).lowercase())) {
                    filtered.put(spell)
                    break
                }
            }
        }
        if (filtered.length() == 0) {
            return JSONObject()
                .put("classes", JSONArray(normalized))
                .put("message", "No spells found for the provided classes in the local compendium.")
                .toString()
        }
        return filtered.toString()
    }
}
