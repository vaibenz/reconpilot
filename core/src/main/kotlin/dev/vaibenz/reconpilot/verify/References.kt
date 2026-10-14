package dev.vaibenz.reconpilot.verify

/** Helpers for pulling invoice references out of free text bank descriptions. */
object References {
    private val invoiceRef = Regex("""\bINV(?:OICE)?[\s#:/\-.]*0*(\d{3,})\b""", RegexOption.IGNORE_CASE)

    /** "INV-01042", "inv 1042" and "INVOICE#1042" all normalise to "INV1042". */
    fun normalise(raw: String): String {
        val m = invoiceRef.find(raw) ?: return raw.uppercase().filter { it.isLetterOrDigit() }
        return "INV" + m.groupValues[1]
    }

    /** Every invoice reference mentioned in a description, normalised. */
    fun mentioned(description: String): Set<String> =
        invoiceRef.findAll(description).map { "INV" + it.groupValues[1] }.toSet()

    private val noise = setOf("PTE", "LTD", "LIMITED", "SDN", "BHD", "PT", "TBK", "INC", "LLC", "CO", "CORP", "THE")

    /** Name tokens with legal suffixes and punctuation stripped, so "Acme Pte. Ltd." matches "ACME PTE LTD". */
    fun nameTokens(name: String): Set<String> =
        name.uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.length > 1 && it !in noise }
            .toSet()
}
