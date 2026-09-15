package com.superplanner.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ReplanChange(val type: String, val activityId: String? = null, val deltaMinutes: Int? = null)

@Serializable
data class ReplanContext(val knownActivityIds: List<String> = emptyList(), val fixedActivityIds: List<String> = emptyList(), val evidence: List<String> = emptyList(), val availableWindows: List<String> = emptyList())

@Serializable
data class ReplanRequest(val schemaVersion: String = "1", val context: ReplanContext = ReplanContext(), val changes: List<ReplanChange> = emptyList())

class AdaptiveAiCapabilityService(private val generator: AiTextGenerator, private val json: Json = Json { ignoreUnknownKeys = false }) {
    fun replan(request: ReplanRequest, id: String): AiCapabilityResponse {
        validateRequest(request)
        return generate(id, """You are the adaptive replanning proposal layer of Super Planner. IA proposes; the domain decides. Never execute or claim that anything was applied. Preserve fixed commitments. Use only supplied IDs and evidence. Explicitly expose conflicts and trade-offs. If context is insufficient, use LOW confidence, requiresConfirmation=true and a limited proposal. Return ONLY JSON: {"proposal":[{"activityId":string,"action":"MOVE|KEEP|SHORTEN|DEFER|SPLIT","target":string}],"conflicts":[string],"tradeoffs":[string],"preservedIds":[string],"evidence":[string],"confidence":"HIGH|MEDIUM|LOW","requiresConfirmation":true}. Request: ${json.encodeToString(ReplanRequest.serializer(), request)}""") { result ->
            requireStringArray(result, "conflicts")
            requireStringArray(result, "tradeoffs")
            requireStringArray(result, "preservedIds")
            requireStringArray(result, "evidence")
            requireEnum(result, "confidence", CONFIDENCE)
            requireBooleanTrue(result, "requiresConfirmation")
            val known = request.context.knownActivityIds.toSet()
            val fixed = request.context.fixedActivityIds.toSet()
            val preserved = strings(result, "preservedIds")
            require(fixed.all { it in preserved })
            require(preserved.all { it in known })
            val evidence = strings(result, "evidence")
            require(evidence.all { it in request.context.evidence })
            val proposals = result["proposal"] as? JsonArray ?: throw IllegalArgumentException("proposal must be an array")
            proposals.forEach { value ->
                val item = value as? JsonObject ?: throw IllegalArgumentException("proposal item must be an object")
                requireString(item, "activityId"); requireString(item, "action"); requireString(item, "target")
                val activityId = (item["activityId"] as JsonPrimitive).content
                require(activityId in known); require(activityId !in fixed)
                require((item["action"] as JsonPrimitive).content in PROPOSAL_ACTIONS)
            }
        }
    }

    fun nextAction(request: NextActionRequest, id: String): AiCapabilityResponse = AiCapabilityService(generator, json).nextAction(request, id)
    fun scenario(request: ScenarioRequest, id: String): AiCapabilityResponse = AiCapabilityService(generator, json).scenario(request, id)

    private fun validateRequest(r: ReplanRequest) {
        require(r.schemaVersion == "1") { "unsupported schemaVersion" }
        require(r.context.knownActivityIds.distinct().size == r.context.knownActivityIds.size)
        require(r.context.fixedActivityIds.all { it in r.context.knownActivityIds })
        require(r.context.evidence.size <= MAX_LIST_ITEMS); require(r.context.evidence.all { it.length <= MAX_INPUT_LENGTH })
        require(r.changes.size <= MAX_LIST_ITEMS)
        r.changes.forEach { require(it.type in CHANGE_TYPES); it.activityId?.let { activityId -> require(activityId in r.context.knownActivityIds) }; it.deltaMinutes?.let { minutes -> require(minutes != 0 && kotlin.math.abs(minutes) <= MAX_CHANGE_MINUTES) } }
    }

    private fun generate(id: String, prompt: String, validator: (JsonObject) -> Unit): AiCapabilityResponse {
        val result = try { json.decodeFromString<JsonObject>(cleanJson(generator.generate(prompt))) } catch (e: Exception) { throw InvalidAiCapabilityException("AI returned invalid JSON", e) }
        try { require(result.isNotEmpty()); validator(result) } catch (e: IllegalArgumentException) { throw InvalidAiCapabilityException("AI returned an invalid adaptive capability response", e) }
        return AiCapabilityResponse(id, result, generator.modelName)
    }

    private fun strings(r: JsonObject, key: String) = (r[key] as JsonArray).map { (it as JsonPrimitive).content }
    private fun requireString(r: JsonObject, key: String) { val v = r[key] as? JsonPrimitive; require(v != null && v.isString && v.content.isNotBlank()) }
    private fun requireBooleanTrue(r: JsonObject, key: String) { val v = r[key] as? JsonPrimitive; require(v != null && !v.isString && v.content == "true") }
    private fun requireEnum(r: JsonObject, key: String, allowed: Set<String>) { val v = r[key] as? JsonPrimitive; require(v != null && v.isString && v.content in allowed) }
    private fun requireStringArray(r: JsonObject, key: String) { val v = r[key]; require(v is JsonArray && v.size <= MAX_LIST_ITEMS && v.all { it is JsonPrimitive && it.isString && it.content.length <= MAX_INPUT_LENGTH }) }
    private fun cleanJson(raw: String) = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    companion object {
        private const val MAX_INPUT_LENGTH = 12_000; private const val MAX_LIST_ITEMS = 100; private const val MAX_CHANGE_MINUTES = 24 * 60
        private val CHANGE_TYPES = setOf("DELAY", "CANCEL", "ADD", "DURATION_CHANGE", "CONFLICT")
        private val PROPOSAL_ACTIONS = setOf("MOVE", "KEEP", "SHORTEN", "DEFER", "SPLIT")
        private val CONFIDENCE = setOf("HIGH", "MEDIUM", "LOW")
    }
}
