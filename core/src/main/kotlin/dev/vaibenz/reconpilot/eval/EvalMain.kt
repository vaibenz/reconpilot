package dev.vaibenz.reconpilot.eval

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.vaibenz.reconpilot.Reconciler
import dev.vaibenz.reconpilot.io.Csv
import dev.vaibenz.reconpilot.llm.AnthropicClient
import dev.vaibenz.reconpilot.llm.CachingLlmClient
import dev.vaibenz.reconpilot.llm.LlmMatcher
import dev.vaibenz.reconpilot.match.RulesMatcher
import dev.vaibenz.reconpilot.model.RejectionReason
import java.io.File
import java.time.LocalDate

/**
 * Runs the rules baseline and, when a model is available, the LLM pipeline before and after
 * verification, then writes a markdown report and a log of every AI suggestion the verifier rejected.
 *
 * Usage: eval [dataDir] [outDir]
 * Set ANTHROPIC_API_KEY to call the model; replies are cached in outDir/llm_cache.jsonl and replayed later.
 */
object EvalMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val dataDir = File(args.getOrElse(0) { "data" })
        val outDir = File(args.getOrElse(1) { "eval" }).apply { mkdirs() }

        val lines = Csv.readBankLines(File(dataDir, "bank_lines.csv"))
        val invoices = Csv.readInvoices(File(dataDir, "invoices.csv"))
        val labelRows = Csv.parse(File(dataDir, "labels.csv").readText())
        val labels = labelRows.associate { it.getValue("bank_line_id") to it.getValue("invoice_id").ifEmpty { null } }
        val scenarios = labelRows.associate { it.getValue("bank_line_id") to it.getValue("scenario") }

        val report = StringBuilder()
        report.appendLine("# ReconPilot evaluation")
        report.appendLine()
        report.appendLine("Dataset: ${lines.size} bank lines, ${invoices.size} open invoices, ${labels.values.count { it != null }} true matches. Generated ${LocalDate.now()}.")
        report.appendLine()

        val rulesResult = Reconciler(RulesMatcher()).run(lines, invoices)
        val rules = Scorer.score("Rules only (exact reference and amount)", rulesResult, labels)
        val rows = mutableListOf(rules)
        val scenarioColumns = linkedMapOf("Rules" to rulesResult.accepted.associate { it.proposal.bankLineId to it.proposal.invoiceId })

        val cacheFile = File(outDir, "llm_cache.jsonl")
        val live = AnthropicClient.fromEnv()
        val cached = CachingLlmClient(live, cacheFile)
        val llmAvailable = live != null || cached.size > 0

        if (llmAvailable) {
            val matcher = LlmMatcher(cached)
            val reconciler = Reconciler(matcher)
            val proposals = matcher.propose(lines, invoices)
            val raw = Scorer.unverified(proposals)
            val verified = reconciler.resolve(proposals, lines, invoices)
            rows += Scorer.score("LLM proposals, unverified", raw, labels)
            rows += Scorer.score("LLM proposals + deterministic checks", verified, labels)
            scenarioColumns["LLM raw"] = raw
            scenarioColumns["LLM + checks"] = verified.accepted.associate { it.proposal.bankLineId to it.proposal.invoiceId }

            val gson = GsonBuilder().disableHtmlEscaping().create()
            val rejectionLog = File(outDir, "llm_rejections.jsonl")
            var caught = 0
            var blocked = 0
            rejectionLog.writeText("")
            verified.rejected.forEach { r ->
                val p = r.verified.proposal
                val correct = labels[p.bankLineId] == p.invoiceId
                if (r.reason != RejectionReason.CONFLICT) if (correct) blocked++ else caught++
                val record = JsonObject().apply {
                    addProperty("bank_line_id", p.bankLineId)
                    addProperty("invoice_id", p.invoiceId)
                    addProperty("confidence", p.confidence)
                    addProperty("rationale", p.rationale)
                    addProperty("reason", r.reason.name)
                    addProperty("note", r.note)
                    addProperty("label_invoice_id", labels[p.bankLineId] ?: "")
                    addProperty("scenario", scenarios[p.bankLineId] ?: "")
                    addProperty("verdict", if (correct) "verifier blocked a correct match" else "verifier caught a wrong match")
                    add("checks", JsonArray().apply {
                        r.verified.checks.forEach { c -> add(JsonObject().apply { addProperty("name", c.name); addProperty("passed", c.passed); addProperty("detail", c.detail) }) }
                    })
                }
                rejectionLog.appendText(gson.toJson(record) + "\n")
            }
            val errors = matcher.decisions.count { it.error != null }
            report.appendLine("Model: ${System.getenv("RECONPILOT_MODEL") ?: AnthropicClient.DEFAULT_MODEL}${if (live == null) " (replayed from cache)" else ""}. LLM errors: $errors.")
            report.appendLine()
            report.appendLine(Metrics.HEADER)
            rows.forEach { report.appendLine(it.row()) }
            report.appendLine()
            report.appendLine("The verifier rejected ${verified.rejected.size} LLM suggestions: $caught were genuinely wrong, $blocked were correct matches it was too strict on, and ${verified.rejected.count { it.reason == RejectionReason.CONFLICT }} lost a conflict. Every one is in `llm_rejections.jsonl`.")
        } else {
            report.appendLine("No ANTHROPIC_API_KEY set and no cached replies, so only the rules baseline ran.")
            report.appendLine()
            report.appendLine(Metrics.HEADER)
            rows.forEach { report.appendLine(it.row()) }
        }

        report.appendLine()
        report.appendLine("## Per scenario")
        report.appendLine()
        report.appendLine("Lines handled correctly (right invoice, or correctly left unmatched) out of total.")
        report.appendLine()
        report.appendLine("| Scenario | " + scenarioColumns.keys.joinToString(" | ") + " |")
        report.appendLine("|---|" + scenarioColumns.keys.joinToString("") { "---|" })
        val perColumn = scenarioColumns.mapValues { (_, m) -> Scorer.byScenario(m, labels, scenarios) }
        scenarios.values.toSortedSet().forEach { s ->
            report.appendLine("| $s | " + perColumn.values.joinToString(" | ") { val (hit, n) = it.getValue(s); "$hit / $n" } + " |")
        }

        File(outDir, "report.md").writeText(report.toString())
        println(report)
    }
}
