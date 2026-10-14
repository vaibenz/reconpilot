package dev.vaibenz.reconpilot.llm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vaibenz.reconpilot.match.CandidateFinder
import dev.vaibenz.reconpilot.match.Matcher
import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ProposalSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the model said about one bank line, kept whether or not it turned into a proposal. */
data class LlmDecision(
    val bankLineId: String,
    val candidateIds: List<String>,
    val invoiceId: String?,
    val confidence: Double,
    val rationale: String,
    val error: String? = null,
)

/**
 * Asks the LLM to pick the most likely invoice for each bank line from a shortlist.
 * Output is only a proposal: the [dev.vaibenz.reconpilot.verify.Verifier] decides whether it survives.
 */
class LlmMatcher(
    private val client: LlmClient,
    private val candidateFinder: CandidateFinder = CandidateFinder(),
    private val parallelism: Int = 4,
    /** Called with (done, total) after each bank line, for progress output on long runs. */
    private val onProgress: ((Int, Int) -> Unit)? = null,
) : Matcher {

    private val log = ConcurrentLinkedQueue<LlmDecision>()

    /** Every decision from the most recent run, in no particular order. */
    val decisions: List<LlmDecision> get() = log.toList()

    override fun propose(lines: List<BankLine>, invoices: List<Invoice>): List<MatchProposal> {
        log.clear()
        val pool = Executors.newFixedThreadPool(parallelism)
        try {
            val done = java.util.concurrent.atomic.AtomicInteger()
            val futures = lines.map { line ->
                pool.submit<MatchProposal?> {
                    try {
                        decide(line, invoices)
                    } finally {
                        onProgress?.invoke(done.incrementAndGet(), lines.size)
                    }
                }
            }
            return futures.mapNotNull { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    private fun decide(line: BankLine, invoices: List<Invoice>): MatchProposal? {
        val shortlist = candidateFinder.candidates(line, invoices)
        val ids = shortlist.map { it.id }
        if (shortlist.isEmpty()) {
            log += LlmDecision(line.id, ids, null, 0.0, "no candidates in range")
            return null
        }
        val raw = try {
            client.complete(Prompts.SYSTEM, Prompts.user(line, shortlist))
        } catch (e: Exception) {
            log += LlmDecision(line.id, ids, null, 0.0, "", error = e.message ?: e.javaClass.simpleName)
            return null
        }
        val parsed = parse(raw)
        if (parsed == null) {
            log += LlmDecision(line.id, ids, null, 0.0, "", error = "unparseable response: ${raw.take(200)}")
            return null
        }
        log += parsed.copy(bankLineId = line.id, candidateIds = ids)
        val invoiceId = parsed.invoiceId ?: return null
        return MatchProposal(line.id, invoiceId, parsed.confidence, parsed.rationale, ProposalSource.LLM)
    }

    companion object {
        /** Pulls the first JSON object out of a model reply, tolerating code fences and stray prose. */
        fun parse(raw: String): LlmDecision? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj: JsonObject = try {
                JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject
            } catch (e: Exception) {
                return null
            }
            val idElement = obj.get("invoice_id")
            val invoiceId = if (idElement == null || idElement.isJsonNull) null else idElement.asString.trim().ifEmpty { null }
            val confidence = obj.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble?.coerceIn(0.0, 1.0) ?: 0.0
            val rationale = obj.get("rationale")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            return LlmDecision("", emptyList(), invoiceId, confidence, rationale)
        }
    }
}

internal object Prompts {
    val SYSTEM = """
        You reconcile incoming bank payments against open invoices for a finance team.
        For the bank line you are given, choose the single invoice it most likely pays, or none.
        Consider references in the description even if they are abbreviated or reformatted,
        payer names that differ in punctuation or legal suffixes, and small shortfalls from bank fees.
        Never pick an invoice in a different currency. If two invoices are equally plausible, choose none.
        Reply with JSON only, no prose:
        {"invoice_id": "<id from the list or null>", "confidence": <0 to 1>, "rationale": "<one sentence>"}
    """.trimIndent()

    fun user(line: BankLine, candidates: List<Invoice>): String = buildString {
        appendLine("Bank line:")
        appendLine("  date: ${line.date}")
        appendLine("  amount: ${line.amount.toPlainString()} ${line.currency}")
        appendLine("  payer: ${line.counterparty}")
        appendLine("  description: ${line.description}")
        appendLine()
        appendLine("Candidate invoices:")
        candidates.forEach {
            appendLine(
                "  - id: ${it.id} | number: ${it.number} | customer: ${it.customer} | " +
                    "amount: ${it.amount.toPlainString()} ${it.currency} | issued: ${it.issueDate} | due: ${it.dueDate}",
            )
        }
    }
}
