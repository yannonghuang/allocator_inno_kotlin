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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.allocator.MaterialImpactRoute")
private val materialImpactScope = CoroutineScope(Dispatchers.IO)
private val materialImpactJobs = ConcurrentHashMap<String, MutableMap<String, Any?>>()
// Cap concurrent re-plans to protect heap during bulk criticality scans.
private val replanSemaphore = Semaphore(permits = 2)
// Terminal jobs older than this are swept on GET in case the client abandoned polling.
private const val JOB_TTL_SECONDS = 600L

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class MaterialImpactRequest(
    val supplyId: String,
    val deliveryDelayDays: Int = 0,
    val quantityDecreasePct: Double = 0.0,
    /** Absolute qty reduction. When set and > 0, takes precedence over quantityDecreasePct. */
    val quantityDecreaseAbs: Double? = null,
    /** Pin to a specific baseline plan run. Omit (or null) to use the latest successful run. */
    val planRunId: Int? = null,
    /**
     * When false, skip persisting the contingent plan run to the database.
     * Use for bulk criticality scans where storing every what-if run would pollute history.
     * Default: true (persist, for interactive what-if analysis).
     */
    val persist: Boolean = true,
    /**
     * Negotiation-chain index. 0 = initial contingent; 1..N = counter-proposal rounds.
     * When set, the persist path deduplicates by (caseId, supplyId, delay, qty, baseline, round)
     * so agent replay/retry returns the existing contingent id instead of inserting a duplicate.
     */
    val negotiationRound: Int? = null,
    /** Id of the prior round's contingent. When provided, the new row supersedes it. */
    val parentPlanRunId: Int? = null,
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
    val quantityDecreaseAbs: Double? = null,
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
    /** Parsed committed-demands map — either from in-memory casePlanResults (ready) or DB (success). */
    val resultMap: Map<String, Any>,
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
                set("completedAt", Instant.now())
            }
            return
        }
        val caseId = supplyLookup.caseId
        val supply = supplyLookup.supply

        // 2. Find baseline plan run — always a persisted ("success") run.
        // Auto-persist happens on the frontend when "analyze criticality" is checked.
        val baselineLookup: BaselineLookup? = if (req.planRunId != null) {
            // Explicit run pinned — must be a persisted run.
            transaction {
                val row = PlanRuns.selectAll()
                    .where { (PlanRuns.id eq req.planRunId) and (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                    .firstOrNull() ?: return@transaction null
                val resultMap = row[PlanRuns.result]?.let { json ->
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        jsonElementToNative(Json.parseToJsonElement(json)) as? Map<String, Any>
                    }.getOrNull()
                }
                resultMap?.let { BaselineLookup(row[PlanRuns.id], it, row[PlanRuns.config]) }
            }
        } else {
            // Latest persisted success run for this case.
            transaction {
                val row = PlanRuns.selectAll()
                    .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                    .orderBy(PlanRuns.id, SortOrder.DESC)
                    .firstOrNull() ?: return@transaction null
                val resultMap = row[PlanRuns.result]?.let { json ->
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        jsonElementToNative(Json.parseToJsonElement(json)) as? Map<String, Any>
                    }.getOrNull()
                }
                resultMap?.let { BaselineLookup(row[PlanRuns.id], it, row[PlanRuns.config]) }
            }
        }

        if (baselineLookup == null) {
            val note = if (req.planRunId != null)
                "Plan run ${req.planRunId} not found for this case."
            else
                "No plan result available. Run a plan first."
            val resp = MaterialImpactResponse(
                caseId = caseId, planRunId = null, supply = supply,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                quantityDecreaseAbs = req.quantityDecreaseAbs,
                impactedDemandCount = 0, impacts = emptyList(), note = note,
            )
            materialImpactJobs[jobId]?.apply {
                set("status", "completed")
                set("result", Json.encodeToJsonElement(resp))
                set("completedAt", Instant.now())
            }
            return
        }

        val baselinePlanRunId = baselineLookup.planRunId

        // 3. Load case data and mutate the target supply
        val caseData = CaseLoader.load(caseId)
        val mutatedSupply = (caseData["supply"] ?: emptyList()).map { s ->
            if ((s["supply_id"] as? String)?.trim() == req.supplyId.trim()) {
                s.toMutableMap().apply {
                    val oldQty = (s["qty"] as? Number)?.toDouble() ?: 0.0
                    val newQty = when {
                        req.quantityDecreaseAbs != null && req.quantityDecreaseAbs > 0 ->
                            maxOf(0.0, oldQty - req.quantityDecreaseAbs)
                        req.quantityDecreasePct > 0 ->
                            oldQty * (1.0 - req.quantityDecreasePct / 100.0)
                        else -> oldQty
                    }
                    if (newQty != oldQty) put("qty", newQty)
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

        // 4b. Cache lookup: has an earlier call already re-planned these exact params?
        // Key: (caseId, baselinePlanRunId, supplyId, deliveryDelayDays, quantityDecreasePct)
        // plus negotiationRound / parentPlanRunId when the caller declares them.
        //
        // Fires for all callers (persist=true or false): material_engine persists the
        // contingent on its first pass, and order_engine's later persist=false call
        // matches on the same params and skips the second 10s runPlanning. Planning
        // agent goes through /material-impact-assessment (not this endpoint), so it is
        // already free of this redundancy — but any future caller of /material-impact
        // picks up the cache automatically via this lookup.
        val cachedHit: Pair<Int, Map<String, Any>>? = transaction {
            val candidates = PlanRuns.selectAll()
                .where {
                    var cond: Op<Boolean> = (PlanRuns.caseId eq caseId) and
                        (PlanRuns.status inList listOf("contingent", "success")) and
                        PlanRuns.supersededByPlanRunId.isNull()
                    if (req.negotiationRound != null) {
                        cond = cond and (PlanRuns.negotiationRound eq req.negotiationRound)
                    }
                    if (req.parentPlanRunId != null) {
                        cond = cond and (PlanRuns.parentPlanRunId eq req.parentPlanRunId)
                    }
                    cond
                }
                .orderBy(PlanRuns.id, SortOrder.DESC)
                .toList()
            candidates.firstNotNullOfOrNull { row ->
                val meta = row[PlanRuns.metadata]?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                } ?: return@firstNotNullOfOrNull null
                val matches = meta["supplyId"]?.jsonPrimitive?.contentOrNull == req.supplyId.trim() &&
                    meta["deliveryDelayDays"]?.jsonPrimitive?.intOrNull == req.deliveryDelayDays &&
                    meta["quantityDecreasePct"]?.jsonPrimitive?.doubleOrNull == req.quantityDecreasePct &&
                    meta["baselinePlanRunId"]?.jsonPrimitive?.intOrNull == baselinePlanRunId
                if (!matches) return@firstNotNullOfOrNull null
                val resultJson = row[PlanRuns.result] ?: return@firstNotNullOfOrNull null
                val parsed = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    jsonElementToNative(Json.parseToJsonElement(resultJson)) as? Map<String, Any>
                }.getOrNull() ?: return@firstNotNullOfOrNull null
                row[PlanRuns.id] to parsed
            }
        }

        val contingentResult: Map<String, Any>
        val contingentPlanRunId: Int?

        if (cachedHit != null) {
            log.info(
                "material-impact cache hit: contingentPlanRunId={} baseline={} supplyId={} delay={} qtyPct={} (skipping re-plan)",
                cachedHit.first, baselinePlanRunId, req.supplyId, req.deliveryDelayDays, req.quantityDecreasePct,
            )
            contingentResult = cachedHit.second
            contingentPlanRunId = cachedHit.first
        } else {
            // 5. Re-run planning with the mutated supply (bounded concurrency via semaphore)
            log.info("material-impact re-plan: jobId={} supplyId={} delay={} qtyDecrease={}",
                jobId, req.supplyId, req.deliveryDelayDays, req.quantityDecreasePct)
            contingentResult = replanSemaphore.withPermit {
                runPlanning(mutatedData, config = parsedConfig)
            }

            // 6. Persist contingent plan run + material event record (only when persist=true).
            // Cache-miss path: insert a fresh contingent row so the next identical request
            // (e.g. order_engine's follow-up with persist=false) hits the cache above.
            contingentPlanRunId = if (req.persist) {
                val metadataJson = buildJsonObject {
                    put("type", "contingent")
                    put("supplyId", req.supplyId)
                    put("deliveryDelayDays", req.deliveryDelayDays)
                    put("quantityDecreasePct", req.quantityDecreasePct)
                    put("baselinePlanRunId", baselinePlanRunId)
                    if (req.negotiationRound != null) put("negotiationRound", req.negotiationRound)
                    if (req.parentPlanRunId != null) put("parentPlanRunId", req.parentPlanRunId)
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
                        if (req.negotiationRound != null) it[PlanRuns.negotiationRound] = req.negotiationRound
                        if (req.parentPlanRunId != null) it[PlanRuns.parentPlanRunId] = req.parentPlanRunId
                    }[PlanRuns.id]
                    if (req.parentPlanRunId != null) {
                        PlanRuns.update({ PlanRuns.id eq req.parentPlanRunId }) {
                            it[PlanRuns.supersededByPlanRunId] = planRunId
                        }
                    }
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
        }

        // 7. Diff baseline vs contingent
        val baselineOutcomes = parseCommittedDemands(baselineLookup.resultMap)
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
                quantityDecreaseAbs = req.quantityDecreaseAbs,
                impactedDemandCount = 0, impacts = emptyList(),
            )
            materialImpactJobs[jobId]?.apply {
                set("status", "completed")
                set("result", Json.encodeToJsonElement(resp))
                set("completedAt", Instant.now())
            }
            return
        }

        // 8. Build impacts list — only when persist=true (interactive what-if).
        // Bulk criticality scans (persist=false) only read `impactedDemandCount`, so skip
        // the demand-metadata DB fetch + per-demand payload to keep the job map payload small.
        val impacts: List<MaterialImpactedDemand> = if (req.persist) {
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

            demandMetas.map { d ->
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
            }.also { list ->
                list.forEach { d ->
                    log.info(
                        "  impacted: demandId={} baselineQty={} contingentQty={} delta={} status={}",
                        d.demandId, d.baselineCommittedQty, d.contingentCommittedQty, d.qtyDelta, d.status,
                    )
                }
            }
        } else emptyList()

        val resp = MaterialImpactResponse(
            caseId = caseId, planRunId = baselinePlanRunId,
            contingentPlanRunId = contingentPlanRunId,
            supply = supply,
            deliveryDelayDays = req.deliveryDelayDays,
            quantityDecreasePct = req.quantityDecreasePct,
            impactedDemandCount = impactedDemandIds.size,
            impacts = impacts,
        )
        materialImpactJobs[jobId]?.apply {
            set("status", "completed")
            set("result", Json.encodeToJsonElement(resp))
            set("completedAt", Instant.now())
        }

    } catch (e: Exception) {
        log.error("material-impact job $jobId failed: ${e.message}", e)
        materialImpactJobs[jobId]?.apply {
            set("status", "failed")
            set("error", e.message ?: "Unknown error")
            set("completedAt", Instant.now())
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
            val status = job["status"]?.toString() ?: "unknown"
            call.respond(buildJsonObject {
                put("status", status)
                val result = job["result"]
                if (result != null) put("result", resultToJson(result))
                val error = job["error"]
                if (error != null) put("error", error.toString())
            })
            // Evict on client-observed terminal state so completed/failed jobs don't pile up.
            if (status == "completed" || status == "failed") {
                materialImpactJobs.remove(jobId)
            }
            // Opportunistic TTL sweep: drop terminal jobs where the client abandoned polling.
            val cutoff = Instant.now().minusSeconds(JOB_TTL_SECONDS)
            materialImpactJobs.entries.removeIf { (_, j) ->
                val s = j["status"]?.toString()
                val completedAt = j["completedAt"] as? Instant
                (s == "completed" || s == "failed") &&
                    completedAt != null && completedAt.isBefore(cutoff)
            }
        }
    }
}

