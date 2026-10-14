package dev.vaibenz.reconpilot

import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.Invoice
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ProposalSource
import dev.vaibenz.reconpilot.verify.Verifier
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifierTest {
    private val verifier = Verifier()

    private val invoice = Invoice(
        id = "I-1", number = "INV-48217", customer = "Kopi Lane Pte. Ltd.",
        issueDate = LocalDate.of(2026, 7, 1), dueDate = LocalDate.of(2026, 7, 31),
        amount = BigDecimal("1200.00"), currency = "SGD",
    )

    private fun line(
        amount: String = "1200.00",
        currency: String = "SGD",
        date: LocalDate = LocalDate.of(2026, 7, 28),
        description: String = "FAST INV-48217",
        counterparty: String = "KOPI LANE PTE LTD",
    ) = BankLine("B-1", date, BigDecimal(amount), currency, description, counterparty)

    private fun check(l: BankLine) = verifier.verify(MatchProposal(l.id, invoice.id, 0.9, "", ProposalSource.LLM), l, invoice)

    @Test fun `exact match passes every check`() = assertTrue(check(line()).passed)

    @Test fun `bank fee within allowance passes`() = assertTrue(check(line(amount = "1180.00")).passed)

    @Test fun `shortfall above fee allowance fails amount`() {
        val v = check(line(amount = "600.00"))
        assertEquals(listOf("amount"), v.failedChecks.map { it.name })
    }

    @Test fun `overpayment fails`() = assertFalse(check(line(amount = "1250.00")).passed)

    @Test fun `different currency fails`() = assertEquals(listOf("currency"), check(line(currency = "USD")).failedChecks.map { it.name })

    @Test fun `payment before issue date fails`() =
        assertEquals(listOf("date"), check(line(date = LocalDate.of(2026, 6, 20))).failedChecks.map { it.name })

    @Test fun `description citing another invoice fails reference`() =
        assertEquals(listOf("reference"), check(line(description = "FAST INV-48218")).failedChecks.map { it.name })

    @Test fun `reformatted reference is still recognised`() = assertTrue(check(line(description = "giro inv 048217")).passed)

    @Test fun `no reference needs a payer that looks like the customer`() {
        assertTrue(check(line(description = "TRANSFER", counterparty = "Kopi Lane")).passed)
        assertEquals(listOf("counterparty"), check(line(description = "TRANSFER", counterparty = "STRIPE PAYMENTS")).failedChecks.map { it.name })
    }

    @Test fun `fee allowance is capped`() {
        val big = invoice.copy(amount = BigDecimal("100000.00"))
        assertEquals(BigDecimal("50.00"), verifier.allowedFee(big))
    }
}
