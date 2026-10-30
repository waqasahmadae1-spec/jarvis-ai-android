package com.example.llama

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudAiClient {
    fun reply(apiKey: String, history: List<Pair<String, String>>): String {
        val messages = JSONArray()

        messages.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "You are JARVIS, an advanced general-purpose and technical AI assistant. Always answer in clear English. " +
                    "You are expected to handle programming, debugging, mathematics, physics, chemistry, biology, science and complex reasoning. " +
                    "For coding, provide correct, practical and complete code with explanations and identify assumptions or version requirements. " +
                    "For mathematics, solve carefully step by step and verify the final result. " +
                    "For science, distinguish established facts from uncertain or theoretical claims. " +
                    "For complex problems, explain the key reasoning clearly and organize the answer with headings when useful. " +
                    "Be accurate, direct and useful. Never invent facts, code results or sources. " +
                    "If uncertain, clearly say so. Always finish your answer completely."
                )
        )

        history.takeLast(12).forEach { (role, content) ->
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content)
                )
            }
        }

        val body = JSONObject()
            .put("model", "nvidia/nemotron-3-ultra-550b-a55b:free")
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 4096)

        val connection = (URL("https://openrouter.ai/api/v1/chat/completions")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response)
                        .optJSONObject("error")
                        ?.optString("message", "")
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }

                throw IllegalStateException(
                    when (code) {
                        401, 403 -> "API key rejected. Check your OpenRouter API key."
                        429 -> "Free-model limit reached. Please wait and try again."
                        else -> "Cloud request failed (HTTP $code). ${detail.take(180)}"
                    }
                )
            }

            val root = JSONObject(response)
            val choices = root.optJSONArray("choices")
                ?: throw IllegalStateException(
                    "The AI provider returned an unexpected response. Please try again."
                )

            if (choices.length() == 0) {
                throw IllegalStateException(
                    "The AI provider returned no answer. Please try again."
                )
            }

            val choice = choices.optJSONObject(0)
                ?: throw IllegalStateException(
                    "The AI provider returned an invalid answer. Please try again."
                )

            val message = choice.optJSONObject("message")
                ?: throw IllegalStateException(
                    "The AI provider returned no message. Please try again."
                )

            val rawContent = message.opt("content")

            val content = when (rawContent) {
                is String -> rawContent.trim()

                is JSONArray -> {
                    val parts = mutableListOf<String>()

                    for (i in 0 until rawContent.length()) {
                        when (val part = rawContent.opt(i)) {
                            is String -> {
                                if (part.isNotBlank()) parts.add(part)
                            }

                            is JSONObject -> {
                                val type = part.optString("type", "")
                                val text = part.optString("text", "").trim()

                                if (text.isNotBlank() &&
                                    (type.isBlank() || type == "text" ||
                                     type == "output_text")
                                ) {
                                    parts.add(text)
                                }
                            }
                        }
                    }

                    parts.joinToString("\n").trim()
                }

                else -> ""
            }

            if (content.isNotBlank()) {
                return content
            }

            val refusal = message.optString("refusal", "").trim()
            if (refusal.isNotBlank()) {
                return refusal
            }

            val finishReason = choice.optString("finish_reason", "")
            val reason = if (finishReason.isNotBlank() && finishReason != "null") {
                " (finish reason: $finishReason)"
            } else {
                ""
            }

            throw IllegalStateException(
                "The AI provider returned empty answer content$reason. Please try again."
            )
        } finally {
            connection.disconnect()
        }
    }
}
