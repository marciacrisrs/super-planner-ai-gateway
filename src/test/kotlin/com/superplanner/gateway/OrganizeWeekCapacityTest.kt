package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertTrue

class OrganizeWeekCapacityTest {
    private class FakeGenerator : AiTextGenerator {
        override val modelName: String = "fake"
        var prompt: String = ""
        override fun generate(prompt: String): String {
            this.prompt = prompt
            return """
                {
                  "summary":{"fixedCommitmentsConsidered":0,"desiresConsidered":0,"commuteMinutesConsidered":0,"preparationMinutesConsidered":0,"aiSuggestionsConsidered":0,"conflictsFound":0,"opportunitiesFound":0},
                  "proposedItems":[],"conflicts":[],"opportunities":[],"explanations":[]
                }
            """.trimIndent()
        }
    }

    @Test
    fun capacity_facts_are_supplied_to_the_provider_as_structured_context() {
        val generator = FakeGenerator()
        OrganizeWeekService(generator).organize(
            OrganizeWeekRequest(
                weekStart = "2026-09-14",
                timezone = "America/Sao_Paulo",
                capacity = WeeklyCapacityFacts(
                    load = "OVER_CAPACITY",
                    totalCapacityMinutes = 600,
                    totalDesiredMinutes = 900,
                    totalRemainingMinutes = 0,
                    days = listOf(DailyCapacityFacts("2026-09-14", "OVER_CAPACITY", 300, 480, 0)),
                    reasons = listOf("sono e recuperação são tempo legítimo"),
                ),
            ),
        )

        assertTrue(generator.prompt.contains("OVER_CAPACITY"))
        assertTrue(generator.prompt.contains("600"))
        assertTrue(generator.prompt.contains("sono e recuperação"))
    }
}
