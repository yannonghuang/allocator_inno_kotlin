package com.allocator.api

import com.allocator.*
import com.allocator.services.CaseLoader
import com.allocator.services.runPlanning
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.allocator.MaterialImpactRoute")
private val materialImpactScope = CoroutineScope(Dispatchers.IO)
private val materialImpactJobs = ConcurrentHashMap<String, MutableMap<String, Any?>>()

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class MaterialImpactRequest(
    val supplyId: String,
    val deliveryDelayDays: Int = 0,
    val quantityDecreasePct: Double = 0.0,
    /** Pin to a specific baseline plan run. Omit (or null) to use the latest successful run. */
    val planRunId: Int? = null,
    /**
     * When false, skip persisting the contingent plan run to the database.
     * Use for bulk criticality scans where storing every what-if run would pollute history.
     * Default: true (persist, for interactive what-if analysis).
     */
    val persist: Boolean = true,
)

@Serializable
data class MaterialSupplyDetail(
    val supplyId: String,
    val productId: String,
    val qty: Double,
    val supplyDate: String?,
    val locationId: String?,
    val vendorId: String?,
)

@Serializable
data class MaterialImpactedDemand(
    val demandId: String,
    val productId: String,
    val locationId: String?,
    val customerId: String,
    val description: String?,
    val priority: Int?,
    val requestDueTime: String?,
    val requestedQty: Double,
    // ── Backward-compat fields (used by AssessmentRoutes prompt builder) ──────
    /** Baseline committed qty — mirrors baselineCommittedQty for older consumers. */
    val consumedSupplyQty: Double = 0.0,
    /** Coarse status: "newly_failed" | "qty_reduced" | "at_risk" (no-replan path). */
    val status: String = "at_risk",
    // ── Re-plan diff fields ───────────────────────────────────────────────────
    val baselineCommittedQty: Double = 0.0,
    val contingentCommittedQty: Double = 0.0,
    /** contingentCommittedQty - baselineCommittedQty; negative means shortfall. */
    val qtyDelta: Double = 0.0,
    val baselineFailed: Boolean = false,
    val contingentFailed: Boolean = false,
    val contingentCommitReason: String? = null,
)

@Serializable
data class MaterialImpactResponse(
    val caseId: Int,
    /** Baseline plan run id (latest successful at time of request). */
    val planRunId: Int?,
    /** The newly-created contingent plan run id (null if re-plan not performed). */
    val contingentPlanRunId: Int? = null,
    val supply: MaterialSupplyDetail,
    val deliveryDelayDays: Int,
    val quantityDecreasePct: Double,
    val impactedDemandCount: Int,
    val impacts: List<MaterialImpactedDemand>,
    val note: String? = null,
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private val SUPPLY_DATE_FORMATS = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,                         // yyyy-MM-dd
    DateTimeFormatter.ofPattern("MM/dd/yyyy"),
    DateTimeFormatter.ofPattern("M/d/yyyy"),
)

private fun parseSupplyDate(dateStr: String?): LocalDate? {
    if (dateStr.isNullOrBlank()) return null
    for (fmt in SUPPLY_DATE_FORMATS) {
        runCatching { return LocalDate.parse(dateStr, fmt) }
    }
    return null
}

private val FAILURE_REASONS_SET = setOf("depth_limit", "cycle_stopped", "no_methods", "no_preferred_method")
private fun isFailure(reason: String?) =
    reason != null && (reason in FAILURE_REASONS_SET || reason.startsWith("child_failed:"))

/**
 * For each demand_id in committed_demands, returns (effectiveQty, failureReason).
 * effectiveQty = sum of all non-failure rows' quantities.
 * failureReason = first failure commit_reason found for this demand (or null).
 */
private data class DemandOutcome(val effectiveQty: Double, val failureReason: String?)

