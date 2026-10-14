package dev.vaibenz.reconpilot.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal chat interface so the matcher does not care which provider sits behind it. */
fun interface LlmClient {
    fun complete(system: String, user: String): String
}

class LlmException(message: String) : RuntimeException(message)

/** Talks to the Anthropic Messages API using only the JDK HTTP client. */
class AnthropicClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
    private val maxTokens: Int = 400,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : LlmClient {

    private val gson = Gson()

    override fun complete(system: String, user: String): String {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", maxTokens)
            addProperty("temperature", 0)
            addProperty("system", system)
            add("messages", gson.toJsonTree(listOf(mapOf("role" to "user", "content" to user))))
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/messages"))
            .timeout(Duration.ofSeconds(60))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        var attempt = 0
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val code = response.statusCode()
            if (code == 200) return extractText(response.body())
            val retryable = code == 429 || code >= 500
            if (!retryable || attempt >= 3) throw LlmException("LLM call failed with HTTP $code: ${response.body().take(300)}")
            Thread.sleep(1000L shl attempt)
            attempt++
        }
    }

    private fun extractText(json: String): String {
        val content = JsonParser.parseString(json).asJsonObject.getAsJsonArray("content")
        return content.filter { it.asJsonObject.get("type")?.asString == "text" }
            .joinToString("") { it.asJsonObject.get("text").asString }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        /** Builds a client from ANTHROPIC_API_KEY and RECONPILOT_MODEL, or returns null if no key is set. */
        fun fromEnv(): AnthropicClient? {
            val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            return AnthropicClient(key, System.getenv("RECONPILOT_MODEL") ?: DEFAULT_MODEL)
        }
    }
}
