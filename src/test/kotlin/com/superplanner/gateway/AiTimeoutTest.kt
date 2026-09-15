package com.superplanner.gateway

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AiTimeoutTest {
    @Test
    fun operation_is_cancelled_when_timeout_is_exceeded() {
        runBlocking {
            assertFailsWith<TimeoutCancellationException> {
                withAiTimeout(20) {
                    delay(200)
                    "unreachable"
                }
            }
        }
    }
}
