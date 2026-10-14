package dev.vaibenz.reconpilot.verify

import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.CheckResult
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.VerifiedProposal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

data class VerifierConfig(
    /** Largest bank fee we tolerate, as a fraction of the invoice amount. */
    val maxFeeFraction: BigDecimal = BigDecimal("0.02"),
    /** Hard cap on the tolerated fee, in invoice currency. */
    val maxFeeAbsolute: BigDecimal = BigDecimal("50.00"),
    /** Rounding slack for overpayment. */
    val overpaymentSlack: BigDecimal = BigDecimal("0.01"),
    /** How long after the due date a payment can still plausibly belong to the invoice. */
    val graceDaysAfterDue: Long = 60,
)

/**
 * Deterministic gate that every proposal must pass, whoever made it.
 * The LLM is allowed to be creative about *which* invoice a payment belongs to,
 * but it never gets to override money, currency, dates or an explicit reference.
 */
class Verifier(private val config: VerifierConfig = VerifierConfig()) {

    fun verify(proposal: MatchProposal, line: BankLine, invoice: Invoice): VerifiedProposal =
        VerifiedProposal(
            proposal,
            listOf(
                currency(line, invoice),
                amount(line, invoice),
                dateWindow(line, invoice),
                reference(line, invoice),
                counterparty(line, invoice),
            ),
        )

    fun allowedFee(invoice: Invoice): BigDecimal =
        invoice.amount.multiply(config.maxFeeFraction)
            .setScale(2, RoundingMode.HALF_UP)
            .min(config.maxFeeAbsolute)

    private fun currency(line: BankLine, invoice: Invoice): CheckResult {
        val ok = line.currency.equals(invoice.currency, ignoreCase = true)
        return CheckResult("currency", ok, if (ok) line.currency else "bank ${line.currency} vs invoice ${invoice.currency}")
    }

    private fun amount(line: BankLine, invoice: Invoice): CheckResult {
        val shortfall = invoice.amount.subtract(line.amount)
        val fee = allowedFee(invoice)
        val ok = shortfall >= config.overpaymentSlack.negate() && shortfall <= fee
        val detail = when {
            shortfall.signum() == 0 -> "exact"
            ok -> "short by ${shortfall.toPlainString()} (fee allowance ${fee.toPlainString()})"
            shortfall.signum() < 0 -> "overpaid by ${shortfall.negate().toPlainString()}"
            else -> "short by ${shortfall.toPlainString()}, above fee allowance ${fee.toPlainString()}"
        }
        return CheckResult("amount", ok, detail)
    }

    private fun dateWindow(line: BankLine, invoice: Invoice): CheckResult {
        val latest = invoice.dueDate.plusDays(config.graceDaysAfterDue)
        val ok = !line.date.isBefore(invoice.issueDate) && !line.date.isAfter(latest)
        val detail = when {
            line.date.isBefore(invoice.issueDate) -> "paid ${ChronoUnit.DAYS.between(line.date, invoice.issueDate)} days before issue"
            line.date.isAfter(latest) -> "paid ${ChronoUnit.DAYS.between(invoice.dueDate, line.date)} days after due"
            else -> "within window"
        }
        return CheckResult("date", ok, detail)
    }

    private fun reference(line: BankLine, invoice: Invoice): CheckResult {
        val mentioned = References.mentioned(line.description)
        val target = References.normalise(invoice.number)
        return when {
            mentioned.isEmpty() -> CheckResult("reference", true, "no reference in description")
            target in mentioned -> CheckResult("reference", true, "description cites $target")
            else -> CheckResult("reference", false, "description cites ${mentioned.joinToString()}, not $target")
        }
    }

    /** Without a matching reference, the payer must at least look like the customer. */
    private fun counterparty(line: BankLine, invoice: Invoice): CheckResult {
        if (References.normalise(invoice.number) in References.mentioned(line.description)) {
            return CheckResult("counterparty", true, "reference match")
        }
        val overlap = References.nameTokens(line.counterparty) intersect References.nameTokens(invoice.customer)
        val ok = overlap.isNotEmpty()
        return CheckResult("counterparty", ok, if (ok) "shares ${overlap.sorted().joinToString()}" else "'${line.counterparty}' vs '${invoice.customer}'")
    }
}
