package com.superplanner.gateway

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class AiProposalService(
    private val aiTextGenerator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    val modelName: String get() = aiTextGenerator.modelName

    fun propose(request: AiProposalRequest, requestId: String = UUID.randomUUID().toString()): AiProposalEnvelope {
        validateRequest(request)

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

    private fun validateRequest(request: AiProposalRequest) {
        require(request.schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported schemaVersion" }
        require(request.message.isNotBlank()) { "message must not be blank" }
        require(request.message.length <= MAX_INPUT_LENGTH) { "input exceeds maximum length" }
        request.context.nowIso?.let { require(it.length <= MAX_CONTEXT_FIELD_LENGTH) { "nowIso exceeds maximum length" } }
        request.context.activeActivityId?.let { require(it.length <= MAX_CONTEXT_FIELD_LENGTH) { "activeActivityId exceeds maximum length" } }
        require(request.context.minimalRouteFacts.size <= MAX_CONTEXT_ITEMS) { "minimalRouteFacts has too many items" }
        request.context.minimalRouteFacts.forEach {
            require(it.length <= MAX_CONTEXT_FIELD_LENGTH) { "minimalRouteFacts contains an oversized item" }
        }
        val contextChars = request.context.nowIso.orEmpty().length +
            request.context.activeActivityId.orEmpty().length +
            request.context.minimalRouteFacts.sumOf(String::length)
        require(contextChars <= MAX_CONTEXT_TOTAL_LENGTH) { "context exceeds maximum size" }
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
        require(proposal.payload.isNotEmpty() || proposal.commandType == "RECALCULATE_ROUTE") { "payload must not be empty" }

        val mutating = proposal.commandType in MUTATING_COMMANDS
        require(!mutating || proposal.requiresConfirmation) { "mutating proposals require confirmation" }

        when (proposal.commandType) {
            "CREATE_ACTIVITY_DRAFT" -> {
                requireString(proposal, "title")
                requireOptionalString(proposal, "date")
                requireOptionalString(proposal, "startTime")
                requireOptionalNumber(proposal, "durationMinutes")
                requireOptionalString(proposal, "recurrence")
            }
            "EXPLAIN_NEXT_ACTIVITY" -> {
                requireString(proposal, "activityId")
                requireStringArray(proposal, "evidence")
            }
            "REORGANIZE_DAY" -> requireNumber(proposal, "delayMinutes")
            "MISSING_INFORMATION" -> requireStringArray(proposal, "fields")
            "RECALCULATE_ROUTE" -> require(proposal.payload.isEmpty()) { "RECALCULATE_ROUTE payload must be empty" }
        }
    }

    private fun requireString(proposal: AiProposal, key: String) {
        val value = proposal.payload[key] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank()) { "$key must be a non-blank string" }
    }

    private fun requireOptionalString(proposal: AiProposal, key: String) {
        val value = proposal.payload[key] ?: return
        if (value === JsonNull) return
        require(value is JsonPrimitive && value.isString) { "$key must be a string or null" }
    }

    private fun requireOptionalNumber(proposal: AiProposal, key: String) {
        val value = proposal.payload[key] ?: return
        if (value === JsonNull) return
        require(value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null) { "$key must be a number or null" }
    }

    private fun requireNumber(proposal: AiProposal, key: String) {
        val value = proposal.payload[key] as? JsonPrimitive
        require(value != null && !value.isString && value.content.toDoubleOrNull() != null) { "$key must be a number" }
    }

    private fun requireStringArray(proposal: AiProposal, key: String) {
        val value = proposal.payload[key]
        require(value is JsonArray && value.size <= MAX_CONTEXT_ITEMS && value.all { it is JsonPrimitive && it.isString && it.content.length <= MAX_CONTEXT_FIELD_LENGTH }) { "$key must be an array of strings" }
    }

    private fun cleanJson(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    companion object {
        const val CURRENT_SCHEMA_VERSION = "1"
        private const val MAX_INPUT_LENGTH = 12_000
        private const val MAX_CONTEXT_ITEMS = 100
        private const val MAX_CONTEXT_FIELD_LENGTH = 4_000
        private const val MAX_CONTEXT_TOTAL_LENGTH = 12_000
        private val SUPPORTED_COMMANDS = setOf(
            "CREATE_ACTIVITY_DRAFT",
            "EXPLAIN_NEXT_ACTIVITY",
            "REORGANIZE_DAY",
            "MISSING_INFORMATION",
            "RECALCULATE_ROUTE",
        )
        private val MUTATING_COMMANDS = setOf(
            "CREATE_ACTIVITY_DRAFT",
            "REORGANIZE_DAY",
            "RECALCULATE_ROUTE",
        )
    }
}

class InvalidAiProposalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
