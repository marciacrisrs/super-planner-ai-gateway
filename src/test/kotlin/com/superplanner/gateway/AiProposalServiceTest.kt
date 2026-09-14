package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiProposalServiceTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName: String = "fake-provider-model"
        override fun generate(prompt: String): String = response
    }

    @Test
    fun valid_proposal_is_provider_independent_and_correlated() {
        val service = AiProposalService(
            FakeGenerator(
                """
                {
                  "commandType":"CREATE_ACTIVITY_DRAFT",
                  "explanation":"Criar a atividade solicitada.",
                  "requiresConfirmation":true,
                  "payload":{"title":"Estudar francês","date":"2026-09-16","durationMinutes":60}
                }
                """.trimIndent(),
            ),
        )

        val response = service.propose(
            AiProposalRequest(
                schemaVersion = "1",
                message = "Quero estudar francês na quarta por uma hora.",
            ),
            requestId = "request-123",
        )

        assertEquals("1", response.schemaVersion)
        assertEquals("request-123", response.requestId)
        assertEquals("CREATE_ACTIVITY_DRAFT", response.proposal.commandType)
        assertEquals("fake-provider-model", response.model)
        assertEquals("Estudar francês", response.proposal.payload["title"]?.toString()?.trim('"'))
    }

    @Test
    fun invalid_model_output_is_rejected() {
        val service = AiProposalService(
            FakeGenerator(
                """
                {"commandType":"NOT_A_COMMAND","explanation":"x","requiresConfirmation":true,"payload":{}}
                """.trimIndent(),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            service.propose(AiProposalRequest("1", "teste"))
        }
    }

    @Test
    fun unsupported_schema_version_is_rejected_before_provider_call() {
        val service = AiProposalService(FakeGenerator("{}"))

        assertFailsWith<IllegalArgumentException> {
            service.propose(AiProposalRequest("2", "teste"))
        }
    }
}
