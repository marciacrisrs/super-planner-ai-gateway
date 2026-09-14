package com.superplanner.gateway

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizeWeekSemanticValidationTest {
    @Test
    fun `fixed commitment must be preserved exactly`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        val response = responseWith(
            ProposedPlanItem("work", "Trabalho", "2026-09-14", "09:30", "18:00", "fixed", fixed = true)
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", fixedCommitments = listOf(fixed))
            )
        }
    }

    @Test
    fun `fixed commitment must not be omitted`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(responseWith())).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", fixedCommitments = listOf(fixed))
            )
        }
    }

    @Test
    fun `proposal rejects invalid time interval`() {
        val response = responseWith(
            ProposedPlanItem("gym", "Academia", "2026-09-14", "18:00", "17:00", "desire")
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo")
            )
        }
    }

    @Test
    fun `proposal cannot overlap a fixed commitment`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        val response = responseWith(
            ProposedPlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", "fixed", fixed = true),
            ProposedPlanItem("gym", "Academia", "2026-09-14", "17:00", "18:30", "desire")
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", fixedCommitments = listOf(fixed))
            )
        }
    }

    @Test
    fun `proposal outside fixed commitment window is accepted`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        val desire = PlanItem("gym", "Academia", "2026-09-14", durationMinutes = 60)
        val result = OrganizeWeekService(
            FakeAi(
                responseWith(
                    ProposedPlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", "fixed", fixed = true),
                    ProposedPlanItem("gym", "Academia", "2026-09-14", "07:00", "08:00", "desire")
                )
            )
        ).organize(
            OrganizeWeekRequest(
                "2026-09-14",
                "America/Sao_Paulo",
                fixedCommitments = listOf(fixed),
                desires = listOf(desire),
            )
        )
        assertEquals(listOf("work", "gym"), result.proposedItems.map { it.id })
    }

    @Test
    fun `proposal cannot start before domain minimum start time`() {
        val existing = PlanItem("appointment", "Consulta", "2026-09-14", "10:00", "11:00")
        val response = responseWith(
            ProposedPlanItem("appointment", "Consulta", "2026-09-14", "09:30", "10:30", "existing")
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest(
                    "2026-09-14",
                    "America/Sao_Paulo",
                    existingPlan = listOf(existing)
                )
            )
        }
    }

    @Test
    fun `proposal at domain minimum start time is accepted`() {
        val existing = PlanItem("appointment", "Consulta", "2026-09-14", "10:00", "11:00")
        val result = OrganizeWeekService(
            FakeAi(responseWith(ProposedPlanItem("appointment", "Consulta", "2026-09-14", "10:00", "10:30", "existing")))
        ).organize(
            OrganizeWeekRequest(
                "2026-09-14",
                "America/Sao_Paulo",
                existingPlan = listOf(existing)
            )
        )
        assertEquals("appointment", result.proposedItems.single().id)
    }

    @Test
    fun `valid fixed commitment and proposal are accepted`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        val result = OrganizeWeekService(
            FakeAi(responseWith(ProposedPlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", "fixed", fixed = true)))
        ).organize(
            OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", fixedCommitments = listOf(fixed))
        )
        assertEquals("work", result.proposedItems.single().id)
    }

    @Test
    fun `domain item cannot be replaced by an invented id`() {
        val desire = PlanItem("gym", "Academia", "2026-09-14", durationMinutes = 60)
        val response = responseWith(
            ProposedPlanItem("invented-gym", "Academia", "2026-09-14", "07:00", "08:00", "desire")
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", desires = listOf(desire))
            )
        }
    }

    @Test
    fun `summary must match supplied context`() {
        val fixed = PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
        val response = Json.encodeToString(
            OrganizeWeekResponse.serializer(),
            OrganizeWeekResponse(
                summary = OrganizeWeekSummary(0, 0, 0, 0, 0, 1, 0),
                proposedItems = listOf(
                    ProposedPlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", "fixed", fixed = true)
                ),
                conflicts = emptyList(),
                opportunities = emptyList(),
                explanations = emptyList(),
            )
        )
        assertFailsWith<IllegalStateException> {
            OrganizeWeekService(FakeAi(response)).organize(
                OrganizeWeekRequest("2026-09-14", "America/Sao_Paulo", fixedCommitments = listOf(fixed))
            )
        }
    }

    @Test
    fun `logistics must identify an affected item or route`() {
        assertFailsWith<IllegalArgumentException> {
            OrganizeWeekService(FakeAi(responseWith())).organize(
                OrganizeWeekRequest(
                    "2026-09-14",
                    "America/Sao_Paulo",
                    logistics = listOf(LogisticConstraint(type = "commute", minutes = 30))
                )
            )
        }
    }

    private fun responseWith(vararg items: ProposedPlanItem): String = Json.encodeToString(
        OrganizeWeekResponse.serializer(),
        OrganizeWeekResponse(
            summary = OrganizeWeekSummary(
                fixedCommitmentsConsidered = items.count { it.source == "fixed" },
                desiresConsidered = items.count { it.source == "desire" },
                commuteMinutesConsidered = 0,
                preparationMinutesConsidered = 0,
                aiSuggestionsConsidered = items.count { it.source == "ai_suggestion" },
                conflictsFound = 0,
                opportunitiesFound = 0,
            ),
            proposedItems = items.toList(),
            conflicts = emptyList(),
            opportunities = emptyList(),
            explanations = emptyList(),
        )
    )

    private class FakeAi(private val response: String) : AiTextGenerator {
        override val modelName = "fake-model"
        override fun generate(prompt: String): String = response
    }
}
