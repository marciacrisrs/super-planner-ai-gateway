package com.superplanner.gateway

import org.slf4j.LoggerFactory
import kotlin.math.roundToLong

object GatewayObservability {
    private val logger = LoggerFactory.getLogger("AiGateway")

    fun success(requestId: String, capability: String, model: String?, startedNanos: Long) {
        logger.info(
            "event=ai_request status=success requestId={} capability={} model={} latencyMs={}",
            requestId,
            capability,
            model ?: "unknown",
            elapsedMs(startedNanos),
        )
    }

    fun providerSuccess(model: String, startedNanos: Long) {
        logger.info(
            "event=ai_provider status=success model={} latencyMs={}",
            model,
            elapsedMs(startedNanos),
        )
    }

    fun providerFailure(model: String, error: String, startedNanos: Long) {
        logger.warn(
            "event=ai_provider status=failure model={} error={} latencyMs={}",
            model,
            error,
            elapsedMs(startedNanos),
        )
    }

    fun failure(requestId: String, capability: String, error: String, startedNanos: Long? = null) {
        if (startedNanos == null) {
            logger.warn(
                "event=ai_request status=failure requestId={} capability={} error={}",
                requestId,
                capability,
                error,
            )
        } else {
            logger.warn(
                "event=ai_request status=failure requestId={} capability={} error={} latencyMs={}",
                requestId,
                capability,
                error,
                elapsedMs(startedNanos),
            )
        }
    }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / NANOS_PER_MILLISECOND).roundToLong()

    private const val NANOS_PER_MILLISECOND = 1_000_000.0
}
