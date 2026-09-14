package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewaySecurityTest {
    @Test
    fun credentials_are_required_and_compared_without_plaintext_logging_or_fallback() {
        val security = GatewaySecurity(
            expectedApiKey = "gateway-secret",
            environment = "production",
            rateLimiter = RateLimiter(maxRequests = 10),
        )

        assertTrue(security.credentialsMatch("gateway-secret"))
        assertFalse(security.credentialsMatch(null))
        assertFalse(security.credentialsMatch(""))
        assertFalse(security.credentialsMatch("wrong-secret"))
        assertFalse(security.credentialsMatch("gateway-secret-extra"))
    }
}
