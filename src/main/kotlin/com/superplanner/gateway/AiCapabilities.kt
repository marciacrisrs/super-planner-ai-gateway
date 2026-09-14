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
data class NextActionCandidate(
    val id: String,
    val durationMinutes: Int? = null,
    val preparationMinutes: Int? = null,
    val travelMinutes: Int? = null,
    val priority: Int? = null,
    val dependenciesSatisfied: Boolean? = null,
    val conflictFree: Boolean? = null,
    val fitsAvailableTime: Boolean? = null,
)

@Serializable
data class NextActionContext(
    val nowIso: String? = null,
    val availableMinutes: Int? = null,
    val evidence: List<String> = emptyList(),
    val candidateFacts: List<NextActionCandidate> = emptyList(),
)

@Serializable
data class NextActionRequest(
    val schemaVersion: String = "1",
    val context: NextActionContext = NextActionContext(),
    val candidates: List<String> = emptyList(),
)

@Serializable
data class AiCapabilityResponse(val schemaVersion: String = "1", val requestId: String, val result: JsonObject, val model: String)

class AiCapabilityService(
    private val generator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    fun naturalLanguage(request: NaturalLanguageRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.message); validateContext(request.context)
        return generate(requestId, naturalLanguagePrompt(request), ::validateNaturalLanguage)
    }

    fun explanation(request: ExplanationRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question); validateStringList(request.evidence, "evidence")
        return generate(requestId, """
            You are the explanation layer of Super Planner. Explain using ONLY supplied evidence.
            If evidence is insufficient, explicitly say so and use LOW confidence. evidenceUsed must contain only exact supplied entries.
            If evidence conflicts, explicitly acknowledge the conflict.
            Return ONLY JSON: {"explanation":string,"evidenceUsed":[string],"confidence":"HIGH|MEDIUM|LOW"}
            Question: ${json.encodeToString(ExplanationRequest.serializer(), request)}
        """.trimIndent(), { result -> validateExplanation(result, request.evidence) })
    }

    fun command(request: CommandRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.message); validateContext(request.context)
        return generate(requestId, """
            Convert the request into exactly one known PlanningCommand. Never execute, calculate routes, access databases, or invent facts.
            Commands: MARK_DELAYED, CANCEL_ACTIVITY, CREATE_ACTIVITY_DRAFT, CHANGE_ACTIVITY, REORGANIZE_DAY, MISSING_INFORMATION.
            Use strict payload schemas. Unknown or ambiguous requests must use MISSING_INFORMATION.
            Return ONLY JSON: {"commandType":"...","requiresConfirmation":true,"payload":{},"explanation":string}
            Request: ${json.encodeToString(CommandRequest.serializer(), request)}
        """.trimIndent(), ::validateCommand)
    }

    fun insight(request: InsightRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }; require(request.sampleSize >= 0) { "sampleSize must not be negative" }
        validateStringList(request.evidence, "evidence")
        return generate(requestId, """
            Interpret only supplied evidence. Do not invent statistics or treat small samples as facts. Never modify commitments or planning rules.
            Return ONLY JSON: {"insights":[{"title":string,"description":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}],"recommendations":[string]}
            Request: ${json.encodeToString(InsightRequest.serializer(), request)}
        """.trimIndent(), { result -> validateInsights(result, request.evidence, request.sampleSize) })
    }

    fun preference(request: PreferenceRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }; validateStringList(request.observations, "observations")
        return generate(requestId, """
            Infer candidate planning preferences only from supplied observations. One isolated observation is insufficient.
            Observed behavior and inferred preference must remain distinct. Do not persist or apply preferences.
            Return ONLY JSON: {"preferences":[{"preference":string,"evidence":[string],"confidence":"HIGH|MEDIUM|LOW"}]}
            Observations: ${json.encodeToString(PreferenceRequest.serializer(), request)}
        """.trimIndent(), { result -> validatePreferences(result, request.observations) })
    }

    fun scenario(request: ScenarioRequest, requestId: String): AiCapabilityResponse {
        validate(request.schemaVersion, request.question); validateContext(request.context)
        return generate(requestId, """
            Interpret a hypothetical planning scenario only. Never mutate or simulate the real plan.
            Separate hypothetical changes from supplied real context. Mark every change hypothetical and list inferred fields.
            Ambiguous or unsupported requests must set requiresClarification=true.
            Return ONLY JSON: {"scenario":string,"changes":[{"field":string,"from":string|null,"to":string,"hypothetical":true}],"assumptions":[string],"inferredFields":[string],"requiresClarification":boolean}
            Request: ${json.encodeToString(ScenarioRequest.serializer(), request)}
        """.trimIndent(), ::validateScenario)
    }

    fun nextAction(request: NextActionRequest, requestId: String): AiCapabilityResponse {
        require(request.schemaVersion == "1") { "unsupported schemaVersion" }
        validateNextActionRequest(request)
        return generate(requestId, """
            Recommend a next action only from the supplied candidate list. Never invent or calculate planning facts.
            Use supplied temporal/planning facts exactly as provided. Respect duration, preparation, travel, priority, dependencies and conflict status when those facts exist.
            fitsAvailableTime is a domain-supplied feasibility fact; do not recompute it. dependenciesSatisfied=false or conflictFree=false means the candidate is not eligible for recommendation.
            If context is insufficient, return recommendedAction=null, confidence=LOW, requiresClarification=true and explain the missing context in uncertainty.
            Evidence must contain only exact supplied context evidence. Suggestions are not execution.
            Return ONLY JSON: {"recommendedAction":"candidate id or null","reason":string,"evidence":[string],"alternatives":["candidate ids"],"confidence":"HIGH|MEDIUM|LOW","uncertainty":[string],"requiresClarification":boolean}
            Alternatives must be supplied candidates and at most two.
            Request: ${json.encodeToString(NextActionRequest.serializer(), request)}
        """.trimIndent(), { result -> validateNextAction(result, request) })
    }

    private fun validate(schemaVersion: String, message: String) {
        require(schemaVersion == "1") { "unsupported schemaVersion" }; require(message.isNotBlank()) { "message must not be blank" }; require(message.length <= MAX_INPUT_LENGTH) { "input exceeds maximum length" }
    }
    private fun validateContext(context: AiProposalContext) { context.nowIso?.let { require(it.length <= MAX_INPUT_LENGTH) }; context.activeActivityId?.let { require(it.length <= MAX_INPUT_LENGTH) }; validateStringList(context.minimalRouteFacts, "minimalRouteFacts") }
    private fun validateStringList(values: List<String>, name: String) { require(values.size <= MAX_LIST_ITEMS) { "$name has too many items" }; values.forEach { require(it.length <= MAX_INPUT_LENGTH) { "$name contains an oversized item" } } }
    private fun generate(requestId: String, prompt: String, validator: (JsonObject) -> Unit): AiCapabilityResponse {
        val result = try { json.decodeFromString<JsonObject>(cleanJson(generator.generate(prompt))) } catch (e: Exception) { throw InvalidAiCapabilityException("AI returned invalid JSON", e) }
        require(result.isNotEmpty()) { "AI returned an empty result" }
        try { validator(result) } catch (e: IllegalArgumentException) { throw InvalidAiCapabilityException("AI returned an invalid capability response", e) }
        return AiCapabilityResponse(requestId = requestId, result = result, model = generator.modelName)
    }
    private fun validateNaturalLanguage(result: JsonObject) { requireEnum(result, "commandType", setOf("CREATE_ACTIVITY_DRAFT", "MISSING_INFORMATION")); requireString(result, "explanation"); requireBoolean(result, "requiresConfirmation"); requireObject(result, "payload"); requireStringArray(result, "inferredFields"); requireStringArray(result, "missingFields"); if ((result["commandType"] as JsonPrimitive).content == "CREATE_ACTIVITY_DRAFT") require(result["requiresConfirmation"]?.toString() == "true") }
    private fun validateExplanation(result: JsonObject, supplied: List<String>) { requireString(result, "explanation"); requireStringArray(result, "evidenceUsed"); requireEnum(result, "confidence", CONFIDENCE); val used = (result["evidenceUsed"] as JsonArray).map { (it as JsonPrimitive).content }; require(used.all { it in supplied }); if (supplied.isEmpty()) { require(used.isEmpty()); require((result["confidence"] as JsonPrimitive).content == "LOW") } }
    private fun validateCommand(result: JsonObject) { requireEnum(result, "commandType", COMMANDS); requireBoolean(result, "requiresConfirmation"); requireObject(result, "payload"); requireString(result, "explanation"); val command = (result["commandType"] as JsonPrimitive).content; if (command != "MISSING_INFORMATION") require(result["requiresConfirmation"]?.toString() == "true"); validateCommandPayload(command, result["payload"] as JsonObject) }
    private fun validateCommandPayload(command: String, payload: JsonObject) {
        when (command) {
            "MARK_DELAYED" -> { requireExactKeys(payload, setOf("activityId", "delayMinutes")); requireString(payload, "activityId"); requirePositiveInteger(payload, "delayMinutes") }
            "CANCEL_ACTIVITY" -> { requireExactKeys(payload, setOf("activityId")); requireString(payload, "activityId") }
            "CREATE_ACTIVITY_DRAFT" -> { require(payload.containsKey("title")); requireString(payload, "title"); val allowed = setOf("title", "date", "startTime", "durationMinutes", "recurrence"); require(payload.keys.all { it in allowed }); listOf("date", "startTime", "recurrence").forEach { if (payload.containsKey(it)) requireString(payload, it) }; if (payload.containsKey("durationMinutes")) requirePositiveInteger(payload, "durationMinutes") }
            "CHANGE_ACTIVITY" -> { requireExactKeys(payload, setOf("activityId", "changes")); requireString(payload, "activityId"); val changes = payload["changes"] as? JsonObject; require(changes != null && changes.isNotEmpty()) }
            "REORGANIZE_DAY" -> { require(payload.isNotEmpty()); val allowed = setOf("date", "reason", "constraints"); require(payload.keys.all { it in allowed }); if (payload.containsKey("date")) requireString(payload, "date"); if (payload.containsKey("reason")) requireString(payload, "reason"); if (payload.containsKey("constraints")) requireStringArray(payload, "constraints") }
            "MISSING_INFORMATION" -> { requireExactKeys(payload, setOf("missingFields")); requireStringArray(payload, "missingFields"); require((payload["missingFields"] as JsonArray).isNotEmpty()) }
        }
    }
    private fun validateInsights(result: JsonObject, supplied: List<String>, sampleSize: Int) { val insights = result["insights"] as? JsonArray ?: throw IllegalArgumentException(); insights.forEach { item -> val obj = item as? JsonObject ?: throw IllegalArgumentException(); requireString(obj,"title"); requireString(obj,"description"); requireStringArray(obj,"evidence"); requireEnum(obj,"confidence",CONFIDENCE); val evidence=(obj["evidence"] as JsonArray).map{(it as JsonPrimitive).content}; require(evidence.all{it in supplied}); if(sampleSize < MIN_INSIGHT_SAMPLE_SIZE) require((obj["confidence"] as JsonPrimitive).content=="LOW") }; requireStringArray(result,"recommendations") }
    private fun validatePreferences(result: JsonObject, observations: List<String>) { val preferences=result["preferences"] as? JsonArray ?: throw IllegalArgumentException(); preferences.forEach { item -> val obj=item as? JsonObject ?: throw IllegalArgumentException(); requireString(obj,"preference"); requireStringArray(obj,"evidence"); requireEnum(obj,"confidence",CONFIDENCE); val evidence=(obj["evidence"] as JsonArray).map{(it as JsonPrimitive).content}; require(evidence.isNotEmpty()); require(evidence.all{it in observations}); if(observations.size < MIN_PREFERENCE_SAMPLE_SIZE) require((obj["confidence"] as JsonPrimitive).content=="LOW") } }
    private fun validateScenario(result: JsonObject) { requireString(result,"scenario"); requireStringArray(result,"assumptions"); requireStringArray(result,"inferredFields"); requireBoolean(result,"requiresClarification"); val changes=result["changes"] as? JsonArray ?: throw IllegalArgumentException(); changes.forEach { item -> val c=item as? JsonObject ?: throw IllegalArgumentException(); requireString(c,"field"); requireString(c,"to"); requireBoolean(c,"hypothetical"); require(c["hypothetical"]?.toString()=="true"); c["from"]?.let{ require(it is JsonPrimitive && it.isString) } } }

    private fun validateNextActionRequest(request: NextActionRequest) {
        require(request.candidates.size <= MAX_LIST_ITEMS) { "candidates has too many items" }
        require(request.candidates.distinct().size == request.candidates.size) { "candidates must be unique" }
        validateStringList(request.candidates, "candidates")
        request.context.nowIso?.let { require(it.length <= MAX_INPUT_LENGTH) }
        request.context.availableMinutes?.let { require(it >= 0) }
        validateStringList(request.context.evidence, "evidence")
        require(request.context.candidateFacts.size <= MAX_LIST_ITEMS) { "candidateFacts has too many items" }
        request.context.candidateFacts.forEach { fact ->
            require(fact.id.isNotBlank() && fact.id.length <= MAX_INPUT_LENGTH)
            require(fact.id in request.candidates)
            fact.durationMinutes?.let { require(it > 0) }
            fact.preparationMinutes?.let { require(it >= 0) }
            fact.travelMinutes?.let { require(it >= 0) }
            fact.priority?.let { require(it >= 0) }
        }
        require(request.context.candidateFacts.map { it.id }.distinct().size == request.context.candidateFacts.size) { "candidateFacts ids must be unique" }
    }

    private fun validateNextAction(result: JsonObject, request: NextActionRequest) {
        val recommended = result["recommendedAction"]?.let { value ->
            when (value) {
                is JsonPrimitive -> if (value.content == "null" && !value.isString) null else value.content
                else -> throw IllegalArgumentException()
            }
        }
        require(recommended == null || recommended in request.candidates)
        requireString(result, "reason")
        requireStringArray(result, "evidence")
        requireStringArray(result, "uncertainty")
        requireEnum(result, "confidence", CONFIDENCE)
        requireBoolean(result, "requiresClarification")
        val evidence = (result["evidence"] as JsonArray).map { (it as JsonPrimitive).content }
        require(evidence.all { it in request.context.evidence })
        val alternatives = result["alternatives"] as? JsonArray ?: throw IllegalArgumentException()
        require(alternatives.size <= 2)
        require(alternatives.all { it is JsonPrimitive && it.isString && it.content in request.candidates })
        require(alternatives.distinct().size == alternatives.size)
        require(recommended !in alternatives.map { (it as JsonPrimitive).content })

        val factsById = request.context.candidateFacts.associateBy { it.id }
        fun eligible(id: String): Boolean {
            val facts = factsById[id] ?: return true
            return facts.dependenciesSatisfied != false && facts.conflictFree != false && facts.fitsAvailableTime != false
        }
        if (recommended != null) require(eligible(recommended))
        alternatives.forEach { require(eligible((it as JsonPrimitive).content)) }

        val hasPlanningContext = request.context.nowIso != null || request.context.availableMinutes != null || request.context.evidence.isNotEmpty() || request.context.candidateFacts.isNotEmpty()
        if (request.candidates.isEmpty() || !hasPlanningContext) {
            require(recommended == null)
            require((result["confidence"] as JsonPrimitive).content == "LOW")
            require((result["requiresClarification"] as JsonPrimitive).content == "true")
        }
        if (recommended == null) require((result["requiresClarification"] as JsonPrimitive).content == "true")
    }

    private fun requireString(result: JsonObject,key:String){val v=result[key] as? JsonPrimitive; require(v!=null&&v.isString&&v.content.isNotBlank())}
    private fun requireBoolean(result: JsonObject,key:String){val v=result[key] as? JsonPrimitive; require(v!=null&&!v.isString&&v.content in setOf("true","false"))}
    private fun requirePositiveInteger(result: JsonObject,key:String){val v=result[key] as? JsonPrimitive; require(v!=null&&!v.isString&&v.content.toIntOrNull()?.let{it>0}==true)}
    private fun requireObject(result: JsonObject,key:String){require(result[key] is JsonObject)}
    private fun requireExactKeys(payload:JsonObject,expected:Set<String>){require(payload.keys==expected)}
    private fun requireStringArray(result:JsonObject,key:String){val v=result[key]; require(v is JsonArray&&v.size<=MAX_LIST_ITEMS&&v.all{it is JsonPrimitive&&it.isString&&it.content.length<=MAX_INPUT_LENGTH})}
    private fun requireEnum(result:JsonObject,key:String,allowed:Set<String>){val v=result[key] as? JsonPrimitive; require(v!=null&&v.isString&&v.content in allowed)}
    private fun cleanJson(raw:String):String=raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    private fun naturalLanguagePrompt(request:NaturalLanguageRequest):String="""Interpret planning intent without execution or persistence. Return ONLY JSON with commandType, explanation, requiresConfirmation, payload, inferredFields and missingFields. Use only CREATE_ACTIVITY_DRAFT or MISSING_INFORMATION; ambiguity must be explicit. Request: ${json.encodeToString(NaturalLanguageRequest.serializer(),request)}"""
    companion object { private const val MAX_INPUT_LENGTH=12_000; private const val MAX_LIST_ITEMS=100; private const val MIN_INSIGHT_SAMPLE_SIZE=3; private const val MIN_PREFERENCE_SAMPLE_SIZE=3; private val COMMANDS=setOf("MARK_DELAYED","CANCEL_ACTIVITY","CREATE_ACTIVITY_DRAFT","CHANGE_ACTIVITY","REORGANIZE_DAY","MISSING_INFORMATION"); private val CONFIDENCE=setOf("HIGH","MEDIUM","LOW") }
}
