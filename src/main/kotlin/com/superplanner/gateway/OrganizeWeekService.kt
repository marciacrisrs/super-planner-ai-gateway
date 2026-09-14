package com.superplanner.gateway

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

        val raw = aiTextGenerator.generate(buildPrompt(request))
        val jsonObject = parseJsonObject(raw)

        return try {
            json.decodeFromJsonElement(OrganizeWeekResponse.serializer(), jsonObject)
                .copy(model = aiTextGenerator.modelName)
        } catch (exception: Exception) {
            throw IllegalStateException("AI returned an invalid organize-week proposal", exception)
        }
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
        7. Desires should be fitted realistically according to priority and available capacity.
        8. AI tips are suggestions, never mandatory commitments.
        9. Preserve the original plan; the response is a proposal for review, not an automatic mutation.
        10. Every proposed item must identify its source: existing, fixed, desire, logistics, or ai_suggestion.

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
}
