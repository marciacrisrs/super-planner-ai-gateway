package com.superplanner.gateway

import io.ktor.server.application.call
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
    post("/v1/ai/generate") {
        if (!security.requireAccess(call)) return@post
        call.executeAiRoute("generate") {
            val request = call.receive<GenerateAiRequest>()
            require(request.prompt.isNotBlank()) { "prompt must not be blank" }
            require(request.prompt.length <= MAX_PROMPT_LENGTH) { "prompt exceeds maximum length" }
            val text = aiTextGenerator.generate(request.prompt)
            AiRouteResult(GenerateAiResponse(text, aiTextGenerator.modelName), aiTextGenerator.modelName)
        }
    }

    post("/v1/ai/propose") {
        if (!security.requireAccess(call)) return@post
        call.executeAiRoute(
            capability = "propose",
            invalidException = InvalidAiProposalException::class.java,
            invalidError = { requestId ->
                AiProposalError("invalid_ai_proposal", "AI proposal could not be validated", requestId)
            },
            providerError = AiProposalError("ai_provider_error", "AI operation failed", GatewaySecurity.requestId(call)),
        ) {
            val request = call.receive<AiProposalRequest>()
            require(request.message.length <= MAX_PROMPT_LENGTH) { "message exceeds maximum length" }
            val response = aiProposalService.propose(request, GatewaySecurity.requestId(call))
            AiRouteResult(response, response.model)
        }
    }

    post("/v1/ai/organize-week") {
        if (!security.requireAccess(call)) return@post
        call.executeAiRoute(
            capability = ORGANIZE_WEEK_CAPABILITY,
            invalidException = InvalidOrganizeWeekException::class.java,
            invalidError = { requestId ->
                GatewayError("invalid_ai_response", "AI returned an invalid organize-week response", requestId)
            },
        ) {
            val response = organizeWeekService.organize(call.receive<OrganizeWeekRequest>())
            AiRouteResult(response, response.model)
        }
    }

    post("/v1/ai/natural-language") {
        capabilityRoute(security, "natural-language") {
            capabilityService.naturalLanguage(call.receive<NaturalLanguageRequest>(), it)
        }
    }
    post("/v1/ai/explain") {
        capabilityRoute(security, "explain") {
            capabilityService.explanation(call.receive<ExplanationRequest>(), it)
        }
    }
    post("/v1/ai/command") {
        capabilityRoute(security, "command") {
            capabilityService.command(call.receive<CommandRequest>(), it)
        }
    }
    post("/v1/ai/insights") {
        capabilityRoute(security, "insights") {
            capabilityService.insight(call.receive<InsightRequest>(), it)
        }
    }
    post("/v1/ai/preferences") {
        capabilityRoute(security, "preferences") {
            capabilityService.preference(call.receive<PreferenceRequest>(), it)
        }
    }
    post("/v1/ai/scenario") {
        capabilityRoute(security, "scenario") {
            capabilityService.scenario(call.receive<ScenarioRequest>(), it)
        }
    }
    post("/v1/ai/next-action") {
        capabilityRoute(security, "next-action") {
            capabilityService.nextAction(call.receive<NextActionRequest>(), it)
        }
    }
}

private fun Route.capabilityRoute(
    security: GatewaySecurity,
    capability: String,
    block: suspend (String) -> AiCapabilityResponse,
) {
    post { path ->
        if (!security.requireAccess(call)) return@post
        call.executeAiRoute(capability, InvalidAiCapabilityException::class.java) {
            val requestId = GatewaySecurity.requestId(call)
            val result = block(requestId)
            AiRouteResult(result, result.model)
        }
    }
}

private const val ORGANIZE_WEEK_CAPABILITY = "organize-week"
