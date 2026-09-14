package com.superplanner.gateway

import kotlinx.serialization.Serializable

@Serializable
data class AiProposalRequest(
    val schemaVersion: String,
    val message: String,
    val context: AiProposalContext = AiProposalContext(),
)

@Serializable
data class AiProposalContext(
    val nowIso: String? = null,
    val activeActivityId: String? = null,
    val minimalRouteFacts: List<String> = emptyList(),
)

@Serializable
data class AiProposalEnvelope(
    val schemaVersion: String,
    val requestId: String,
    val proposal: AiProposal,
    val model: String,
)

@Serializable
data class AiProposal(
    val commandType: String,
    val explanation: String,
    val requiresConfirmation: Boolean,
    val payload: kotlinx.serialization.json.JsonObject,
)

@Serializable
data class AiProposalError(
    val error: String,
    val message: String,
    val requestId: String,
)
