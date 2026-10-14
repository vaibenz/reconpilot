package dev.vaibenz.reconpilot

import dev.vaibenz.reconpilot.match.Matcher
import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.CheckResult
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ReconciliationResult
import dev.vaibenz.reconpilot.model.RejectedProposal
import dev.vaibenz.reconpilot.model.RejectionReason
import dev.vaibenz.reconpilot.model.VerifiedProposal
import dev.vaibenz.reconpilot.verify.Verifier

/**
 * Runs a matcher, then puts every proposal through the verifier and a one to one assignment.
 * Nothing reaches the review queue without passing every deterministic check.
 */
class Reconciler(
    private val matcher: Matcher,
    private val verifier: Verifier = Verifier(),
) {
    fun run(lines: List<BankLine>, invoices: List<Invoice>): ReconciliationResult =
        resolve(matcher.propose(lines, invoices), lines, invoices)

    fun resolve(proposals: List<MatchProposal>, lines: List<BankLine>, invoices: List<Invoice>): ReconciliationResult {
        val lineById = lines.associateBy { it.id }
        val invoiceById = invoices.associateBy { it.id }
        val rejected = mutableListOf<RejectedProposal>()
        val passing = mutableListOf<VerifiedProposal>()

        for (p in proposals) {
            val line = lineById[p.bankLineId]
            val invoice = invoiceById[p.invoiceId]
            if (line == null || invoice == null) {
                val missing = if (line == null) "bank line ${p.bankLineId}" else "invoice ${p.invoiceId}"
                rejected += RejectedProposal(
                    VerifiedProposal(p, listOf(CheckResult("exists", false, "$missing does not exist"))),
                    RejectionReason.UNKNOWN_ID,
                    "proposal referenced $missing",
                )
                continue
            }
            val verified = verifier.verify(p, line, invoice)
            if (verified.passed) {
                passing += verified
            } else {
                rejected += RejectedProposal(
                    verified,
                    RejectionReason.FAILED_CHECKS,
                    verified.failedChecks.joinToString("; ") { "${it.name}: ${it.detail}" },
                )
            }
        }

        // Highest confidence wins when two proposals want the same bank line or invoice.
        val usedLines = HashSet<String>()
        val usedInvoices = HashSet<String>()
        val accepted = mutableListOf<VerifiedProposal>()
        for (v in passing.sortedWith(compareByDescending<VerifiedProposal> { it.proposal.confidence }.thenBy { it.proposal.bankLineId })) {
            val p = v.proposal
            when {
                p.bankLineId in usedLines ->
                    rejected += RejectedProposal(v, RejectionReason.CONFLICT, "bank line already matched with higher confidence")
                p.invoiceId in usedInvoices ->
                    rejected += RejectedProposal(v, RejectionReason.CONFLICT, "invoice already matched with higher confidence")
                else -> {
                    accepted += v
                    usedLines += p.bankLineId
                    usedInvoices += p.invoiceId
                }
            }
        }

        val unmatched = lines.map { it.id }.filterNot { it in usedLines }
        return ReconciliationResult(accepted.sortedBy { it.proposal.bankLineId }, rejected, unmatched)
    }
}
