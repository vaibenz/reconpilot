package dev.vaibenz.reconpilot.eval

import dev.vaibenz.reconpilot.io.Csv
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.random.Random

/**
 * Builds a labelled reconciliation dataset with the mess real bank feeds have:
 * reformatted references, missing references, bank fees, partial payments,
 * wrong currency, look alike invoices and unrelated noise.
 * Seeded, so the same seed always produces the same files.
 */
class DatasetGenerator(seed: Long = 42) {
    private val rnd = Random(seed)

    enum class Scenario(val labelled: Boolean) {
        CLEAN(true),
        REF_REFORMATTED(true),
        REF_NUMBER_ONLY(true),
        FEE_DEDUCTED(true),
        NAME_ONLY(true),
        TRUNCATED_REF(true),
        AMBIGUOUS_TWIN(true),
        PARTIAL_PAYMENT(false),
        WRONG_CURRENCY(false),
        UNRELATED(false),
    }

    data class Customer(val legal: String, val bankStyle: List<String>)

    private val customers = listOf(
        Customer("Kopi Lane Pte. Ltd.", listOf("KOPI LANE PTE LTD", "KOPI LANE", "Kopi Lane Pte Ltd")),
        Customer("Harbourline Logistics Pte Ltd", listOf("HARBOURLINE LOGISTICS", "HARBOURLINE LOG PTE", "Harbourline Logistics")),
        Customer("Batik Studio Sdn Bhd", listOf("BATIK STUDIO SDN BHD", "BATIK STUDIO", "Batik Studio S/B")),
        Customer("Northwind Robotics Pte. Ltd.", listOf("NORTHWIND ROBOTICS", "NORTHWIND ROBOTICS PTE", "Northwind Robotics")),
        Customer("Teman Dagang Trading", listOf("TEMAN DAGANG TRADING", "TEMAN DAGANG", "Teman Dagang Trd")),
        Customer("Lumen Dental Clinic Pte Ltd", listOf("LUMEN DENTAL CLINIC", "LUMEN DENTAL", "Lumen Dental Clinic")),
        Customer("Sungai Fresh Produce Sdn Bhd", listOf("SUNGAI FRESH PRODUCE", "SUNGAI FRESH", "Sungai Fresh Produce SB")),
        Customer("Orbit Print House", listOf("ORBIT PRINT HOUSE", "ORBIT PRINT", "Orbit Print Hse")),
        Customer("Marina Events Co.", listOf("MARINA EVENTS CO", "MARINA EVENTS", "Marina Events")),
        Customer("Pacific Crate Supply Pty Ltd", listOf("PACIFIC CRATE SUPPLY", "PACIFIC CRATE", "Pacific Crate Supply Pty")),
        Customer("Bluegum Analytics Pty Ltd", listOf("BLUEGUM ANALYTICS", "BLUEGUM ANALYTICS PTY", "Bluegum Analytics")),
        Customer("Jalan Besar Bikes", listOf("JALAN BESAR BIKES", "JLN BESAR BIKES", "Jalan Besar Bikes")),
    )
    private val currencies = listOf("SGD", "SGD", "SGD", "MYR", "USD", "AUD")
    private val unrelatedPayers = listOf(
        "DBS BANK LTD" to "INTEREST CREDIT",
        "OWN ACCOUNT" to "TRANSFER FROM SAVINGS",
        "STRIPE PAYMENTS" to "PAYOUT BATCH",
        "IRAS" to "GST REFUND",
        "OFFICE LANDLORD PTE LTD" to "DEPOSIT REFUND",
        "CPF BOARD" to "WAGE CREDIT SCHEME",
    )

    data class InvoiceRow(val id: String, val number: String, val customer: Customer, val issue: LocalDate, val due: LocalDate, val amount: BigDecimal, val currency: String)
    data class LineRow(val id: String, val date: LocalDate, val amount: BigDecimal, val currency: String, val description: String, val counterparty: String, val invoiceId: String?, val scenario: Scenario)

    data class Dataset(val invoices: List<InvoiceRow>, val lines: List<LineRow>)

    private var invoiceSeq = 0
    private val usedNumbers = HashSet<Int>()
    private var lineSeq = 0
    private val start = LocalDate.of(2026, 6, 1)

    private fun money(min: Int, max: Int): BigDecimal =
        BigDecimal(rnd.nextInt(min * 100, max * 100)).movePointLeft(2)

