package com.allocator.api

import com.allocator.*
import com.allocator.services.emitPlanRunEvent
import com.allocator.services.resequenceFromPegging
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.allocator.WorkOrderImpactRoute")
private val woImpactScope = CoroutineScope(Dispatchers.IO)
private val woImpactJobs = ConcurrentHashMap<String, MutableMap<String, Any?>>()
private const val WO_JOB_TTL_SECONDS = 600L

// ── DTOs ──────────────────────────────────────────────────────────────────────

/**
 * One bulk shift descriptor. The UI assembles this after the user has picked
 * a concrete set of WOs from a filterable preview list — so the backend
 * doesn't re-do the filtering. `bucketStart`/`bucketEnd` define the time
 * window the shift is anchored to:
 *   - For `delayDays N`, the window is informational (shift = N for every
 *     WO in `woGroupIds`).
 *   - For `delayToDate D`, the shift is `D - bucketStart` (uniform across
 *     all matched WOs, preserving their relative spacing).
 *
 * The UI may either let the user pick a granularity+bucket (week/month/…)
 * or derive `bucketStart = min(start_time)` / `bucketEnd = max(end_time)`
 * across the chosen WOs. Either way, the backend sees explicit ISO dates.
 */
@Serializable
data class WoScheduleSelector(
    val bucketStart: String,                    // ISO yyyy-MM-dd, REQUIRED
    val bucketEnd: String,                      // ISO yyyy-MM-dd, REQUIRED
    /** Concrete WO group ids the user picked from the preview list. REQUIRED, non-empty. */
    val woGroupIds: List<String> = emptyList(),
)

@Serializable
data class WoScheduleImpactRequest(
    val selectors: List<WoScheduleSelector>,    // OR across; AND within
    val delayDays: Int? = null,                  // mutually exclusive with delayToDate
    val delayToDate: String? = null,             // ISO yyyy-MM-dd
    /** Pin to a specific baseline plan run. Omit (or null) to use the latest successful run. */
    val planRunId: Int? = null,
    val caseId: Int? = null,                     // pinned by caller if known, else inferred
    val persist: Boolean = true,
    val note: String? = null,
    /** When the analyze call originates from a saved WoScheduleEvent, this tags the
     *  resulting contingent plan run back to that event so it appears in the per-event
     *  run-history endpoint. Optional — omit for ad-hoc analyses (e.g. per-WO modal). */
    val woScheduleEventId: Int? = null,
)

@Serializable
data class WoImpactedDemand(
    val demandId: String,
    val productId: String,
    val locationId: String?,
    val customerId: String,
    val description: String?,
    val priority: Int?,
    val requestDueTime: String?,
    val requestedQty: Double,
    val baselineCommitTime: String?,
    val contingentCommitTime: String?,
    /** ChronoUnit.DAYS.between(baseline, contingent). 0 if either is null. */
    val daysDelta: Int,
    /** "delivery_delayed" | "newly_late_vs_due" | "no_change" */
    val status: String,
)

@Serializable
data class WoScheduleImpactResponse(
    val caseId: Int,
    val planRunId: Int?,
    val contingentPlanRunId: Int?,
    val matchedWoCount: Int,
    val delayDays: Int?,
    val delayToDate: String?,
    val impactedDemandCount: Int,
    val impacts: List<WoImpactedDemand>,
    val note: String? = null,
)

// ── Helpers: bucket math ──────────────────────────────────────────────────────

private val ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE
private val ISO_WEEK_DATE = DateTimeFormatter.ISO_WEEK_DATE

internal fun bucketOf(date: LocalDate, granularity: String): String = when (granularity.lowercase()) {
    "day" -> date.format(ISO_DATE)
    "week" -> {
        val wk = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val wkYear = date.get(IsoFields.WEEK_BASED_YEAR)
        "%d-W%02d".format(wkYear, wk)
    }
    "month" -> "%d-%02d".format(date.year, date.monthValue)
    "quarter" -> {
        val q = ((date.monthValue - 1) / 3) + 1
        "%d-Q%d".format(date.year, q)
    }
    else -> throw IllegalArgumentException("Unknown bucket granularity: $granularity")
}

