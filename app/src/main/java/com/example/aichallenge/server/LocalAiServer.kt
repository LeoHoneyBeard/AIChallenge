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

            val prompt = try {
                JSONObject(raw).optString("prompt", "").trim()
            } catch (_: Exception) {
                ""
            }

            if (prompt.isEmpty()) {
                return jsonError(400, "Field 'prompt' is required")
            }

            Log.i(TAG, "Incoming /chat request. prompt='$prompt'")
            val answer = callYandex(prompt)
            val resp = JSONObject()
                .put("prompt", prompt)
                .put("answer", answer)

            return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString()).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            }
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Internal error")
        }
    }

    private fun callYandex(userPrompt: String): String {
        val payloadJson = JSONObject()
            .put("modelUri", "gpt://$folderId/yandexgpt-lite")
            .put(
                "completionOptions",
                JSONObject()
                    .put("stream", false)
                    .put("temperature", 0.8)
                    .put("maxTokens", "1000")
            )
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("text", "Ты тибетский монах, который отвечает мудростями или загадками")
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("text", userPrompt)
                    )
            )
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

