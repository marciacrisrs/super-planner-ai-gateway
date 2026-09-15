package com.superplanner.gateway

class InvalidAiCapabilityException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
