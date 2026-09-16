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
    registerGenerateRoute(aiTextGenerator, security)
    registerProposeRoute(aiProposalService, security)
    registerOrganizeWeekRoute(organizeWeekService, security)
    registerCapabilityRoutes(capabilityService, security)
}

private fun Route.registerGenerateRoute(
    generator: AiTextGenerator,
    security: GatewaySecurity,
) {
    post("/v1/ai/generate") {
        if (!security.requireAccess(call)) return@post
        handleGenerate(call, generator)
    }
}

private fun Route.registerProposeRoute(
    service: AiProposalService,
    security: GatewaySecurity,
) {
    post("/v1/ai/propose") {
        if (!security.requireAccess(call)) return@post
        handlePropose(call, service)
    }
}

private fun Route.registerOrganizeWeekRoute(
    service: OrganizeWeekService,
    security: GatewaySecurity,
) {
    post("/v1/ai/organize-week") {
        if (!security.requireAccess(call)) return@post
        handleOrganizeWeek(call, service)
    }
}

private fun Route.registerCapabilityRoutes(
    service: AiCapabilityService,
    security: GatewaySecurity,
) {
    registerCapabilityRoute("natural-language", security) { id ->
        service.naturalLanguage(call.receive<NaturalLanguageRequest>(), id)
    }
    registerCapabilityRoute("explain", security) { id ->
        service.explanation(call.receive<ExplanationRequest>(), id)
    }
    registerCapabilityRoute("command", security) { id ->
        service.command(call.receive<CommandRequest>(), id)
    }
    registerCapabilityRoute("insights", security) { id ->
        service.insight(call.receive<InsightRequest>(), id)
    }
    registerCapabilityRoute("preferences", security) { id ->
        service.preference(call.receive<PreferenceRequest>(), id)
    }
    registerCapabilityRoute("scenario", security) { id ->
        service.scenario(call.receive<ScenarioRequest>(), id)
    }
    registerCapabilityRoute("next-action", security) { id ->
        service.nextAction(call.receive<NextActionRequest>(), id)
    }
}

private fun Route.registerCapabilityRoute(
    capability: String,
    security: GatewaySecurity,
    block: suspend ApplicationCall.(String) -> AiCapabilityResponse,
) {
    post("/v1/ai/$capability") {
        if (!security.requireAccess(call)) return@post
        val requestId = prepareRequest(call)
        executeCapability(call, requestId, capability) {
            withAiTimeout(AI_TIMEOUT_MS) { block(call, requestId) }
        }
    }
}

private suspend fun handleGenerate(call: ApplicationCall, generator: AiTextGenerator) {
    val requestId = prepareRequest(call)
    executeCapability(call, requestId, "generate", generator.modelName) {
        val request = call.receive<GenerateAiRequest>()
        require(request.prompt.isNotBlank()) { "prompt must not be blank" }
        require(request.prompt.length <= MAX_PROMPT_LENGTH) { "prompt exceeds maximum length" }
        GenerateAiResponse(
            withAiTimeout(AI_TIMEOUT_MS) { generator.generate(request.prompt) },
            generator.modelName,
        )
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

private suspend fun <T : Any> executeCapability(
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
        failure(
            call,
            requestId,
            capability,
            "invalid_ai_proposal",
            started,
            HttpStatusCode.BadGateway,
            "AI proposal could not be validated",
        )
    } catch (e: InvalidOrganizeWeekException) {
        failure(
            call,
            requestId,
            capability,
            "invalid_ai_response",
            started,
            HttpStatusCode.BadGateway,
            "AI returned an invalid organize-week response",
        )
    } catch (e: InvalidAiCapabilityException) {
        failure(
            call,
            requestId,
            capability,
            "invalid_ai_response",
            started,
            HttpStatusCode.BadGateway,
            "AI returned an invalid response",
        )
    } catch (e: IllegalArgumentException) {
        failure(
            call,
            requestId,
            capability,
            "invalid_request",
            started,
            HttpStatusCode.BadRequest,
            e.message ?: "invalid request",
        )
    } catch (e: TimeoutCancellationException) {
        failure(
            call,
            requestId,
            capability,
            "timeout",
            started,
            HttpStatusCode.GatewayTimeout,
            "AI operation timed out",
        )
    } catch (e: Exception) {
        failure(
            call,
            requestId,
            capability,
            "ai_provider_error",
            started,
            HttpStatusCode.BadGateway,
            "AI provider unavailable",
        )
    }
}

private suspend fun failure(
    call: ApplicationCall,
    requestId: String,
    capability: String,
    reason: String,
    started: Long,
    status: HttpStatusCode,
    message: String,
) {
    GatewayObservability.failure(requestId, capability, reason, started)
    call.respond(status, ErrorResponse(message))
}

private const val MAX_PROMPT_LENGTH = 12_000
private val AI_TIMEOUT_MS: Long = System.getenv("AI_TIMEOUT_MS")
    ?.toLongOrNull()
    ?.coerceIn(1_000, 120_000)
    ?: 30_000
