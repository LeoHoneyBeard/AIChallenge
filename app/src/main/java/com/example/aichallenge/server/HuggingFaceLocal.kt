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

class HuggingFaceLocal(
    port: Int,
) : NanoHTTPD("127.0.0.1", port), HuggingLocalServer {

    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val endpoint = "https://router.huggingface.co/v1/chat/completions"
    private val TAG = "HuggingFaceLocal"
    private var hfToken = BuildConfig.HF_TOKEN
    private val history = mutableListOf<JSONObject>()
    private val permanentHistory = mutableListOf<JSONObject>()
    private var modelChoice: Model = Model.LLAMA_3_1_8B

    // Selected chat model comes from Model enum

    init {
        HuggingServerRegistry.instance = this
    }

    override fun setModel(newModel: Model) {
        if (modelChoice != newModel) {
            modelChoice = newModel
            history.clear()
        }
    }

    override fun getModel(): Model = modelChoice

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method != Method.POST || session.uri != "/chat") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }

            if (hfToken.isBlank()) {
                return jsonError(400, "Missing HF_TOKEN. Add it to local.properties.")
            }

            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val raw = bodyMap["postData"] ?: "{}"

            val bodyJson = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            val prompt = bodyJson.optString("prompt", "").trim()

            if (prompt.isEmpty()) {
                return jsonError(400, "Field 'prompt' is required")
            }

            val finalMessages = buildOpenAiMessages(prompt)
            val respText = callHuggingFace(finalMessages)

            // Persist new exchange to both histories (store as role/text for simplicity)
            val userObj = JSONObject().put("role", "user").put("text", prompt)
            val assistantObj = JSONObject().put("role", "assistant").put("text", respText)
            history.add(userObj)
            history.add(assistantObj)
            permanentHistory.add(JSONObject(userObj.toString()))
            permanentHistory.add(JSONObject(assistantObj.toString()))

            return newFixedLengthResponse(Response.Status.OK, "text/plain", respText).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            }
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Internal error")
        }
    }

    private fun buildOpenAiMessages(prompt: String): JSONArray {
        val msgs = JSONArray()
        // Choose appropriate history
        val chosenHistory = if (modelChoice.isPermanentHistoryNeeded) permanentHistory else history
        chosenHistory.forEach { hist ->
            val r = hist.optString("role")
            val t = hist.optString("text")
            if (r.isNotEmpty() && t.isNotEmpty()) {
                msgs.put(
                    JSONObject()
                        .put("role", r)
                        .put("content", t)
                )
            }
        }
        // New user message
        msgs.put(
            JSONObject()
                .put("role", "user")
                .put("content", prompt)
        )
        return msgs
    }

    private fun callHuggingFace(messages: JSONArray): String {
        // OpenAI-compatible Chat Completions payload per HF docs
        val payloadJsonObject = JSONObject()
            .put("model", modelChoice.model)
            .put("messages", messages)
            .put("stream", false)

        val payloadJson = payloadJsonObject.toString()

        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $hfToken")
            .addHeader("Content-Type", "application/json")
            .post(payloadJson.toRequestBody(jsonMedia))
            .build()

        Log.d(TAG, "HF request -> url=$endpoint, payload=$payloadJson")
        val startMs = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "HF error code=${resp.code} body=$body")
                throw IllegalStateException("HF error ${resp.code}: $body")
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            val json = JSONObject(body)
            Log.d(TAG, "HF response <- response'=${prettyJson(json.toString())}")
            val answer = json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            // Extract usage fields if present
            val usage = json.optJSONObject("usage")
            val totalTokens = usage?.optInt("total_tokens")
            val completionTokens = usage?.optInt("completion_tokens")
            val promptTokens = usage?.optInt("prompt_tokens")

            val sb = StringBuilder()
                .append(answer)

            sb.append("\n\nИтоги запроса :\n")

            if (totalTokens != null) sb.append("\n").append("total_tokens : ").append(totalTokens)
            if (completionTokens != null) sb.append("\n").append("completion_tokens : ").append(completionTokens)
            if (promptTokens != null) sb.append("\n").append("prompt_tokens : ").append(promptTokens)
            sb.append("\n").append("request_time_ms : ").append(elapsedMs)

            val finalText = sb.toString()
            Log.d(TAG, "HF response <- text='${finalText}'")
            return finalText
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
}
