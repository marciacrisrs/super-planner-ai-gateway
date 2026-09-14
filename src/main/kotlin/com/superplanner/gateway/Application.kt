package com.superplanner.gateway

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Application.module() {
    val geminiService = GeminiService()
    val organizeWeekService = OrganizeWeekService(geminiService)
    val aiProposalService = AiProposalService(geminiService)

    install(CallLogging)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = false
            encodeDefaults = true
        })
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        get("/ready") {
            call.respond(HealthResponse(status = "ready"))
        }

        aiRoutes(geminiService, organizeWeekService, aiProposalService)
    }
}

@Serializable
data class HealthResponse(val status: String)
