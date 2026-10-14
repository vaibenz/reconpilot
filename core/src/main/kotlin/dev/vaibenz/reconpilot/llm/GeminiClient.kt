package dev.vaibenz.reconpilot.llm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to the Gemini generateContent API using only the JDK HTTP client.
 * Requests are spaced out to stay under [requestsPerMinute], which keeps a full
 * evaluation run inside the free tier, and 429s are retried with backoff.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    requestsPerMinute: Int = 12,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : LlmClient {

    private val minIntervalMs = 60_000L / requestsPerMinute.coerceAtLeast(1)
    private var nextSlot = 0L

    override fun complete(system: String, user: String): String {
        val body = JsonObject().apply {
            add("system_instruction", JsonObject().apply { add("parts", textParts(system)) })
            add(
                "contents",
                JsonArray().apply {
                    add(JsonObject().apply { addProperty("role", "user"); add("parts", textParts(user)) })
                },
            )
            add(
                "generationConfig",
                JsonObject().apply {
                    addProperty("temperature", 0)
                    addProperty("responseMimeType", "application/json")
                },
            )
        }
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1beta/models/$model:generateContent"))
            .timeout(Duration.ofSeconds(60))
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        var attempt = 0
        while (true) {
            awaitSlot()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val code = response.statusCode()
            if (code == 200) return extractText(response.body())
            val retryable = code == 429 || code >= 500
            if (!retryable || attempt >= 5) throw LlmException("Gemini call failed with HTTP $code: ${response.body().take(300)}")
            val retryAfter = response.headers().firstValue("retry-after").map { it.toLongOrNull() }.orElse(null)
            Thread.sleep(retryAfter?.times(1000) ?: (5_000L shl attempt))
            attempt++
        }
    }

    /** Simple shared limiter: each call waits for its own slot, so parallel workers cannot burst. */
    private fun awaitSlot() {
        val wait: Long
        synchronized(this) {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, nextSlot)
            nextSlot = slot + minIntervalMs
            wait = slot - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    private fun textParts(text: String) = JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        /** Joins the text parts of the first candidate. Throws if the reply was blocked or empty. */
        fun extractText(json: String): String {
            val root = JsonParser.parseString(json).asJsonObject
            val candidate = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                ?: throw LlmException("Gemini returned no candidates: ${json.take(200)}")
            val parts = candidate.getAsJsonObject("content")?.getAsJsonArray("parts")
                ?: throw LlmException("Gemini candidate had no content (finishReason ${candidate.get("finishReason")})")
            return parts.mapNotNull { it.asJsonObject.get("text")?.asString }.joinToString("")
        }

        /** Builds a client from GEMINI_API_KEY, RECONPILOT_GEMINI_MODEL and RECONPILOT_RPM, or returns null if no key is set. */
        fun fromEnv(): GeminiClient? {
            val key = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            return GeminiClient(
                key,
                System.getenv("RECONPILOT_GEMINI_MODEL") ?: DEFAULT_MODEL,
                System.getenv("RECONPILOT_RPM")?.toIntOrNull() ?: 12,
            )
        }
    }
}

/** A configured model plus a human readable label for reports. */
data class ConfiguredModel(val client: LlmClient, val label: String)

object LlmClients {
    /** Prefers Anthropic when its key is set, then Gemini, otherwise null (rules only). */
    fun fromEnv(): ConfiguredModel? {
        AnthropicClient.fromEnv()?.let { return ConfiguredModel(it, "Anthropic ${System.getenv("RECONPILOT_MODEL") ?: AnthropicClient.DEFAULT_MODEL}") }
        GeminiClient.fromEnv()?.let { return ConfiguredModel(it, "Gemini ${System.getenv("RECONPILOT_GEMINI_MODEL") ?: GeminiClient.DEFAULT_MODEL}") }
        return null
    }
}
