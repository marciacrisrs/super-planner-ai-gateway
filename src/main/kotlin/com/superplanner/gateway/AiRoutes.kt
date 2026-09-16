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
        handleGenerate(call, aiTextGenerator)
    }
    post("/v1/ai/propose") {
        if (!security.requireAccess(call)) return@post
        handlePropose(call, aiProposalService)
    }
    post("/v1/ai/organize-week") {
        if (!security.requireAccess(call)) return@post
        handleOrganizeWeek(call, organizeWeekService)
    }
    capabilityRoute("natural-language", security) { capabilityService.naturalLanguage(call.receive(), it) }
    capabilityRoute("explain", security) { capabilityService.explanation(call.receive(), it) }
    capabilityRoute("command", security) { capabilityService.command(call.receive(), it) }
    capabilityRoute("insights", security) { capabilityService.insight(call.receive(), it) }
    capabilityRoute("preferences", security) { capabilityService.preference(call.receive(), it) }
    capabilityRoute("scenario", security) { capabilityService.scenario(call.receive(), it) }
    capabilityRoute("next-action", security) { capabilityService.nextAction(call.receive(), it) }
}

private suspend fun handleGenerate(call: ApplicationCall, generator: AiTextGenerator) {
    val requestId = prepareRequest(call)
    executeCapability(call, requestId, "generate", generator.modelName) {
        val request = call.receive<GenerateAiRequest>()
        require(request.prompt.isNotBlank()) { "prompt must not be blank" }
        require(request.prompt.length <= MAX_PROMPT_LENGTH) { "prompt exceeds maximum length" }
        GenerateAiResponse(withAiTimeout(AI_TIMEOUT_MS) { generator.generate(request.prompt) }, generator.modelName)
    }
}

private suspend fun handlePropose(call: ApplicationCall, service: AiProposalService) {
    val requestId = prepareRequest(call)
    executeCapability(call, requestId, "propose") {
        val request = call.receive<AiProposalRequest>()
        require(request.message.length <= MAX_PROMPT_LENGTH) { "message exceeds maximum length" }
        withAiTimeout(AI_TIMEOUT_MS) { service.propose(request, requestId) }
    }
}

private suspend fun handleOrganizeWeek(call: ApplicationCall, service: OrganizeWeekService) {
    val requestId = prepareRequest(call)
    executeCapability(call, requestId, "organize-week") {
        withAiTimeout(AI_TIMEOUT_MS) { service.organize(call.receive()) }
    }
}

private fun prepareRequest(call: ApplicationCall): String {
    val requestId = GatewaySecurity.requestId(call)
    call.response.headers.append("X-Request-Id", requestId)
    return requestId
}

private fun Route.capabilityRoute(
    capability: String,
    security: GatewaySecurity,
    block: suspend ApplicationCall.(String) -> AiCapabilityResponse,
) {
    post("/v1/ai/capabilities/$capability") {
        if (!security.requireAccess(call)) return@post
        val requestId = prepareRequest(call)
        executeCapability(this, requestId, capability) { block(this, requestId) }
    }
}

private suspend fun <T> executeCapability(
    call: ApplicationCall,
    requestId: String,
    capability: String,
    model: String? = null,
    block: suspend () -> T,
) {
    val started = System.nanoTime()
    try {
        val result = block()
        call.respond(result)
        val resultModel = (result as? AiCapabilityResponse)?.model ?: model
        GatewayObservability.success(requestId, capability, resultModel, started)
    } catch (e: InvalidAiProposalException) {
        failure(call, requestId, capability, "invalid_ai_proposal", started, HttpStatusCode.BadGateway, "AI proposal could not be validated")
    } catch (e: InvalidOrganizeWeekException) {
        failure(call, requestId, capability, "invalid_ai_response", started, HttpStatusCode.BadGateway, "AI returned an invalid organize-week response")
    } catch (e: InvalidAiCapabilityException) {
        failure(call, requestId, capability, "invalid_ai_response", started, HttpStatusCode.BadGateway, "AI returned an invalid response")
    } catch (e: IllegalArgumentException) {
        failure(call, requestId, capability, "invalid_request", started, HttpStatusCode.BadRequest, e.message ?: "invalid request")
    } catch (e: TimeoutCancellationException) {
        failure(call, requestId, capability, "timeout", started, HttpStatusCode.GatewayTimeout, "AI operation timed out")
    } catch (e: Exception) {
        failure(call, requestId, capability, "ai_provider_error", started, HttpStatusCode.BadGateway, "AI provider unavailable")
    }
}

private suspend fun failure(
    call: ApplicationCall,
    requestId: String,
    capability: String,
    error: String,
    started: Long,
    status: HttpStatusCode,
    message: String,
) {
    GatewayObservability.failure(requestId, capability, error, started)
    call.respond(status, GatewayError(error, message, requestId))
}

private val AI_TIMEOUT_MS: Long = System.getenv("AI_TIMEOUT_MS")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 30_000
private const val MAX_PROMPT_LENGTH = 12000
