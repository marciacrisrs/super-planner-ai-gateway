package com.superplanner.gateway

import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class OrganizeWeekService(
    private val aiTextGenerator: AiTextGenerator,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun organize(request: OrganizeWeekRequest): OrganizeWeekResponse {
        require(request.weekStart.isNotBlank()) { "weekStart must not be blank" }
        require(request.timezone.isNotBlank()) { "timezone must not be blank" }
        validateInput(request)

        val raw = aiTextGenerator.generate(buildPrompt(request))
        val jsonObject = parseJsonObject(raw)

        return try {
            val response = json.decodeFromJsonElement(OrganizeWeekResponse.serializer(), jsonObject)
            validateResponse(request, response)
            response.copy(model = aiTextGenerator.modelName)
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("AI returned an invalid organize-week proposal", exception)
        } catch (exception: Exception) {
            throw IllegalStateException("AI returned an invalid organize-week proposal", exception)
        }
    }

    private fun validateInput(request: OrganizeWeekRequest) {
        request.existingPlan.validatePlanItems("existingPlan")
        request.fixedCommitments.validatePlanItems("fixedCommitments")
        request.desires.validatePlanItems("desires")
        require(request.aiTips.size <= MAX_LIST_ITEMS) { "aiTips has too many items" }
        request.aiTips.forEach { require(it.length <= MAX_STRING_LENGTH) { "aiTips contains an oversized item" } }
        request.logistics.forEach {
            require(it.minutes >= 0) { "logistics minutes must be non-negative" }
            require(it.type.isNotBlank()) { "logistics type must not be blank" }
        }
        request.capacity?.let { capacity ->
            require(capacity.totalCapacityMinutes >= 0)
            require(capacity.totalDesiredMinutes >= 0)
            require(capacity.totalRemainingMinutes >= 0)
            capacity.days.forEach {
                require(it.schedulableMinutes >= 0)
                require(it.desiredMinutes >= 0)
                require(it.remainingMinutes >= 0)
            }
        }
    }

    private fun validateResponse(request: OrganizeWeekRequest, response: OrganizeWeekResponse) {
        val proposedIds = response.proposedItems.map { it.id }
        require(proposedIds.all(String::isNotBlank)) { "proposed item ids must not be blank" }
        require(proposedIds.distinct().size == proposedIds.size) { "proposed item ids must be unique" }
        response.proposedItems.forEach { item ->
            require(item.title.isNotBlank()) { "proposed item title must not be blank" }
            require(item.source in ALLOWED_SOURCES) { "unsupported proposal source" }
            val start = parseTime(item.startTime, "startTime")
            val end = parseTime(item.endTime, "endTime")
            require(start.isBefore(end)) { "proposed item endTime must be after startTime" }
        }

        val fixedProposals = request.fixedCommitments.associateBy { it.id }
        response.proposedItems.forEach { proposed ->
            fixedProposals[proposed.id]?.let { fixed ->
                return@forEach
            }
            val proposedStart = parseTime(proposed.startTime, "startTime")
            val proposedEnd = parseTime(proposed.endTime, "endTime")
            request.fixedCommitments
                .filter { it.date == proposed.date && it.startTime != null && it.endTime != null }
                .forEach { fixed ->
                    val fixedStart = parseTime(fixed.startTime!!, "fixed startTime")
                    val fixedEnd = parseTime(fixed.endTime!!, "fixed endTime")
                    require(proposedEnd <= fixedStart || proposedStart >= fixedEnd) {
                        "proposal ${proposed.id} overlaps fixed commitment ${fixed.id}"
                    }
                }
        }

        response.conflicts.forEach { conflict ->
            require(conflict.severity in ALLOWED_SEVERITIES) { "unsupported conflict severity" }
            require(conflict.affectedItemIds.distinct().size == conflict.affectedItemIds.size) { "conflict affected ids must be unique" }
        }
        request.fixedCommitments.forEach { fixed ->
            val proposed = response.proposedItems.find { it.id == fixed.id }
                ?: throw IllegalArgumentException("fixed commitment ${fixed.id} was omitted")
            require(proposed.fixed) { "fixed commitment ${fixed.id} lost fixed=true" }
            require(proposed.title == fixed.title) { "fixed commitment ${fixed.id} title was changed" }
            require(proposed.date == fixed.date) { "fixed commitment ${fixed.id} date was changed" }
            require(proposed.startTime == fixed.startTime) { "fixed commitment ${fixed.id} startTime was changed" }
            require(proposed.endTime == fixed.endTime) { "fixed commitment ${fixed.id} endTime was changed" }
        }
    }

    private fun List<PlanItem>.validatePlanItems(name: String) {
        require(size <= MAX_LIST_ITEMS) { "$name has too many items" }
        val ids = map { it.id }
        require(ids.none(String::isBlank)) { "$name contains a blank id" }
        require(ids.distinct().size == ids.size) { "$name ids must be unique" }
        forEach {
            require(it.title.length <= MAX_STRING_LENGTH) { "$name contains an oversized title" }
            it.durationMinutes?.let { minutes -> require(minutes > 0) { "$name duration must be positive" } }
        }
    }

    private fun parseTime(value: String, field: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (exception: Exception) {
        throw IllegalArgumentException("invalid $field")
    }

    private fun buildPrompt(request: OrganizeWeekRequest): String = """
        You are the AI planning assistant for Super Planner.

        Core rule: AI converses and proposes; the domain/planning engine decides and validates.
        You are NOT allowed to silently change fixed commitments or invent a second scheduling logic.
        Return ONLY valid JSON. Do not use Markdown, code fences, comments, or additional text.

        Organize the requested week using the supplied planning context.
        Respect these rules:
        1. Fixed commitments cannot be changed silently.
        2. An item with a defined startTime must never start before that time.
        3. Activities may be scheduled before work; work is not the start of the day.
        4. Required commute and preparation time are real occupied time and cannot overlap optional activities.
        5. Calculate preparation/commute backwards when a fixed start time requires it.
        6. Detect conflicts instead of hiding them or forcing an impossible schedule.
        7. Desires should be scheduled only when the supplied capacity supports them.
        8. If capacity is TIGHT, prefer fewer activities and preserve meaningful recovery margin.
        9. If capacity is OVER_CAPACITY, do not pretend all desires fit; expose explicit trade-offs/conflicts.
        10. Treat sleep, recovery, work, commitments, logistics and preparation represented by the capacity facts as protected time.
        11. Use historical capacity facts as evidence, not as a reason to blame or score the person.
        12. AI tips are suggestions, never mandatory commitments.
        13. Preserve the original plan; the response is a proposal for review, not an automatic mutation.
        14. Every proposed item must identify its source: existing, fixed, desire, logistics, or ai_suggestion.

        Output this exact JSON shape:
        {
          "summary": {
            "fixedCommitmentsConsidered": 0,
            "desiresConsidered": 0,
            "commuteMinutesConsidered": 0,
            "preparationMinutesConsidered": 0,
            "aiSuggestionsConsidered": 0,
            "conflictsFound": 0,
            "opportunitiesFound": 0
          },
          "proposedItems": [
            {
              "id": "string",
              "title": "string",
              "date": "YYYY-MM-DD",
              "startTime": "HH:mm",
              "endTime": "HH:mm",
              "source": "existing|fixed|desire|logistics|ai_suggestion",
              "fixed": false,
              "reason": "string or null"
            }
          ],
          "conflicts": [
            {
              "id": "string",
              "title": "string",
              "affectedItemIds": ["string"],
              "reason": "string",
              "severity": "low|medium|high"
            }
          ],
          "opportunities": [
            {
              "id": "string",
              "title": "string",
              "reason": "string"
            }
          ],
          "explanations": [
            {
              "itemId": "string or null",
              "message": "string"
            }
          ]
        }

        Planning context:
        ${json.encodeToString(OrganizeWeekRequest.serializer(), request)}
    """.trimIndent()

    private fun parseJsonObject(raw: String): JsonObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (exception: Exception) {
            throw IllegalStateException("AI returned non-JSON organize-week output", exception)
        }
    }

    companion object {
        private const val MAX_LIST_ITEMS = 100
        private const val MAX_STRING_LENGTH = 12_000
        private val ALLOWED_SOURCES = setOf("existing", "fixed", "desire", "logistics", "ai_suggestion")
        private val ALLOWED_SEVERITIES = setOf("low", "medium", "high")
    }
}
