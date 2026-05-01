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
    @SerialName("override_snapshot_preview") val overrideSnapshotPreview: JsonElement? = null,
    val name: String? = null,
    val notes: String? = null,
    @SerialName("is_initial") val isInitial: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_active_designated") val isActiveDesignated: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("chosen_depth") val chosenDepth: Int? = null,
    val attempts: JsonElement? = null,  // [{depth, duration_ms}, ...] for optimal-depth runs
    @SerialName("soundness_status") val soundnessStatus: String = "unchecked",
    @SerialName("soundness_checked_at") val soundnessCheckedAt: String? = null,
    /** Free-form provenance (parsed plan_run.metadata). Currently includes
     *  `bootstrap: true` + `preset_id` + `preset_label` + `primary_axis` +
     *  `signature` for KB-seeded runs; null for ordinary user-driven runs. */
    val metadata: JsonElement? = null,
)

@Serializable
data class SoundnessReportDto(
    @SerialName("overall_sound") val overallSound: Boolean,
    @SerialName("demand_count") val demandCount: Int,
    @SerialName("sound_count") val soundCount: Int,
    val demands: List<DemandSoundnessDto>,
    @SerialName("cross_demand_violations") val crossDemandViolations: List<ViolationDto>,
    @SerialName("deep_check") val deepCheck: Boolean,
    @SerialName("checked_at") val checkedAt: String? = null,
)

@Serializable
data class DemandSoundnessDto(
    @SerialName("demand_id") val demandId: String,
    val sound: Boolean,
    val violations: List<ViolationDto>,
)

@Serializable
data class ViolationDto(
    val rule: String,
    @SerialName("node_path") val nodePath: String,
    val message: String,
    val expected: JsonElement? = null,
    val actual: JsonElement? = null,
)

@Serializable
data class PlanRunEventDto(
    val id: Int,
    val kind: String,
    val payload: JsonElement? = null,
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
    val name: String? = null,
    val notes: String? = null,
    @SerialName("is_initial") val isInitial: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_active_designated") val isActiveDesignated: Boolean = false,
    val events: List<PlanRunEventDto> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("chosen_depth") val chosenDepth: Int? = null,
    val attempts: JsonElement? = null,  // [{depth, duration_ms}, ...] for optimal-depth runs
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