@Suppress("UNCHECKED_CAST")
private fun parseCommittedDemands(result: Map<String, Any>): Map<String, DemandOutcome> {
    val committed = result["committed_demands"] as? List<Map<String, Any?>> ?: return emptyMap()
    val qtyMap = mutableMapOf<String, Double>()
    val reasonMap = mutableMapOf<String, String?>()   // first failure reason per demand
    for (row in committed) {
        val id = (row["demand_id"]?.toString() ?: "").trim()
        if (id.isBlank()) continue
        val reason = row["commit_reason"] as? String
        if (!isFailure(reason)) {
            val qty = (row["quantity"] as? Number)?.toDouble() ?: 0.0
            qtyMap[id] = (qtyMap[id] ?: 0.0) + qty
        } else if (!reasonMap.containsKey(id)) {
            reasonMap[id] = reason
        }
    }
    // Union of all demand_ids seen
    val allIds = committed
        .map { (it["demand_id"]?.toString() ?: "").trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return allIds.associateWith { id ->
        DemandOutcome(qtyMap[id] ?: 0.0, reasonMap[id])
    }
}

/** Lightweight JSON serializer for Map<String, Any?> result storage. */
private fun resultToJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, vv) -> put(k.toString(), resultToJson(vv)) } }
    is List<*> -> buildJsonArray { v.forEach { add(resultToJson(it)) } }
    else -> JsonPrimitive(v.toString())
}

// ── Helper data classes for reading DB rows inside transactions ───────────────

private data class SupplyLookup(
    val caseId: Int,
    val supply: MaterialSupplyDetail,
)

private data class BaselineLookup(
    val planRunId: Int,
    val resultJson: String,
    val configJson: String?,
)

private data class DemandMeta(
    val demandId: String,
    val productId: String,
    val locationId: String?,
    val customerId: String,
    val description: String?,
    val priority: Int?,
    val requestDueTime: String?,
    val requestedQty: Double,
)

// ── Background job ────────────────────────────────────────────────────────────

