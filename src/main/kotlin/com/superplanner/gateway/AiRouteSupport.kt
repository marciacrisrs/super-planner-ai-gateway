package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.coroutines.TimeoutCancellationException

internal data class AiRouteResult<T>(
    val response: T,
    val model: String,
)

internal suspend fun <T> ApplicationCall.executeAiRoute(
    capability: String,
    invalidException: Class<out Exception>? = null,
    invalidError: (String) -> Any = { requestId ->
        GatewayError(INVALID_AI_RESPONSE_CODE, INVALID_AI_RESPONSE_MESSAGE, requestId)
    },
    providerError: (String) -> Any = { requestId ->
        GatewayError("ai_provider_error", AI_OPERATION_FAILED_MESSAGE, requestId)
    },
    block: suspend () -> AiRouteResult<T>,
) {
    val requestId = GatewaySecurity.requestId(this)
    response.headers.append(GatewaySecurity.REQUEST_ID_HEADER, requestId)
    val started = System.nanoTime()

    try {
        val result = withAiTimeout(AI_TIMEOUT_MS) { block() }
        respond(result.response)
        GatewayObservability.success(requestId, capability, result.model, started)
    } catch (e: Exception) {
        handleAiRouteException(
            requestId = requestId,
            capability = capability,
            started = started,
            exception = e,
            invalidException = invalidException,
            invalidError = invalidError,
            providerError = providerError,
        )
    }
}

private suspend fun ApplicationCall.handleAiRouteException(
    requestId: String,
    capability: String,
    started: Long,
    exception: Exception,
    invalidException: Class<out Exception>?,
    invalidError: (String) -> Any,
    providerError: (String) -> Any,
) {
    when {
        invalidException?.isInstance(exception) == true -> {
            GatewayObservability.failure(requestId, capability, "invalid_ai_response", started)
            respond(HttpStatusCode.BadGateway, invalidError(requestId))
        }
        exception is IllegalArgumentException -> {
            GatewayObservability.failure(requestId, capability, "invalid_request", started)
            respond(HttpStatusCode.BadRequest, GatewayError("invalid_request", exception.message ?: INVALID_REQUEST_MESSAGE, requestId))
        }
        exception is TimeoutCancellationException -> {
            GatewayObservability.failure(requestId, capability, "timeout", started)
            respond(HttpStatusCode.GatewayTimeout, GatewayError("timeout", AI_TIMEOUT_MESSAGE, requestId))
        }
        else -> {
            GatewayObservability.failure(requestId, capability, "ai_provider_error", started)
            respond(HttpStatusCode.BadGateway, providerError(requestId))
        }
    }
}

internal const val INVALID_AI_RESPONSE_CODE = "invalid_ai_response"
internal const val INVALID_AI_RESPONSE_MESSAGE = "AI returned an invalid response"
internal const val INVALID_REQUEST_MESSAGE = "invalid request"
internal const val AI_TIMEOUT_MESSAGE = "AI operation timed out"
internal const val AI_OPERATION_FAILED_MESSAGE = "AI operation failed"
internal const val AI_PROVIDER_UNAVAILABLE_MESSAGE = "AI provider unavailable"
private val AI_TIMEOUT_MS: Long = System.getenv("AI_TIMEOUT_MS")?.toLongOrNull()?.coerceIn(1_000, 120_000) ?: 30_000
internal const val MAX_PROMPT_LENGTH = 12_000
