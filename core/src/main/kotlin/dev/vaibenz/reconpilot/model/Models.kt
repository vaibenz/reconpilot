package dev.vaibenz.reconpilot.model

import java.math.BigDecimal
import java.time.LocalDate

/** A single line from a bank statement: money that actually arrived. */
data class BankLine(
    val id: String,
    val date: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val counterparty: String,
)

/** An open receivable that we expect a customer to pay. */
data class Invoice(
    val id: String,
    val number: String,
    val customer: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
)

enum class ProposalSource { RULES, LLM }

/** A suggested pairing of a bank line with an invoice. Proposals are never trusted on their own. */
data class MatchProposal(
    val bankLineId: String,
    val invoiceId: String,
    val confidence: Double,
    val rationale: String,
    val source: ProposalSource,
)

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/** A proposal plus the outcome of every deterministic check run against it. */
data class VerifiedProposal(
    val proposal: MatchProposal,
    val checks: List<CheckResult>,
) {
    val passed: Boolean get() = checks.all { it.passed }
    val failedChecks: List<CheckResult> get() = checks.filterNot { it.passed }
}

/** Why a proposal did not make it into the review queue. */
enum class RejectionReason { FAILED_CHECKS, CONFLICT, UNKNOWN_ID }

data class RejectedProposal(
    val verified: VerifiedProposal,
    val reason: RejectionReason,
    val note: String,
)

data class ReconciliationResult(
    /** Proposals that passed every check and won any conflict. These go to a human for approval. */
    val accepted: List<VerifiedProposal>,
    /** Proposals the verifier or conflict resolver threw out, kept for auditing. */
    val rejected: List<RejectedProposal>,
    /** Bank lines that ended up with no accepted proposal. */
    val unmatchedBankLineIds: List<String>,
)
