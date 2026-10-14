package dev.vaibenz.reconpilot.match

import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ProposalSource
import dev.vaibenz.reconpilot.verify.References
import java.math.BigDecimal
import java.time.LocalDate

/** Anything that can suggest which invoice each bank line pays. */
fun interface Matcher {
    fun propose(lines: List<BankLine>, invoices: List<Invoice>): List<MatchProposal>
}

/**
 * The baseline most finance teams start with: match only when the description
 * quotes the invoice number and the amount and currency are identical.
 * Very precise, but misses anything with a mangled reference or a bank fee.
 */
class RulesMatcher : Matcher {
    override fun propose(lines: List<BankLine>, invoices: List<Invoice>): List<MatchProposal> {
        val byRef = invoices.groupBy { References.normalise(it.number) }
        return lines.mapNotNull { line ->
            References.mentioned(line.description)
                .flatMap { byRef[it].orEmpty() }
                .firstOrNull { it.currency == line.currency && it.amount.compareTo(line.amount) == 0 }
                ?.let { MatchProposal(line.id, it.id, 1.0, "exact reference and amount", ProposalSource.RULES) }
        }
    }
}

/**
 * Narrows the invoice list for one bank line so the LLM sees a short, relevant shortlist
 * instead of the whole ledger. Deliberately looser than the verifier.
 */
class CandidateFinder(
    private val maxCandidates: Int = 8,
    private val amountBand: BigDecimal = BigDecimal("0.10"),
) {
    fun candidates(line: BankLine, invoices: List<Invoice>): List<Invoice> {
        val cited = References.mentioned(line.description)
        val payer = References.nameTokens(line.counterparty)
        return invoices
            .asSequence()
            .filter { it.currency == line.currency }
            .filter { withinBand(line.amount, it.amount) || References.normalise(it.number) in cited }
            .filter { !line.date.isBefore(it.issueDate.minusDays(7)) && !line.date.isAfter(it.dueDate.plusDays(120)) }
            .sortedWith(
                compareByDescending<Invoice> { References.normalise(it.number) in cited }
                    .thenByDescending { (References.nameTokens(it.customer) intersect payer).size }
                    .thenBy { it.amount.subtract(line.amount).abs() }
                    .thenBy { daysApart(line.date, it.dueDate) },
            )
            .take(maxCandidates)
            .toList()
    }

    private fun withinBand(paid: BigDecimal, owed: BigDecimal): Boolean {
        if (owed.signum() == 0) return false
        val diff = owed.subtract(paid).abs()
        return diff <= owed.multiply(amountBand)
    }

    private fun daysApart(a: LocalDate, b: LocalDate): Long = kotlin.math.abs(a.toEpochDay() - b.toEpochDay())
}
