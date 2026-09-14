package com.superplanner.gateway

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganizeWeekRouteTest {
    @Test
    fun `organize-week returns structured proposal through HTTP route`() = testApplication {
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = FakeAi(validResponse()),
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/organize-week") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "route-test-1")
            setBody(
                """
                {
                  "weekStart":"2026-09-14",
                  "timezone":"America/Sao_Paulo",
                  "fixedCommitments":[{
                    "id":"work",
                    "title":"Trabalho",
                    "date":"2026-09-14",
                    "startTime":"09:00",
                    "endTime":"18:00",
                    "required":true
                  }],
                  "desires":[{
                    "id":"gym",
                    "title":"Academia",
                    "date":"2026-09-14",
                    "durationMinutes":60
                  }]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("route-test-1", response.headers["X-Request-Id"])
        assertTrue(response.bodyAsText().contains("\"model\":\"fake-model\""))
        assertTrue(response.bodyAsText().contains("\"work\""))
        assertTrue(response.bodyAsText().contains("\"gym\""))
    }

    @Test
    fun `organize-week rejects invalid request through HTTP route`() = testApplication {
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = FakeAi(validResponse()),
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/organize-week") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "route-test-2")
            setBody(
                """
                {
                  "weekStart":"",
                  "timezone":"America/Sao_Paulo"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("route-test-2", response.headers["X-Request-Id"])
        assertTrue(response.bodyAsText().contains("weekStart must not be blank"))
    }

    private class FakeAi(private val response: String) : AiTextGenerator {
        override val modelName: String = "fake-model"
        override fun generate(prompt: String): String = response
    }

    private fun validResponse(): String =
        """
        {
          "summary": {
            "fixedCommitmentsConsidered": 1,
            "desiresConsidered": 1,
            "commuteMinutesConsidered": 0,
            "preparationMinutesConsidered": 0,
            "aiSuggestionsConsidered": 0,
            "conflictsFound": 0,
            "opportunitiesFound": 0
          },
          "proposedItems": [
            {
              "id":"gym",
              "title":"Academia",
              "date":"2026-09-14",
              "startTime":"07:00",
              "endTime":"08:00",
              "source":"desire",
              "fixed":false,
              "reason":"Fits before work"
            },
            {
              "id":"work",
              "title":"Trabalho",
              "date":"2026-09-14",
              "startTime":"09:00",
              "endTime":"18:00",
              "source":"fixed",
              "fixed":true,
              "reason":"Fixed commitment"
            }
          ],
          "conflicts": [],
          "opportunities": [],
          "explanations": []
        }
        """.trimIndent()
}
