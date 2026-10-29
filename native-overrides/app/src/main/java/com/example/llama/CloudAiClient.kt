package com.example.llama

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudAiClient {
    fun reply(apiKey: String, history: List<Pair<String, String>>): String {
        val messages = JSONArray()
        messages.put(JSONObject()
            .put("role", "system")
            .put("content", "You are JARVIS, a helpful AI assistant. Always answer in clear English. Be accurate, direct, and concise. For simple questions, use 1-4 short sentences. Do not repeat the question or add unnecessary introductions. If uncertain, say so instead of inventing facts."))
        history.takeLast(12).forEach { (role, content) ->
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }

        val body = JSONObject()
            .put("model", "openrouter/free")
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 400)

        val connection = (URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Exception) { "" }

                throw IllegalStateException(when (code) {
                    401, 403 -> "API key rejected. Check your OpenRouter API key."
                    429 -> "Free-model limit reached. Please wait and try again."
                    else -> "Cloud request failed (HTTP $code). ${detail.take(180)}"
                })
            }

            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()

            return content.ifBlank { "I couldn't generate a clear answer. Please try again." }
        } finally {
            connection.disconnect()
        }
    }
}
