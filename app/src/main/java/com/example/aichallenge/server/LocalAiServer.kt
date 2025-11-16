package com.example.aichallenge.server

import android.content.Context
import android.util.Log
import com.example.aichallenge.BuildConfig
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private data class CompletionResult(
    val answer: String,
    val totalTokens: Int?,
    val completionTokens: Int?,
    val promptTokens: Int?,
    val elapsedMs: Long,
)

private const val MAX_HISTORY_MESSAGES = 10
private const val COMPRESSION_SYSTEM_PROMPT = "Ты помогаешь сжимать историю сообщений без потери контекста"
private const val COMPRESSION_SUMMARY_PROMPT = "Сделай summary всего полученного диалога, без учёта этого сообщения, чтобы контекст не потерялся"

class LocalAiServer(
    port: Int,
    context: Context,
) : NanoHTTPD("127.0.0.1", port), ChatLocalServer {

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

    init {
        LocalServerRegistry.instance = this
        loadHistoryFromDisk()
    }

    override fun setRole(newRole: Role) {
        // Roles are disabled for now; ignore requests to change role.
    }

    override fun getRole(): Role = role

    override fun serve(session: IHTTPSession): Response {
        return try {
            waitForCompressionIfNeeded()
            if (session.method == Method.GET && session.uri == "/history") {
                return historyResponse()
            }
            if (session.method == Method.POST && session.uri == "/clear") {
                return clearHistoryResponse()
            }
            if (session.method != Method.POST || session.uri != "/chat") {
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
            val finalMessages = JSONArray().put(buildSystemMessageFromRole())
            synchronized(permanentHistory) { permanentHistory.forEach { finalMessages.put(it) } }
            finalMessages.put(
                JSONObject()
                    .put("role", "user")
                    .put("text", prompt)
            )

            val completionResult = callYandex(finalMessages)
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

    private fun callYandex(messages: JSONArray, temperature: Double = role.temperature, maxTokens: Int = 1000): CompletionResult {
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


    private fun buildSystemMessageFromRole(): JSONObject =
        JSONObject()
            .put("role", "system")
            .put(
                "text",
                role.roleDescription
            )

    override fun stop() {
        super.stop()
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
}
