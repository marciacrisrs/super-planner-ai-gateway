package com.superplanner.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun limits_requests_per_key() {
        val limiter = RateLimiter(maxRequests = 2, windowMillis = 60_000)
        assertTrue(limiter.allow("client-a"))
        assertTrue(limiter.allow("client-a"))
        assertFalse(limiter.allow("client-a"))
        assertTrue(limiter.allow("client-b"))
    }
}
