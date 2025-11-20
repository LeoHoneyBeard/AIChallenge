package com.example.aichallenge.server

import android.content.Context
import android.util.Log
import com.example.aichallenge.BuildConfig
import com.example.aichallenge.mcp.McpClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

private data class CompletionResult(
    val answer: String,
    val totalTokens: Int?,
    val completionTokens: Int?,
    val promptTokens: Int?,
    val elapsedMs: Long,
    val usedTool: String? = null,
)

private data class GithubIssueComment(
    val id: Long,
    val issueNumber: Int?,
    val author: String,
    val body: String,
    val createdAt: String?,
    val updatedAt: String?,
)

private const val ISSUE_SUMMARY_SYSTEM_PROMPT = "Ты даёшь сводку по текущим issue на github по полученным комментариям"
private const val ISSUE_SUMMARY_TOOL_NAME = "github_issue_comments"
private const val ISSUE_SUMMARY_REFRESH_MS = 1 * 60 * 1000L
private const val ISSUE_SUMMARY_COMMENT_LIMIT = 20
private const val ISSUE_SUMMARY_MAX_BODY_CHARS = 500
private const val ISSUE_SUMMARY_HEARTBEAT_INTERVAL_MS = 10_000L
private const val ISSUE_SUMMARY_FEATURE_ENABLED = false

private const val MAX_HISTORY_MESSAGES = 10
private const val COMPRESSION_SYSTEM_PROMPT = "Ты помогаешь сжимать историю сообщений без потери контекста"
private const val COMPRESSION_SUMMARY_PROMPT = "Сделай summary всего полученного диалога, без учёта этого сообщения, чтобы контекст не потерялся"

class LocalAiServer(
    port: Int,
    context: Context,
) : NanoWSD("127.0.0.1", port), ChatLocalServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private val TAG = "LocalAiServer"
    private var apiKey = BuildConfig.YANDEX_API_KEY
    private var folderId = BuildConfig.YC_FOLDER_ID
    private val historyFile = File(context.applicationContext.filesDir, "local_ai_history.json")
    private val permanentHistory = mutableListOf<JSONObject>()
    private val role: Role = Role.DEFAULT
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compressionJobLock = Any()
    @Volatile private var compressionJob: Job? = null
    @Volatile private var issueSummaryJob: Job? = null
    @Volatile private var lastIssueSummaryAnswer: String? = null
    @Volatile private var lastIssueSummaryTimestamp: Long = 0L
    private val issueSummaryVersion = AtomicLong(0)
    private val issueSummarySockets = Collections.synchronizedSet(mutableSetOf<IssueSummaryWebSocket>())
    @Volatile private var issueSummaryHeartbeatJob: Job? = null
    private val toolMarker = "MCP_TOOL:"

    init {
        LocalServerRegistry.instance = this
        loadHistoryFromDisk()
        if (ISSUE_SUMMARY_FEATURE_ENABLED) {
            startIssueSummaryJob()
        }
    }

    override fun openWebSocket(handshake: IHTTPSession?): WebSocket =
        IssueSummaryWebSocket(handshake)


    override fun setRole(newRole: Role) {
        // Roles are disabled for now; ignore requests to change role.
    }

    override fun getRole(): Role = role

    override fun serveHttp(session: IHTTPSession): Response {
        return try {
            waitForCompressionIfNeeded()
            if (session.method == NanoHTTPD.Method.GET && session.uri == "/history") {
                return historyResponse()
            }
            if (session.method == NanoHTTPD.Method.POST && session.uri == "/clear") {
                return clearHistoryResponse()
            }
            if (session.method != NanoHTTPD.Method.POST || session.uri != "/chat") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }

            if (apiKey.isBlank()) {
                return jsonError(400, "Missing YANDEX_API_KEY. Add it to local.properties.")
            }
            if (folderId.isBlank()) {
                return jsonError(400, "Missing YC_FOLDER_ID. Add it to local.properties.")
            }

            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val raw = bodyMap["postData"] ?: "{}"

            val bodyJson = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            val prompt = bodyJson.optString("prompt", "").trim()

            if (prompt.isEmpty()) {
                return jsonError(400, "Field 'prompt' is required")
            }

            // Build full conversation: system + stored history + new user message
            val toolListText = runCatching { buildToolSummary() }.getOrDefault("")
            val finalMessages = JSONArray().put(buildSystemMessageFromRole(toolListText))
            synchronized(permanentHistory) { permanentHistory.forEach { finalMessages.put(it) } }
            finalMessages.put(
                JSONObject()
                    .put("role", "user")
                    .put("text", prompt)
            )

            var completionResult = callYandex(finalMessages)
            val usedTools = mutableListOf<String>()

            while (true) {
                val toolReq = parseToolRequest(completionResult.answer.orEmpty()) ?: break
                val toolName = toolReq.name
                if (toolName.isBlank()) break
                usedTools.add(toolName)

                // Record the assistant tool request
                finalMessages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("text", completionResult.answer)
                )

                val toolResultText = runCatching { runBlocking { McpClient.callTool(toolName, toolReq.parameters) } }
                    .getOrElse { "Не получилось выполнить MCP инструмент $toolName: ${it.message}" }

                // Add tool result as a new message to the model
                finalMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("text", "Результат обращения к mcp: $toolResultText. Ты обязан проанализировать полученный ответ и либо вернуть запрос на использование другого инструмента, либо дать итоговое решение пользователю.")
                )

                completionResult = callYandex(finalMessages)
            }
            if (usedTools.isNotEmpty()) {
                completionResult = completionResult.copy(usedTool = usedTools.joinToString(", "))
            }

            val responseText = buildClientResponse(completionResult)

            // Persist new exchange to both histories
            val userObj = JSONObject()
                .put("role", "user")
                .put("text", prompt)
            val assistantObj = JSONObject()
                .put("role", "assistant")
                .put("text", completionResult.answer)

            permanentHistory.add(JSONObject(userObj.toString()))
            permanentHistory.add(JSONObject(assistantObj.toString()))
            persistHistories()

            maybeScheduleCompression(permanentHistory)

