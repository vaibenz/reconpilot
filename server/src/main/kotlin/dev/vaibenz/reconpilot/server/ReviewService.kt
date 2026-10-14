package dev.vaibenz.reconpilot.server

import dev.vaibenz.reconpilot.Reconciler
import dev.vaibenz.reconpilot.io.Csv
import dev.vaibenz.reconpilot.llm.AnthropicClient
import dev.vaibenz.reconpilot.llm.LlmMatcher
import dev.vaibenz.reconpilot.match.Matcher
import dev.vaibenz.reconpilot.match.RulesMatcher
import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.ReconciliationResult
import dev.vaibenz.reconpilot.model.VerifiedProposal
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ReviewStatus { PENDING, APPROVED, REJECTED }

data class CheckView(val name: String, val passed: Boolean, val detail: String)

data class BankLineView(val id: String, val date: String, val amount: String, val currency: String, val description: String, val counterparty: String)

data class InvoiceView(val id: String, val number: String, val customer: String, val issueDate: String, val dueDate: String, val amount: String, val currency: String)

data class ProposalView(
    val bankLine: BankLineView,
    val invoice: InvoiceView?,
    val source: String,
    val confidence: Double,
    val rationale: String,
    val checks: List<CheckView>,
    val status: String,
    val note: String? = null,
)

data class SummaryView(
    val mode: String,
    val bankLines: Int,
    val invoices: Int,
    val pending: Int,
    val approved: Int,
    val rejected: Int,
    val blockedByChecks: Int,
    val unmatched: Int,
)

data class ReconcileRequest(val bankLinesCsv: String?, val invoicesCsv: String?)

/** Holds the current reconciliation run and the human review decisions on top of it. */
class ReviewService(private val matcher: Matcher, val mode: String) {

    @Volatile private var lines: List<BankLine> = emptyList()
    @Volatile private var invoices: List<Invoice> = emptyList()
    @Volatile private var result = ReconciliationResult(emptyList(), emptyList(), emptyList())
    private val statuses = ConcurrentHashMap<String, ReviewStatus>()

    @Synchronized
    fun load(newLines: List<BankLine>, newInvoices: List<Invoice>) {
        val run = Reconciler(matcher).run(newLines, newInvoices)
        lines = newLines
        invoices = newInvoices
        result = run
        statuses.clear()
        run.accepted.forEach { statuses[it.proposal.bankLineId] = ReviewStatus.PENDING }
    }

    fun loadCsv(bankLinesCsv: String, invoicesCsv: String) = load(Csv.bankLines(bankLinesCsv), Csv.invoices(invoicesCsv))

    fun summary(): SummaryView = SummaryView(
        mode = mode,
        bankLines = lines.size,
        invoices = invoices.size,
        pending = statuses.values.count { it == ReviewStatus.PENDING },
        approved = statuses.values.count { it == ReviewStatus.APPROVED },
        rejected = statuses.values.count { it == ReviewStatus.REJECTED },
        blockedByChecks = result.rejected.size,
        unmatched = result.unmatchedBankLineIds.size,
    )

    fun queue(): List<ProposalView> = result.accepted.map { view(it, statuses[it.proposal.bankLineId] ?: ReviewStatus.PENDING, null) }

    fun blocked(): List<ProposalView> = result.rejected.map { view(it.verified, null, "${it.reason.name.lowercase().replace('_', ' ')}: ${it.note}") }

    fun unmatched(): List<BankLineView> {
        val byId = lines.associateBy { it.id }
        return result.unmatchedBankLineIds.mapNotNull { byId[it]?.let(::bankLineView) }
    }

    /** Returns false when there is no proposal for that bank line. */
    fun decide(bankLineId: String, status: ReviewStatus): Boolean {
        if (!statuses.containsKey(bankLineId)) return false
        statuses[bankLineId] = status
        return true
    }

    /** Approved matches as CSV, ready to post to the ledger. */
    fun exportApproved(): String {
        val approved = result.accepted.filter { statuses[it.proposal.bankLineId] == ReviewStatus.APPROVED }
        return Csv.write(
            listOf("bank_line_id", "invoice_id", "source", "confidence"),
            approved.map { listOf(it.proposal.bankLineId, it.proposal.invoiceId, it.proposal.source.name, it.proposal.confidence.toString()) },
        )
    }

    private fun view(v: VerifiedProposal, status: ReviewStatus?, note: String?): ProposalView {
        val line = lines.first { it.id == v.proposal.bankLineId }
        val invoice = invoices.firstOrNull { it.id == v.proposal.invoiceId }
        return ProposalView(
            bankLine = bankLineView(line),
            invoice = invoice?.let(::invoiceView),
            source = v.proposal.source.name,
            confidence = v.proposal.confidence,
            rationale = v.proposal.rationale,
            checks = v.checks.map { CheckView(it.name, it.passed, it.detail) },
            status = status?.name ?: "BLOCKED",
            note = note,
        )
    }

    private fun bankLineView(l: BankLine) = BankLineView(l.id, l.date.toString(), l.amount.toPlainString(), l.currency, l.description, l.counterparty)

    private fun invoiceView(i: Invoice) = InvoiceView(i.id, i.number, i.customer, i.issueDate.toString(), i.dueDate.toString(), i.amount.toPlainString(), i.currency)

    companion object {
        /** Uses the LLM when ANTHROPIC_API_KEY is set, otherwise falls back to the rules baseline. */
        fun fromEnvironment(): ReviewService {
            val client = AnthropicClient.fromEnv()
            return if (client != null) ReviewService(LlmMatcher(client), "llm") else ReviewService(RulesMatcher(), "rules")
        }

        fun firstExisting(vararg paths: String?): File? = paths.filterNotNull().map(::File).firstOrNull { it.exists() }
    }
}
