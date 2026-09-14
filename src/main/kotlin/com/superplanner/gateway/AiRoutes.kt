package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class GenerateAiRequest(val prompt: String)

@Serializable
data class GenerateAiResponse(val text: String, val model: String)

fun Route.aiRoutes(
    geminiService: GeminiService,
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
            val text = geminiService.generate(request.prompt)
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(GenerateAiResponse(text, geminiService.modelName))
            GatewayObservability.success(requestId, "generate", geminiService.modelName, started)
        } catch (e: IllegalArgumentException) {
            GatewayObservability.failure(requestId, "generate", "invalid_request", started)
            call.respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", e.message ?: "invalid request", requestId))
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
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(aiProposalService.propose(request, requestId))
            GatewayObservability.success(requestId, "propose", aiProposalService.modelName(), started)
        } catch (e: IllegalArgumentException) {
            GatewayObservability.failure(requestId, "propose", "invalid_request", started)
            call.respond(HttpStatusCode.BadRequest, AiProposalError("invalid_request", e.message ?: "invalid request", requestId))
        } catch (e: InvalidAiProposalException) {
            GatewayObservability.failure(requestId, "propose", "invalid_ai_proposal", started)
            call.respond(HttpStatusCode.BadGateway, AiProposalError("invalid_ai_proposal", "AI proposal could not be validated", requestId))
        } catch (e: Exception) {
            GatewayObservability.failure(requestId, "propose", "ai_provider_error", started)
            call.respond(HttpStatusCode.BadGateway, AiProposalError("ai_provider_error", "AI provider unavailable", requestId))
        }
    }

    post("/v1/ai/organize-week") {
        if (!security.requireAccess(call)) return@post
        val requestId = GatewaySecurity.requestId(call)
        val started = System.nanoTime()
        try {
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(organizeWeekService.organize(call.receive()))
            GatewayObservability.success(requestId, "organize-week", geminiService.modelName, started)
        } catch (e: Exception) {
            GatewayObservability.failure(requestId, "organize-week", "ai_error", started)
            call.respond(HttpStatusCode.BadGateway, GatewayError("ai_error", "AI operation failed", requestId))
        }
    }

    post("/v1/ai/natural-language") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "natural-language") { id -> capabilityService.naturalLanguage(call.receive(), id) }
    }
    post("/v1/ai/explain") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "explain") { id -> capabilityService.explanation(call.receive(), id) }
    }
    post("/v1/ai/command") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "command") { id -> capabilityService.command(call.receive(), id) }
    }
    post("/v1/ai/insights") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "insights") { id -> capabilityService.insight(call.receive(), id) }
    }
    post("/v1/ai/preferences") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "preferences") { id -> capabilityService.preference(call.receive(), id) }
    }
    post("/v1/ai/scenario") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "scenario") { id -> capabilityService.scenario(call.receive(), id) }
    }
    post("/v1/ai/next-action") {
        if (!security.requireAccess(call)) return@post
        capabilityRoute(call, security, "next-action") { id -> capabilityService.nextAction(call.receive(), id) }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondCapability(
    capability: String,
    requestId: String,
    block: suspend (String) -> AiCapabilityResponse,
) {
    val started = System.nanoTime()
    try {
        response.headers.append("X-Request-Id", requestId)
        respond(block(requestId))
        GatewayObservability.success(requestId, capability, null, started)
    } catch (e: IllegalArgumentException) {
        GatewayObservability.failure(requestId, capability, "invalid_request", started)
        respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", e.message ?: "invalid request", requestId))
    } catch (e: Exception) {
        GatewayObservability.failure(requestId, capability, "ai_error", started)
        respond(HttpStatusCode.BadGateway, GatewayError("ai_error", "AI operation failed", requestId))
    }
}

private suspend fun capabilityRoute(
    call: io.ktor.server.application.ApplicationCall,
    security: GatewaySecurity,
    capability: String,
    block: suspend (String) -> AiCapabilityResponse,
) {
    val requestId = GatewaySecurity.requestId(call)
    call.respondCapability(capability, requestId, block)
}

private const val MAX_PROMPT_LENGTH = 12000

private fun AiProposalService.modelName(): String =
    javaClass.getDeclaredField("aiTextGenerator").let { field ->
        field.isAccessible = true
        (field.get(this) as AiTextGenerator).modelName
    }