@Serializable
private data class PromoteResponse(val planRunId: Int, val caseId: Int, val status: String)

@Serializable
data class NegotiationChainEntry(
    val planRunId: Int,
    val round: Int?,
    val parentPlanRunId: Int?,
    val supersededByPlanRunId: Int?,
    val status: String,
    val supplyId: String?,
    val deliveryDelayDays: Int?,
    val quantityDecreasePct: Double?,
    val baselinePlanRunId: Int?,
    val rating: String?,
    val explanation: String?,
    val createdAt: String,
)

/**
 * POST /cases/{caseId}/plan-runs/{planRunId}/promote
 * Promotes a contingent plan run to "success" status, making it the active plan for the case.
 * Only plan runs with status="contingent" can be promoted.
 * Rejects promotion of a run that has been superseded by a later round in a negotiation chain.
 * Idempotent: already-promoted runs return 200 without error.
 *
 * GET /cases/{caseId}/negotiation-chains/{baselinePlanRunId}
 * Returns all contingent plan runs derived from the given baseline, ordered by round/creation,
 * joined with their MaterialImpactAssessments rating/explanation where available.
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
            val supersededBy = row[PlanRuns.supersededByPlanRunId]
            when (status) {
                "contingent" -> {
                    if (supersededBy != null) {
                        throw IllegalArgumentException(
                            "PlanRun $planRunId has been superseded by $supersededBy; promote the latest round in the chain."
                        )
                    }
                    PlanRuns.update({ PlanRuns.id eq planRunId }) {
                        it[PlanRuns.status] = "success"
                    }
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

    get("/cases/{caseId}/negotiation-chains/{baselinePlanRunId}") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val baselinePlanRunId = call.parameters["baselinePlanRunId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid baselinePlanRunId")

        val entries: List<NegotiationChainEntry> = transaction {
            // Select all contingent plan_runs whose metadata declares this baseline.
            val allContingents = PlanRuns.selectAll()
                .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status.inList(listOf("contingent", "success"))) }
                .orderBy(PlanRuns.id, SortOrder.ASC)
                .toList()

            val chainRows = allContingents.filter { row ->
                val meta = row[PlanRuns.metadata]?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                } ?: return@filter false
                meta["baselinePlanRunId"]?.jsonPrimitive?.intOrNull == baselinePlanRunId
            }

            // Collect assessments keyed by (supplyId, delay, qty, planRunId=baseline) for rating lookup.
            // Rating rows are created against the baseline plan, not against the contingent,
            // so we key on baselinePlanRunId + the per-round params.
            val assessments = MaterialImpactAssessments.selectAll()
                .where { (MaterialImpactAssessments.caseId eq caseId) and (MaterialImpactAssessments.planRunId eq baselinePlanRunId) }
                .orderBy(MaterialImpactAssessments.id, SortOrder.DESC)
                .toList()
            fun latestAssessment(supplyId: String, delay: Int, qtyPct: Double) =
                assessments.firstOrNull { a ->
                    a[MaterialImpactAssessments.supplyId] == supplyId &&
                    a[MaterialImpactAssessments.deliveryDelayDays] == delay &&
                    kotlin.math.abs(a[MaterialImpactAssessments.quantityDecreasePct] - qtyPct) < 1e-9
                }

            chainRows.map { row ->
                val meta = row[PlanRuns.metadata]?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                val supplyId = meta?.get("supplyId")?.jsonPrimitive?.contentOrNull
                val delay = meta?.get("deliveryDelayDays")?.jsonPrimitive?.intOrNull
                val qty = meta?.get("quantityDecreasePct")?.jsonPrimitive?.doubleOrNull
                val assessment = if (supplyId != null && delay != null && qty != null)
                    latestAssessment(supplyId, delay, qty) else null
                NegotiationChainEntry(
                    planRunId = row[PlanRuns.id],
                    round = row[PlanRuns.negotiationRound],
                    parentPlanRunId = row[PlanRuns.parentPlanRunId],
                    supersededByPlanRunId = row[PlanRuns.supersededByPlanRunId],
                    status = row[PlanRuns.status],
                    supplyId = supplyId,
                    deliveryDelayDays = delay,
                    quantityDecreasePct = qty,
                    baselinePlanRunId = baselinePlanRunId,
                    rating = assessment?.get(MaterialImpactAssessments.rating),
                    explanation = assessment?.get(MaterialImpactAssessments.explanation),
                    createdAt = row[PlanRuns.createdAt].toString(),
                )
            }
        }
        call.respond(entries)
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

    // AI agent path: only use persisted "success" runs — never in-memory "ready" state.
    // If no saved run exists, throw so the agent knows to run and save a plan first.
    val latestPlanRun = if (req.planRunId != null) {
        PlanRuns.selectAll()
            .where { (PlanRuns.id eq req.planRunId) and (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
            .firstOrNull()
            ?: throw NoSuchElementException(
                "Plan run ${req.planRunId} not found or has not been saved yet. Save the plan run before requesting an assessment."
            )
    } else {
        PlanRuns.selectAll()
            .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
            .orderBy(PlanRuns.id, SortOrder.DESC)
            .firstOrNull()
            ?: throw NoSuchElementException(
                "No saved plan run found for this case. Run a plan and save it before requesting an assessment."
            )
    }

    val resolvedPlanRunId = latestPlanRun[PlanRuns.id]

    val planResult: JsonElement = run {
        val resultJson = latestPlanRun[PlanRuns.result]
            ?: throw IllegalStateException("Plan run $resolvedPlanRunId has no result data.")
        Json.parseToJsonElement(resultJson)
    }

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
