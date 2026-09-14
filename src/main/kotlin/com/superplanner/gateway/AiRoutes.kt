package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class GenerateAiRequest(
    val prompt: String
)

@Serializable
data class GenerateAiResponse(
    val text: String,
    val model: String
)

fun Route.aiRoutes(
    geminiService: GeminiService,
    organizeWeekService: OrganizeWeekService,
    aiProposalService: AiProposalService,
) {
    post("/v1/ai/generate") {
        val request = call.receive<GenerateAiRequest>()
        if (request.prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "prompt must not be blank"))
            return@post
        }

        val text = geminiService.generate(request.prompt)
        call.respond(
            GenerateAiResponse(
                text = text,
                model = geminiService.modelName
            )
        )
    }

    post("/v1/ai/propose") {
        val request = call.receive<AiProposalRequest>()
        val requestId = call.request.headers["X-Request-Id"]
            ?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()

        try {
            call.respond(aiProposalService.propose(request, requestId))
        } catch (exception: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                AiProposalError("invalid_request", exception.message ?: "invalid request", requestId),
            )
        } catch (exception: InvalidAiProposalException) {
            call.respond(
                HttpStatusCode.BadGateway,
                AiProposalError("invalid_ai_proposal", "AI proposal could not be validated", requestId),
            )
        } catch (exception: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                AiProposalError("ai_provider_error", "AI provider unavailable", requestId),
            )
        }
    }

    post("/v1/ai/organize-week") {
        val request = call.receive<OrganizeWeekRequest>()
        val response = organizeWeekService.organize(request)
        call.respond(response)
    }
}
