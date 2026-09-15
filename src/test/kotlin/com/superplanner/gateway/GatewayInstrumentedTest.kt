package com.superplanner.gateway

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GatewayInstrumentedTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `health and readiness expose operational state without authentication`() = testApplication {
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = RecordingAi(SUCCESSFUL_GENERATE_RESPONSE),
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("{\"status\":\"ok\"}", health.bodyAsText())

        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("{\"status\":\"ready\"}", ready.bodyAsText())
    }

    @Test
    fun `generate route sends the exact user prompt to provider and returns provider model`() = testApplication {
        val ai = RecordingAi("generated answer")
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "instrumented-generate-1")
            setBody("{\"prompt\":\"organize my Thursday around the fixed meeting\"}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("instrumented-generate-1", response.headers["X-Request-Id"])
        assertContains(response.bodyAsText(), "\"text\":\"generated answer\"")
        assertContains(response.bodyAsText(), "\"model\":\"instrumented-model\"")
        assertEquals("organize my Thursday around the fixed meeting", ai.lastPrompt)
    }

    @Test
    fun `invalid generate request never reaches provider and returns request id`() = testApplication {
        val ai = RecordingAi("should never be returned")
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "instrumented-generate-2")
            setBody("""{"prompt":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("instrumented-generate-2", response.headers["X-Request-Id"])
        assertContains(response.bodyAsText(), "\"invalid_request\"")
        assertFalse(ai.wasCalled)
    }

    @Test
    fun `provider failure is translated to gateway error without leaking provider exception`() = testApplication {
        val ai = RecordingAi(failure = IllegalStateException("upstream secret detail"))
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "instrumented-provider-error")
            setBody("{\"prompt\":\"hello\"}")
        }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("instrumented-provider-error", response.headers["X-Request-Id"])
        assertContains(response.bodyAsText(), "\"ai_provider_error\"")
        assertContains(response.bodyAsText(), "AI provider unavailable")
        assertFalse(response.bodyAsText().contains("upstream secret detail"))
    }

    @Test
    fun `production security rejects missing and invalid credentials before provider execution`() = testApplication {
        val ai = RecordingAi(SUCCESSFUL_GENERATE_RESPONSE)
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(
                        expectedApiKey = "integration-secret",
                        environment = "production",
                    ),
                )
            )
        }

        val missing = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "security-missing")
            setBody("{\"prompt\":\"hello\"}")
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("security-missing", missing.headers["X-Request-Id"])
        assertContains(missing.bodyAsText(), "\"unauthorized\"")
        assertFalse(ai.wasCalled)

        val wrong = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "security-wrong")
            header("X-Gateway-Api-Key", "wrong-secret")
            setBody("{\"prompt\":\"hello\"}")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("security-wrong", wrong.headers["X-Request-Id"])
        assertContains(wrong.bodyAsText(), "\"unauthorized\"")
        assertFalse(ai.wasCalled)
    }

    @Test
    fun `production security applies rate limit to authenticated provider calls`() = testApplication {
        val ai = RecordingAi(SUCCESSFUL_GENERATE_RESPONSE)
        val limiter = RateLimiter(maxRequests = 1, windowMillis = 60_000)
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(
                        expectedApiKey = "integration-secret",
                        environment = "production",
                        rateLimiter = limiter,
                    )
                )
            )
        }

        val first = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "rate-limit-1")
            header("X-Gateway-Api-Key", "integration-secret")
            setBody("{\"prompt\":\"hello\"}")
        }
        val second = client.post("/v1/ai/generate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "rate-limit-2")
            header("X-Gateway-Api-Key", "integration-secret")
            setBody("{\"prompt\":\"hello again\"}")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertEquals("rate-limit-2", second.headers["X-Request-Id"])
        assertContains(second.bodyAsText(), "\"rate_limited\"")
        assertEquals(1, ai.callCount)
    }

    @Test
    fun `organize-week integration preserves structured contract from provider through HTTP`() = testApplication {
        val ai = RecordingAi(ORGANIZE_WEEK_RESPONSE)
        application {
            module(
                GatewayDependencies(
                    aiTextGenerator = ai,
                    security = GatewaySecurity(environment = "test"),
                )
            )
        }

        val response = client.post("/v1/ai/organize-week") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header("X-Request-Id", "instrumented-organize-1")
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
                  }],
                  "logistics":[{
                    "type":"commute",
                    "minutes":45,
                    "beforeItemId":"work"
                  }]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("instrumented-organize-1", response.headers["X-Request-Id"])

        val decoded = json.decodeFromString(OrganizeWeekResponse.serializer(), response.bodyAsText())
        assertEquals("instrumented-model", decoded.model)
        assertEquals(1, decoded.summary.fixedCommitmentsConsidered)
        assertEquals(1, decoded.summary.desiresConsidered)
        assertEquals(45, decoded.summary.commuteMinutesConsidered)
        assertEquals(listOf("work", "gym"), decoded.proposedItems.map { it.id }.sorted())
        assertContains(ai.lastPrompt.orEmpty(), "COMMUTE")
        assertContains(ai.lastPrompt.orEmpty(), "45")
        assertContains(ai.lastPrompt.orEmpty(), "America/Sao_Paulo")
    }

    private class RecordingAi(
        private val response: String? = null,
        private val failure: Throwable? = null,
    ) : AiTextGenerator {
        override val modelName: String = "instrumented-model"
        var lastPrompt: String? = null
            private set
        var callCount: Int = 0
            private set
        val wasCalled: Boolean
            get() = callCount > 0

        override fun generate(prompt: String): String {
            callCount += 1
            lastPrompt = prompt
            failure?.let { throw it }
            return response ?: error("Test provider response not configured")
        }
    }

    companion object {
        private const val SUCCESSFUL_GENERATE_RESPONSE = "generated answer"

        private val ORGANIZE_WEEK_RESPONSE =
            """
            {
              "summary": {
                "fixedCommitmentsConsidered": 1,
                "desiresConsidered": 1,
                "commuteMinutesConsidered": 45,
                "preparationMinutesConsidered": 0,
                "aiSuggestionsConsidered": 0,
                "conflictsFound": 0,
                "opportunitiesFound": 0
              },
              "proposedItems": [
                {
                  "id":"work",
                  "title":"Trabalho",
                  "date":"2026-09-14",
                  "startTime":"09:00",
                  "endTime":"18:00",
                  "source":"fixed",
                  "fixed":true,
                  "reason":"Fixed commitment"
                },
                {
                  "id":"gym",
                  "title":"Academia",
                  "date":"2026-09-14",
                  "startTime":"07:00",
                  "endTime":"08:00",
                  "source":"desire",
                  "fixed":false,
                  "reason":"Fits before work"
                }
              ],
              "conflicts":[],
              "opportunities":[],
              "explanations":[]
            }
            """.trimIndent()
    }
}