private suspend fun runMaterialImpactBackground(jobId: String, req: MaterialImpactRequest) {
    try {
        // 1. Look up supply — read all columns inside transaction
        val supplyLookup: SupplyLookup? = transaction {
            Supplies.selectAll()
                .where { Supplies.supplyId eq req.supplyId }
                .firstOrNull()
                ?.let { row ->
                    val productId = row[Supplies.productId].trim()
                    SupplyLookup(
                        caseId = row[Supplies.caseId],
                        supply = MaterialSupplyDetail(
                            supplyId = row[Supplies.supplyId],
                            productId = productId,
                            qty = row[Supplies.qty],
                            supplyDate = row[Supplies.supplyDate],
                            locationId = row[Supplies.locationId],
                            vendorId = row[Supplies.vendorId],
                        ),
                    )
                }
        }
        if (supplyLookup == null) {
            materialImpactJobs[jobId]?.apply {
                set("status", "failed")
                set("error", "Supply '${req.supplyId}' not found")
            }
            return
        }
        val caseId = supplyLookup.caseId
        val supply = supplyLookup.supply

        // 2. Find baseline plan run — read all columns inside transaction
        val baselineLookup: BaselineLookup? = transaction {
            val row = if (req.planRunId != null) {
                PlanRuns.selectAll()
                    .where { (PlanRuns.id eq req.planRunId) and (PlanRuns.caseId eq caseId) }
                    .firstOrNull()
            } else {
                PlanRuns.selectAll()
                    .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                    .orderBy(PlanRuns.id, SortOrder.DESC)
                    .firstOrNull()
            }
            row?.let { BaselineLookup(it[PlanRuns.id], it[PlanRuns.result] ?: "", it[PlanRuns.config]) }
        }

        if (baselineLookup == null) {
            val note = if (req.planRunId != null)
                "Plan run ${req.planRunId} not found for this case."
            else
                "No successful plan run found for this case."
            val resp = MaterialImpactResponse(
                caseId = caseId, planRunId = null, supply = supply,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                impactedDemandCount = 0, impacts = emptyList(), note = note,
            )
            materialImpactJobs[jobId]?.apply { set("status", "completed"); set("result", Json.encodeToJsonElement(resp)) }
            return
        }

        val baselinePlanRunId = baselineLookup.planRunId
        val baselineResultJson = baselineLookup.resultJson
        if (baselineResultJson.isBlank()) {
            val resp = MaterialImpactResponse(
                caseId = caseId, planRunId = baselinePlanRunId, supply = supply,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                impactedDemandCount = 0, impacts = emptyList(),
                note = "Baseline plan run has no result data.",
            )
            materialImpactJobs[jobId]?.apply { set("status", "completed"); set("result", Json.encodeToJsonElement(resp)) }
            return
        }

        // 3. Load case data and mutate the target supply
        val caseData = CaseLoader.load(caseId)
        val mutatedSupply = (caseData["supply"] ?: emptyList()).map { s ->
            if ((s["supply_id"] as? String)?.trim() == req.supplyId.trim()) {
                s.toMutableMap().apply {
                    if (req.quantityDecreasePct > 0) {
                        val oldQty = (s["qty"] as? Number)?.toDouble() ?: 0.0
                        put("qty", oldQty * (1.0 - req.quantityDecreasePct / 100.0))
                    }
                    if (req.deliveryDelayDays > 0) {
                        val newDate = parseSupplyDate(s["supply_date"] as? String)
                            ?.plusDays(req.deliveryDelayDays.toLong())
                            ?: LocalDate.now().plusDays(req.deliveryDelayDays.toLong())
                        put("supply_date", newDate.toString())
                    }
                }
            } else s
        }
        @Suppress("UNCHECKED_CAST")
        val mutatedData = (caseData as Map<String, Any>).toMutableMap()
            .apply { put("supply", mutatedSupply) } as Map<String, List<Map<String, Any?>>>

        // 4. Parse baseline config for consistent comparison (all values already extracted)
        val parsedConfig: Map<String, Any?>? = baselineLookup.configJson?.let { cfgStr ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                jsonElementToNative(Json.parseToJsonElement(cfgStr)) as? Map<String, Any?>
            }.getOrNull()
        }

        // 5. Re-run planning with the mutated supply
        log.info("material-impact re-plan: jobId={} supplyId={} delay={} qtyDecrease={}",
            jobId, req.supplyId, req.deliveryDelayDays, req.quantityDecreasePct)
        val contingentResult = runPlanning(mutatedData, config = parsedConfig)

        // 6. Persist contingent plan run + material event record (only when persist=true)
        val contingentPlanRunId: Int? = if (req.persist) {
            val metadataJson = buildJsonObject {
                put("type", "contingent")
                put("supplyId", req.supplyId)
                put("deliveryDelayDays", req.deliveryDelayDays)
                put("quantityDecreasePct", req.quantityDecreasePct)
                put("baselinePlanRunId", baselinePlanRunId)
            }.toString()
            val contingentResultJson = runCatching { resultToJson(contingentResult).toString() }.getOrNull()
            transaction {
                val planRunId = PlanRuns.insert {
                    it[PlanRuns.caseId] = caseId
                    it[PlanRuns.jobId] = jobId
                    it[PlanRuns.status] = "contingent"
                    it[PlanRuns.config] = baselineLookup.configJson
                    it[PlanRuns.result] = contingentResultJson
                    it[PlanRuns.metadata] = metadataJson
                }[PlanRuns.id]
                // Record the material event so it appears in the case view
                MaterialEvents.insert {
                    it[MaterialEvents.caseId]         = caseId
                    it[MaterialEvents.supplyId]       = req.supplyId.trim()
                    it[MaterialEvents.delayDays]      = req.deliveryDelayDays
                    it[MaterialEvents.qtyDecreasePct] = req.quantityDecreasePct
                    it[MaterialEvents.note]           = "AI agent analysis (planRun=$planRunId)"
                }
                planRunId
            }
        } else null

        // 7. Diff baseline vs contingent
        @Suppress("UNCHECKED_CAST")
        val baselineResultMap = runCatching {
            jsonElementToNative(Json.parseToJsonElement(baselineResultJson)) as? Map<String, Any>
        }.getOrNull() ?: emptyMap<String, Any>()

        val baselineOutcomes = parseCommittedDemands(baselineResultMap)
        val contingentOutcomes = parseCommittedDemands(contingentResult)

        // Demands that had committed qty in baseline but degraded in contingent
        val impactedDemandIds = baselineOutcomes.entries
            .filter { (_, base) -> base.effectiveQty > 1e-9 }   // was committed in baseline
            .filter { (id, base) ->
                val cont = contingentOutcomes[id]
                val contQty = cont?.effectiveQty ?: 0.0
                contQty < base.effectiveQty - 1e-9              // qty decreased
            }
            .map { it.key }
            .toSet()

        log.info("material-impact diff: jobId={} baselinePlanRunId={} contingentPlanRunId={} impacted={}",
            jobId, baselinePlanRunId, contingentPlanRunId, impactedDemandIds.size)

        if (impactedDemandIds.isEmpty()) {
            val resp = MaterialImpactResponse(
                caseId = caseId, planRunId = baselinePlanRunId,
                contingentPlanRunId = contingentPlanRunId,
                supply = supply,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                impactedDemandCount = 0, impacts = emptyList(),
            )
            materialImpactJobs[jobId]?.apply { set("status", "completed"); set("result", Json.encodeToJsonElement(resp)) }
            return
        }

        // 8. Fetch demand metadata for impacted demands — read all columns inside transaction
        val demandMetas: List<DemandMeta> = transaction {
            Demands.selectAll()
                .where { (Demands.caseId eq caseId) and (Demands.demandId inList impactedDemandIds.toList()) }
                .map { d ->
                    DemandMeta(
                        demandId = d[Demands.demandId],
                        productId = d[Demands.productId],
                        locationId = d[Demands.locationId],
                        customerId = d[Demands.customerId],
                        description = d[Demands.description],
                        priority = d[Demands.priority],
                        requestDueTime = d[Demands.requestDueTime],
                        requestedQty = d[Demands.quantity],
                    )
                }
        }

        val impacts = demandMetas.map { d ->
            val did = d.demandId
            val base = baselineOutcomes[did]
            val cont = contingentOutcomes[did]
            val baseQty = base?.effectiveQty ?: 0.0
            val contQty = cont?.effectiveQty ?: 0.0
            val qtyDelta = contQty - baseQty
            val contingentFailed = contQty < 1e-9
            val status = when {
                contingentFailed -> "newly_failed"
                else -> "qty_reduced"
            }
            MaterialImpactedDemand(
                demandId = did,
                productId = d.productId,
                locationId = d.locationId,
                customerId = d.customerId,
                description = d.description,
                priority = d.priority,
                requestDueTime = d.requestDueTime,
                requestedQty = d.requestedQty,
                consumedSupplyQty = baseQty,
                status = status,
                baselineCommittedQty = baseQty,
                contingentCommittedQty = contQty,
                qtyDelta = qtyDelta,
                baselineFailed = base?.failureReason != null,
                contingentFailed = contingentFailed,
                contingentCommitReason = cont?.failureReason,
            )
        }

        impacts.forEach { d ->
            log.info(
                "  impacted: demandId={} baselineQty={} contingentQty={} delta={} status={}",
                d.demandId, d.baselineCommittedQty, d.contingentCommittedQty, d.qtyDelta, d.status,
            )
        }

        val resp = MaterialImpactResponse(
            caseId = caseId, planRunId = baselinePlanRunId,
            contingentPlanRunId = contingentPlanRunId,
            supply = supply,
            deliveryDelayDays = req.deliveryDelayDays,
            quantityDecreasePct = req.quantityDecreasePct,
            impactedDemandCount = impacts.size,
            impacts = impacts,
        )
        materialImpactJobs[jobId]?.apply { set("status", "completed"); set("result", Json.encodeToJsonElement(resp)) }

    } catch (e: Exception) {
        log.error("material-impact job $jobId failed: ${e.message}", e)
        materialImpactJobs[jobId]?.apply {
            set("status", "failed")
            set("error", e.message ?: "Unknown error")
        }
    }
}

