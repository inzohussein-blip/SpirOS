package com.spiros.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Streaming chat against the local llama.cpp server (OpenAI-compatible API). */
object AiChat {

    data class Message(val role: String, val text: String)

    const val PRESET_GENERAL = "You are a helpful assistant running privately on the user's phone. Answer in the language the user writes in. Be concise."
    const val PRESET_TRADING = "You are a careful trading analyst. When the user gives prices, indicators or MT5 data, explain trends, support/resistance, risk and position sizing step by step. Never promise profits; always mention risk. Answer in the language the user writes in."
    const val PRESET_CODE = "You are a programming assistant for Python and MQL5 (MetaTrader 5). Give complete, working code with short explanations. Answer in the language the user writes in."

    /** Sends the conversation and calls [onToken] for each streamed piece of the reply. */
    suspend fun complete(system: String, history: List<Message>, onToken: (String) -> Unit) = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.6)
            .put("max_tokens", 1024)
            .toString()

        val c = URL("http://127.0.0.1:${AiServer.PORT}/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 600_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer ${AiServer.apiKey}")
            c.outputStream.use { it.write(body.toByteArray()) }
            check(c.responseCode == 200) { "AI server: HTTP ${c.responseCode}" }
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val piece = delta?.optString("content").orEmpty()
                    if (piece.isNotEmpty() && piece != "null") onToken(piece)
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
