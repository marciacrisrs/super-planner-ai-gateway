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
        validateContext(request.context)
        val response = generate(requestId, naturalLanguagePrompt(request))
        validateNaturalLanguage(response.result)
        return response
    }

    fun explanation(request: ExplanationRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question)
        validateStringList(request.evidence, "evidence")
        val response = generate(requestId, """
            You are the explanation layer of Super Planner.
            Explain the answer using ONLY the supplied evidence. Never invent facts.
            If evidence is insufficient, say so. Return ONLY JSON:
            {"explanation": string, "evidenceUsed": [string], "confidence": "HIGH|MEDIUM|LOW"}
            Question: ${json.encodeToString(ExplanationRequest.serializer(), request)}
        """.trimIndent())
        validateExplanation(response.result)
        return response
    }

    fun command(request: CommandRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.message)
        validateContext(request.context)
        val response = generate(requestId, """
            You are the command interpretation layer of Super Planner.
            Convert the user's request into exactly one safe, known PlanningCommand.
            Never execute it, calculate a route, access a database, or invent facts.
            Return ONLY JSON:
            {"commandType":"MARK_DELAYED|CANCEL_ACTIVITY|CREATE_ACTIVITY_DRAFT|CHANGE_ACTIVITY|REORGANIZE_DAY|MISSING_INFORMATION",
             "requiresConfirmation":true,"payload":{},"explanation":string}
            Unknown or ambiguous requests must use MISSING_INFORMATION.
            Request: ${json.encodeToString(CommandRequest.serializer(), request)}
        """.trimIndent())
        validateCommand(response.result)
        return response
    }

    fun insight(request: InsightRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        require(request.sampleSize >= 0) { "sampleSize must not be negative" }
        validateStringList(request.evidence, "evidence")
        val response = generate(requestId, """
            You are the planning insights layer of Super Planner.
            Interpret only supplied evidence. Do not invent statistics or treat small samples as facts.
            Never modify planning rules or commitments. Return ONLY JSON:
            {"insights":[{"title":string,"description":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}],"recommendations":[string]}
            Request: ${json.encodeToString(InsightRequest.serializer(), request)}
        """.trimIndent())
        validateInsights(response.result)
        return response
    }

    fun preference(request: PreferenceRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        validateStringList(request.observations, "observations")
        val response = generate(requestId, """
            Infer stable planning preferences only when the observations provide sufficient evidence.
            One isolated observation is not enough. Do not persist anything.
            Return ONLY JSON:
            {"preferences":[{"preference":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}]}
            Observations: ${json.encodeToString(PreferenceRequest.serializer(), request)}
        """.trimIndent())
        validatePreferences(response.result)
        return response
    }

    fun scenario(request: ScenarioRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question)
        validateContext(request.context)
        val response = generate(requestId, """
            Interpret the user's hypothetical planning scenario. Do not modify the real plan.
            Return ONLY JSON:
            {"scenario":string,"changes":[],"assumptions":[],"requiresClarification":boolean}
            The app will run the scenario through its PlanningEngine.
            Request: ${json.encodeToString(ScenarioRequest.serializer(), request)}
        """.trimIndent())
        validateScenario(response.result)
        return response
    }

    fun nextAction(request: NextActionRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        validateContext(request.context)
        require(request.candidates.distinct().size == request.candidates.size) { "candidates must be unique" }
        validateStringList(request.candidates, "candidates")
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
        validateNextAction(response.result, request.candidates)
        return response
    }

    private fun validate(schemaVersion: String, message: String) {
        require(schemaVersion == "1") { "unsupported schemaVersion" }
        require(message.isNotBlank()) { "message must not be blank" }
        require(message.length <= MAX_INPUT_LENGTH) { "input exceeds maximum length" }
    }

    private fun validateContext(context: AiProposalContext) {
        context.nowIso?.let { require(it.length <= MAX_INPUT_LENGTH) { "nowIso exceeds maximum length" } }
        context.activeActivityId?.let { require(it.length <= MAX_INPUT_LENGTH) { "activeActivityId exceeds maximum length" } }
        validateStringList(context.minimalRouteFacts, "minimalRouteFacts")
    }

    private fun validateStringList(values: List<String>, name: String) {
        require(values.size <= MAX_LIST_ITEMS) { "$name has too many items" }
        values.forEach { value -> require(value.length <= MAX_INPUT_LENGTH) { "$name contains an oversized item" } }
    }

    private fun generate(requestId: String, prompt: String): AiCapabilityResponse {
        val raw = generator.generate(prompt)
        val result = json.decodeFromString<JsonObject>(cleanJson(raw))
        require(result.isNotEmpty()) { "AI returned an empty result" }
        return AiCapabilityResponse(requestId = requestId, result = result, model = generator.modelName)
    }

    private fun validateNaturalLanguage(result: JsonObject) {
        requireEnum(result, "commandType", setOf("CREATE_ACTIVITY_DRAFT", "MISSING_INFORMATION"))
        requireString(result, "explanation")
        requireBoolean(result, "requiresConfirmation")
        requireObject(result, "payload")
        requireStringArray(result, "inferredFields")
        requireStringArray(result, "missingFields")
        val command = (result["commandType"] as JsonPrimitive).content
        if (command == "CREATE_ACTIVITY_DRAFT") {
            require(result["requiresConfirmation"]?.toString() == "true") { "natural-language mutations require confirmation" }
        }
    }

    private fun validateExplanation(result: JsonObject) {
        requireString(result, "explanation")
        requireStringArray(result, "evidenceUsed")
        requireEnum(result, "confidence", CONFIDENCE)
    }

    private fun validateCommand(result: JsonObject) {
        requireEnum(result, "commandType", COMMANDS)
        requireBoolean(result, "requiresConfirmation")
        requireObject(result, "payload")
        requireString(result, "explanation")
        val command = (result["commandType"] as JsonPrimitive).content
        if (command != "MISSING_INFORMATION") require(result["requiresConfirmation"]?.toString() == "true") { "command proposals require confirmation" }
    }

    private fun validateInsights(result: JsonObject) {
        val insights = result["insights"] as? JsonArray ?: throw IllegalArgumentException("insights must be an array")
        insights.forEach { item ->
            val obj = item as? JsonObject ?: throw IllegalArgumentException("each insight must be an object")
            requireString(obj, "title")
            requireString(obj, "description")
            requireStringArray(obj, "evidence")
            requireEnum(obj, "confidence", CONFIDENCE)
        }
        requireStringArray(result, "recommendations")
    }

    private fun validatePreferences(result: JsonObject) {
        val preferences = result["preferences"] as? JsonArray ?: throw IllegalArgumentException("preferences must be an array")
        preferences.forEach { item ->
            val obj = item as? JsonObject ?: throw IllegalArgumentException("each preference must be an object")
            requireString(obj, "preference")
            requireStringArray(obj, "evidence")
            requireEnum(obj, "confidence", CONFIDENCE)
        }
    }

    private fun validateScenario(result: JsonObject) {
        requireString(result, "scenario")
        requireStringArray(result, "changes")
        requireStringArray(result, "assumptions")
        requireBoolean(result, "requiresClarification")
    }

    private fun validateNextAction(result: JsonObject, candidates: List<String>) {
        val recommended = result["recommendedAction"]?.let { value ->
            when (value) {
                is JsonPrimitive -> if (value.content == "null" && !value.isString) null else value.content
                else -> throw IllegalArgumentException("recommendedAction must be a string or null")
            }
        }
        require(recommended == null || recommended in candidates) { "AI recommended a non-eligible candidate" }
        requireString(result, "reason")
        requireEnum(result, "confidence", CONFIDENCE)
        val alternatives = result["alternatives"] as? JsonArray ?: throw IllegalArgumentException("next-action response must contain alternatives")
        require(alternatives.size <= 2) { "next-action response has too many alternatives" }
        require(alternatives.all { it is JsonPrimitive && it.isString && it.content in candidates }) {
            "next-action alternatives contain a non-eligible candidate"
        }
        require(alternatives.distinct().size == alternatives.size) { "next-action alternatives must be unique" }
    }

    private fun requireString(result: JsonObject, key: String) {
        val value = result[key] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank()) { "$key must be a non-blank string" }
    }

    private fun requireBoolean(result: JsonObject, key: String) {
        val value = result[key] as? JsonPrimitive
        require(value != null && !value.isString && value.content in setOf("true", "false")) { "$key must be a boolean" }
    }

    private fun requireObject(result: JsonObject, key: String) {
        require(result[key] is JsonObject) { "$key must be an object" }
    }

    private fun requireStringArray(result: JsonObject, key: String) {
        val value = result[key]
        require(value is JsonArray && value.size <= MAX_LIST_ITEMS && value.all { it is JsonPrimitive && it.isString && it.content.length <= MAX_INPUT_LENGTH }) { "$key must be an array of strings" }
    }

    private fun requireEnum(result: JsonObject, key: String, allowed: Set<String>) {
        val value = result[key] as? JsonPrimitive
        require(value != null && value.isString && value.content in allowed) { "$key contains an unsupported value" }
    }

    private fun cleanJson(raw: String): String = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    companion object {
        private const val MAX_INPUT_LENGTH = 12000
        private const val MAX_LIST_ITEMS = 100
        private val CONFIDENCE = setOf("HIGH", "MEDIUM", "LOW")
        private val COMMANDS = setOf("MARK_DELAYED", "CANCEL_ACTIVITY", "CREATE_ACTIVITY_DRAFT", "CHANGE_ACTIVITY", "REORGANIZE_DAY", "MISSING_INFORMATION")
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
}