// ── Routes ────────────────────────────────────────────────────────────────────

/**
 * POST /material-impact
 * Submits an async material impact analysis job. Returns 202 with a jobId.
 * The job re-runs the planning engine with the supply modified and diffs the result
 * against the baseline, so impacted demands reflect actual planning outcomes
 * (alternatives are naturally used — no static escape-route guessing).
 *
 * GET /material-impact/status/{jobId}
 * Polls for job result. Returns {"status":"running|completed|failed","result"?:...,"error"?:...}
 */
fun Routing.materialImpactRoutes() {
    route("/material-impact") {

        // ── POST — submit async job ────────────────────────────────────────────
        post {
            val rawBody = call.receiveText()
            log.info("material-impact raw body: {}", rawBody)
            val req = try {
                Json.decodeFromString<MaterialImpactRequest>(rawBody)
            } catch (e: Exception) {
                log.error("material-impact body parse failed: {}", e.message)
                throw e
            }
            log.info(
                "material-impact request: supplyId={} delayDays={} qtyDecreasePct={}",
                req.supplyId, req.deliveryDelayDays, req.quantityDecreasePct,
            )

            val jobId = UUID.randomUUID().toString()
            materialImpactJobs[jobId] = mutableMapOf(
                "status" to "running",
                "result" to null,
                "error" to null,
            )
            materialImpactScope.launch { runMaterialImpactBackground(jobId, req) }

            call.response.headers.append("Location", "/material-impact/status/$jobId")
            call.respond(HttpStatusCode.Accepted, buildJsonObject {
                put("jobId", jobId)
                put("status", "running")
                put("message", "Poll GET /material-impact/status/$jobId for result.")
            })
        }

        // ── GET /status/{jobId} — poll ─────────────────────────────────────────
        get("/status/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw IllegalArgumentException("jobId required")
            val job = materialImpactJobs[jobId]
                ?: throw NoSuchElementException("Material impact job '$jobId' not found")
            call.respond(buildJsonObject {
                put("status", job["status"]?.toString() ?: "unknown")
                val result = job["result"]
                if (result != null) put("result", resultToJson(result))
                val error = job["error"]
                if (error != null) put("error", error.toString())
            })
        }
    }
}

