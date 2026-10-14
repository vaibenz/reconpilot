package dev.vaibenz.reconpilot.server

import dev.vaibenz.reconpilot.io.CsvFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.time.format.DateTimeParseException

fun main() {
    val service = ReviewService.fromEnvironment()
    val data = ReviewService.firstExisting(System.getenv("RECONPILOT_DATA"), "data", "../data")
    if (data != null) {
        service.loadCsv(File(data, "bank_lines.csv").readText(), File(data, "invoices.csv").readText())
    }
    val web = ReviewService.firstExisting(System.getenv("RECONPILOT_WEB"), "web/dist", "../web/dist")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(service, web) }.start(wait = true)
}

data class ErrorView(val error: String)

fun Application.module(service: ReviewService, webDir: File? = null) {
    install(CallLogging)
    install(ContentNegotiation) { gson { disableHtmlEscaping() } }
    install(StatusPages) {
        exception<CsvFormatException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ErrorView(cause.message ?: "bad csv")) }
        exception<DateTimeParseException> { call, cause -> call.respond(HttpStatusCode.BadRequest, ErrorView("bad date: ${cause.parsedString}")) }
        exception<NumberFormatException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorView("bad amount")) }
    }
    routing {
        route("/api") { api(service) }
        if (webDir != null) staticFiles("/", webDir)
    }
}

private fun Route.api(service: ReviewService) {
    get("/health") { call.respond(mapOf("status" to "ok")) }
    get("/summary") { call.respond(service.summary()) }
    get("/queue") { call.respond(service.queue()) }
    get("/blocked") { call.respond(service.blocked()) }
    get("/unmatched") { call.respond(service.unmatched()) }
    get("/export") { call.respondText(service.exportApproved(), ContentType.Text.CSV) }

    post("/proposals/{bankLineId}/{decision}") {
        val id = call.parameters["bankLineId"].orEmpty()
        val status = when (call.parameters["decision"]) {
            "approve" -> ReviewStatus.APPROVED
            "reject" -> ReviewStatus.REJECTED
            "reset" -> ReviewStatus.PENDING
            else -> return@post call.respond(HttpStatusCode.BadRequest, ErrorView("decision must be approve, reject or reset"))
        }
        if (service.decide(id, status)) call.respond(service.summary())
        else call.respond(HttpStatusCode.NotFound, ErrorView("no proposal for bank line $id"))
    }

    post("/reconcile") {
        val body = call.receive<ReconcileRequest>()
        val lines = body.bankLinesCsv
        val invoices = body.invoicesCsv
        if (lines.isNullOrBlank() || invoices.isNullOrBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorView("bankLinesCsv and invoicesCsv are both required"))
        }
        service.loadCsv(lines, invoices)
        call.respond(service.summary())
    }
}
