package com.superplanner.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class NaturalLanguageRequest(val schemaVersion: String = "1", val message: String, val context: AiProposalContext = AiProposalContext())
@Serializable
data class ExplanationRequest(val schemaVersion: String = "1", val question: String, val evidence: List<String> = emptyList())
@Serializable
data class CommandRequest(val schemaVersion: String = "1", val message: String, val context: AiProposalContext = AiProposalContext())
@Serializable
data class InsightRequest(val schemaVersion: String = "1", val evidence: List<String> = emptyList(), val sampleSize: Int = 0)
@Serializable
data class PreferenceRequest(val schemaVersion: String = "1", val observations: List<String> = emptyList())
@Serializable
data class ScenarioRequest(val schemaVersion: String = "1", val question: String, val context: AiProposalContext = AiProposalContext())
@Serializable
data class NextActionRequest(val schemaVersion: String = "1", val context: AiProposalContext = AiProposalContext(), val candidates: List<String> = emptyList())

@Serializable
data class AiCapabilityResponse(val schemaVersion: String = "1", val requestId: String, val result: JsonObject, val model: String)

class AiCapabilityService(
    private val generator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    fun naturalLanguage(request: NaturalLanguageRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.message)
        return generate(requestId, naturalLanguagePrompt(request))
    }

    fun explanation(request: ExplanationRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question)
        return generate(requestId, """
            You are the explanation layer of Super Planner.
            Explain the answer using ONLY the supplied evidence. Never invent facts.
            If evidence is insufficient, say so. Return ONLY JSON:
            {"explanation": string, "evidenceUsed": [string], "confidence": "HIGH|MEDIUM|LOW"}
            Question: ${json.encodeToString(ExplanationRequest.serializer(), request)}
        """.trimIndent())
    }

    fun command(request: CommandRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.message)
        return generate(requestId, """
            You are the command interpretation layer of Super Planner.
            Convert the user's request into exactly one safe, known PlanningCommand.
            Never execute it, calculate a route, access a database, or invent facts.
            Return ONLY JSON:
            {"commandType":"MARK_DELAYED|CANCEL_ACTIVITY|CREATE_ACTIVITY_DRAFT|CHANGE_ACTIVITY|REORGANIZE_DAY|MISSING_INFORMATION",
             "requiresConfirmation":true,"payload":{},"explanation":string}
            Unknown or ambiguous requests must use MISSING_INFORMATION.
            Request: ${json.encodeToString(CommandRequest.serializer(), request)}
        """.trimIndent())
    }

    fun insight(request: InsightRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        require(request.sampleSize >= 0) { "sampleSize must not be negative" }
        return generate(requestId, """
            You are the planning insights layer of Super Planner.
            Interpret only supplied evidence. Do not invent statistics or treat small samples as facts.
            Never modify planning rules or commitments. Return ONLY JSON:
            {"insights":[{"title":string,"description":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}],"recommendations":[string]}
            Request: ${json.encodeToString(InsightRequest.serializer(), request)}
        """.trimIndent())
    }

    fun preference(request: PreferenceRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            Infer stable planning preferences only when the observations provide sufficient evidence.
            One isolated observation is not enough. Do not persist anything.
            Return ONLY JSON:
            {"preferences":[{"preference":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}]}
            Observations: ${json.encodeToString(PreferenceRequest.serializer(), request)}
        """.trimIndent())

    fun scenario(request: ScenarioRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question)
        return generate(requestId, """
            Interpret the user's hypothetical planning scenario. Do not modify the real plan.
            Return ONLY JSON:
            {"scenario":string,"changes":[],"assumptions":[],"requiresClarification":boolean}
            The app will run the scenario through its PlanningEngine.
            Request: ${json.encodeToString(ScenarioRequest.serializer(), request)}
        """.trimIndent())
    }

    fun nextAction(request: NextActionRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        require(request.candidates.distinct().size == request.candidates.size) { "candidates must be unique" }
        val response = generate(requestId, """
            Recommend a next action from the supplied planning context.
            The candidate list is a domain-owned feasibility boundary. You MUST return a recommendedAction that is exactly one of the supplied candidates, or null when no candidate is feasible.
            Never invent time, capacity, priorities, dependencies, preparation or travel facts.
            Consider available time, priorities, dependencies, preparation and travel facts when present.
            Return ONLY JSON:
            {"recommendedAction":"candidate id or null","reason":string,"alternatives":["candidate ids"],"confidence":"HIGH|MEDIUM|LOW"}
            Alternatives must also come only from the candidate list and must contain at most two items.
            Request: ${json.encodeToString(NextActionRequest.serializer(), request)}
        """.trimIndent())

        val recommended = response.result["recommendedAction"]
            ?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() && it != "null" }
        require(recommended == null || recommended in request.candidates) { "AI recommended a non-eligible candidate" }

        val alternatives = response.result["alternatives"] as? JsonArray
            ?: throw IllegalArgumentException("next-action response must contain alternatives")
        require(alternatives.size <= 2) { "next-action response has too many alternatives" }
        require(alternatives.all { it is JsonPrimitive && it.content in request.candidates }) {
            "next-action alternatives contain a non-eligible candidate"
        }
        return response
    }

    private fun validate(schemaVersion: String, message: String) {
        require(schemaVersion == "1") { "unsupported schemaVersion" }
        require(message.isNotBlank()) { "message must not be blank" }
    }

    private fun generate(requestId: String, prompt: String): AiCapabilityResponse {
        val raw = generator.generate(prompt)
        val result = json.decodeFromString<JsonObject>(cleanJson(raw))
        require(result.isNotEmpty()) { "AI returned an empty result" }
        return AiCapabilityResponse(requestId = requestId, result = result, model = generator.modelName)
    }

    private fun naturalLanguagePrompt(request: NaturalLanguageRequest): String = """
        You are the natural-language interpretation layer for Super Planner.
        Convert the message into one provider-independent AiProposal.
        Infer only what is supported. If important information is missing, ask for it.
        Never execute, persist, schedule, or calculate authoritative feasibility.
        Return ONLY JSON:
        {"commandType":"CREATE_ACTIVITY_DRAFT|MISSING_INFORMATION","explanation":string,
         "requiresConfirmation":true,"payload":{},"inferredFields":[string],"missingFields":[string]}
        Request: ${json.encodeToString(NaturalLanguageRequest.serializer(), request)}
    """.trimIndent()

    private fun cleanJson(raw: String): String = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