@Serializable
private data class PromoteResponse(val planRunId: Int, val caseId: Int, val status: String)

/**
 * POST /cases/{caseId}/plan-runs/{planRunId}/promote
 * Promotes a contingent plan run to "success" status, making it the active plan for the case.
 * Only plan runs with status="contingent" can be promoted.
 * Idempotent: already-promoted runs return 200 without error.
 */
fun Routing.planRunRoutes() {
    post("/cases/{caseId}/plan-runs/{planRunId}/promote") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val planRunId = call.parameters["planRunId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid planRunId")
        transaction {
            val row = PlanRuns.selectAll()
                .where { (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId) }
                .firstOrNull()
                ?: throw NoSuchElementException("PlanRun $planRunId not found for case $caseId")
            val status = row[PlanRuns.status]
            when (status) {
                "contingent" -> PlanRuns.update({ PlanRuns.id eq planRunId }) {
                    it[PlanRuns.status] = "success"
                }
                "success" -> { /* already promoted — idempotent, no-op */ }
                else -> throw IllegalArgumentException(
                    "PlanRun $planRunId has status='$status'; only 'contingent' runs can be promoted"
                )
            }
        }
        log.info("plan-run promoted: caseId={} planRunId={} status=success", caseId, planRunId)
        call.respond(PromoteResponse(planRunId = planRunId, caseId = caseId, status = "success"))
    }
}

