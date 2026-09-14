package com.superplanner.gateway

import java.util.UUID
import kotlinx.serialization.json.Json

class AiProposalService(
    private val aiTextGenerator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    val modelName: String get() = aiTextGenerator.modelName

    fun propose(request: AiProposalRequest, requestId: String = UUID.randomUUID().toString()): AiProposalEnvelope {
        require(request.schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported schemaVersion" }
        require(request.message.isNotBlank()) { "message must not be blank" }

        val raw = aiTextGenerator.generate(buildPrompt(request))
        val proposal = try {
            json.decodeFromString(AiProposal.serializer(), cleanJson(raw)).also(::validateProposal)
        } catch (exception: Exception) {
            throw InvalidAiProposalException("AI returned an invalid proposal", exception)
        }

        return AiProposalEnvelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            requestId = requestId,
            proposal = proposal,
            model = aiTextGenerator.modelName,
        )
    }

    private fun buildPrompt(request: AiProposalRequest): String = """
        You are the interpretation layer for Super Planner.
        Convert the user's natural-language request into ONE provider-independent proposal.
        The proposal is not execution. Never write to a database, calculate an authoritative route,
        change commitments, or invent facts that are not supported by the request/context.
        If required information is missing or the request is ambiguous, use MISSING_INFORMATION.
        Return ONLY valid JSON matching this exact shape:
        {
          "commandType": "CREATE_ACTIVITY_DRAFT|EXPLAIN_NEXT_ACTIVITY|REORGANIZE_DAY|MISSING_INFORMATION|RECALCULATE_ROUTE",
          "explanation": "short explanation grounded in the request/context",
          "requiresConfirmation": true,
          "payload": {}
        }

        Rules:
        - CREATE_ACTIVITY_DRAFT payload: {"title": string, "date": string|null, "startTime": string|null, "durationMinutes": number|null, "recurrence": string|null}
        - EXPLAIN_NEXT_ACTIVITY payload: {"activityId": string, "evidence": [string]}
        - REORGANIZE_DAY payload: {"delayMinutes": number}
        - MISSING_INFORMATION payload: {"fields": [string]}
        - RECALCULATE_ROUTE payload: {}
        - requiresConfirmation must be true for every proposal that can change user data.
        - Never output Markdown or code fences.

        Request:
        ${json.encodeToString(AiProposalRequest.serializer(), request)}
    """.trimIndent()

    private fun validateProposal(proposal: AiProposal) {
        require(proposal.commandType in SUPPORTED_COMMANDS) { "unsupported commandType" }
        require(proposal.explanation.isNotBlank()) { "explanation must not be blank" }
        when (proposal.commandType) {
            "CREATE_ACTIVITY_DRAFT" -> require(proposal.payload.containsKey("title")) { "CREATE_ACTIVITY_DRAFT requires title" }
            "EXPLAIN_NEXT_ACTIVITY" -> require(proposal.payload.containsKey("evidence")) { "EXPLAIN_NEXT_ACTIVITY requires evidence" }
            "REORGANIZE_DAY" -> require(proposal.payload.containsKey("delayMinutes")) { "REORGANIZE_DAY requires delayMinutes" }
            "MISSING_INFORMATION" -> require(proposal.payload.containsKey("fields")) { "MISSING_INFORMATION requires fields" }
            "RECALCULATE_ROUTE" -> Unit
        }
    }

    private fun cleanJson(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    companion object {
        const val CURRENT_SCHEMA_VERSION = "1"
        private val SUPPORTED_COMMANDS = setOf(
            "CREATE_ACTIVITY_DRAFT",
            "EXPLAIN_NEXT_ACTIVITY",
            "REORGANIZE_DAY",
            "MISSING_INFORMATION",
            "RECALCULATE_ROUTE",
        )
    }
}

class InvalidAiProposalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
