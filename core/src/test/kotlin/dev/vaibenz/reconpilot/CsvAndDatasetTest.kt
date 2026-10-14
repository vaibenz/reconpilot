package dev.vaibenz.reconpilot

import dev.vaibenz.reconpilot.eval.DatasetGenerator
import dev.vaibenz.reconpilot.io.Csv
import dev.vaibenz.reconpilot.io.CsvFormatException
import dev.vaibenz.reconpilot.verify.References
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvAndDatasetTest {

    @Test fun `csv handles quoted commas and escaped quotes`() {
        val rows = Csv.parse("a,b\n\"x, y\",\"say \"\"hi\"\"\"\n")
        assertEquals(mapOf("a" to "x, y", "b" to "say \"hi\""), rows.single())
    }

    @Test fun `csv round trips through write`() {
        val text = Csv.write(listOf("a", "b"), listOf(listOf("1,2", "q\"t")))
        assertEquals(mapOf("a" to "1,2", "b" to "q\"t"), Csv.parse(text).single())
    }

    @Test fun `ragged rows are reported with a line number`() {
        val error = runCatching { Csv.parse("a,b\n1\n") }.exceptionOrNull()
        assertTrue(error is CsvFormatException && error.message!!.contains("row 2"))
    }

    @Test fun `reference normalisation`() {
        assertEquals(setOf("INV48217"), References.mentioned("paid Invoice#048217 thanks"))
        assertEquals("INV48217", References.normalise("INV-48217"))
        assertEquals(setOf("KOPI", "LANE"), References.nameTokens("Kopi Lane Pte. Ltd."))
    }

    @Test fun `dataset generator is deterministic and internally consistent`() {
        val a = DatasetGenerator(7).generate()
        val b = DatasetGenerator(7).generate()
        assertEquals(a, b)
        val invoiceIds = a.invoices.map { it.id }.toSet()
        assertEquals(a.invoices.size, invoiceIds.size)
        assertEquals(a.invoices.size, a.invoices.map { it.number }.toSet().size)
        assertTrue(a.lines.mapNotNull { it.invoiceId }.all { it in invoiceIds })
    }
}

class GeminiParsingTest {
    @Test fun `joins text parts of the first candidate`() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"{\"invoice_id\":"},{"text":"\"I-1\"}"}]},"finishReason":"STOP"}]}"""
        assertEquals("{\"invoice_id\":\"I-1\"}", dev.vaibenz.reconpilot.llm.GeminiClient.extractText(json))
    }

    @Test fun `blocked replies raise instead of returning empty text`() {
        val blocked = runCatching { dev.vaibenz.reconpilot.llm.GeminiClient.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        assertTrue(blocked.exceptionOrNull() is dev.vaibenz.reconpilot.llm.LlmException)
    }
}