// ── Legacy synchronous path (used by AssessmentRoutes Mode A) ─────────────────
//
// Kept intact so AssessmentRoutes can call it directly without going through the
// async job queue. Uses the original pegging-tree walk (fast, approximate).
//
internal fun computeMaterialImpact(req: MaterialImpactRequest): MaterialImpactResponse = transaction {
    val supplyRow = Supplies.selectAll()
        .where { Supplies.supplyId eq req.supplyId }
        .firstOrNull()
        ?: throw IllegalArgumentException("Supply '${req.supplyId}' not found")

    val caseId = supplyRow[Supplies.caseId]
    val productId = supplyRow[Supplies.productId].trim()
    val supply = MaterialSupplyDetail(
        supplyId = supplyRow[Supplies.supplyId],
        productId = productId,
        qty = supplyRow[Supplies.qty],
        supplyDate = supplyRow[Supplies.supplyDate],
        locationId = supplyRow[Supplies.locationId],
        vendorId = supplyRow[Supplies.vendorId],
    )

    val latestPlanRun = if (req.planRunId != null) {
        PlanRuns.selectAll()
            .where { (PlanRuns.id eq req.planRunId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull()
    } else {
        PlanRuns.selectAll()
            .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
            .orderBy(PlanRuns.id, SortOrder.DESC)
            .firstOrNull()
    }

    if (latestPlanRun == null) {
        val note = if (req.planRunId != null)
            "Plan run ${req.planRunId} not found for this case."
        else
            "No successful plan run found for this case."
        return@transaction MaterialImpactResponse(
            caseId = caseId, planRunId = null, supply = supply,
            deliveryDelayDays = req.deliveryDelayDays,
            quantityDecreasePct = req.quantityDecreasePct,
            impactedDemandCount = 0, impacts = emptyList(), note = note,
        )
    }

    val resolvedPlanRunId = latestPlanRun[PlanRuns.id]
    val resultJson = latestPlanRun[PlanRuns.result]
        ?: return@transaction MaterialImpactResponse(
            caseId = caseId, planRunId = resolvedPlanRunId, supply = supply,
            deliveryDelayDays = req.deliveryDelayDays,
            quantityDecreasePct = req.quantityDecreasePct,
            impactedDemandCount = 0, impacts = emptyList(),
            note = "Plan run has no result data.",
        )

    val planResult = Json.parseToJsonElement(resultJson)
    val pegging = (planResult as? JsonObject)?.get("planning_pegging") as? JsonArray
        ?: JsonArray(emptyList())

    val demandAllocated = mutableMapOf<String, Double>()
    for (entry in pegging) {
        val obj = entry as? JsonObject ?: continue
        val demandId = obj["demand_id"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
        if (demandId.isBlank()) continue
        val tree = obj["tree"] ?: continue
        if (treeContainsSupply(tree, req.supplyId)) {
            val qty = supplyQtyInTree(tree, req.supplyId)
            demandAllocated[demandId] = (demandAllocated[demandId] ?: 0.0) + qty
        }
    }

    if (demandAllocated.isEmpty()) {
        return@transaction MaterialImpactResponse(
            caseId = caseId, planRunId = resolvedPlanRunId, supply = supply,
            deliveryDelayDays = req.deliveryDelayDays,
            quantityDecreasePct = req.quantityDecreasePct,
            impactedDemandCount = 0, impacts = emptyList(),
        )
    }

    // Look up baseline committed qty for each demand from committed_demands in the plan result.
    // This lets the assessment use the real at-risk quantity rather than just this supply's share.
    @Suppress("UNCHECKED_CAST")
    val baselineResultMap = runCatching {
        jsonElementToNative(planResult) as? Map<String, Any>
    }.getOrNull() ?: emptyMap<String, Any>()
    val baselineOutcomes = parseCommittedDemands(baselineResultMap)

    val demandRows = Demands.selectAll()
        .where { (Demands.caseId eq caseId) and (Demands.demandId inList demandAllocated.keys.toList()) }
        .toList()

    val impactStatus = if (req.deliveryDelayDays > 0) "delayed" else "at_risk"

    val impacts = demandRows.map { d ->
        val did = d[Demands.demandId]
        val consumed = demandAllocated[did] ?: 0.0
        val baselineCommittedQty = baselineOutcomes[did]?.effectiveQty ?: 0.0
        MaterialImpactedDemand(
            demandId = did,
            productId = d[Demands.productId],
            locationId = d[Demands.locationId],
            customerId = d[Demands.customerId],
            description = d[Demands.description],
            priority = d[Demands.priority],
            requestDueTime = d[Demands.requestDueTime],
            requestedQty = d[Demands.quantity],
            consumedSupplyQty = consumed,
            status = impactStatus,
            baselineCommittedQty = baselineCommittedQty,
            // contingentCommittedQty stays 0 — sync path has no contingent re-plan
        )
    }

    log.info("material-impact (sync): supplyId={} product={} impactedDemands={}",
        supply.supplyId, supply.productId, impacts.size)

    MaterialImpactResponse(
        caseId = caseId, planRunId = resolvedPlanRunId, supply = supply,
        deliveryDelayDays = req.deliveryDelayDays,
        quantityDecreasePct = req.quantityDecreasePct,
        impactedDemandCount = impacts.size,
        impacts = impacts,
    )
}

// ── Pegging tree helpers (used by legacy sync path) ───────────────────────────

private fun treeContainsSupply(node: JsonElement, supplyId: String): Boolean {
    val obj = node as? JsonObject ?: return false
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "supply" &&
        obj["supply_id"]?.jsonPrimitive?.contentOrNull?.trim() == supplyId
    ) return true
    return (obj["children"] as? JsonArray ?: JsonArray(emptyList()))
        .any { treeContainsSupply(it, supplyId) }
}

private fun supplyQtyInTree(node: JsonElement, supplyId: String): Double {
    val obj = node as? JsonObject ?: return 0.0
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "supply" &&
        obj["supply_id"]?.jsonPrimitive?.contentOrNull?.trim() == supplyId
    ) return obj["quantity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    return (obj["children"] as? JsonArray ?: JsonArray(emptyList()))
        .sumOf { supplyQtyInTree(it, supplyId) }
}
