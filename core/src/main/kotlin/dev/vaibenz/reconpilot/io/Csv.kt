package dev.vaibenz.reconpilot.io

import dev.vaibenz.reconpilot.model.BankLine
import dev.vaibenz.reconpilot.model.Invoice
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

class CsvFormatException(message: String) : IllegalArgumentException(message)

/** Small RFC 4180 style reader and writer. Handles quoted fields, escaped quotes and commas inside quotes. */
object Csv {

    fun parse(text: String): List<Map<String, String>> {
        val rows = rows(text).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        return rows.drop(1).mapIndexed { i, row ->
            if (row.size != header.size) throw CsvFormatException("row ${i + 2} has ${row.size} fields, expected ${header.size}")
            header.zip(row).toMap()
        }
    }

    private fun rows(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row += field.toString(); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    out += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (inQuotes) throw CsvFormatException("unterminated quoted field")
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); out += row }
        return out
    }

    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun write(header: List<String>, rows: List<List<String>>): String = buildString {
        appendLine(header.joinToString(","))
        rows.forEach { appendLine(it.joinToString(",") { v -> escape(v) }) }
    }

    private fun Map<String, String>.req(key: String): String =
        this[key]?.trim() ?: throw CsvFormatException("missing column '$key'")

    fun bankLines(text: String): List<BankLine> = parse(text).map {
        BankLine(
            id = it.req("id"),
            date = LocalDate.parse(it.req("date")),
            amount = BigDecimal(it.req("amount")),
            currency = it.req("currency").uppercase(),
            description = it.req("description"),
            counterparty = it.req("counterparty"),
        )
    }

    fun invoices(text: String): List<Invoice> = parse(text).map {
        Invoice(
            id = it.req("id"),
            number = it.req("number"),
            customer = it.req("customer"),
            issueDate = LocalDate.parse(it.req("issue_date")),
            dueDate = LocalDate.parse(it.req("due_date")),
            amount = BigDecimal(it.req("amount")),
            currency = it.req("currency").uppercase(),
        )
    }

    /** Ground truth: bank_line_id to invoice_id, with a blank invoice for lines that match nothing. */
    fun labels(text: String): Map<String, String?> =
        parse(text).associate { it.req("bank_line_id") to it.req("invoice_id").ifEmpty { null } }

    fun readBankLines(file: File) = bankLines(file.readText())
    fun readInvoices(file: File) = invoices(file.readText())
    fun readLabels(file: File) = labels(file.readText())
}
