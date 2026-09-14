package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextActionCapabilityTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test fun little_available_time_cannot_recommend_domain_infeasible_candidate() {
        val request = NextActionRequest(
            context = NextActionContext(
                nowIso = "2026-09-14T12:00:00-03:00",
                availableMinutes = 20,
                candidateFacts = listOf(NextActionCandidate(id = "study", durationMinutes = 45, fitsAvailableTime = false)),
            ),
            candidates = listOf("study", "walk"),
        )
        assertFailsWith<InvalidAiCapabilityException> {
            AiCapabilityService(FakeGenerator("""{"recommendedAction":"study","reason":"é a próxima","evidence":[],"alternatives":["walk"],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(request, "req-time")
        }
    }

    @Test fun conflicts_and_unsatisfied_dependencies_cannot_be_recommended() {
        val request = NextActionRequest(
            context = NextActionContext(
                nowIso = "2026-09-14T12:00:00-03:00",
                candidateFacts = listOf(
                    NextActionCandidate(id = "meeting", durationMinutes = 30, conflictFree = false),
                    NextActionCandidate(id = "study", durationMinutes = 30, dependenciesSatisfied = false),
                ),
            ),
            candidates = listOf("meeting", "study"),
        )
        assertFailsWith<InvalidAiCapabilityException> {
            AiCapabilityService(FakeGenerator("""{"recommendedAction":"meeting","reason":"prioridade alta","evidence":[],"alternatives":["study"],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(request, "req-conflict")
        }
    }

    @Test fun structured_response_grounds_reason_and_limits_alternatives() {
        val request = NextActionRequest(
            context = NextActionContext(
                nowIso = "2026-09-14T12:00:00-03:00",
                availableMinutes = 60,
                evidence = listOf("reunião termina às 13h", "academia tem prioridade 1"),
                candidateFacts = listOf(
                    NextActionCandidate(id = "gym", durationMinutes = 45, preparationMinutes = 10, travelMinutes = 5, priority = 1, dependenciesSatisfied = true, conflictFree = true, fitsAvailableTime = true),
                    NextActionCandidate(id = "read", durationMinutes = 30, priority = 2, dependenciesSatisfied = true, conflictFree = true, fitsAvailableTime = true),
                    NextActionCandidate(id = "mail", durationMinutes = 15, priority = 3, dependenciesSatisfied = true, conflictFree = true, fitsAvailableTime = true),
                ),
            ),
            candidates = listOf("gym", "read", "mail"),
        )
        val r = AiCapabilityService(FakeGenerator("""{"recommendedAction":"gym","reason":"A academia tem maior prioridade e foi marcada como viável.","evidence":["academia tem prioridade 1"],"alternatives":["read","mail"],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(request, "req-structured")
        assertEquals("gym", r.result["recommendedAction"]?.toString()?.trim('"'))
        assertEquals(2, (r.result["alternatives"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun no_planning_context_requires_clarification_and_low_confidence() {
        val request = NextActionRequest(candidates = listOf("study"))
        val r = AiCapabilityService(FakeGenerator("""{"recommendedAction":null,"reason":"Não há contexto suficiente para escolher.","evidence":[],"alternatives":[],"confidence":"LOW","uncertainty":["faltam horário disponível e fatos das atividades"],"requiresClarification":true}""")).nextAction(request, "req-no-context")
        assertEquals("null", r.result["recommendedAction"].toString())
        assertEquals("LOW", r.result["confidence"]?.toString()?.trim('"'))
        assertEquals("true", r.result["requiresClarification"].toString())
    }
}
