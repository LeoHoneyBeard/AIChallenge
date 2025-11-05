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
) : NanoHTTPD("127.0.0.1", port) {

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private val TAG = "LocalAiServer"
    private val apiKey = BuildConfig.YANDEX_API_KEY
    private val folderId = BuildConfig.YC_FOLDER_ID
    private val history = mutableListOf<JSONObject>()

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
            val finalMessages = JSONArray().put(buildSystemMessage())
            history.forEach { finalMessages.put(it) }
            finalMessages.put(
                JSONObject()
                    .put("role", "user")
                    .put("text", prompt)
            )

            Log.i(TAG, "Incoming /chat request. prompt='${'$'}prompt', history=${'$'}{history.size}")
            val resp = callYandex(finalMessages)

            // Persist new exchange to server-side history
            history.add(
                JSONObject()
                    .put("role", "user")
                    .put("text", prompt)
            )
            history.add(
                JSONObject()
                    .put("role", "assistant")
                    .put("text", resp)
            )

            Log.d(TAG, "/chat response =\n${prettyJson(resp)}")
            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()).apply {
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

    private fun callYandex(userPrompt: String): String {
        val messages = JSONArray()
            .put(buildSystemMessage())
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("text", userPrompt)
            )
        return callYandex(messages)
    }

    private fun callYandex(messages: JSONArray): String {
        val payloadJson = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt-lite")
            .put(
                "completionOptions",
                JSONObject()
                    .put("stream", false)
                    .put("temperature", 0.8)
                    .put("maxTokens", "1000")
            )
            .put("json_schema", buildJsonSchema())
            .put("messages", messages)
            .toString()

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

    private fun buildSystemMessage(): JSONObject =
        JSONObject()
            .put("role", "system")
            .put("text", "Ты ИИ-агент, который даёт информацию о фильмах и сериалах с imdb. Если вопрос не про конкретный фильм, в поля title и rating нужно ставить прочерки")



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
