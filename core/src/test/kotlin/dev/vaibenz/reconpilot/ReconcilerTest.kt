package dev.vaibenz.reconpilot

import dev.vaibenz.reconpilot.io.Csv
import dev.vaibenz.reconpilot.llm.LlmClient
import dev.vaibenz.reconpilot.llm.LlmMatcher
import dev.vaibenz.reconpilot.match.RulesMatcher
import dev.vaibenz.reconpilot.model.MatchProposal
import dev.vaibenz.reconpilot.model.ProposalSource
import dev.vaibenz.reconpilot.model.RejectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilerTest {

    private val invoices = Csv.invoices(
        """
        id,number,customer,issue_date,due_date,amount,currency
        I-1,INV-48217,Kopi Lane Pte. Ltd.,2026-07-01,2026-07-31,1200.00,SGD
        I-2,INV-51003,Harbourline Logistics Pte Ltd,2026-07-05,2026-08-04,860.40,SGD
        I-3,INV-62290,"Batik Studio Sdn Bhd",2026-07-10,2026-08-09,3100.00,MYR
        """.trimIndent(),
    )

    private val lines = Csv.bankLines(
        """
        id,date,amount,currency,description,counterparty
        B-1,2026-07-28,1200.00,SGD,FAST INV-48217,KOPI LANE PTE LTD
        B-2,2026-08-01,845.40,SGD,TT INV-51003 LESS CHARGES,HARBOURLINE LOGISTICS
        B-3,2026-08-03,1550.00,MYR,IBG INV-62290 PART PAYMENT,BATIK STUDIO
        B-4,2026-08-03,99.00,SGD,INTEREST CREDIT,DBS BANK LTD
        """.trimIndent(),
    )

    /** Fake model that answers from a fixed script keyed on a phrase in the prompt. */
    private fun scripted(vararg replies: Pair<String, String>) = LlmClient { _, user ->
        replies.firstOrNull { user.contains(it.first) }?.second ?: """{"invoice_id": null, "confidence": 0.1, "rationale": "nothing fits"}"""
    }

    @Test fun `rules baseline only takes exact reference and amount`() {
        val result = Reconciler(RulesMatcher()).run(lines, invoices)
        assertEquals(listOf("B-1" to "I-1"), result.accepted.map { it.proposal.bankLineId to it.proposal.invoiceId })
    }

    @Test fun `llm finds the fee case and the verifier blocks the partial payment`() {
        val client = scripted(
            "FAST INV-48217" to """{"invoice_id": "I-1", "confidence": 0.98, "rationale": "reference and amount match"}""",
            "LESS CHARGES" to "```json\n{\"invoice_id\": \"I-2\", \"confidence\": 0.9, \"rationale\": \"fee deducted\"}\n```",
            "PART PAYMENT" to """{"invoice_id": "I-3", "confidence": 0.7, "rationale": "same customer"}""",
        )
        val result = Reconciler(LlmMatcher(client, parallelism = 1)).run(lines, invoices)

        assertEquals(setOf("B-1" to "I-1", "B-2" to "I-2"), result.accepted.map { it.proposal.bankLineId to it.proposal.invoiceId }.toSet())
        val partial = result.rejected.single { it.verified.proposal.bankLineId == "B-3" }
        assertEquals(RejectionReason.FAILED_CHECKS, partial.reason)
        assertTrue(partial.verified.failedChecks.any { it.name == "amount" })
        assertTrue("B-4" in result.unmatchedBankLineIds)
    }

    @Test fun `hallucinated invoice ids are rejected, not trusted`() {
        val client = scripted("FAST INV-48217" to """{"invoice_id": "I-999", "confidence": 0.99, "rationale": "made up"}""")
        val result = Reconciler(LlmMatcher(client, parallelism = 1)).run(lines, invoices)
        assertTrue(result.accepted.isEmpty())
        assertEquals(RejectionReason.UNKNOWN_ID, result.rejected.single().reason)
    }

    @Test fun `two lines claiming one invoice keeps the more confident one`() {
        val repeatPayment = lines.first().copy(id = "B-5", description = "FAST TRANSFER KOPI LANE")
        val all = lines + repeatPayment
        val proposals = listOf(
            MatchProposal("B-5", "I-1", 0.6, "same payer and amount", ProposalSource.LLM),
            MatchProposal("B-1", "I-1", 0.95, "reference and amount", ProposalSource.LLM),
        )
        val result = Reconciler(RulesMatcher()).resolve(proposals, all, invoices)
        assertEquals(listOf("B-1" to "I-1"), result.accepted.map { it.proposal.bankLineId to it.proposal.invoiceId })
        assertEquals(RejectionReason.CONFLICT, result.rejected.single().reason)
    }

    @Test fun `parser tolerates prose around the json and rejects garbage`() {
        assertEquals("I-2", LlmMatcher.parse("Sure! {\"invoice_id\": \"I-2\", \"confidence\": 1.4, \"rationale\": \"x\"} hope that helps")?.invoiceId)
        assertEquals(1.0, LlmMatcher.parse("{\"invoice_id\": \"I-2\", \"confidence\": 1.4}")?.confidence)
        assertNull(LlmMatcher.parse("{\"invoice_id\": null}")?.invoiceId)
        assertNull(LlmMatcher.parse("no json here"))
    }
}
