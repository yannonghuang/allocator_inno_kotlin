package com.allocator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request / response DTOs — port of schemas.py Pydantic models.
 */

@Serializable
data class CaseCreate(val name: String)

@Serializable
data class CaseUpdate(val name: String? = null)

@Serializable
data class CaseResponse(
    val id: Int,
    val name: String,
    val createdAt: String,          // ISO-8601 string; timestamp formatted on serialization
)

@Serializable
data class CaseDetailResponse(
    val id: Int,
    val name: String,
    val createdAt: String,
    val demandCount: Int = 0,
    val supplyCount: Int = 0,
    val runCount: Int = 0,
)

@Serializable
data class AllocationRunResponse(
    val id: Int,
    val caseId: Int,
    val createdAt: String,
    val status: String,
    val config: JsonElement? = null,
)

@Serializable
data class AllocationActionResponse(
    val id: Int,
    val runId: Int,
    val variantKey: String,
    val reqComponentIds: List<String>,
    val reqRates: List<Double>? = null,
    val qty: Double,
    val demandId: String? = null,
    val targetProductId: String? = null,
    val targetLocationId: String? = null,
    val outputPeriod: Int? = null,
    val edgeType: String? = null,
)

@Serializable
data class ManualOverrideCreate(
    val entityType: String,
    val entityKey: String,
    val payload: JsonElement,
)

@Serializable
data class ManualOverrideResponse(
    val id: Int,
    val caseId: Int,
    val entityType: String,
    val entityKey: String,
    val payload: JsonElement,
)

@Serializable
data class FeasibleDemandSummary(
    val demandId: String,
    val productId: String,
    val requestedQty: Double,
    val allocatedQty: Double,
    val status: String,             // "fulfilled" | "partial" | "unfulfilled"
)

@Serializable
data class ImportCsvResponse(val status: String, val caseId: Int)

@Serializable
data class StatusResponse(val status: String)
