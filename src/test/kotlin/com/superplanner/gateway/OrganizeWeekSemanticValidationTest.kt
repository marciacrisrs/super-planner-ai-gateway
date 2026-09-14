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
        val result = OrganizeWeekService(
            FakeAi(responseWith(ProposedPlanItem("gym", "Academia", "2026-09-14", "07:00", "08:00", "desire")))
        ).organize(
            OrganizeWeekRequest(
                "2026-09-14",
                "America/Sao_Paulo",
                fixedCommitments = listOf(
                    fixed,
                    PlanItem("prep", "Preparação", "2026-09-14", "07:00", "08:00")
                )
            )
        )
        assertEquals("gym", result.proposedItems.single().id)
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

    private fun responseWith(vararg items: ProposedPlanItem): String = Json.encodeToString(
        OrganizeWeekResponse.serializer(),
        OrganizeWeekResponse(
            summary = OrganizeWeekSummary(0, 0, 0, 0, 0, 0, 0),
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
