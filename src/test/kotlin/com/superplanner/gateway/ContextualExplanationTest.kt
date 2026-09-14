package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextualExplanationTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
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
        assertEquals("[\"$first\",\"$second\"]", response.result["evidenceUsed"]?.toString())
        assert(response.result["explanation"]?.toString()?.contains("conflito") == true)
    }
}
