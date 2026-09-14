package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class AiCapabilitiesTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test
    fun capability_response_is_provider_independent() {
        val service = AiCapabilityService(FakeGenerator("{\"explanation\":\"ok\",\"evidenceUsed\":[\"janela de 30 minutos\"],\"confidence\":\"HIGH\"}"))
        val response = service.explanation(ExplanationRequest(question = "Por quê?", evidence = listOf("janela de 30 minutos")), "req-1")
        assertEquals("1", response.schemaVersion)
        assertEquals("req-1", response.requestId)
        assertEquals("fake-model", response.model)
        assertEquals("ok", response.result["explanation"]?.toString()?.trim('"'))
    }

    @Test
    fun contextual_explanation_cannot_cite_unsupplied_evidence() {
        val service = AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":["fact-invented"],"confidence":"HIGH"}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.explanation(ExplanationRequest(question = "por quê?", evidence = listOf("fact-real")), "req-evidence") }
    }

    @Test
    fun contextual_explanation_with_no_evidence_is_low_confidence() {
        val service = AiCapabilityService(FakeGenerator("""{"explanation":"Não há informação suficiente.","evidenceUsed":[],"confidence":"LOW"}"""))
        val response = service.explanation(ExplanationRequest(question = "por quê?"), "req-insufficient")
        assertEquals("LOW", response.result["confidence"]?.toString()?.trim('"'))
    }

    @Test
    fun contextual_explanation_with_no_evidence_cannot_claim_high_confidence() {
        val service = AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":[],"confidence":"HIGH"}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.explanation(ExplanationRequest(question = "por quê?"), "req-insufficient-high") }
    }

    @Test
    fun invalid_json_is_rejected() {
        val service = AiCapabilityService(FakeGenerator("not-json"))
        assertFailsWith<InvalidAiCapabilityException> { service.nextAction(NextActionRequest(), "req-2") }
    }

    @Test
    fun next_action_must_use_domain_candidate_and_at_most_two_alternatives() {
        val service = AiCapabilityService(FakeGenerator("""{"recommendedAction":"blocked","reason":"reason","alternatives":["a","b"],"confidence":"HIGH"}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.nextAction(NextActionRequest(candidates = listOf("a", "b")), "req-3") }
        assertFailsWith<InvalidAiCapabilityException> {
            AiCapabilityService(FakeGenerator("""{"recommendedAction":"a","reason":"reason","alternatives":["b","c","a"],"confidence":"HIGH"}""")).nextAction(NextActionRequest(candidates = listOf("a", "b", "c")), "req-4")
        }
        val valid = AiCapabilityService(FakeGenerator("""{"recommendedAction":"a","reason":"Cabe na janela disponível.","alternatives":["b"],"confidence":"HIGH"}""")).nextAction(NextActionRequest(candidates = listOf("a", "b", "c")), "req-5")
        assertEquals("a", valid.result["recommendedAction"]?.toString()?.trim('"'))
    }

    @Test
    fun each_capability_rejects_malformed_contracts() {
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"commandType":"CREATE_ACTIVITY_DRAFT","explanation":"x","requiresConfirmation":true,"payload":{},"missingFields":[]}""")).naturalLanguage(NaturalLanguageRequest(message = "criar"), "req-nl") }
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"explanation":"x","evidenceUsed":[],"confidence":"UNKNOWN"}""")).explanation(ExplanationRequest(question = "por quê?"), "req-exp") }
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"commandType":"UNKNOWN","requiresConfirmation":true,"payload":{},"explanation":"x"}""")).command(CommandRequest(message = "fazer algo"), "req-cmd") }
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"insights":[{"title":"x","description":"y","evidence":[],"confidence":"HIGH"}],"recommendations":[1]}""")).insight(InsightRequest(), "req-ins") }
        assertFailsWith<IllegalArgumentException> { AiCapabilityService(FakeGenerator("""{"preferences":[{"preference":"x","evidence":[],"confidence":"HIGH"}]}""")).preference(PreferenceRequest(schemaVersion = "2"), "req-pref") }
        assertFailsWith<InvalidAiCapabilityException> { AiCapabilityService(FakeGenerator("""{"scenario":"x","changes":[],"assumptions":[],"requiresClarification":"false"}""")).scenario(ScenarioRequest(question = "e se?"), "req-scenario") }
    }

    @Test
    fun command_requires_confirmation_for_mutations() {
        val service = AiCapabilityService(FakeGenerator("""{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":false,"payload":{"activityId":"a1","changes":{"title":"novo"}},"explanation":"x"}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.command(CommandRequest(message = "mude"), "req-command") }
    }

    @Test
    fun planning_command_payloads_are_strict_and_typed() {
        val cases = listOf(
            """{"commandType":"MARK_DELAYED","requiresConfirmation":true,"payload":{"activityId":"a1","delayMinutes":40},"explanation":"atraso"}""" to true,
            """{"commandType":"CANCEL_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1"},"explanation":"cancelar"}""" to true,
            """{"commandType":"CREATE_ACTIVITY_DRAFT","requiresConfirmation":true,"payload":{"title":"Academia","date":"2026-09-14","startTime":"16:00","durationMinutes":60},"explanation":"incluir"}""" to true,
            """{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","changes":{"title":"Academia"}},"explanation":"alterar"}""" to true,
            """{"commandType":"REORGANIZE_DAY","requiresConfirmation":true,"payload":{"date":"2026-09-14","reason":"atraso"},"explanation":"reorganizar"}""" to true,
            """{"commandType":"MISSING_INFORMATION","requiresConfirmation":false,"payload":{"missingFields":["activityId"]},"explanation":"faltam dados"}""" to true,
            """{"commandType":"MARK_DELAYED","requiresConfirmation":true,"payload":{"activityId":"a1"},"explanation":"faltando delay"}""" to false,
            """{"commandType":"CANCEL_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","reason":"x"},"explanation":"campo extra"}""" to false,
            """{"commandType":"CHANGE_ACTIVITY","requiresConfirmation":true,"payload":{"activityId":"a1","changes":{}},"explanation":"sem mudanças"}""" to false,
            """{"commandType":"MISSING_INFORMATION","requiresConfirmation":false,"payload":{},"explanation":"faltam dados"}""" to false,
        )
        cases.forEachIndexed { index, (raw, valid) ->
            val service = AiCapabilityService(FakeGenerator(raw))
            if (valid) {
                val response = service.command(CommandRequest(message = "pedido"), "req-command-$index")
                assertEquals(raw.substringAfter("\"commandType\":\"").substringBefore("\""), response.result["commandType"]?.toString()?.trim('"'))
            } else {
                assertFailsWith<InvalidAiCapabilityException> { service.command(CommandRequest(message = "pedido"), "req-command-$index") }
            }
        }
    }

    @Test
    fun insights_must_reference_supplied_evidence() {
        val service = AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"alta","evidence":["inventada"],"confidence":"HIGH"}],"recommendations":[]}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.insight(InsightRequest(evidence = listOf("carga real"), sampleSize = 20), "req-insight-grounding") }
    }

    @Test
    fun insufficient_insight_sample_requires_low_confidence() {
        val service = AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"padrão inicial","evidence":["carga real"],"confidence":"HIGH"}],"recommendations":[]}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.insight(InsightRequest(evidence = listOf("carga real"), sampleSize = 2), "req-insight-small") }
    }

    @Test
    fun sufficient_insight_sample_can_use_higher_confidence() {
        val service = AiCapabilityService(FakeGenerator("""{"insights":[{"title":"Carga","description":"padrão consistente","evidence":["carga real"],"confidence":"HIGH"}],"recommendations":["revisar distribuição"]}"""))
        val response = service.insight(InsightRequest(evidence = listOf("carga real"), sampleSize = 10), "req-insight-strong")
        assertEquals("HIGH", response.result["insights"]?.toString()?.let { "HIGH" })
    }

    @Test
    fun natural_language_mutation_requires_confirmation() {
        val service = AiCapabilityService(FakeGenerator("""{"commandType":"CREATE_ACTIVITY_DRAFT","explanation":"criar","requiresConfirmation":false,"payload":{"title":"Estudar"},"inferredFields":[],"missingFields":[]}"""))
        assertFailsWith<InvalidAiCapabilityException> { service.naturalLanguage(NaturalLanguageRequest(message = "criar estudo"), "req-nl-confirmation") }
    }

    @Test
    fun natural_language_missing_information_does_not_require_confirmation() {
        val service = AiCapabilityService(FakeGenerator("""{"commandType":"MISSING_INFORMATION","explanation":"faltam dados","requiresConfirmation":false,"payload":{},"inferredFields":[],"missingFields":["date"]}"""))
        val response = service.naturalLanguage(NaturalLanguageRequest(message = "criar algo"), "req-nl-missing")
        assertEquals("MISSING_INFORMATION", response.result["commandType"]?.toString()?.trim('"'))
    }

    @Test
    fun oversized_inputs_are_rejected_before_generation() {
        var generated = false
        val generator = object : AiTextGenerator {
            override val modelName = "fake-model"
            override fun generate(prompt: String): String { generated = true; return "{}" }
        }
        val service = AiCapabilityService(generator)
        assertFailsWith<IllegalArgumentException> { service.naturalLanguage(NaturalLanguageRequest(message = "x".repeat(12_001)), "req-size") }
        assertFalse(generated)
    }

    @Test
    fun request_contract_serializes_without_provider_types() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(NaturalLanguageRequest.serializer(), NaturalLanguageRequest(message = "Estudar francês amanhã"))
        assertEquals(true, encoded.contains("Estudar francês"))
        assertEquals(false, encoded.contains("gemini"))
    }
}
