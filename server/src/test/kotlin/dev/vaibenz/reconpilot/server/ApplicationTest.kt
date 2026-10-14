package dev.vaibenz.reconpilot.server

import dev.vaibenz.reconpilot.match.RulesMatcher
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    private val bankCsv = "id,date,amount,currency,description,counterparty\\n" +
        "B-1,2026-07-28,1200.00,SGD,FAST INV-48217,KOPI LANE PTE LTD\\n" +
        "B-2,2026-08-03,99.00,SGD,INTEREST CREDIT,DBS BANK LTD\\n"
    private val invoiceCsv = "id,number,customer,issue_date,due_date,amount,currency\\n" +
        "I-1,INV-48217,Kopi Lane Pte. Ltd.,2026-07-01,2026-07-31,1200.00,SGD\\n"

    private fun service() = ReviewService(RulesMatcher(), "rules").apply {
        loadCsv(bankCsv.replace("\\n", "\n"), invoiceCsv.replace("\\n", "\n"))
    }

    @Test fun `summary reports the queue`() = testApplication {
        application { module(service()) }
        val res = client.get("/api/summary")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body.contains("\"pending\":1"), body)
        assertTrue(body.contains("\"unmatched\":1"), body)
    }

    @Test fun `approving moves a proposal out of pending and into the export`() = testApplication {
        application { module(service()) }
        assertEquals(HttpStatusCode.OK, client.post("/api/proposals/B-1/approve").status)
        assertTrue(client.get("/api/summary").bodyAsText().contains("\"approved\":1"))
        assertTrue(client.get("/api/export").bodyAsText().contains("B-1,I-1,RULES"))
    }

    @Test fun `unknown proposals and decisions are rejected`() = testApplication {
        application { module(service()) }
        assertEquals(HttpStatusCode.NotFound, client.post("/api/proposals/B-9/approve").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/proposals/B-1/maybe").status)
    }

    @Test fun `reconcile accepts new csv and rejects malformed csv`() = testApplication {
        application { module(service()) }
        val ok = client.post("/api/reconcile") {
            contentType(ContentType.Application.Json)
            setBody("""{"bankLinesCsv": "$bankCsv", "invoicesCsv": "$invoiceCsv"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())

        val bad = client.post("/api/reconcile") {
            contentType(ContentType.Application.Json)
            setBody("{\"bankLinesCsv\": \"id,date\\nB-1\", \"invoicesCsv\": \"$invoiceCsv\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }
}