    private fun newInvoice(customer: Customer = customers.random(rnd), currency: String = currencies.random(rnd), amount: BigDecimal = money(180, 9_000)): InvoiceRow {
        invoiceSeq++
        var number: Int
        do { number = rnd.nextInt(20_000, 99_999) } while (!usedNumbers.add(number))
        val issue = start.plusDays(rnd.nextLong(0, 75))
        return InvoiceRow("I-%04d".format(invoiceSeq), "INV-$number", customer, issue, issue.plusDays(listOf(14L, 30L, 30L, 45L).random(rnd)), amount, currency)
    }

    private fun payDate(inv: InvoiceRow): LocalDate = inv.due.plusDays(rnd.nextLong(-12, 20)).let { if (it.isBefore(inv.issue)) inv.issue.plusDays(1) else it }

    private fun line(date: LocalDate, amount: BigDecimal, currency: String, description: String, counterparty: String, invoiceId: String?, scenario: Scenario): LineRow {
        lineSeq++
        return LineRow("B-%04d".format(lineSeq), date, amount, currency, description, counterparty, invoiceId, scenario)
    }

    private fun digits(inv: InvoiceRow) = inv.number.removePrefix("INV-")
    private fun payer(inv: InvoiceRow) = inv.customer.bankStyle.random(rnd)
    private val channels = listOf("GIRO", "FAST", "TT", "PAYNOW", "IBG", "WIRE")

    fun generate(counts: Map<Scenario, Int> = defaultCounts): Dataset {
        val invoices = mutableListOf<InvoiceRow>()
        val lines = mutableListOf<LineRow>()

        fun add(inv: InvoiceRow) = inv.also { invoices += it }

        repeat(counts.getValue(Scenario.CLEAN)) {
            val inv = add(newInvoice())
            lines += line(payDate(inv), inv.amount, inv.currency, "${channels.random(rnd)} ${inv.number} ${payer(inv)}", payer(inv), inv.id, Scenario.CLEAN)
        }
        repeat(counts.getValue(Scenario.REF_REFORMATTED)) {
            val inv = add(newInvoice())
            val ref = listOf("inv${digits(inv)}", "INV ${digits(inv)}", "Invoice#${digits(inv)}", "INV/0${digits(inv)}").random(rnd)
            lines += line(payDate(inv), inv.amount, inv.currency, "${channels.random(rnd)} $ref", payer(inv), inv.id, Scenario.REF_REFORMATTED)
        }
        repeat(counts.getValue(Scenario.REF_NUMBER_ONLY)) {
            val inv = add(newInvoice())
            val ref = listOf("#${digits(inv)}", "REF ${digits(inv)}", "bill ${digits(inv)}").random(rnd)
            lines += line(payDate(inv), inv.amount, inv.currency, "${channels.random(rnd)} ${payer(inv)} $ref", payer(inv), inv.id, Scenario.REF_NUMBER_ONLY)
        }
        repeat(counts.getValue(Scenario.FEE_DEDUCTED)) {
            val inv = add(newInvoice())
            val fee = listOf(BigDecimal("8.00"), BigDecimal("12.50"), BigDecimal("20.00"), BigDecimal("25.00"))
                .random(rnd).min(inv.amount.multiply(BigDecimal("0.015")).setScale(2, RoundingMode.HALF_UP))
            lines += line(payDate(inv), inv.amount.subtract(fee), inv.currency, "TT ${inv.number} LESS CHARGES", payer(inv), inv.id, Scenario.FEE_DEDUCTED)
        }
        repeat(counts.getValue(Scenario.NAME_ONLY)) {
            val inv = add(newInvoice())
            lines += line(payDate(inv), inv.amount, inv.currency, "${channels.random(rnd)} TRANSFER ${payer(inv)}", payer(inv), inv.id, Scenario.NAME_ONLY)
        }
        repeat(counts.getValue(Scenario.TRUNCATED_REF)) {
            val inv = add(newInvoice())
            lines += line(payDate(inv), inv.amount, inv.currency, "${channels.random(rnd)} ${payer(inv)} ${inv.number.dropLast(1)}", payer(inv), inv.id, Scenario.TRUNCATED_REF)
        }
        repeat(counts.getValue(Scenario.AMBIGUOUS_TWIN)) {
            val customer = customers.random(rnd)
            val currency = currencies.random(rnd)
            val amount = money(300, 3_000)
            val paid = add(newInvoice(customer, currency, amount))
            add(newInvoice(customer, currency, amount)) // a twin with the same customer and amount
            lines += line(payDate(paid), amount, currency, "${channels.random(rnd)} TRANSFER ${customer.bankStyle.random(rnd)}", customer.bankStyle.random(rnd), paid.id, Scenario.AMBIGUOUS_TWIN)
        }
        repeat(counts.getValue(Scenario.PARTIAL_PAYMENT)) {
            val inv = add(newInvoice())
            val part = inv.amount.multiply(listOf(BigDecimal("0.5"), BigDecimal("0.3"), BigDecimal("0.4")).random(rnd)).setScale(2, RoundingMode.HALF_UP)
            lines += line(payDate(inv), part, inv.currency, "${channels.random(rnd)} ${inv.number} PART PAYMENT", payer(inv), null, Scenario.PARTIAL_PAYMENT)
        }
        repeat(counts.getValue(Scenario.WRONG_CURRENCY)) {
            val inv = add(newInvoice(currency = "SGD"))
            lines += line(payDate(inv), inv.amount, "USD", "WIRE ${inv.number}", payer(inv), null, Scenario.WRONG_CURRENCY)
        }
        repeat(counts.getValue(Scenario.UNRELATED)) {
            val (who, what) = unrelatedPayers.random(rnd)
            lines += line(start.plusDays(rnd.nextLong(10, 110)), money(20, 6_000), currencies.random(rnd), what, who, null, Scenario.UNRELATED)
        }
        // Open invoices nobody has paid yet, so the matcher has to ignore plausible decoys.
        repeat(counts.values.sum() / 3) { add(newInvoice()) }

        // Renumber ids in shuffled order so they leak nothing about the scenario or about twin invoices.
        val shuffledInvoices = invoices.shuffled(rnd)
        val newInvoiceId = shuffledInvoices.mapIndexed { i, inv -> inv.id to "I-%04d".format(i + 1) }.toMap()
        val finalInvoices = shuffledInvoices.map { it.copy(id = newInvoiceId.getValue(it.id)) }.sortedBy { it.id }
        val finalLines = lines.shuffled(rnd).mapIndexed { i, l ->
            l.copy(id = "B-%04d".format(i + 1), invoiceId = l.invoiceId?.let { newInvoiceId.getValue(it) })
        }
        return Dataset(finalInvoices, finalLines)
    }

