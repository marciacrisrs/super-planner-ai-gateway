package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdaptiveAiCapabilityTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test fun replan_preserves_fixed_items_and_returns_explicit_tradeoffs() {
        val request = ReplanRequest(context = ReplanContext(knownActivityIds = listOf("meeting", "gym", "study"), fixedActivityIds = listOf("meeting"), evidence = listOf("meeting atrasou 30 minutos")), changes = listOf(ReplanChange(type = "DELAY", activityId = "meeting", deltaMinutes = 30)))
        val result = AdaptiveAiCapabilityService(FakeGenerator("""{"proposal":[{"activityId":"gym","action":"MOVE","target":"18:00"}],"conflicts":[],"tradeoffs":["gym termina mais tarde"],"preservedIds":["meeting"],"evidence":["meeting atrasou 30 minutos"],"confidence":"HIGH","requiresConfirmation":true}""")).replan(request, "req-25")
        assertEquals("HIGH", result.result["confidence"]?.toString()?.trim('"'))
        assertEquals("meeting", (result.result["preservedIds"] as kotlinx.serialization.json.JsonArray)[0].toString().trim('"'))
    }

    @Test fun replan_rejects_unknown_activity_ids() {
        val request = ReplanRequest(context = ReplanContext(knownActivityIds = listOf("gym")))
        assertFailsWith<InvalidAiCapabilityException> { AdaptiveAiCapabilityService(FakeGenerator("""{"proposal":[{"activityId":"invented","action":"MOVE","target":"18:00"}],"conflicts":[],"tradeoffs":[],"preservedIds":[],"evidence":[],"confidence":"HIGH","requiresConfirmation":true}""")).replan(request, "req-unknown") }
    }

    @Test fun replan_rejects_evidence_not_present_in_context() {
        val request = ReplanRequest(context = ReplanContext(knownActivityIds = listOf("gym"), evidence = listOf("gym tem prioridade 1")))
        assertFailsWith<InvalidAiCapabilityException> { AdaptiveAiCapabilityService(FakeGenerator("""{"proposal":[],"conflicts":[],"tradeoffs":[],"preservedIds":[],"evidence":["prioridade inventada"],"confidence":"HIGH","requiresConfirmation":true}""")).replan(request, "req-evidence") }
    }

    @Test fun replan_rejects_execution_claims() {
        val request = ReplanRequest(context = ReplanContext(knownActivityIds = listOf("gym")))
        assertFailsWith<InvalidAiCapabilityException> { AdaptiveAiCapabilityService(FakeGenerator("""{"proposal":[{"activityId":"gym","action":"APPLIED","target":"18:00"}],"conflicts":[],"tradeoffs":[],"preservedIds":[],"evidence":[],"confidence":"HIGH","requiresConfirmation":true}""")).replan(request, "req-execution") }
    }

    @Test fun next_action_rejects_ineligible_candidate_even_when_model_recommends_it() {
        val request = NextActionRequest(context = NextActionContext(candidateFacts = listOf(NextActionCandidate(id = "gym", fitsAvailableTime = false))), candidates = listOf("gym"))
        assertFailsWith<InvalidAiCapabilityException> { AdaptiveAiCapabilityService(FakeGenerator("""{"recommendedAction":"gym","reason":"prioridade","evidence":[],"alternatives":[],"confidence":"HIGH","uncertainty":[],"requiresClarification":false}""")).nextAction(request, "req-next") }
    }

    @Test fun scenario_marks_changes_hypothetical_and_rejects_realistic_execution_claim() {
        val request = ScenarioRequest(question = "E se eu trabalhar presencialmente na sexta?")
        assertFailsWith<InvalidAiCapabilityException> { AdaptiveAiCapabilityService(FakeGenerator("""{"scenario":"sexta presencial","changes":[{"field":"workMode","from":"remote","to":"office","hypothetical":false}],"assumptions":[],"inferredFields":[],"requiresClarification":false}""")).scenario(request, "req-scenario") }
    }

    @Test fun insufficient_scenario_context_requires_clarification() {
        val request = ScenarioRequest(question = "E se eu adicionar academia?")
        val result = AdaptiveAiCapabilityService(FakeGenerator("""{"scenario":"adicionar academia","changes":[],"assumptions":[],"inferredFields":["duration"],"requiresClarification":true}""")).scenario(request, "req-scenario-low")
        assertEquals("true", result.result["requiresClarification"].toString())
    }
}
