package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class GatewaySecurity(
    private val expectedApiKey: String = System.getenv("GATEWAY_API_KEY")?.trim().orEmpty(),
    private val environment: String = System.getenv("ENVIRONMENT")?.trim()?.lowercase() ?: "production",
    private val rateLimiter: RateLimiter = RateLimiter(),
) {
    suspend fun requireAccess(call: ApplicationCall): Boolean {
        if (environment == "test") return true
        if (expectedApiKey.isBlank()) {
            val requestId = requestId(call)
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                GatewayError("gateway_not_configured", "Gateway authentication is not configured", requestId),
            )
            return false
        }
        val supplied = call.request.headers["X-Gateway-Api-Key"]?.trim()
        if (!credentialsMatch(supplied)) {
            val requestId = requestId(call)
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(
                HttpStatusCode.Unauthorized,
                GatewayError("unauthorized", "Invalid gateway credentials", requestId),
            )
            return false
        }
        if (!rateLimiter.allow(supplied!!)) {
            val requestId = requestId(call)
            call.response.headers.append("X-Request-Id", requestId)
            call.respond(
                HttpStatusCode.TooManyRequests,
                GatewayError("rate_limited", "Too many requests", requestId),
            )
            return false
        }
        return true
    }

    internal fun credentialsMatch(supplied: String?): Boolean =
        !supplied.isNullOrBlank() && constantTimeEquals(supplied, expectedApiKey)

    companion object {
        fun requestId(call: ApplicationCall): String =
            call.request.headers["X-Request-Id"]?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var result = 0
            for (i in a.indices) result = result or (a[i].code xor b[i].code)
            return result == 0
        }
    }
}

@kotlinx.serialization.Serializable
data class GatewayError(
    val error: String,
    val message: String,
    val requestId: String,
)
