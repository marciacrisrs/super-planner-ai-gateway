package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizeWeekServiceTest {
    @Test
    fun `organize parses a structured proposal and stamps model`() {
        val ai = FakeAi(
            """
            {
              "summary": {
                "fixedCommitmentsConsidered": 1,
                "desiresConsidered": 1,
                "commuteMinutesConsidered": 45,
                "preparationMinutesConsidered": 30,
                "aiSuggestionsConsidered": 1,
                "conflictsFound": 0,
                "opportunitiesFound": 1
              },
              "proposedItems": [
                {
                  "id": "work",
                  "title": "Trabalho",
                  "date": "2026-09-14",
                  "startTime": "09:00",
                  "endTime": "18:00",
                  "source": "fixed",
                  "fixed": true,
                  "reason": "Compromisso fixo"
                }
              ],
              "conflicts": [],
              "opportunities": [
                {
                  "id": "op-1",
                  "title": "Janela livre",
                  "reason": "Existe capacidade disponível"
                }
              ],
              "explanations": [
                {
                  "itemId": "work",
                  "message": "O trabalho foi preservado."
                }
              ]
            }
            """.trimIndent()
        )
        val service = OrganizeWeekService(ai)

        val result = service.organize(
            OrganizeWeekRequest(
                weekStart = "2026-09-14",
                timezone = "America/Sao_Paulo"
            )
        )

        assertEquals("fake-model", result.model)
        assertEquals(1, result.summary.fixedCommitmentsConsidered)
        assertEquals("work", result.proposedItems.single().id)
    }

    @Test
    fun `organize rejects a missing week start`() {
        val service = OrganizeWeekService(FakeAi("{}"))

        assertFailsWith<IllegalArgumentException> {
            service.organize(
                OrganizeWeekRequest(
                    weekStart = "",
                    timezone = "America/Sao_Paulo"
                )
            )
        }
    }

    @Test
    fun `organize rejects unknown logistics item references`() {
        val service = OrganizeWeekService(FakeAi("{}"))

        assertFailsWith<IllegalArgumentException> {
            service.organize(
                OrganizeWeekRequest(
                    weekStart = "2026-09-14",
                    timezone = "America/Sao_Paulo",
                    fixedCommitments = listOf(
                        PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
                    ),
                    logistics = listOf(
                        LogisticConstraint(type = "commute", minutes = 45, beforeItemId = "missing")
                    )
                )
            )
        }
    }

    @Test
    fun `organize accepts logistics references to known items`() {
        val ai = FakeAi("""
            {
              "summary":{"fixedCommitmentsConsidered":1,"desiresConsidered":0,"commuteMinutesConsidered":45,"preparationMinutesConsidered":0,"aiSuggestionsConsidered":0,"conflictsFound":0,"opportunitiesFound":0},
              "proposedItems":[{"id":"work","title":"Trabalho","date":"2026-09-14","startTime":"09:00","endTime":"18:00","source":"fixed","fixed":true,"reason":"fixo"}],
              "conflicts":[],"opportunities":[],"explanations":[]
            }
        """.trimIndent())
        val result = OrganizeWeekService(ai).organize(
            OrganizeWeekRequest(
                weekStart = "2026-09-14",
                timezone = "America/Sao_Paulo",
                fixedCommitments = listOf(
                    PlanItem("work", "Trabalho", "2026-09-14", "09:00", "18:00", required = true)
                ),
                logistics = listOf(
                    LogisticConstraint(type = "commute", minutes = 45, beforeItemId = "work")
                )
            )
        )
        assertEquals("work", result.proposedItems.single().id)
    }

    private class FakeAi(private val response: String) : AiTextGenerator {
        override val modelName: String = "fake-model"
        override fun generate(prompt: String): String = response
    }
}
