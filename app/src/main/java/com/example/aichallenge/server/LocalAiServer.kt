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
            val incomingMessages = bodyJson.optJSONArray("messages")

            if (prompt.isEmpty()) {
                return jsonError(400, "Field 'prompt' is required")
            }

            val (_, resp) = if (incomingMessages != null && incomingMessages.length() > 0) {
                // Build full conversation: system + normalized history + new user message
                val normalized = JSONArray()
                for (i in 0 until incomingMessages.length()) {
                    val obj = incomingMessages.optJSONObject(i) ?: continue
                    val roleRaw = obj.optString("role", "").lowercase()
                    val text = obj.optString("text", "").trim()
                    if (text.isEmpty()) continue
                    val mappedRole = when (roleRaw) {
                        "user" -> "user"
                        "assistant" -> "assistant"
                        "bot" -> "assistant"
                        "system" -> "system"
                        else -> "user"
                    }
                    // Skip external system messages; we'll add our own system prompt at start
                    if (mappedRole == "system") continue

                    normalized.put(
                        JSONObject()
                            .put("role", mappedRole)
                            .put("text", text)
                    )
                }

                val finalMessages = JSONArray().put(buildSystemMessage())
                for (i in 0 until normalized.length()) {
                    finalMessages.put(normalized.getJSONObject(i))
                }
                // Append the newly entered prompt as last user message
                finalMessages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("text", prompt)
                )

                Log.i(TAG, "Incoming /chat with history=${incomingMessages.length()} + prompt")
                val ans = callYandex(finalMessages)
                val respObj = JSONObject().put("answer", ans)
                ans to respObj
            } else {
                Log.i(TAG, "Incoming /chat request. prompt='$prompt'")
                val ans = callYandex(prompt)
                val respObj = JSONObject()
                    .put("prompt", prompt)
                    .put("answer", ans)
                ans to respObj
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            }
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Internal error")
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

    private fun buildSystemMessage(): JSONObject =
        JSONObject()
            .put("role", "system")
            .put("text", "Ты тибетский монах, который отвечает мудростями или загадками")

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
