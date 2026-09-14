package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.module() {
    val geminiService = GeminiService()
    val organizeWeekService = OrganizeWeekService(geminiService)
    val aiProposalService = AiProposalService(geminiService)
    val capabilityService = AiCapabilityService(geminiService)
    val security = GatewaySecurity()
    val configurationReady = gatewayConfigurationReady()

    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val requestId = GatewaySecurity.requestId(call)
            GatewayObservability.failure(requestId, "unhandled", cause::class.simpleName ?: "error")
            call.respond(HttpStatusCode.InternalServerError, GatewayError("internal_error", "Internal server error", requestId))
        }
    }
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = false
            encodeDefaults = true
        })
    }

    routing {
        get("/health") { call.respond(HealthResponse(status = "ok")) }
        get("/ready") {
            if (configurationReady) {
                call.respond(HealthResponse(status = "ready"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "not_ready"))
            }
        }
        aiRoutes(geminiService, organizeWeekService, aiProposalService, capabilityService, security)
    }
}

private fun gatewayConfigurationReady(): Boolean {
    val environment = System.getenv("ENVIRONMENT")?.trim()?.lowercase() ?: "production"
    val project = System.getenv("GOOGLE_CLOUD_PROJECT")?.trim().orEmpty()
    val location = System.getenv("GOOGLE_CLOUD_LOCATION")?.trim().orEmpty()
    val model = System.getenv("GEMINI_MODEL")?.trim().orEmpty()
    val apiKey = System.getenv("GATEWAY_API_KEY")?.trim().orEmpty()
    return project.isNotBlank() && location.isNotBlank() && model.isNotBlank() &&
        (environment == "test" || apiKey.isNotBlank())
}

@Serializable
data class HealthResponse(val status: String)
