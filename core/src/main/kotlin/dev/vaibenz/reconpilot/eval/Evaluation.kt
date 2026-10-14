package dev.vaibenz.reconpilot.eval

import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ReconciliationResult
import java.util.Locale

data class Metrics(
    val name: String,
    val proposed: Int,
    val correct: Int,
    val positives: Int,
) {
    val precision: Double get() = if (proposed == 0) 1.0 else correct.toDouble() / proposed
    val recall: Double get() = if (positives == 0) 1.0 else correct.toDouble() / positives
    val f1: Double get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    val wrong: Int get() = proposed - correct

    fun row(): String = String.format(
        Locale.ROOT, "| %s | %d | %d | %d | %.3f | %.3f | %.3f |",
        name, proposed, correct, wrong, precision, recall, f1,
    )

    companion object {
        const val HEADER = "| Pipeline | Matches made | Correct | Wrong | Precision | Recall | F1 |\n|---|---|---|---|---|---|---|"
    }
}

/** Scores matches against ground truth. A match counts only if it names exactly the labelled invoice. */
object Scorer {

    fun score(name: String, matches: Map<String, String>, labels: Map<String, String?>): Metrics {
        val positives = labels.values.count { it != null }
        val correct = matches.count { (line, invoice) -> labels[line] == invoice }
        return Metrics(name, matches.size, correct, positives)
    }

    fun score(name: String, result: ReconciliationResult, labels: Map<String, String?>): Metrics =
        score(name, result.accepted.associate { it.proposal.bankLineId to it.proposal.invoiceId }, labels)

    /** Raw proposals with no verification: keep the most confident one per bank line and per invoice. */
    fun unverified(proposals: List<MatchProposal>): Map<String, String> {
        val usedInvoices = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        proposals.sortedByDescending { it.confidence }.forEach {
            if (it.bankLineId !in out && usedInvoices.add(it.invoiceId)) out[it.bankLineId] = it.invoiceId
        }
        return out
    }

    /** Recall per scenario, to show where each pipeline wins and loses. */
    fun byScenario(matches: Map<String, String>, labels: Map<String, String?>, scenarios: Map<String, String>): Map<String, Pair<Int, Int>> =
        scenarios.entries.groupBy({ it.value }, { it.key }).mapValues { (_, lines) ->
            val hits = lines.count { line ->
                val truth = labels[line]
                val got = matches[line]
                if (truth == null) got == null else got == truth
            }
            hits to lines.size
        }.toSortedMap()
}
