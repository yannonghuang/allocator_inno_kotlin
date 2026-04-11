package com.allocator

import kotlinx.serialization.SerialName
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
    @SerialName("created_at")    val createdAt: String,
    @SerialName("demand_count")  val demandCount: Int = 0,
    @SerialName("supply_count")  val supplyCount: Int = 0,
    @SerialName("run_count")     val runCount: Int = 0,
    @SerialName("plan_run_count") val planRunCount: Int = 0,
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
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_key") val entityKey: String,
    val payload: JsonElement,
)

@Serializable
data class ManualOverrideResponse(
    val id: Int,
    @SerialName("case_id") val caseId: Int,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_key") val entityKey: String,
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

@Serializable
data class PlanRunResponse(
    val id: Int,
    @SerialName("case_id") val caseId: Int,
    @SerialName("job_id") val jobId: String? = null,
    val status: String,
    val config: JsonElement? = null,
    @SerialName("override_count") val overrideCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PlanRunFullResponse(
    val id: Int,
    @SerialName("case_id") val caseId: Int,
    @SerialName("job_id") val jobId: String? = null,
    val status: String,
    val config: JsonElement? = null,
    @SerialName("override_snapshot") val overrideSnapshot: JsonElement? = null,
    val result: JsonElement? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class BomGraphNode(
    val id: String,                   // "{productId}|{locationId}"
    val productId: String,
    val locationId: String,
    val productDescription: String?,
    val locationDescription: String?,
    val establishedBy: List<String>,  // subset of ["buy","make","move"]
    val isDemand: Boolean,            // true if this node originated from a demand row
)

@Serializable
data class BomGraphEdge(
    val id: String,
    val source: String,               // upstream node id (component/source)
    val target: String,               // downstream node id (parent/destination)
    val edgeType: String,             // "make" | "move"
    val bomId: String?,
    val altGroup: String?,
    val rate: Double?,
    val preference: Int?,
    val leadDays: Double?,
)

@Serializable
data class BomGraphResponse(
    val nodes: List<BomGraphNode>,
    val edges: List<BomGraphEdge>,
    val nodeCount: Int,
    val edgeCount: Int,
)
