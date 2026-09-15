package com.superplanner.gateway

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
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
    aiPost("/v1/ai/generate", security, "generate") {
        val request = call.receive<GenerateAiRequest>()
        require(request.prompt.isNotBlank()) { "prompt must not be blank" }
        require(request.prompt.length <= MAX_PROMPT_LENGTH) { "prompt exceeds maximum length" }
        val text = aiTextGenerator.generate(request.prompt)
        AiRouteResult(GenerateAiResponse(text, aiTextGenerator.modelName), aiTextGenerator.modelName)
    }

    aiPost(
        path = "/v1/ai/propose",
        security = security,
        capability = "propose",
        invalidException = InvalidAiProposalException::class.java,
        invalidError = { requestId ->
            AiProposalError("invalid_ai_proposal", "AI proposal could not be validated", requestId)
        },
        providerError = { requestId ->
            AiProposalError("ai_provider_error", AI_OPERATION_FAILED_MESSAGE, requestId)
        },
    ) { requestId ->
        val request = call.receive<AiProposalRequest>()
        require(request.message.length <= MAX_PROMPT_LENGTH) { "message exceeds maximum length" }
        val response = aiProposalService.propose(request, requestId)
        AiRouteResult(response, response.model)
    }

    aiPost(
        path = "/v1/ai/organize-week",
        security = security,
        capability = ORGANIZE_WEEK_CAPABILITY,
        invalidException = InvalidOrganizeWeekException::class.java,
        invalidError = { requestId ->
            GatewayError("invalid_ai_response", "AI returned an invalid organize-week response", requestId)
        },
    ) {
        val response = organizeWeekService.organize(call.receive<OrganizeWeekRequest>())
        AiRouteResult(response, response.model)
    }

    capabilityPost("natural-language", security) { requestId ->
        capabilityService.naturalLanguage(call.receive<NaturalLanguageRequest>(), requestId)
    }
    capabilityPost("explain", security) { requestId ->
        capabilityService.explanation(call.receive<ExplanationRequest>(), requestId)
    }
    capabilityPost("command", security) { requestId ->
        capabilityService.command(call.receive<CommandRequest>(), requestId)
    }
    capabilityPost("insights", security) { requestId ->
        capabilityService.insight(call.receive<InsightRequest>(), requestId)
    }
    capabilityPost("preferences", security) { requestId ->
        capabilityService.preference(call.receive<PreferenceRequest>(), requestId)
    }
    capabilityPost("scenario", security) { requestId ->
        capabilityService.scenario(call.receive<ScenarioRequest>(), requestId)
    }
    capabilityPost("next-action", security) { requestId ->
        capabilityService.nextAction(call.receive<NextActionRequest>(), requestId)
    }
}

private fun Route.capabilityPost(
    capability: String,
    security: GatewaySecurity,
    block: suspend ApplicationCall.(String) -> AiCapabilityResponse,
) = aiPost(
    path = "/v1/ai/$capability",
    security = security,
    capability = capability,
    invalidException = InvalidAiCapabilityException::class.java,
) { requestId ->
    val result = block(requestId)
    AiRouteResult(result, result.model)
}

private fun Route.aiPost(
    path: String,
    security: GatewaySecurity,
    capability: String,
    invalidException: Class<out Exception>? = null,
    invalidError: (String) -> Any = { requestId ->
        GatewayError(INVALID_AI_RESPONSE_CODE, INVALID_AI_RESPONSE_MESSAGE, requestId)
    },
    providerError: (String) -> Any = { requestId ->
        GatewayError("ai_provider_error", AI_OPERATION_FAILED_MESSAGE, requestId)
    },
    block: suspend ApplicationCall.(String) -> AiRouteResult<*>,
) = post(path) {
    if (!security.requireAccess(call)) return@post
    call.executeAiRoute(
        capability = capability,
        invalidException = invalidException,
        invalidError = invalidError,
        providerError = providerError,
    ) { requestId -> block(requestId) }
}

private const val ORGANIZE_WEEK_CAPABILITY = "organize-week"