    companion object {
        val defaultCounts = mapOf(
            Scenario.CLEAN to 40,
            Scenario.REF_REFORMATTED to 20,
            Scenario.REF_NUMBER_ONLY to 15,
            Scenario.FEE_DEDUCTED to 20,
            Scenario.NAME_ONLY to 20,
            Scenario.TRUNCATED_REF to 8,
            Scenario.AMBIGUOUS_TWIN to 8,
            Scenario.PARTIAL_PAYMENT to 10,
            Scenario.WRONG_CURRENCY to 6,
            Scenario.UNRELATED to 25,
        )

        fun writeTo(dir: File, data: Dataset) {
            dir.mkdirs()
            File(dir, "invoices.csv").writeText(
                Csv.write(
                    listOf("id", "number", "customer", "issue_date", "due_date", "amount", "currency"),
                    data.invoices.map { listOf(it.id, it.number, it.customer.legal, it.issue.toString(), it.due.toString(), it.amount.toPlainString(), it.currency) },
                ),
            )
            File(dir, "bank_lines.csv").writeText(
                Csv.write(
                    listOf("id", "date", "amount", "currency", "description", "counterparty"),
                    data.lines.map { listOf(it.id, it.date.toString(), it.amount.toPlainString(), it.currency, it.description, it.counterparty) },
                ),
            )
            File(dir, "labels.csv").writeText(
                Csv.write(
                    listOf("bank_line_id", "invoice_id", "scenario"),
                    data.lines.map { listOf(it.id, it.invoiceId ?: "", it.scenario.name) },
                ),
            )
        }

        @JvmStatic
        fun main(args: Array<String>) {
            val dir = File(args.getOrElse(0) { "data" })
            val seed = args.getOrNull(1)?.toLong() ?: 42L
            val data = DatasetGenerator(seed).generate()
            writeTo(dir, data)
            println("Wrote ${data.invoices.size} invoices and ${data.lines.size} bank lines to ${dir.path}")
        }
    }
}