internal fun bucketStartOf(bucketKey: String, granularity: String): LocalDate = when (granularity.lowercase()) {
    "day" -> LocalDate.parse(bucketKey, ISO_DATE)
    "week" -> {
        // "yyyy-Www" — Monday of that ISO week. Use ISO_WEEK_DATE which parses
        // "yyyy-W##-d" where d is day-of-week (1=Monday).
        val match = Regex("""^(\d{4})-W(\d{1,2})$""").matchEntire(bucketKey)
            ?: throw IllegalArgumentException("Invalid week bucket key: $bucketKey")
        val (y, w) = match.destructured
        val isoStr = "%s-W%02d-1".format(y, w.toInt())
        LocalDate.parse(isoStr, ISO_WEEK_DATE)
    }
    "month" -> {
        val match = Regex("""^(\d{4})-(\d{1,2})$""").matchEntire(bucketKey)
            ?: throw IllegalArgumentException("Invalid month bucket key: $bucketKey")
        val (y, m) = match.destructured
        LocalDate.of(y.toInt(), m.toInt(), 1)
    }
    "quarter" -> {
        val match = Regex("""^(\d{4})-Q([1-4])$""").matchEntire(bucketKey)
            ?: throw IllegalArgumentException("Invalid quarter bucket key: $bucketKey")
        val (y, q) = match.destructured
        val firstMonth = (q.toInt() - 1) * 3 + 1
        LocalDate.of(y.toInt(), firstMonth, 1)
    }
    else -> throw IllegalArgumentException("Unknown bucket granularity: $granularity")
}

/** Best-effort ISO-yyyy-MM-dd parse; returns null when input is null/blank/non-parseable. */
private fun parseIsoDateLoose(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    val raw = s.trim().take(10)
    return runCatching { LocalDate.parse(raw, ISO_DATE) }.getOrNull()
}

// ── Result-storage helpers (mirrors MaterialImpactRoutes.resultToJson) ────────

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

// ── Background job ────────────────────────────────────────────────────────────

private data class BaselineLoad(
    val caseId: Int,
    val planRunId: Int,
    val workOrders: List<Map<String, Any?>>,
    val peggingTrees: List<Map<String, Any?>>,
    val resultMap: Map<String, Any>,
    val configJson: String?,
)

private data class WoMeta(
    val demandId: String,
    val productId: String,
    val locationId: String?,
    val customerId: String,
    val description: String?,
    val priority: Int?,
    val requestDueTime: String?,
    val requestedQty: Double,
)

