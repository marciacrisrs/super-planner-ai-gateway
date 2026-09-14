package com.superplanner.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class NaturalLanguageRequest(
    val schemaVersion: String = "1",
    val message: String,
    val context: AiProposalContext = AiProposalContext(),
)

@Serializable
data class ExplanationRequest(
    val schemaVersion: String = "1",
    val question: String,
    val evidence: List<String> = emptyList(),
)

@Serializable
data class CommandRequest(
    val schemaVersion: String = "1",
    val message: String,
    val context: AiProposalContext = AiProposalContext(),
)

@Serializable
data class InsightRequest(
    val schemaVersion: String = "1",
    val evidence: List<String> = emptyList(),
    val sampleSize: Int = 0,
)

@Serializable
data class PreferenceRequest(
    val schemaVersion: String = "1",
    val observations: List<String> = emptyList(),
)

@Serializable
data class ScenarioRequest(
    val schemaVersion: String = "1",
    val question: String,
    val context: AiProposalContext = AiProposalContext(),
)

@Serializable
data class NextActionRequest(
    val schemaVersion: String = "1",
    val context: AiProposalContext = AiProposalContext(),
    val candidates: List<String> = emptyList(),
)

@Serializable
data class AiCapabilityResponse(
    val schemaVersion: String = "1",
    val requestId: String,
    val result: JsonObject,
    val model: String,
)

class AiCapabilityService(
    private val generator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    fun naturalLanguage(request: NaturalLanguageRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, naturalLanguagePrompt(request))

    fun explanation(request: ExplanationRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            You are the explanation layer of Super Planner.
            Explain the answer using ONLY the supplied evidence. Never invent facts.
            If evidence is insufficient, say so. Return ONLY JSON:
            {"explanation": string, "evidenceUsed": [string], "confidence": "HIGH|MEDIUM|LOW"}
            Question: ${json.encodeToString(ExplanationRequest.serializer(), request)}
        """.trimIndent())

    fun command(request: CommandRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            You are the command interpretation layer of Super Planner.
            Convert the user's request into exactly one safe, known PlanningCommand.
            Never execute it, calculate a route, access a database, or invent facts.
            Return ONLY JSON:
            {"commandType":"MARK_DELAYED|CANCEL_ACTIVITY|CREATE_ACTIVITY_DRAFT|CHANGE_ACTIVITY|REORGANIZE_DAY|MISSING_INFORMATION",
             "requiresConfirmation":true,"payload":{},"explanation":string}
            Unknown or ambiguous requests must use MISSING_INFORMATION.
            Request: ${json.encodeToString(CommandRequest.serializer(), request)}
        """.trimIndent())

    fun insight(request: InsightRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            You are the planning insights layer of Super Planner.
            Interpret only supplied evidence. Do not invent statistics or treat small samples as facts.
            Never modify planning rules or commitments. Return ONLY JSON:
            {"insights":[{"title":string,"description":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}],"recommendations":[string]}
            Request: ${json.encodeToString(InsightRequest.serializer(), request)}
        """.trimIndent())

    fun preference(request: PreferenceRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            Infer stable planning preferences only when the observations provide sufficient evidence.
            One isolated observation is not enough. Do not persist anything.
            Return ONLY JSON:
            {"preferences":[{"preference":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}]}
            Observations: ${json.encodeToString(PreferenceRequest.serializer(), request)}
        """.trimIndent())

    fun scenario(request: ScenarioRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            Interpret the user's hypothetical planning scenario. Do not modify the real plan.
            Return ONLY JSON:
            {"scenario":string,"changes":[],"assumptions":[],"requiresClarification":boolean}
            The app will run the scenario through its PlanningEngine.
            Request: ${json.encodeToString(ScenarioRequest.serializer(), request)}
        """.trimIndent())

    fun nextAction(request: NextActionRequest, requestId: String): AiCapabilityResponse =
        generate(requestId, """
            Recommend a next action from the supplied planning context.
            Never claim an action is feasible when the context does not establish that.
            Consider available time, priorities, dependencies, preparation and travel facts when present.
            Return ONLY JSON:
            {"recommendedAction":string,"reason":string,"alternatives":[string],"confidence":"HIGH|MEDIUM|LOW"}
            Request: ${json.encodeToString(NextActionRequest.serializer(), request)}
        """.trimIndent())

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

    private fun cleanJson(raw: String): String = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
