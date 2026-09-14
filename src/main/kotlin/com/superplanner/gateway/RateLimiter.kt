package com.superplanner.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RateLimiter(
    private val maxRequests: Long = System.getenv("GATEWAY_RATE_LIMIT")?.toLongOrNull()?.coerceAtLeast(1) ?: 60,
    private val windowMillis: Long = 60_000,
) {
    private data class Window(val startedAt: AtomicLong, val count: AtomicLong)
    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val window = windows.computeIfAbsent(key) { Window(AtomicLong(now), AtomicLong(0)) }
        if (now - window.startedAt.get() >= windowMillis) {
            synchronized(window) {
                if (now - window.startedAt.get() >= windowMillis) {
                    window.startedAt.set(now)
                    window.count.set(0)
                }
            }
        }
        return window.count.incrementAndGet() <= maxRequests
    }
}