@Suppress("UNCHECKED_CAST")
private fun loadBaseline(req: WoScheduleImpactRequest): BaselineLoad? = transaction {
    val baselineRow = if (req.planRunId != null) {
        val cond: Op<Boolean> = (PlanRuns.id eq req.planRunId) and (PlanRuns.status eq "success")
        val finalCond = if (req.caseId != null) cond and (PlanRuns.caseId eq req.caseId) else cond
        PlanRuns.selectAll().where { finalCond }.firstOrNull()
    } else if (req.caseId != null) {
        PlanRuns.selectAll()
            .where { (PlanRuns.caseId eq req.caseId) and (PlanRuns.status eq "success") }
            .orderBy(PlanRuns.id, SortOrder.DESC)
            .firstOrNull()
    } else null

    if (baselineRow == null) return@transaction null

    val resultJson = baselineRow[PlanRuns.result] ?: return@transaction null
    val resultMap = runCatching {
        jsonElementToNative(Json.parseToJsonElement(resultJson)) as? Map<String, Any>
    }.getOrNull() ?: return@transaction null

    val workOrders = (resultMap["work_orders"] as? List<Map<String, Any?>>) ?: emptyList()
    val peggingTrees = (resultMap["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()

    BaselineLoad(
        caseId = baselineRow[PlanRuns.caseId],
        planRunId = baselineRow[PlanRuns.id],
        workOrders = workOrders,
        peggingTrees = peggingTrees,
        resultMap = resultMap,
        configJson = baselineRow[PlanRuns.config],
    )
}

private fun validateRequest(req: WoScheduleImpactRequest): String? {
    if (req.selectors.isEmpty()) return "selectors must be non-empty"
    if (req.delayDays == null && req.delayToDate.isNullOrBlank())
        return "Either delayDays or delayToDate must be specified"
    if (req.delayDays != null && !req.delayToDate.isNullOrBlank())
        return "delayDays and delayToDate are mutually exclusive"
    if (req.delayDays != null && req.delayDays <= 0)
        return "delayDays must be > 0 (only forward shifts supported)"
    if (!req.delayToDate.isNullOrBlank() && parseIsoDateLoose(req.delayToDate) == null)
        return "delayToDate must be ISO yyyy-MM-dd"
    for (s in req.selectors) {
        if (s.woGroupIds.isEmpty())
            return "Each selector must declare at least one woGroupId"
        val start = parseIsoDateLoose(s.bucketStart)
            ?: return "Selector bucketStart must be ISO yyyy-MM-dd, got '${s.bucketStart}'"
        val end = parseIsoDateLoose(s.bucketEnd)
            ?: return "Selector bucketEnd must be ISO yyyy-MM-dd, got '${s.bucketEnd}'"
        if (end < start) return "Selector bucketEnd must be ≥ bucketStart"
    }
    return null
}

/**
 * For each selector, match every WO whose `wo_group_id` ∈ `selector.woGroupIds`
 * AND whose start_time falls in `[bucketStart, bucketEnd]`. The window check
 * is defensive — the UI should already have filtered to in-window WOs before
 * picking the gids — but it guards against stale baseline shifts.
 *
 * Returns (lot → forward-shift days) and the set of matched gids. Lots use
 * object identity from the input list so the caller can mutate them in place.
 */
@Suppress("UNCHECKED_CAST")
private fun computeShifts(
    workOrders: List<Map<String, Any?>>,
    selectors: List<WoScheduleSelector>,
    delayDays: Int?,
    delayToDate: LocalDate?,
): Pair<Map<Map<String, Any?>, Long>, Set<String>> {
    val lotShift = HashMap<Map<String, Any?>, Long>()
    val matchedGids = HashSet<String>()

    for (sel in selectors) {
        val bucketStart = parseIsoDateLoose(sel.bucketStart) ?: continue
        val bucketEnd = parseIsoDateLoose(sel.bucketEnd) ?: continue
        val selGids = sel.woGroupIds.toHashSet()
        if (selGids.isEmpty()) continue

        val shift: Long = when {
            delayDays != null -> delayDays.toLong()
            delayToDate != null -> ChronoUnit.DAYS.between(bucketStart, delayToDate)
            else -> 0L
        }
        if (shift == 0L) continue

        for (wo in workOrders) {
            val gid = wo["wo_group_id"] as? String ?: continue
            if (gid !in selGids) continue
            val startStr = wo["start_time"] as? String ?: continue
            val startDt = parseIsoDateLoose(startStr) ?: continue
            if (startDt < bucketStart || startDt > bucketEnd) continue

            // Forward-only: take max shift across selectors.
            val prior = lotShift[wo] ?: 0L
            if (shift > prior) lotShift[wo] = shift
            matchedGids.add(gid)
        }
    }
    return lotShift to matchedGids
}

/**
 * Mutate the work-order list and pegging trees by the per-lot shift.
 *
 * Lots: returns a new list of MutableMaps with shifted start_time/end_time.
 * Trees: walks each tree, shifting start_time/end_time on any work_order node
 *   whose (wo_group_id, method_slot_index) matches a shifted lot, by the SAME
 *   shift as that lot. (Resequence Step 3 will rederive node times from lots
 *   anyway, but we mutate the tree here too so leaf-time pre-shift sees a
 *   coherent view if a moved supply leaf later constrains the node.)
 */
@Suppress("UNCHECKED_CAST")
private fun applyShifts(
    workOrders: List<Map<String, Any?>>,
    peggingTrees: List<Map<String, Any?>>,
    lotShift: Map<Map<String, Any?>, Long>,
): Pair<List<MutableMap<String, Any?>>, List<Map<String, Any?>>> {
    // Build a key from lot → shift (gid + method_slot_index + start_time + product_id + location_id)
    // for tree-side matching, since tree nodes don't share object identity with the lot list.
    data class LotKey(val gid: String, val altIdx: Int?, val product: String?, val location: String?)
    val shiftByLotKey = mutableMapOf<LotKey, Long>()

    val mutated = workOrders.map { wo ->
        val shift = lotShift[wo]
        val mut = wo.toMutableMap()
        if (shift != null && shift != 0L) {
            parseIsoDateLoose(wo["start_time"] as? String)?.let {
                mut["start_time"] = it.plusDays(shift).format(ISO_DATE)
            }
            parseIsoDateLoose(wo["end_time"] as? String)?.let {
                mut["end_time"] = it.plusDays(shift).format(ISO_DATE)
            }
            val gid = wo["wo_group_id"] as? String
            if (gid != null) {
                val key = LotKey(
                    gid,
                    wo["method_slot_index"] as? Int,
                    wo["product_id"] as? String,
                    wo["location_id"] as? String,
                )
                // Tree nodes show min(start)/max(end) of all matching lots; if multiple
                // lots map to the same node and they get different shifts, the tree-side
                // mutation here is approximate. Step 3 of resequenceFromPegging is
                // authoritative — it rederives node start/end from lots regardless.
                val prior = shiftByLotKey[key] ?: 0L
                if (shift > prior) shiftByLotKey[key] = shift
            }
        }
        mut
    }

    fun walkAndShift(node: Map<String, Any?>, depth: Int): Map<String, Any?> {
        if (depth > 60) return node
        val updated = node.toMutableMap()
        if (node["type"] == "work_order" && node["failed"] != true) {
            val gid = node["wo_group_id"] as? String
            if (gid != null) {
                val key = LotKey(
                    gid,
                    node["method_slot_index"] as? Int,
                    node["product_id"] as? String,
                    node["location_id"] as? String,
                )
                shiftByLotKey[key]?.takeIf { it != 0L }?.let { shift ->
                    parseIsoDateLoose(node["start_time"] as? String)?.let {
                        updated["start_time"] = it.plusDays(shift).format(ISO_DATE)
                    }
                    parseIsoDateLoose(node["end_time"] as? String)?.let {
                        updated["end_time"] = it.plusDays(shift).format(ISO_DATE)
                    }
                }
            }
        }
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        if (children.isNotEmpty()) {
            updated["children"] = children.map { walkAndShift(it, depth + 1) }
        }
        return updated
    }

    val mutatedTrees = peggingTrees.map { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        entry.toMutableMap().apply { put("tree", walkAndShift(tree, 0)) }
    }

    return mutated to mutatedTrees
}

/** Pull baseline commit_time from each demand-root tree. */
@Suppress("UNCHECKED_CAST")
private fun extractCommitTimes(peggingTrees: List<Map<String, Any?>>): Map<String, String?> {
    val out = mutableMapOf<String, String?>()
    for (entry in peggingTrees) {
        val demandId = entry["demand_id"]?.toString()?.trim() ?: continue
        if (demandId.isBlank()) continue
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        val commit = tree["commit_time"] as? String
        // Multiple pegging entries may share a demand_id (consolidation): keep the latest.
        val prior = out[demandId]
        val priorD = parseIsoDateLoose(prior)
        val curD = parseIsoDateLoose(commit)
        out[demandId] = if (priorD == null || (curD != null && curD > priorD)) commit else prior
    }
    return out
}

private suspend fun runWoScheduleImpactBackground(jobId: String, req: WoScheduleImpactRequest) {
    try {
        val validationError = validateRequest(req)
        if (validationError != null) {
            woImpactJobs[jobId]?.apply {
                set("status", "failed")
                set("error", validationError)
                set("completedAt", Instant.now())
            }
            return
        }

        val baseline = loadBaseline(req)
        if (baseline == null) {
            val note = if (req.planRunId != null)
                "Plan run ${req.planRunId} not found or not in success state."
            else
                "No successful plan run available. Run a plan first."
            woImpactJobs[jobId]?.apply {
                set("status", "failed")
                set("error", note)
                set("completedAt", Instant.now())
            }
            return
        }

        val delayToDate = req.delayToDate?.let { parseIsoDateLoose(it) }
        val (lotShift, matchedGids) = computeShifts(
            baseline.workOrders, req.selectors, req.delayDays, delayToDate,
        )

        if (lotShift.isEmpty()) {
            woImpactJobs[jobId]?.apply {
                set("status", "failed")
                set("error", "No work orders matched the selectors / shift was zero")
                set("completedAt", Instant.now())
            }
            return
        }

        log.info(
            "wo-schedule-impact: jobId={} caseId={} baseline={} matched_lots={} matched_gids={} delayDays={} delayToDate={}",
            jobId, baseline.caseId, baseline.planRunId, lotShift.size, matchedGids.size,
            req.delayDays, req.delayToDate,
        )

        val (mutatedLots, mutatedTrees) = applyShifts(baseline.workOrders, baseline.peggingTrees, lotShift)
        val sequenced = resequenceFromPegging(mutatedLots, mutatedTrees)

        // ── Diff ─────────────────────────────────────────────────────────────
        val baselineCommits = extractCommitTimes(baseline.peggingTrees)
        val contingentCommits = extractCommitTimes(sequenced.peggingTrees)
        val allDemandIds = (baselineCommits.keys + contingentCommits.keys).toSet()

        val impactedRows = mutableListOf<Pair<String, Triple<String?, String?, Int>>>()
        // (demandId, baselineCommit, contingentCommit, daysDelta)
        for (did in allDemandIds) {
            val b = baselineCommits[did]
            val c = contingentCommits[did]
            val bD = parseIsoDateLoose(b)
            val cD = parseIsoDateLoose(c)
            val delta = if (bD != null && cD != null) ChronoUnit.DAYS.between(bD, cD).toInt() else 0
            if (delta > 0) impactedRows.add(did to Triple(b, c, delta))
        }

        val contingentResultMap: Map<String, Any> = baseline.resultMap.toMutableMap().apply {
            put("work_orders", sequenced.workOrders)
            put("planning_pegging", sequenced.peggingTrees)
        }

        // ── Build impacts list (mirrors material-impact's enrichment).
        // Built BEFORE persistence so the impacts can be cached in metadata for
        // fast per-event run-history rendering without re-deriving from the full
        // baseline+contingent diff on every list call.
        val impacts: List<WoImpactedDemand> = if (impactedRows.isNotEmpty()) {
            val demandIds = impactedRows.map { it.first }
            val metas: Map<String, WoMeta> = transaction {
                Demands.selectAll()
                    .where { (Demands.caseId eq baseline.caseId) and (Demands.demandId inList demandIds) }
                    .associate { d ->
                        d[Demands.demandId] to WoMeta(
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
            impactedRows.mapNotNull { (did, t) ->
                val (b, c, delta) = t
                val m = metas[did] ?: return@mapNotNull null
                val bD = parseIsoDateLoose(b)
                val cD = parseIsoDateLoose(c)
                val dueD = parseIsoDateLoose(m.requestDueTime)
                val status = when {
                    delta <= 0 -> "no_change"
                    bD != null && cD != null && dueD != null && bD <= dueD && cD > dueD -> "newly_late_vs_due"
                    else -> "delivery_delayed"
                }
                WoImpactedDemand(
                    demandId = m.demandId,
                    productId = m.productId,
                    locationId = m.locationId,
                    customerId = m.customerId,
                    description = m.description,
                    priority = m.priority,
                    requestDueTime = m.requestDueTime,
                    requestedQty = m.requestedQty,
                    baselineCommitTime = b,
                    contingentCommitTime = c,
                    daysDelta = delta,
                    status = status,
                )
            }.sortedByDescending { it.daysDelta }
        } else emptyList()

        // ── Persist contingent plan run (if requested) ───────────────────────
        val contingentPlanRunId: Int? = if (req.persist) {
            val metadataJson = buildJsonObject {
                put("type", "wo_schedule_contingent")
                if (req.delayDays != null) put("delayDays", req.delayDays)
                if (req.delayToDate != null) put("delayToDate", req.delayToDate)
                put("baselinePlanRunId", baseline.planRunId)
                put("matchedWoCount", lotShift.size)
                put("impactedDemandCount", impactedRows.size)
                req.note?.let { put("note", it) }
                req.woScheduleEventId?.let { put("woScheduleEventId", it) }
                put("selectors", buildJsonArray {
                    for (sel in req.selectors) {
                        add(buildJsonObject {
                            put("bucketStart", sel.bucketStart)
                            put("bucketEnd", sel.bucketEnd)
                            put("woGroupIds", buildJsonArray { sel.woGroupIds.forEach { add(it) } })
                        })
                    }
                })
                // Cache the per-demand impacts so the per-event run-history endpoint
                // can return them without re-deriving from the full plan result.
                put("impacts", Json.encodeToJsonElement(impacts))
            }.toString()
            val contingentResultJson = runCatching { resultToJson(contingentResultMap).toString() }.getOrNull()
            transaction {
                val planRunId = PlanRuns.insert {
                    it[PlanRuns.caseId] = baseline.caseId
                    it[PlanRuns.jobId] = jobId
                    it[PlanRuns.status] = "contingent"
                    it[PlanRuns.config] = baseline.configJson
                    it[PlanRuns.result] = contingentResultJson
                    it[PlanRuns.metadata] = metadataJson
                    if (req.woScheduleEventId != null) it[PlanRuns.woScheduleEventId] = req.woScheduleEventId
                }[PlanRuns.id]
                emitPlanRunEvent(baseline.caseId, planRunId, "created", buildJsonObject {
                    put("source", "wo_schedule_impact")
                    put("baseline_plan_run_id", JsonPrimitive(baseline.planRunId))
                    put("matched_wo_count", JsonPrimitive(lotShift.size))
                    if (req.woScheduleEventId != null) put("wo_schedule_event_id", JsonPrimitive(req.woScheduleEventId))
                })
                planRunId
            }
        } else null

        val resp = WoScheduleImpactResponse(
            caseId = baseline.caseId,
            planRunId = baseline.planRunId,
            contingentPlanRunId = contingentPlanRunId,
            matchedWoCount = lotShift.size,
            delayDays = req.delayDays,
            delayToDate = req.delayToDate,
            impactedDemandCount = impactedRows.size,
            impacts = impacts,
            note = req.note,
        )
        woImpactJobs[jobId]?.apply {
            set("status", "completed")
            set("result", Json.encodeToJsonElement(resp))
            set("completedAt", Instant.now())
        }
    } catch (e: Exception) {
        log.error("wo-schedule-impact job $jobId failed: ${e.message}", e)
        woImpactJobs[jobId]?.apply {
            set("status", "failed")
            set("error", e.message ?: "Unknown error")
            set("completedAt", Instant.now())
        }
    }
}

// ── Routes ────────────────────────────────────────────────────────────────────

/**
 * POST /wo-schedule-impact   → 202 { jobId } async
 * GET  /wo-schedule-impact/status/{jobId}
 * CRUD /cases/{caseId}/wo-schedule-events
 */
fun Routing.workOrderImpactRoutes() {
    route("/wo-schedule-impact") {
        post {
            val rawBody = call.receiveText()
            log.info("wo-schedule-impact raw body: {}", rawBody)
            val req = try {
                Json.decodeFromString<WoScheduleImpactRequest>(rawBody)
            } catch (e: Exception) {
                log.error("wo-schedule-impact body parse failed: {}", e.message)
                throw e
            }

            val jobId = UUID.randomUUID().toString()
            woImpactJobs[jobId] = mutableMapOf(
                "status" to "running",
                "result" to null,
                "error" to null,
                "progress" to mapOf("current" to 0, "total" to 0),
            )
            woImpactScope.launch { runWoScheduleImpactBackground(jobId, req) }

            call.response.headers.append("Location", "/wo-schedule-impact/status/$jobId")
            call.respond(HttpStatusCode.Accepted, buildJsonObject {
                put("jobId", jobId)
                put("status", "running")
                put("message", "Poll GET /wo-schedule-impact/status/$jobId for result.")
            })
        }

        get("/status/{jobId}") {
            val jobId = call.parameters["jobId"]
                ?: throw IllegalArgumentException("jobId required")
            val job = woImpactJobs[jobId]
                ?: throw NoSuchElementException("WO schedule impact job '$jobId' not found")
            val status = job["status"]?.toString() ?: "unknown"
            call.respond(buildJsonObject {
                put("status", status)
                @Suppress("UNCHECKED_CAST")
                (job["progress"] as? Map<String, Any?>)?.let { p ->
                    put("progress", buildJsonObject {
                        put("current", JsonPrimitive((p["current"] as? Number)?.toLong() ?: 0L))
                        put("total", JsonPrimitive((p["total"] as? Number)?.toLong() ?: 0L))
                    })
                }
                val result = job["result"]
                if (result != null) put("result", resultToJson(result))
                val error = job["error"]
                if (error != null) put("error", error.toString())
            })
            if (status == "completed" || status == "failed") {
                woImpactJobs.remove(jobId)
            }
            val cutoff = Instant.now().minusSeconds(WO_JOB_TTL_SECONDS)
            woImpactJobs.entries.removeIf { (_, j) ->
                val s = j["status"]?.toString()
                val completedAt = j["completedAt"] as? Instant
                (s == "completed" || s == "failed") &&
                    completedAt != null && completedAt.isBefore(cutoff)
            }
        }
    }
}

// ── WO schedule event CRUD ────────────────────────────────────────────────────

@Serializable
data class WoScheduleEventRequest(
    val selectors: List<WoScheduleSelector>,
    val delayDays: Int? = null,
    val delayToDate: String? = null,
    val note: String? = null,
)

@Serializable
data class WoScheduleEventResponse(
    val id: Int,
    val caseId: Int,
    val selectors: List<WoScheduleSelector>,
    val delayDays: Int?,
    val delayToDate: String?,
    val note: String?,
    val createdAt: String,
)

/** A historical contingent run linked to a saved WoScheduleEvent. */
@Serializable
data class WoScheduleRunResponse(
    val planRunId: Int,
    val baselinePlanRunId: Int?,
    val createdAt: String,
    val matchedWoCount: Int,
    val impactedDemandCount: Int,
    val delayDays: Int?,
    val delayToDate: String?,
    val note: String?,
    /** Status of the contingent plan run: "contingent" until promoted, then "success". */
    val status: String,
    /** Cached impact details (decoded from PlanRuns.metadata.impacts when present). */
    val impacts: List<WoImpactedDemand>,
)

private fun selectorsToJson(selectors: List<WoScheduleSelector>): String =
    Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(WoScheduleSelector.serializer()), selectors)

private fun selectorsFromJson(s: String?): List<WoScheduleSelector> {
    if (s.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WoScheduleSelector.serializer()), s)
    }.getOrDefault(emptyList())
}

private fun rowToWoScheduleEvent(row: ResultRow) = WoScheduleEventResponse(
    id          = row[WoScheduleEvents.id],
    caseId      = row[WoScheduleEvents.caseId],
    selectors   = selectorsFromJson(row[WoScheduleEvents.selectorsJson]),
    delayDays   = row[WoScheduleEvents.delayDays],
    delayToDate = row[WoScheduleEvents.delayToDate],
    note        = row[WoScheduleEvents.note],
    createdAt   = row[WoScheduleEvents.createdAt].toString(),
)

fun Routing.woScheduleEventRoutes() {
    route("/cases/{case_id}/wo-schedule-events") {
        get {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            transaction {
                Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
                    ?: throw NoSuchElementException("Case $caseId not found")
            }
            val rows = transaction {
                WoScheduleEvents.selectAll()
                    .where { WoScheduleEvents.caseId eq caseId }
                    .orderBy(WoScheduleEvents.createdAt, SortOrder.DESC)
                    .map { rowToWoScheduleEvent(it) }
            }
            call.respond(rows)
        }

        post {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            transaction {
                Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
                    ?: throw NoSuchElementException("Case $caseId not found")
            }
            val rawBody = call.receiveText()
            val req = try {
                Json.decodeFromString<WoScheduleEventRequest>(rawBody)
            } catch (e: Exception) {
                log.error("wo-schedule-events POST body parse failed: {} body={}", e.message, rawBody)
                throw IllegalArgumentException("Invalid wo-schedule-event body: ${e.message}")
            }
            if (req.selectors.isEmpty())
                throw IllegalArgumentException("selectors must be non-empty")
            if (req.delayDays == null && req.delayToDate.isNullOrBlank())
                throw IllegalArgumentException("delayDays or delayToDate must be set")
            if (req.delayDays != null && !req.delayToDate.isNullOrBlank())
                throw IllegalArgumentException("delayDays and delayToDate are mutually exclusive")
            val created = transaction {
                val newId = WoScheduleEvents.insert {
                    it[WoScheduleEvents.caseId]      = caseId
                    it[WoScheduleEvents.selectorsJson] = selectorsToJson(req.selectors)
                    it[WoScheduleEvents.delayDays]   = req.delayDays
                    it[WoScheduleEvents.delayToDate] = req.delayToDate?.takeIf { d -> d.isNotBlank() }
                    it[WoScheduleEvents.note]        = req.note?.trim()
                }[WoScheduleEvents.id]
                WoScheduleEvents.selectAll()
                    .where { WoScheduleEvents.id eq newId }
                    .map { rowToWoScheduleEvent(it) }
                    .first()
            }
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{event_id}") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val eventId = call.parameters["event_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event_id")
            val rawBody = call.receiveText()
            val req = try {
                Json.decodeFromString<WoScheduleEventRequest>(rawBody)
            } catch (e: Exception) {
                log.error("wo-schedule-events PUT body parse failed: {} body={}", e.message, rawBody)
                throw IllegalArgumentException("Invalid wo-schedule-event body: ${e.message}")
            }
            transaction {
                val updated = WoScheduleEvents.update(
                    { (WoScheduleEvents.id eq eventId) and (WoScheduleEvents.caseId eq caseId) }
                ) {
                    it[selectorsJson] = selectorsToJson(req.selectors)
                    it[delayDays]     = req.delayDays
                    it[delayToDate]   = req.delayToDate?.takeIf { d -> d.isNotBlank() }
                    it[note]          = req.note?.trim()
                }
                if (updated == 0) throw NoSuchElementException("Event $eventId not found for case $caseId")
            }
            val updated = transaction {
                WoScheduleEvents.selectAll()
                    .where { (WoScheduleEvents.id eq eventId) and (WoScheduleEvents.caseId eq caseId) }
                    .map { rowToWoScheduleEvent(it) }
                    .first()
            }
            call.respond(updated)
        }

        delete("/{event_id}") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val eventId = call.parameters["event_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event_id")
            transaction {
                val deleted = WoScheduleEvents.deleteWhere {
                    (WoScheduleEvents.id eq eventId) and (WoScheduleEvents.caseId eq caseId)
                }
                if (deleted == 0) throw NoSuchElementException("Event $eventId not found for case $caseId")
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // ── GET .../runs — historical contingent runs derived from this event ──
        get("/{event_id}/runs") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val eventId = call.parameters["event_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event_id")
            transaction {
                Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
                    ?: throw NoSuchElementException("Case $caseId not found")
            }
            val runs: List<WoScheduleRunResponse> = transaction {
                PlanRuns.selectAll()
                    .where { (PlanRuns.caseId eq caseId) and (PlanRuns.woScheduleEventId eq eventId) }
                    .orderBy(PlanRuns.id, SortOrder.DESC)
                    .map { row ->
                        val metaJson = row[PlanRuns.metadata]?.let {
                            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                        }
                        val impacts: List<WoImpactedDemand> = metaJson?.get("impacts")?.let { el ->
                            runCatching {
                                Json.decodeFromJsonElement(
                                    kotlinx.serialization.builtins.ListSerializer(WoImpactedDemand.serializer()),
                                    el,
                                )
                            }.getOrDefault(emptyList())
                        } ?: emptyList()
                        WoScheduleRunResponse(
                            planRunId = row[PlanRuns.id],
                            baselinePlanRunId = metaJson?.get("baselinePlanRunId")?.jsonPrimitive?.intOrNull,
                            createdAt = row[PlanRuns.createdAt].toString(),
                            matchedWoCount = metaJson?.get("matchedWoCount")?.jsonPrimitive?.intOrNull ?: 0,
                            impactedDemandCount = metaJson?.get("impactedDemandCount")?.jsonPrimitive?.intOrNull ?: impacts.size,
                            delayDays = metaJson?.get("delayDays")?.jsonPrimitive?.intOrNull,
                            delayToDate = metaJson?.get("delayToDate")?.jsonPrimitive?.contentOrNull,
                            note = metaJson?.get("note")?.jsonPrimitive?.contentOrNull,
                            status = row[PlanRuns.status],
                            impacts = impacts,
                        )
                    }
            }
            call.respond(runs)
        }
    }
}
