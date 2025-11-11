package com.example.aichallenge.server

import android.util.Log
import com.example.aichallenge.BuildConfig
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class LocalAiServer(
    port: Int,
) : NanoHTTPD("127.0.0.1", port), ChatLocalServer {

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private val TAG = "LocalAiServer"
    private var apiKey = BuildConfig.YANDEX_API_KEY
    private var folderId = BuildConfig.YC_FOLDER_ID
    private val history = mutableListOf<JSONObject>()
    private val permanentHistory = mutableListOf<JSONObject>()
    private var role: Role = Role.DEFAULT

    init {
        LocalServerRegistry.instance = this
    }

    override fun setRole(newRole: Role) {
        if (role != newRole) {
            role = newRole
            history.clear()
        }
    }

    override fun getRole(): Role = role

    override fun serve(session: IHTTPSession): Response {
        return try {
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
            val finalMessages = JSONArray().put(
                buildSystemMessageFromRole()
            )
            val chosenHistory = if (role.isPermanentHistoryNeeded) permanentHistory else history
            chosenHistory.forEach { finalMessages.put(it) }
            finalMessages.put(
                JSONObject()
                    .put("role", "user")
                    .put("text", prompt)
            )

            val resp = callYandex(finalMessages)

            // Persist new exchange to both histories
            val userObj = JSONObject()
                .put("role", "user")
                .put("text", prompt)
            val assistantObj = JSONObject()
                .put("role", "assistant")
                .put("text", resp)

            history.add(userObj)
            history.add(assistantObj)
            permanentHistory.add(JSONObject(userObj.toString()))
            permanentHistory.add(JSONObject(assistantObj.toString()))

//            Log.d(TAG, "${session.uri} response =\n${prettyJson(resp)}")
            return newFixedLengthResponse(Response.Status.OK, "text/plain", resp.toString()).apply {
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

    private fun callYandex(messages: JSONArray): String {
        val payloadJsonObject = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt-5.1")
            .put(
                "completionOptions",
                JSONObject()
                    .put("stream", false)
                    .put("temperature", role.temperature)
                    .put("maxTokens", "1000")
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
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Yandex LLM error code=${resp.code} body=$body")
                throw IllegalStateException("Yandex LLM error ${resp.code}: $body")
            }
            val json = JSONObject(body)
            val text = json
                .optJSONObject("result")
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("text")
                ?.trim()
            val answer = text?.takeIf { it.isNotEmpty() } ?: ""
            Log.d(TAG, "Yandex LLM response <- text='$answer'")
            return answer
        }
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
