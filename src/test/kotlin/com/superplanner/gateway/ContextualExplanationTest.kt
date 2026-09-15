package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextualExplanationTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }

    @Test
    fun contextual_explanation_returns_a_traceable_valid_explanation() {
        val window = "janela disponível: 90 minutos"
        val duration = "duração da atividade: 60 minutos"
        val response = AiCapabilityService(
            FakeGenerator(
                """{"explanation":"A atividade cabe na janela disponível.","evidenceUsed":["$window","$duration"],"confidence":"HIGH"}"""
            )
        ).explanation(
            ExplanationRequest(
                question = "Por que esta atividade cabe?",
                evidence = listOf(window, duration),
            ),
            "req-explanation-valid",
        )

        assertEquals("HIGH", response.result["confidence"]?.toString()?.trim('"'))
        val expectedEvidence = "[\"$window\",\"$duration\"]"
        assertEquals(expectedEvidence, response.result["evidenceUsed"]?.toString())
    }

    @Test
    fun contextual_explanation_with_insufficient_evidence_requires_low_confidence() {
        val response = AiCapabilityService(
            FakeGenerator(
                """
                {"explanation":"Não há informação suficiente para justificar a decisão.",
                "evidenceUsed":[],"confidence":"LOW"}
                """.trimIndent().replace("\n", "")
            )
        ).explanation(
            ExplanationRequest(
                question = "Por que esta é a próxima atividade?",
                evidence = emptyList(),
            ),
            "req-explanation-insufficient",
        )

        assertEquals("LOW", response.result["confidence"]?.toString()?.trim('"'))
        assertEquals("[]", response.result["evidenceUsed"]?.toString())
    }

    @Test
    fun contextual_explanation_rejects_evidence_not_supplied_by_domain() {
        val supplied = "janela disponível: 30 minutos"
        val invented = "prioridade: alta"
        val response = """
            {"explanation":"A atividade deve ser feita agora porque tem prioridade alta.",
            "evidenceUsed":["$supplied","$invented"],"confidence":"HIGH"}
        """.trimIndent().replace("\n", "")

        val exception = runCatching {
            AiCapabilityService(FakeGenerator(response)).explanation(
                ExplanationRequest(
                    question = "Por que esta é a próxima atividade?",
                    evidence = listOf(supplied),
                ),
                "req-explanation-ungrounded",
            )
        }.exceptionOrNull()

        assertEquals(InvalidAiCapabilityException::class, exception?.javaClass)
    }

    @Test
    fun contextual_explanation_explicitly_acknowledges_conflicting_evidence() {
        val first = "janela disponível: 30 minutos"
        val second = "duração da atividade: 60 minutos"
        val response = AiCapabilityService(
            FakeGenerator(
                """{"explanation":"As evidências entram em conflito: a janela disponível é menor que a duração da atividade.","evidenceUsed":["$first","$second"],"confidence":"MEDIUM"}"""
            )
        ).explanation(
            ExplanationRequest(
                question = "Por que não cabe nesta janela?",
                evidence = listOf(first, second),
            ),
            "req-explanation-conflict",
        )

        assertEquals("MEDIUM", response.result["confidence"]?.toString()?.trim('"'))
        val expectedEvidence = "[\"$first\",\"$second\"]"
        assertEquals(expectedEvidence, response.result["evidenceUsed"]?.toString())
        assert(response.result["explanation"]?.toString()?.contains("conflito") == true)
    }
}
