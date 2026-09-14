package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AiProposalServiceTest {
    private class FakeGenerator(private val response: String) : AiTextGenerator {
        override val modelName: String = "fake-provider-model"
        override fun generate(prompt: String): String = response
    }

    @Test
    fun valid_create_command_is_provider_independent_and_correlated() {
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
            AiProposalRequest(schemaVersion = "1", message = "Quero estudar francês na quarta por uma hora."),
            requestId = "request-123",
        )

        assertEquals("1", response.schemaVersion)
        assertEquals("request-123", response.requestId)
        assertEquals("CREATE_ACTIVITY_DRAFT", response.proposal.commandType)
        assertEquals("fake-provider-model", response.model)
        assertEquals("Estudar francês", response.proposal.payload["title"]?.toString()?.trim('"'))
    }

    @Test
    fun delay_request_maps_to_reorganize_day() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"REORGANIZE_DAY","explanation":"Aplicar o atraso informado.","requiresConfirmation":true,"payload":{"delayMinutes":40}}"""),
        )
        val response = service.propose(AiProposalRequest("1", "Estou 40 minutos atrasada."))
        assertEquals("REORGANIZE_DAY", response.proposal.commandType)
        assertEquals("40", response.proposal.payload["delayMinutes"]?.toString())
    }

    @Test
    fun cancellation_requires_existing_activity_id_and_confirmation() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"CANCEL_ACTIVITY","explanation":"Cancelar a atividade identificada.","requiresConfirmation":true,"payload":{"activityId":"activity-42"}}"""),
        )
        val response = service.propose(
            AiProposalRequest("1", "Não vou conseguir fazer academia hoje.", AiProposalContext(activeActivityId = "activity-42")),
        )
        assertEquals("CANCEL_ACTIVITY", response.proposal.commandType)
        assertEquals("activity-42", response.proposal.payload["activityId"]?.toString()?.trim('"'))
    }

    @Test
    fun change_request_requires_explicit_existing_activity_id() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"CHANGE_ACTIVITY","explanation":"Alterar o horário da atividade identificada.","requiresConfirmation":true,"payload":{"activityId":"activity-42","changes":{"startTime":"16:00"}}}"""),
        )
        val response = service.propose(
            AiProposalRequest("1", "Mude esta atividade para 16h.", AiProposalContext(activeActivityId = "activity-42")),
        )
        assertEquals("CHANGE_ACTIVITY", response.proposal.commandType)
        assertEquals("activity-42", response.proposal.payload["activityId"]?.toString()?.trim('"'))
    }

    @Test
    fun ambiguous_cancellation_without_activity_id_is_rejected() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"CANCEL_ACTIVITY","explanation":"x","requiresConfirmation":true,"payload":{"activityId":"invented-id"}}"""),
        )
        assertFailsWith<InvalidAiProposalException> {
            service.propose(AiProposalRequest("1", "Não vou conseguir fazer isso hoje."))
        }
    }

    @Test
    fun invalid_model_output_is_rejected() {
        val service = AiProposalService(FakeGenerator("""{"commandType":"NOT_A_COMMAND","explanation":"x","requiresConfirmation":true,"payload":{}}"""))
        assertFailsWith<InvalidAiProposalException> {
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

    @Test
    fun mutating_proposal_must_require_confirmation() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"REORGANIZE_DAY","explanation":"x","requiresConfirmation":false,"payload":{"delayMinutes":30}}"""),
        )
        assertFailsWith<InvalidAiProposalException> {
            service.propose(AiProposalRequest("1", "reorganize"))
        }
    }

    @Test
    fun malformed_payload_types_are_rejected() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"CREATE_ACTIVITY_DRAFT","explanation":"x","requiresConfirmation":true,"payload":{"title":123}}"""),
        )
        assertFailsWith<InvalidAiProposalException> {
            service.propose(AiProposalRequest("1", "criar"))
        }
    }

    @Test
    fun change_command_with_unknown_field_is_rejected() {
        val service = AiProposalService(
            FakeGenerator("""{"commandType":"CHANGE_ACTIVITY","explanation":"x","requiresConfirmation":true,"payload":{"activityId":"a1","changes":{"location":"unknown"}}}"""),
        )
        assertFailsWith<InvalidAiProposalException> {
            service.propose(AiProposalRequest("1", "mude local"))
        }
    }

    @Test
    fun oversized_message_is_rejected_before_provider_call() {
        var generated = false
        val generator = object : AiTextGenerator {
            override val modelName: String = "fake-provider-model"
            override fun generate(prompt: String): String {
                generated = true
                return "{}"
            }
        }
        val service = AiProposalService(generator)
        assertFailsWith<IllegalArgumentException> {
            service.propose(AiProposalRequest("1", "x".repeat(12_001)))
        }
        assertFalse(generated)
    }

    @Test
    fun oversized_context_is_rejected_before_provider_call() {
        var generated = false
        val generator = object : AiTextGenerator {
            override val modelName: String = "fake-provider-model"
            override fun generate(prompt: String): String {
                generated = true
                return "{}"
            }
        }
        val service = AiProposalService(generator)
        assertFailsWith<IllegalArgumentException> {
            service.propose(
                AiProposalRequest(
                    "1",
                    "teste",
                    AiProposalContext(minimalRouteFacts = listOf("x".repeat(4_001))),
                ),
            )
        }
        assertFalse(generated)
    }
}
