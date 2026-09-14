package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable

@Serializable
data class GenerateAiRequest(val prompt: String)

@Serializable
data class GenerateAiResponse(val text: String, val model: String)

fun Route.aiRoutes(
    aiTextGenerator: AiTextGenerator,
    organizeWeekService: OrganizeWeekService,
    aiProposalService: AiProposalService,
    capabilityService: AiCapabilityService,
    security: GatewaySecurity,
) {
    post("/v1/ai/generate") {
        if (!security.requireAccess(call)) return@post
        val requestId = GatewaySecurity.requestId(call)
        val started = System.nanoTime()
        try {
            val request = call.receive<GenerateAiRequest>()
            require(request.prompt.isNotBlank()) { "prompt must not be blank" }
            require(request.prompt.length <= MAX_PROMPT_LENGTH) { "prompt exceeds maximum length" }
            val text = withAiTimeout(AI_TIMEOUT_MS) { aiTextGenerator.generate(request.prompt) }
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(GenerateAiResponse(text, aiTextGenerator.modelName))
            GatewayObservability.success(requestId, "generate", aiTextGenerator.modelName, started)
        } catch (e: IllegalArgumentException) {
            GatewayObservability.failure(requestId, "generate", "invalid_request", started)
            call.respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", e.message ?: "invalid request", requestId))
        } catch (e: TimeoutCancellationException) {
            GatewayObservability.failure(requestId, "generate", "timeout", started)
            call.respond(HttpStatusCode.GatewayTimeout, GatewayError("timeout", "AI operation timed out", requestId))
        } catch (e: Exception) {
            GatewayObservability.failure(requestId, "generate", "ai_provider_error", started)
            call.respond(HttpStatusCode.BadGateway, GatewayError("ai_provider_error", "AI provider unavailable", requestId))
        }
    }

    post("/v1/ai/propose") {
        if (!security.requireAccess(call)) return@post
        val requestId = GatewaySecurity.requestId(call)
        val started = System.nanoTime()
        try {
            val request = call.receive<AiProposalRequest>()
            require(request.message.length <= MAX_PROMPT_LENGTH) { "message exceeds maximum length" }
            val response = withAiTimeout(AI_TIMEOUT_MS) { aiProposalService.propose(request, requestId) }
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(response)
            GatewayObservability.success(requestId, "propose", response.model, started)
        } catch (e: InvalidAiProposalException) {
            GatewayObservability.failure(requestId, "propose", "invalid_ai_proposal", started)
            call.respond(HttpStatusCode.BadGateway, AiProposalError("invalid_ai_proposal", "AI proposal could not be validated", requestId))
        } catch (e: IllegalArgumentException) {
            GatewayObservability.failure(requestId, "propose", "invalid_request", started)
            call.respond(HttpStatusCode.BadRequest, AiProposalError("invalid_request", e.message ?: "invalid request", requestId))
        } catch (e: TimeoutCancellationException) {
            GatewayObservability.failure(requestId, "propose", "timeout", started)
            call.respond(HttpStatusCode.GatewayTimeout, AiProposalError("timeout", "AI operation timed out", requestId))
        } catch (e: Exception) {
            GatewayObservability.failure(requestId, "propose", "ai_provider_error", started)
            call.respond(HttpStatusCode.BadGateway, AiProposalError("ai_provider_error", "AI operation failed", requestId))
        }
    }

    post("/v1/ai/organize-week") {
        if (!security.requireAccess(call)) return@post
        val requestId = GatewaySecurity.requestId(call)
        val started = System.nanoTime()
        try {
            val request = call.receive<OrganizeWeekRequest>()
            val response = withAiTimeout(AI_TIMEOUT_MS) { organizeWeekService.organize(request) }
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(response)
            GatewayObservability.success(requestId, "organize-week", response.model, started)
        } catch (e: IllegalArgumentException) {
            GatewayObservability.failure(requestId, "organize-week", "invalid_request", started)
            call.respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", e.message ?: "invalid request", requestId))
        } catch (e: TimeoutCancellationException) {
            GatewayObservability.failure(requestId, "organize-week", "timeout", started)
            call.respond(HttpStatusCode.GatewayTimeout, GatewayError("timeout", "AI operation timed out", requestId))
        } catch (e: Exception) {
            GatewayObservability.failure(requestId, "organize-week", "ai_error", started)
            call.respond(HttpStatusCode.BadGateway, GatewayError("ai_error", "AI operation failed", requestId))
        }
    }

    post("/v1/ai/natural-language") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("natural-language") { id ->
            capabilityService.naturalLanguage(call.receive<NaturalLanguageRequest>(), id)
        }
    }
    post("/v1/ai/explain") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("explain") { id ->
            capabilityService.explanation(call.receive<ExplanationRequest>(), id)
        }
    }
    post("/v1/ai/command") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("command") { id ->
            capabilityService.command(call.receive<CommandRequest>(), id)
        }
    }
    post("/v1/ai/insights") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("insights") { id ->
            capabilityService.insight(call.receive<InsightRequest>(), id)
        }
    }
    post("/v1/ai/preferences") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("preferences") { id ->
            capabilityService.preference(call.receive<PreferenceRequest>(), id)
        }
    }
    post("/v1/ai/scenario") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("scenario") { id ->
            capabilityService.scenario(call.receive<ScenarioRequest>(), id)
        }
    }
    post("/v1/ai/next-action") {
        if (!security.requireAccess(call)) return@post
        call.capabilityRoute("next-action") { id ->
            capabilityService.nextAction(call.receive<NextActionRequest>(), id)
        }
    }
}

private suspend fun ApplicationCall.capabilityRoute(
    capability: String,
    block: suspend (String) -> AiCapabilityResponse,
) {
    val requestId = GatewaySecurity.requestId(this)
    val started = System.nanoTime()
    try {
        response.headers.append("X-Request-Id", requestId)
        val result = withAiTimeout(AI_TIMEOUT_MS) { block(requestId) }
        respond(result)
        GatewayObservability.success(requestId, capability, result.model, started)
    } catch (e: InvalidAiCapabilityException) {
        GatewayObservability.failure(requestId, capability, "invalid_ai_response", started)
        respond(HttpStatusCode.BadGateway, GatewayError("invalid_ai_response", "AI returned an invalid response", requestId))
    } catch (e: IllegalArgumentException) {
        GatewayObservability.failure(requestId, capability, "invalid_request", started)
        respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", e.message ?: "invalid request", requestId))
    } catch (e: TimeoutCancellationException) {
        GatewayObservability.failure(requestId, capability, "timeout", started)
        respond(HttpStatusCode.GatewayTimeout, GatewayError("timeout", "AI operation timed out", requestId))
    } catch (e: Exception) {
        GatewayObservability.failure(requestId, capability, "ai_provider_error", started)
        respond(HttpStatusCode.BadGateway, GatewayError("ai_provider_error", "AI provider unavailable", requestId))
    }
}

private val AI_TIMEOUT_MS: Long = System.getenv("AI_TIMEOUT_MS")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 30_000
private const val MAX_PROMPT_LENGTH = 12000
