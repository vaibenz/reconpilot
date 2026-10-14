package dev.vaibenz.reconpilot.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Records every LLM reply to a JSONL file keyed by a hash of the prompt, and replays it on later runs.
 * Makes evaluation runs reproducible and free after the first pass. With [offline] set,
 * a cache miss throws instead of calling the model.
 */
class CachingLlmClient(
    private val delegate: LlmClient?,
    private val file: File,
    private val offline: Boolean = delegate == null,
) : LlmClient {

    private val cache = ConcurrentHashMap<String, String>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    val obj = JsonParser.parseString(line).asJsonObject
                    cache[obj.get("key").asString] = obj.get("reply").asString
                }
            }
        }
    }

    val size: Int get() = cache.size

    override fun complete(system: String, user: String): String {
        val key = hash(system + "\u0000" + user)
        cache[key]?.let { return it }
        if (offline || delegate == null) throw LlmException("no cached reply for prompt $key and no live model configured")
        val reply = delegate.complete(system, user)
        cache[key] = reply
        synchronized(this) {
            file.parentFile?.mkdirs()
            val record = JsonObject().apply {
                addProperty("key", key)
                addProperty("reply", reply)
            }
            file.appendText(record.toString() + "\n")
        }
        return reply
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
