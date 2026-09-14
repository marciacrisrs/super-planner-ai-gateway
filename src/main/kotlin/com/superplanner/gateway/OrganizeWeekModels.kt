package com.superplanner.gateway

import kotlinx.serialization.Serializable

@Serializable
data class OrganizeWeekRequest(
    val weekStart: String,
    val timezone: String,
    val existingPlan: List<PlanItem> = emptyList(),
    val fixedCommitments: List<PlanItem> = emptyList(),
    val desires: List<PlanItem> = emptyList(),
    val logistics: List<LogisticConstraint> = emptyList(),
    val preferences: List<PlanningPreference> = emptyList(),
    val aiTips: List<String> = emptyList()
)

@Serializable
data class PlanItem(
    val id: String,
    val title: String,
    val date: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val durationMinutes: Int? = null,
    val priority: String? = null,
    val kind: String? = null,
    val required: Boolean = false
)

@Serializable
data class LogisticConstraint(
    val type: String,
    val minutes: Int,
    val beforeItemId: String? = null,
    val afterItemId: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val required: Boolean = true
)

@Serializable
data class PlanningPreference(
    val key: String,
    val value: String
)

@Serializable
data class OrganizeWeekResponse(
    val summary: OrganizeWeekSummary,
    val proposedItems: List<ProposedPlanItem>,
    val conflicts: List<PlanningConflict>,
    val opportunities: List<PlanningOpportunity>,
    val explanations: List<PlanningExplanation>,
    val model: String = ""
)

@Serializable
data class OrganizeWeekSummary(
    val fixedCommitmentsConsidered: Int,
    val desiresConsidered: Int,
    val commuteMinutesConsidered: Int,
    val preparationMinutesConsidered: Int,
    val aiSuggestionsConsidered: Int,
    val conflictsFound: Int,
    val opportunitiesFound: Int
)

@Serializable
data class ProposedPlanItem(
    val id: String,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String,
    val source: String,
    val fixed: Boolean = false,
    val reason: String? = null
)

@Serializable
data class PlanningConflict(
    val id: String,
    val title: String,
    val affectedItemIds: List<String>,
    val reason: String,
    val severity: String
)

@Serializable
data class PlanningOpportunity(
    val id: String,
    val title: String,
    val reason: String
)

@Serializable
data class PlanningExplanation(
    val itemId: String? = null,
    val message: String
)
