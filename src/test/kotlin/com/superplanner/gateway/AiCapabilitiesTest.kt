package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json

class AiCapabilitiesTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test
    fun capability_response_is_provider_independent() {
        val service = AiCapabilityService(FakeGenerator("{\"explanation\":\"ok\",\"confidence\":\"HIGH\"}"))
        val response = service.explanation(
            ExplanationRequest(question = "Por quê?", evidence = listOf("janela de 30 minutos")),
            "req-1",
        )
        assertEquals("1", response.schemaVersion)
        assertEquals("req-1", response.requestId)
        assertEquals("fake-model", response.model)
        assertEquals("ok", response.result["explanation"]?.toString()?.trim('"'))
    }

    @Test
    fun invalid_json_is_rejected() {
        val service = AiCapabilityService(FakeGenerator("not-json"))
        assertFailsWith<Exception> {
            service.nextAction(NextActionRequest(), "req-2")
        }
    }

    @Test
    fun request_contract_serializes_without_provider_types() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(
            NaturalLanguageRequest.serializer(),
            NaturalLanguageRequest(message = "Estudar francês amanhã"),
        )
        assertEquals(true, encoded.contains("Estudar francês"))
        assertEquals(false, encoded.contains("gemini"))
    }
}
