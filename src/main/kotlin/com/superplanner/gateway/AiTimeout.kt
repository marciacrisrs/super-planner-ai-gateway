package com.superplanner.gateway

import kotlinx.coroutines.withTimeout

suspend fun <T> withAiTimeout(timeoutMs: Long, block: suspend () -> T): T =
    withTimeout(timeoutMs) { block() }