//            Log.d(TAG, "${session.uri} response =\n${prettyJson(responseText)}")
            return newFixedLengthResponse(Response.Status.OK, "text/plain", responseText).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            }
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Internal error")
        }
    }

    private fun prettyJson(raw: String): String = try {
        // Try object first
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        try {
            // Then array
            JSONArray(raw).toString(2)
        } catch (_: Exception) {
            // Fallback: return as-is
            raw
        }
    }

    private fun callYandex(messages: JSONArray, temperature: Double = role.temperature, maxTokens: Int = 2000): CompletionResult {
        val payloadJsonObject = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt-5.1")
            .put(
                "completionOptions",
                JSONObject()
                    .put("stream", false)
                    .put("temperature", temperature)
                    .put("maxTokens", maxTokens)
            )
            .put("messages", messages)

        val payloadJson = payloadJsonObject.toString()

        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Api-Key $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payloadJson.toRequestBody(jsonMedia))
            .build()

        Log.d(TAG, "Yandex LLM request -> url=$endpoint, payload=$payloadJson")
        val startMs = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            Log.d(TAG, "Yandex LLM <- response'=${prettyJson(json.toString())}")
            if (!resp.isSuccessful) {
                Log.e(TAG, "Yandex LLM error code=${resp.code} body=$body")
                throw IllegalStateException("Yandex LLM error ${resp.code}: $body")
            }
            val elapsedMs = System.currentTimeMillis() - startMs

            val result = json
                .optJSONObject("result")
            val answer = result
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("text")
                ?.trim()
                .orEmpty()

            // Extract usage fields if present
            val usage = result?.optJSONObject("usage")
            val totalTokens = usage?.optInt("totalTokens")
            val completionTokens = usage?.optInt("completionTokens")
            val promptTokens = usage?.optInt("inputTextTokens")

            Log.d(TAG, "HF response <- text='${answer}'")
            return CompletionResult(
                answer = answer,
                totalTokens = totalTokens,
                completionTokens = completionTokens,
                promptTokens = promptTokens,
                elapsedMs = elapsedMs,
            )
        }
    }

    private fun startIssueSummaryJob() {
        if (issueSummaryJob != null) return
        val job = serverScope.launch {
            while (isActive) {
                runCatching { fetchIssueSummaryAndStore() }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            Log.w(TAG, "Issue summary iteration failed", error)
                        }
                    }
                delay(ISSUE_SUMMARY_REFRESH_MS)
            }
        }
        job.invokeOnCompletion { issueSummaryJob = null }
        issueSummaryJob = job
    }

    private suspend fun fetchIssueSummaryAndStore() {
        val comments = fetchIssueCommentsFromMcp()
        if (comments.isEmpty()) {
            return
        }
        val userMessage = buildIssueSummaryUserMessage(comments)
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("text", ISSUE_SUMMARY_SYSTEM_PROMPT)
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("text", userMessage)
            )
        val completion = callYandex(messages, temperature = 0.2, maxTokens = 800)
        val answer = completion.answer.trim()
        if (answer.isEmpty()) {
            Log.d(TAG, "Issue summary returned empty response")
            return
        }
        storeIssueSummaryAnswer(answer)
        Log.i(TAG, "GitHub issue summary updated (${answer.length} chars)")
    }

    private suspend fun fetchIssueCommentsFromMcp(): List<GithubIssueComment> {
        val args = mapOf("per_page" to ISSUE_SUMMARY_COMMENT_LIMIT)
        val raw = runCatching { McpClient.callTool(ISSUE_SUMMARY_TOOL_NAME, args) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to call MCP tool $ISSUE_SUMMARY_TOOL_NAME", error)
                return emptyList()
            }
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) {
            Log.w(TAG, "Issue comments payload is not JSON array: ${trimmed.take(120)}")
            return emptyList()
        }
        return parseIssueComments(trimmed)
    }

    private fun parseIssueComments(raw: String): List<GithubIssueComment> = runCatching {
        val arr = JSONArray(raw)
        val result = mutableListOf<GithubIssueComment>()
        val count = minOf(arr.length(), ISSUE_SUMMARY_COMMENT_LIMIT)
        for (i in 0 until count) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id")
            if (id <= 0L) continue
            val body = obj.optString("body").orEmpty().trim()
            if (body.isBlank()) continue
            val issueUrl = obj.optString("issue_url")
            val issueNumber = issueUrl
                .substringAfterLast("/issues/", "")
                .toIntOrNull()
            val author = obj.optJSONObject("user")?.optString("login").orEmpty()
            val createdAt = obj.optString("created_at").takeIf { it.isNotBlank() }
            val updatedAt = obj.optString("updated_at").takeIf { it.isNotBlank() }
            result.add(
                GithubIssueComment(
                    id = id,
                    issueNumber = issueNumber,
                    author = author,
                    body = body,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            )
        }
        result
    }.getOrElse { error ->
        Log.e(TAG, "Failed to parse GitHub issue comments", error)
        emptyList()
    }

    private fun buildIssueSummaryUserMessage(comments: List<GithubIssueComment>): String {
        val sb = StringBuilder("Комментарии по актуальным issue:\n")
        comments.forEachIndexed { index, comment ->
            sb.append(index + 1)
                .append(". Issue #")
            sb.append(comment.issueNumber?.toString() ?: "?")
            if (comment.author.isNotBlank()) {
                sb.append(" (").append(comment.author).append(")")
            }
            comment.createdAt?.let { created ->
                sb.append(" – ").append(created)
            }
            sb.append(":\n")
            sb.append(normalizeCommentBody(comment.body))
            sb.append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun normalizeCommentBody(body: String): String {
        val normalized = body
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (normalized.length <= ISSUE_SUMMARY_MAX_BODY_CHARS) {
            return normalized
        }
        return normalized.take(ISSUE_SUMMARY_MAX_BODY_CHARS) + "..."
    }

    private fun storeIssueSummaryAnswer(answer: String) {
        val assistantObj = JSONObject()
            .put("role", "assistant")
            .put("text", answer)
        synchronized(permanentHistory) {
            permanentHistory.add(JSONObject(assistantObj.toString()))
        }
        persistHistories()
        maybeScheduleCompression(permanentHistory)
        lastIssueSummaryAnswer = answer
        lastIssueSummaryTimestamp = System.currentTimeMillis()
        issueSummaryVersion.incrementAndGet()
        broadcastLatestSummary()
    }

    private fun buildSummaryPayload(): String? {
        val summary = lastIssueSummaryAnswer ?: return null
        return JSONObject()
            .put("type", "issue_summary")
            .put("text", summary)
            .put("timestamp", lastIssueSummaryTimestamp)
            .put("version", issueSummaryVersion.get())
            .toString()
    }

    private fun broadcastLatestSummary() {
        val payload = buildSummaryPayload() ?: return
        broadcastWebSocketPayload(payload)
    }

    private fun broadcastHeartbeat() {
        val payload = JSONObject()
            .put("type", "heartbeat")
            .put("timestamp", System.currentTimeMillis())
            .toString()
        broadcastWebSocketPayload(payload)
    }

    private fun broadcastWebSocketPayload(payload: String) {
        val sockets = synchronized(issueSummarySockets) { issueSummarySockets.toList() }
        sockets.forEach { socket ->
            sendPayloadToSocket(socket, payload)
        }
    }

    private fun sendPayloadToSocket(socket: IssueSummaryWebSocket, payload: String) {
        runCatching { socket.send(payload) }
            .onFailure { removeWebSocket(socket) }
    }

    private fun waitForCompressionIfNeeded() {
        while (true) {
            val job = compressionJob ?: return
            runBlocking { job.join() }
            if (job === compressionJob) {
                return
            }
        }
    }

    private fun maybeScheduleCompression(targetHistory: MutableList<JSONObject>) {
        val historySnapshot = synchronized(targetHistory) {
            if (targetHistory.size <= MAX_HISTORY_MESSAGES) {
                return
            }
            targetHistory.map { JSONObject(it.toString()) }
        }

        synchronized(compressionJobLock) {
            if (compressionJob?.isActive == true) {
                return
            }
            compressionJob = serverScope.launch {
                try {
                    val summary = requestHistoryCompression(historySnapshot)
                    synchronized(targetHistory) {
                        targetHistory.clear()
                        targetHistory.add(
                            JSONObject()
                                .put("role", "assistant")
                                .put("text", summary)
                        )
                    }
                    persistHistories()
                    Log.d(TAG, "History compressed to summary (${summary.length} chars)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to compress history", e)
                } finally {
                    synchronized(compressionJobLock) {
                        compressionJob = null
                    }
                }
            }
        }
    }

    private fun requestHistoryCompression(historySnapshot: List<JSONObject>): String {
        val compressionMessages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("text", COMPRESSION_SYSTEM_PROMPT)
            )

        historySnapshot.forEach { compressionMessages.put(it) }

        compressionMessages.put(
            JSONObject()
                .put("role", "user")
                .put("text", COMPRESSION_SUMMARY_PROMPT)
        )

        val response = callYandex(compressionMessages, temperature = 0.3, maxTokens = 1000)
        return if (response.answer.isNotBlank()) response.answer else "Summary недоступен"
    }

    private fun buildClientResponse(result: CompletionResult): String {
        val sb = StringBuilder()
            .append(result.answer)

        sb.append("\n\nИтоги запроса :\n")

        if (result.totalTokens != null) sb.append("\n").append("totalTokens : ").append(result.totalTokens)
        if (result.completionTokens != null) sb.append("\n").append("completionTokens : ").append(result.completionTokens)
        if (result.promptTokens != null) sb.append("\n").append("inputTextTokens : ").append(result.promptTokens)
        sb.append("\n").append("request_time_ms : ").append(result.elapsedMs)
        result.usedTool?.let { sb.append("\n").append("used mcp tool : ").append(it) }

        return sb.toString()
    }

    private fun buildJsonSchema(): JSONObject =
        JSONObject().put(
            "schema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "title",
                            JSONObject()
                                .put("title", "Title")
                                .put("description", "Название фильма или сериала")
                                .put("type", "string")
                        )
                        .put(
                            "rating",
                            JSONObject()
                                .put("title", "Rating")
                                .put("description", "Рейтинг на imdb")
                                .put("type", "number")
                        )
                        .put(
                            "description",
                            JSONObject()
                                .put("title", "Description")
                                .put("description", "Содержание фильма или сериала")
                                .put("type", "string")
                        )
                )
                .put(
                    "required",
                    JSONArray().put("title").put("rating").put("description")
                )
        )


    private fun buildSystemMessageFromRole(toolSummary: String): JSONObject =
        JSONObject()
            .put("role", "system")
            .put(
                "text",
                role.roleDescription + if (toolSummary.isNotBlank()) {
                    "\nу тебя есть доступ к следующим инструментам:\n$toolSummary\n" +
                        "Ты можешь запросить доступ к любому инструменту. \n" +
                        "Если для использования инструмента у тебя не хватает данных, но они могут быть в другом инструменту, ты можешь сначала вернуть ответ для использования другого инструмента, у которого эти данные есть. +\n" +
                        "Для запроса доступа к инструменту ответь в формате: MCP_TOOL: {\"name\":\"tool.name\",\"parameters\":{...}}. " +
                        "Например MCP_TOOL: {\"name\":\"list_activities\",\"parameters\":{\"city_id\":\"213\"}}" + "\n"
                } else ""
            )

    private fun buildToolSummary(): String = runBlocking {
        val tools: List<Tool> = runCatching { McpClient.fetchTools() }.getOrDefault(emptyList())
        if (tools.isEmpty()) return@runBlocking ""
        tools.joinToString(separator = "\n") { tool ->
            val desc = tool.description ?: ""
            val input = tool.inputSchema
            val inputSchema = runCatching { input.toString() }.getOrDefault(input?.toString().orEmpty())
            "name: ${tool.name}, description: $desc, inputSchema: $inputSchema"
        }
    }

    private data class ToolRequest(val name: String, val parameters: Map<String, Any?>)

    private fun parseToolRequest(answer: String): ToolRequest? {
        val idx = answer.indexOf(toolMarker, ignoreCase = true)
        if (idx == -1) return null
        val after = answer.substring(idx + toolMarker.length).trim()
        if (after.isBlank()) return null

        if (after.startsWith("{")) {
            return runCatching {
                val obj = JSONObject(after)
                val name = obj.optString("name").trim()
                if (name.isBlank()) return@runCatching null
                val paramsObj = obj.optJSONObject("parameters")
                val params = paramsObj?.toMap() ?: emptyMap()
                ToolRequest(name, params)
            }.getOrNull()
        }

        return ToolRequest(after, emptyMap())
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            val value = opt(key)
            result[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until value.length()) {
                        list.add(value.opt(i))
                    }
                    list
                }
                else -> value
            }
        }
        return result
    }

    override fun stop() {
        super.stop()
        issueSummaryJob?.cancel()
        issueSummaryJob = null
        issueSummaryHeartbeatJob?.cancel()
        issueSummaryHeartbeatJob = null
        synchronized(issueSummarySockets) {
            issueSummarySockets.forEach { socket ->
                runCatching { socket.close(WebSocketFrame.CloseCode.GoingAway, "Server stopping", false) }
            }
            issueSummarySockets.clear()
        }
        serverScope.cancel()
    }

    private fun historyResponse(): Response {
        val arr = JSONArray().apply {
            synchronized(permanentHistory) {
                permanentHistory.forEach { put(JSONObject(it.toString())) }
            }
        }
        val payload = JSONObject()
            .put("messages", arr)
            .put("role", role.name)
        return newFixedLengthResponse(Response.Status.OK, "application/json", payload.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun clearHistoryResponse(): Response {
        synchronized(permanentHistory) {
            permanentHistory.clear()
        }
        persistHistories()
        return newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("cleared", true).toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun persistHistories() {
        val permanentCopy = synchronized(permanentHistory) { permanentHistory.map { JSONObject(it.toString()) } }
        runCatching {
            historyFile.parentFile?.mkdirs()
            val payload = JSONObject()
                .put("permanentHistory", JSONArray().apply { permanentCopy.forEach { put(it) } })
            historyFile.writeText(payload.toString())
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist history to file: ${historyFile.absolutePath}", error)
        }
    }

    private fun loadHistoryFromDisk() {
        if (!historyFile.exists()) return
        runCatching {
            val saved = historyFile.readText()
            if (saved.isBlank()) return@runCatching
            val obj = JSONObject(saved)
            obj.optJSONArray("permanentHistory")?.let { arr ->
                synchronized(permanentHistory) {
                    permanentHistory.clear()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { permanentHistory.add(JSONObject(it.toString())) }
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to load history from file: ${historyFile.absolutePath}", error)
        }
    }

    private fun jsonError(code: Int, message: String): Response {
        val obj = JSONObject().put("error", message)
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", obj.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun removeWebSocket(socket: IssueSummaryWebSocket) {
        issueSummarySockets.remove(socket)
        runCatching { socket.close(WebSocketFrame.CloseCode.NormalClosure, "closing", false) }
        stopIssueSummaryHeartbeatIfNeeded()
    }

    private inner class IssueSummaryWebSocket(handshakeRequest: IHTTPSession?) : WebSocket(handshakeRequest) {
        override fun onOpen() {
            issueSummarySockets.add(this)
            ensureIssueSummaryHeartbeat()
            buildSummaryPayload()?.let { sendPayloadToSocket(this, it) }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            issueSummarySockets.remove(this)
            stopIssueSummaryHeartbeatIfNeeded()
        }

        override fun onMessage(message: WebSocketFrame?) {
            // No-op: server pushes only
        }

        override fun onPong(pong: WebSocketFrame?) {
            // No-op
        }

        override fun onException(exception: IOException?) {
            if (exception is SocketTimeoutException) {
                Log.d(TAG, "Issue summary websocket timeout, ignoring")
                return
            }
            removeWebSocket(this)
        }
    }

    private fun ensureIssueSummaryHeartbeat() {
        if (issueSummaryHeartbeatJob?.isActive == true) return
        issueSummaryHeartbeatJob = serverScope.launch {
            while (isActive) {
                broadcastHeartbeat()
                delay(ISSUE_SUMMARY_HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopIssueSummaryHeartbeatIfNeeded() {
        val hasSockets = synchronized(issueSummarySockets) { issueSummarySockets.isNotEmpty() }
        if (hasSockets) return
        issueSummaryHeartbeatJob?.cancel()
        issueSummaryHeartbeatJob = null
    }
}

