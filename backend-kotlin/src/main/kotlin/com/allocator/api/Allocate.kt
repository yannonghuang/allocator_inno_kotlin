package com.allocator.api

import com.allocator.*
import com.allocator.services.CaseLoader
import com.allocator.services.getMethods
import com.allocator.services.resolveMethodSelection
import com.allocator.services.resolveVariantSelection
import com.allocator.services.roundQty
import com.allocator.services.runAllocation
import com.allocator.services.runPlanning
import com.opencsv.CSVReaderHeaderAware
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.FileReader
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.allocator.AllocateRoute")
private val engineScope = CoroutineScope(Dispatchers.IO)

// ── In-memory plan job state ───────────────────────────────────────────────────
private val planJobs = ConcurrentHashMap<String, MutableMap<String, Any?>>()
internal val casePlanResults = ConcurrentHashMap<Int, Map<String, Any>>()
// jobId → plan_run.id for associating async jobs with persisted runs
private val planJobRunIds = ConcurrentHashMap<String, Int>()

/**
 * Allocation run routes — port of api/allocate.py.
 * Background allocation runs via Kotlin coroutines; status persisted in DB.
 */
fun Routing.allocateRoutes() {

    // ── POST /cases/{case_id}/allocate — start async run ──────────────────────
    post("/cases/{case_id}/allocate") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")

        val runId = transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
            val demands = Demands.selectAll().where { Demands.caseId eq caseId }.count()
            val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.count()
            if (demands == 0L) throw IllegalArgumentException("No demand data")
            if (supplies == 0L) throw IllegalArgumentException("No supply data")

            AllocationRuns.insert {
                it[AllocationRuns.caseId] = caseId
                it[status] = "running"
                it[config] = "{}"
            }[AllocationRuns.id]
        }

        // Fire-and-forget in coroutine
        engineScope.launch { runAllocationBackground(caseId, runId) }

        val response = transaction {
            val row = AllocationRuns.selectAll().where { AllocationRuns.id eq runId }.single()
            AllocationRunResponse(
                id = row[AllocationRuns.id],
                caseId = row[AllocationRuns.caseId],
                createdAt = formatTs(row[AllocationRuns.createdAt]),
                status = row[AllocationRuns.status],
                config = row[AllocationRuns.config]?.let { Json.parseToJsonElement(it) },
            )
        }
        call.respond(HttpStatusCode.Accepted, response)
    }

    // ── GET /cases/{case_id}/runs — list runs ──────────────────────────────────
    get("/cases/{case_id}/runs") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val runs = transaction {
            AllocationRuns.selectAll().where { AllocationRuns.caseId eq caseId }
                .orderBy(AllocationRuns.createdAt, SortOrder.DESC)
                .map {
                    AllocationRunResponse(
                        id = it[AllocationRuns.id],
                        caseId = it[AllocationRuns.caseId],
                        createdAt = formatTs(it[AllocationRuns.createdAt]),
                        status = it[AllocationRuns.status],
                        config = it[AllocationRuns.config]?.let { c -> Json.parseToJsonElement(c) },
                    )
                }
        }
        call.respond(runs)
    }

    // ── GET /cases/{case_id}/runs/{run_id}/status — lightweight poll ───────────
    get("/cases/{case_id}/runs/{run_id}/status") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")
        val json = transaction {
            val row = AllocationRuns.selectAll().where { (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId) }
                .singleOrNull() ?: throw NoSuchElementException("Run not found")
            buildJsonObject {
                put("id", row[AllocationRuns.id])
                put("status", row[AllocationRuns.status])
                put("config", row[AllocationRuns.config]?.let { Json.parseToJsonElement(it) } ?: JsonNull)
            }
        }
        call.respond(json)
    }

    // ── GET /cases/{case_id}/runs/{run_id} — full run + actions ───────────────
    get("/cases/{case_id}/runs/{run_id}") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")
        val json = transaction {
            val r = AllocationRuns.selectAll().where { (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId) }
                .singleOrNull() ?: throw NoSuchElementException("Run not found")
            val acts = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                .orderBy(AllocationActions.id, SortOrder.ASC)
                .map { a ->
                    AllocationActionResponse(
                        id = a[AllocationActions.id],
                        runId = a[AllocationActions.runId],
                        variantKey = a[AllocationActions.variantKey],
                        reqComponentIds = Json.decodeFromString(a[AllocationActions.reqComponentIds]),
                        reqRates = a[AllocationActions.reqRates]?.let { Json.decodeFromString(it) },
                        qty = a[AllocationActions.qty],
                        demandId = a[AllocationActions.demandId],
                        targetProductId = a[AllocationActions.targetProductId],
                        targetLocationId = a[AllocationActions.targetLocationId],
                        outputPeriod = a[AllocationActions.outputPeriod],
                        edgeType = a[AllocationActions.edgeType],
                    )
                }
            buildJsonObject {
                put("id", r[AllocationRuns.id])
                put("case_id", r[AllocationRuns.caseId])
                put("created_at", formatTs(r[AllocationRuns.createdAt]))
                put("status", r[AllocationRuns.status])
                put("config", r[AllocationRuns.config]?.let { Json.parseToJsonElement(it) } ?: JsonNull)
                put("actions", Json.encodeToJsonElement(acts))
            }
        }
        call.respond(json)
    }

    // ── GET /cases/{case_id}/runs/{run_id}/feasible-demands ──────────────────
    get("/cases/{case_id}/runs/{run_id}/feasible-demands") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")
        transaction {
            AllocationRuns.selectAll().where { (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId) }
                .singleOrNull() ?: throw NoSuchElementException("Run not found")
        }
        val actions = transaction {
            AllocationActions.selectAll().where { AllocationActions.runId eq runId }.toList()
        }
        val feasible = feasibleDemandsFromActions(caseId, actions)
        call.respond(buildJsonObject {
            put("feasible_demands", buildJsonArray {
                feasible.forEach { fd -> add(anyToJson(fd)) }
            })
        })
    }

    // ── GET /cases/{case_id}/plan/products-with-real-bom ─────────────────────
    get("/cases/{case_id}/plan/products-with-real-bom") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val bomCsv = resolveBomCsv()
        val pairs = mutableListOf<List<String>>()
        if (bomCsv.toFile().exists()) {
            try {
                CSVReaderHeaderAware(FileReader(bomCsv.toFile())).use { reader ->
                    var row: Map<String, String>?
                    val firstRow = reader.readMap()
                    val hasVirtual = firstRow != null && "VIRTUAL" in firstRow
                    if (firstRow != null) {
                        val parent = (firstRow["PARENT_ID"] ?: "").trim()
                        val child = (firstRow["CHILD_ID"] ?: "").trim()
                        val virtual = (firstRow["VIRTUAL"] ?: "").trim().uppercase()
                        if (parent.isNotBlank() && child.isNotBlank() && (!hasVirtual || virtual != "Y")) {
                            pairs.add(listOf(parent, child))
                        }
                    }
                    while (reader.readMap().also { row = it } != null) {
                        val r = row!!
                        val parent = (r["PARENT_ID"] ?: "").trim()
                        val child = (r["CHILD_ID"] ?: "").trim()
                        if (parent.isBlank() || child.isBlank()) continue
                        if (hasVirtual && (r["VIRTUAL"] ?: "").trim().uppercase() == "Y") continue
                        pairs.add(listOf(parent, child))
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to read bom.csv: ${e.message}")
            }
        }
        call.respond(mapOf("pairs" to pairs))
    }

    // ── GET /cases/{case_id}/plan/moves-with-transit ──────────────────────────
    get("/cases/{case_id}/plan/moves-with-transit") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val moves = transaction {
            MethodMoves.selectAll().where { (MethodMoves.caseId eq caseId) and (MethodMoves.transitTime neq null) }
                .filter { it[MethodMoves.transitTime] != null && it[MethodMoves.transitTime]!! > 0 }
                .map { listOf(it[MethodMoves.productId].trim(), it[MethodMoves.fromLocationId].trim(), it[MethodMoves.toLocationId].trim()) }
                .distinct()
        }
        call.respond(mapOf("moves" to moves))
    }

    // ── POST /cases/{case_id}/plan — demand-to-supply planning ────────────────
    post("/cases/{case_id}/plan") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val data = transaction { CaseLoader.load(caseId) }
        if (data["demand"].isNullOrEmpty()) throw IllegalArgumentException("No demand data")
        if (data["supply"].isNullOrEmpty()) throw IllegalArgumentException("No supply data")

        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) JsonObject(emptyMap())
                      else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val configJson = payload["config"]
        val config: Map<String, Any?>? = if (configJson != null && configJson !is JsonNull)
            runCatching { @Suppress("UNCHECKED_CAST") (jsonElementToNative(configJson) as? Map<String, Any?>) }.getOrNull()
        else null
        val useAsync = payload["async"]?.jsonPrimitive?.booleanOrNull == true

        if (useAsync) {
            val jobId = UUID.randomUUID().toString()
            val total = data["demand"]?.size ?: 0
            planJobs[jobId] = mutableMapOf(
                "case_id" to caseId,
                "status" to "running",
                "progress" to mapOf("current" to 0, "total" to total),
                "result" to null,
                "error" to null,
            )
            engineScope.launch { runPlanBackground(jobId, caseId, data, config) }
            call.response.headers.append("Location", "/cases/$caseId/plan/status/$jobId")
            call.respond(HttpStatusCode.Accepted, buildJsonObject {
                put("job_id", jobId)
                put("status", "running")
                put("message", "Poll GET /cases/$caseId/plan/status/$jobId for progress and result.")
            })
            return@post
        }

        val result = runPlanning(data, config = config)
        val enriched = enrichPlanResultWithData(caseId, result, data)
        casePlanResults[caseId] = enriched
        call.respond(anyToJson(enriched))
    }

    // ── GET /cases/{case_id}/plan/status/{job_id} ─────────────────────────────
    get("/cases/{case_id}/plan/status/{job_id}") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val jobId = call.parameters["job_id"] ?: throw IllegalArgumentException("Invalid job_id")
        val job = planJobs[jobId] ?: throw NoSuchElementException("Plan job not found")
        if (job["case_id"] != caseId) throw NoSuchElementException("Plan job not found for this case")
        call.respond(buildJsonObject {
            put("status", job["status"]?.toString() ?: "unknown")
            put("progress", anyToJson(job["progress"]))
            if (job["result"] != null) put("result", anyToJson(job["result"]))
            if (job["error"] != null) put("error", job["error"]?.toString() ?: "")
            planJobRunIds[jobId]?.let { put("plan_run_id", it) }
        })
    }

    // ── GET /cases/{case_id}/plan/work-order-pegging ──────────────────────────
    get("/cases/{case_id}/plan/work-order-pegging") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val demandId = call.request.queryParameters["demand_id"]?.trim() ?: ""
        val productId = call.request.queryParameters["product_id"]?.trim() ?: ""
        val locationId = call.request.queryParameters["location_id"]?.trim() ?: ""
        val method = call.request.queryParameters["method"]?.trim() ?: ""
        val runIdParam = call.request.queryParameters["run_id"]?.toIntOrNull()
        if (productId.isBlank() || locationId.isBlank() || method.isBlank()) {
            throw IllegalArgumentException("product_id, location_id, method required")
        }
        val result = if (runIdParam != null) {
            loadPlanResultFromDb(caseId, runIdParam)
                ?: throw NoSuchElementException("Plan run $runIdParam not found for case $caseId.")
        } else {
            casePlanResults[caseId]
                ?: loadPlanResultFromDb(caseId)?.also { casePlanResults[caseId] = it }
                ?: throw NoSuchElementException("No plan result for this case. Run plan first.")
        }

        @Suppress("UNCHECKED_CAST")
        val planningPegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()

        // A demand can have multiple pegging trees (one per component group when consolidation is on).
        // It can also live inside a cross-demand consolidated entry (demand_id=null, but
        // consolidated_demand_ids includes this demand). Search both kinds of trees.
        val woNode: Map<String, Any?>? = if (demandId.isNotBlank()) {
            val matchingEntries = planningPegging.filter { e ->
                val entryDemand = (e["demand_id"]?.toString() ?: "").trim()
                if (entryDemand == demandId) return@filter true
                @Suppress("UNCHECKED_CAST")
                val members = e["consolidated_demand_ids"] as? List<String> ?: return@filter false
                entryDemand.isBlank() && members.contains(demandId)
            }
            log.warn("[WO pegging] demand={} productId={} locationId={} method={} planningPegging.size={} matchingEntries.size={}",
                demandId, productId, locationId, method, planningPegging.size, matchingEntries.size)
            if (matchingEntries.isEmpty()) {
                val allIds = planningPegging.map { (it["demand_id"]?.toString() ?: "<null>").trim() }.distinct().take(20)
                log.warn("[WO pegging] No pegging tree for demand={}. All demand_ids in pegging: {}", demandId, allIds)
            } else {
                @Suppress("UNCHECKED_CAST")
                val firstTree = matchingEntries[0]["tree"] as? Map<String, Any?>
                val childTypes = (firstTree?.get("children") as? List<*>)
                    ?.mapNotNull { (it as? Map<*, *>)?.get("type")?.toString() } ?: emptyList()
                log.warn("[WO pegging] First tree root type={} children types={}", firstTree?.get("type"), childTypes)
            }
            val found = matchingEntries
                .firstNotNullOfOrNull { entry -> entry["tree"]?.let { findWoNode(it, productId, locationId, method) } }
            if (found == null && matchingEntries.isNotEmpty()) {
                val allWoKeys = matchingEntries.flatMap { entry -> collectAllWoKeys(entry["tree"]) }
                log.warn("[WO pegging] Tree found for demand={} but WO {}@{}/{} not in it. All WO keys in tree: {}", demandId, productId, locationId, method, allWoKeys)
            }
            found ?: throw NoSuchElementException("Work order ($productId @ $locationId / $method) not found in any pegging tree for demand $demandId")
        } else {
            // Consolidated WO: search all trees where demand_id is null/blank
            planningPegging
                .filter { (it["demand_id"]?.toString() ?: "").isBlank() }
                .firstNotNullOfOrNull { entry -> entry["tree"]?.let { findWoNode(it, productId, locationId, method) } }
                ?: throw NoSuchElementException("Work order ($productId @ $locationId / $method) not found in any consolidated pegging tree")
        }

        // Align quantity with work_orders list sum
        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as? List<Map<String, Any?>> ?: emptyList()
        val woQtySum = workOrders.filter { wo ->
            (wo["demand_id"]?.toString() ?: "").trim() == demandId &&
            (wo["product_id"]?.toString() ?: "").trim() == productId &&
            (wo["location_id"]?.toString() ?: "").trim() == locationId &&
            (wo["method"]?.toString() ?: "").trim() == method
        }.sumOf { (it["quantity"] as? Number ?: 0).toDouble() }

        val treeJson = anyToJson(woNode)
        val finalTree = if (woQtySum > 0 && treeJson is JsonObject) {
            buildJsonObject {
                treeJson.forEach { (k, v) -> if (k == "quantity") put("quantity", roundQty(woQtySum)) else put(k, v) }
            }
        } else treeJson
        call.respond(buildJsonObject { put("tree", finalTree) })
    }

    // ── GET /cases/{case_id}/plan-runs — list persisted plan runs ─────────────
    get("/cases/{case_id}/plan-runs") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val designatedId = transaction {
            val caseRow = Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
            caseRow[Cases.designatedActivePlanRunId]
        }
        val runs = transaction {
            val rows = PlanRuns.selectAll().where { (PlanRuns.caseId eq caseId) and (PlanRuns.status neq "ready") and (PlanRuns.status neq "running") }
                .orderBy(PlanRuns.createdAt, SortOrder.DESC)
                .toList()
            val successIds = rows.filter { it[PlanRuns.status] == "success" }.map { it[PlanRuns.id] }
            val activeRunId = com.allocator.services.resolveActiveRunId(designatedId, successIds)
            val idStatusPairs = rows.map { it[PlanRuns.id] to it[PlanRuns.status] }
            val initialRunId = com.allocator.services.resolveInitialRunId(idStatusPairs)
            rows.map { row ->
                val snapshot = row[PlanRuns.overrideSnapshot]
                val parsedSnapshot = snapshot?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
                val overrideCount = parsedSnapshot?.size ?: 0
                val preview: JsonElement? = parsedSnapshot?.take(3)?.let { JsonArray(it) }
                val runId = row[PlanRuns.id]
                val startedInstant = row[PlanRuns.createdAt]
                val finishedInstant = row[PlanRuns.finishedAt]
                val chosenDepth = row[PlanRuns.chosenDepth]
                val attempts = parseAttempts(row[PlanRuns.attempts])
                PlanRunResponse(
                    id = runId,
                    caseId = row[PlanRuns.caseId],
                    jobId = row[PlanRuns.jobId],
                    status = row[PlanRuns.status],
                    config = row[PlanRuns.config]?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
                    overrideCount = overrideCount,
                    overrideSnapshotPreview = preview,
                    name = row[PlanRuns.name],
                    notes = row[PlanRuns.notes],
                    isInitial = (runId == initialRunId),
                    isActive = (runId == activeRunId),
                    isActiveDesignated = (runId == activeRunId && designatedId != null && runId == designatedId),
                    createdAt = formatTs(startedInstant),
                    finishedAt = finishedInstant?.let { formatTs(it) },
                    durationMs = displayedDurationMs(startedInstant, finishedInstant, chosenDepth, attempts),
                    chosenDepth = chosenDepth,
                    attempts = attempts,
                )
            }
        }
        call.respond(runs)
    }

    // ── GET /cases/{case_id}/plan-runs/unsaved — restore in-memory unsaved run ──
    // Returns the latest "ready" run with its in-memory result so the frontend can
    // restore state after a page swap before the user has explicitly saved the run.
    // If the in-memory result has expired (e.g. server restart), marks the stale row
    // as "failed" and returns 404 so the frontend falls back to the latest saved run.
    get("/cases/{case_id}/plan-runs/unsaved") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")

        data class ReadyRow(
            val id: Int, val name: String?, val notes: String?,
            val config: String?, val createdAt: kotlinx.datetime.Instant,
            val finishedAt: kotlinx.datetime.Instant?, val chosenDepth: Int?,
            val attempts: String?,
        )
        val readyRow = transaction {
            PlanRuns.selectAll()
                .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "ready") }
                .orderBy(PlanRuns.id, SortOrder.DESC)
                .firstOrNull()
                ?.let { r -> ReadyRow(r[PlanRuns.id], r[PlanRuns.name], r[PlanRuns.notes], r[PlanRuns.config], r[PlanRuns.createdAt], r[PlanRuns.finishedAt], r[PlanRuns.chosenDepth], r[PlanRuns.attempts]) }
        }

        if (readyRow == null) {
            call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "no unsaved run") })
            return@get
        }

        val inMemory = casePlanResults[caseId]
        if (inMemory == null) {
            // Server restarted — clean up the stale ready row so it doesn't linger
            transaction { PlanRuns.update({ PlanRuns.id eq readyRow.id }) { it[PlanRuns.status] = "failed" } }
            call.respond(HttpStatusCode.NotFound, buildJsonObject { put("error", "in-memory result expired") })
            return@get
        }

        val readyAttempts = parseAttempts(readyRow.attempts)
        call.respond(PlanRunFullResponse(
            id = readyRow.id,
            caseId = caseId,
            jobId = null,
            status = "ready",
            config = readyRow.config?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
            overrideSnapshot = null,
            result = runCatching { anyToJson(inMemory) }.getOrNull(),
            error = null,
            name = readyRow.name,
            notes = readyRow.notes,
            createdAt = formatTs(readyRow.createdAt),
            finishedAt = readyRow.finishedAt?.let { formatTs(it) },
            durationMs = displayedDurationMs(readyRow.createdAt, readyRow.finishedAt, readyRow.chosenDepth, readyAttempts),
            chosenDepth = readyRow.chosenDepth,
            attempts = readyAttempts,
        ))
    }

    // ── GET /cases/{case_id}/plan-runs/{run_id} — full run with result ────────
    get("/cases/{case_id}/plan-runs/{run_id}") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")

        data class RawRow(
            val id: Int, val caseId: Int, val jobId: String?, val status: String,
            val config: String?, val overrideSnapshot: String?,
            val result: String?, val error: String?,
            val name: String?, val notes: String?,
            val createdAt: kotlinx.datetime.Instant,
            val finishedAt: kotlinx.datetime.Instant?,
            val chosenDepth: Int?,
            val attempts: String?,
        )
        val raw = transaction {
            val row = PlanRuns.selectAll().where {
                (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId)
            }.singleOrNull() ?: throw NoSuchElementException("Plan run not found")
            RawRow(
                id = row[PlanRuns.id], caseId = row[PlanRuns.caseId],
                jobId = row[PlanRuns.jobId], status = row[PlanRuns.status],
                config = row[PlanRuns.config], overrideSnapshot = row[PlanRuns.overrideSnapshot],
                result = row[PlanRuns.result], error = row[PlanRuns.error],
                name = row[PlanRuns.name], notes = row[PlanRuns.notes],
                createdAt = row[PlanRuns.createdAt],
                finishedAt = row[PlanRuns.finishedAt],
                chosenDepth = row[PlanRuns.chosenDepth],
                attempts = row[PlanRuns.attempts],
            )
        }

        // Lazily re-enrich stale results that pre-date requested_qty enrichment.
        // Check by looking at the first committed_demand entry; if requested_qty is absent,
        // reload case data and re-enrich, then persist so it only runs once.
        val resultJson: String? = if (raw.result != null && raw.status == "success") {
            val needsEnrichment = runCatching {
                val parsed = Json.parseToJsonElement(raw.result).jsonObject
                val firstDemand = parsed["committed_demands"]?.jsonArray?.firstOrNull()?.jsonObject
                // Re-enrich if either requested_qty or shortage is absent (shortage was added later)
                firstDemand != null && (!firstDemand.containsKey("requested_qty") || !firstDemand.containsKey("shortage"))
            }.getOrElse { false }

            if (needsEnrichment) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val resultMap = jsonToAny(Json.parseToJsonElement(raw.result)) as? Map<String, Any>
                    if (resultMap != null) {
                        val data = transaction { CaseLoader.load(caseId) }
                        val enriched = enrichPlanResultWithData(caseId, resultMap, data)
                        val enrichedJson = anyToJson(enriched).toString()
                        transaction {
                            PlanRuns.update({ PlanRuns.id eq runId }) {
                                it[PlanRuns.result] = enrichedJson
                            }
                        }
                        enrichedJson
                    } else raw.result
                }.getOrElse { raw.result }
            } else raw.result
        } else raw.result

        // Compute active/initial flags + load events timeline
        data class Flags(val isInitial: Boolean, val isActive: Boolean, val isActiveDesignated: Boolean, val events: List<PlanRunEventDto>)
        val flags = transaction {
            val designatedId = Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()?.get(Cases.designatedActivePlanRunId)
            val caseRuns = PlanRuns.selectAll().where { (PlanRuns.caseId eq caseId) and (PlanRuns.status neq "ready") and (PlanRuns.status neq "running") }
                .map { it[PlanRuns.id] to it[PlanRuns.status] }
            val successIds = caseRuns.filter { it.second == "success" }.map { it.first }
            val activeRunId = com.allocator.services.resolveActiveRunId(designatedId, successIds)
            val initialRunId = com.allocator.services.resolveInitialRunId(caseRuns)
            val events = PlanRunEvents.selectAll().where { PlanRunEvents.planRunId eq raw.id }
                .orderBy(PlanRunEvents.createdAt, SortOrder.ASC)
                .map { row ->
                    PlanRunEventDto(
                        id = row[PlanRunEvents.id],
                        kind = row[PlanRunEvents.kind],
                        payload = row[PlanRunEvents.payload]?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
                        createdAt = formatTs(row[PlanRunEvents.createdAt]),
                    )
                }
            Flags(
                isInitial = (raw.id == initialRunId),
                isActive = (raw.id == activeRunId),
                isActiveDesignated = (raw.id == activeRunId && designatedId != null && raw.id == designatedId),
                events = events,
            )
        }

        val rawAttempts = parseAttempts(raw.attempts)
        val response = PlanRunFullResponse(
            id = raw.id,
            caseId = raw.caseId,
            jobId = raw.jobId,
            status = raw.status,
            config = raw.config?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
            overrideSnapshot = raw.overrideSnapshot?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
            result = resultJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
            error = raw.error,
            name = raw.name,
            notes = raw.notes,
            isInitial = flags.isInitial,
            isActive = flags.isActive,
            isActiveDesignated = flags.isActiveDesignated,
            events = flags.events,
            createdAt = formatTs(raw.createdAt),
            finishedAt = raw.finishedAt?.let { formatTs(it) },
            durationMs = displayedDurationMs(raw.createdAt, raw.finishedAt, raw.chosenDepth, rawAttempts),
            chosenDepth = raw.chosenDepth,
            attempts = rawAttempts,
        )
        call.respond(response)
    }

    // ── DELETE /cases/{case_id}/plan-runs/{run_id} ────────────────────────────
    delete("/cases/{case_id}/plan-runs/{run_id}") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")
        transaction {
            val deleted = PlanRuns.deleteWhere {
                (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId)
            }
            if (deleted == 0) throw NoSuchElementException("Plan run not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // ── POST /cases/{case_id}/plan-runs/{run_id}/save — persist in-memory result ─
    post("/cases/{case_id}/plan-runs/{run_id}/save") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")

        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) JsonObject(emptyMap())
                      else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val nameArg = payload["name"]?.jsonPrimitive?.contentOrNull
        val notesArg = payload["notes"]?.jsonPrimitive?.contentOrNull
        val mode = payload["mode"]?.jsonPrimitive?.contentOrNull ?: "new"
        val targetRunId = payload["target_run_id"]?.jsonPrimitive?.intOrNull

        val runRow = transaction {
            PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }.singleOrNull()
        } ?: throw NoSuchElementException("Plan run $runId not found for case $caseId")

        val currentStatus = runRow[PlanRuns.status]

        // ── Override mode: copy ready run's result/config/overrides into an existing success run ──
        if (mode == "override") {
            if (targetRunId == null) throw IllegalArgumentException("target_run_id is required when mode='override'")
            if (targetRunId == runId) throw IllegalArgumentException("target_run_id cannot equal the ready run id")
            if (currentStatus != "ready") throw IllegalStateException("Override source must be a ready run (got '$currentStatus')")

            val targetRow = transaction {
                PlanRuns.selectAll().where { (PlanRuns.id eq targetRunId) and (PlanRuns.caseId eq caseId) }.singleOrNull()
            } ?: throw NoSuchElementException("Target plan run $targetRunId not found for case $caseId")
            val targetStatus = targetRow[PlanRuns.status]
            if (targetStatus != "success") {
                throw IllegalStateException("Override target must have status 'success' (got '$targetStatus')")
            }

            val enriched = casePlanResults[caseId]
                ?: throw NoSuchElementException("No in-memory plan result for case $caseId. Re-run plan first.")
            val resultJson = runCatching { anyToJson(enriched).toString() }.getOrElse { null }
            val newConfig = runRow[PlanRuns.config]
            val newOverrideSnapshot = runRow[PlanRuns.overrideSnapshot]

            transaction {
                PlanRuns.update({ PlanRuns.id eq targetRunId }) {
                    it[PlanRuns.result] = resultJson
                    it[PlanRuns.config] = newConfig
                    it[PlanRuns.overrideSnapshot] = newOverrideSnapshot
                    if (nameArg != null) it[PlanRuns.name] = nameArg.ifBlank { null }
                    if (notesArg != null) it[PlanRuns.notes] = notesArg.ifBlank { null }
                }
                // Replace supply allocations on the target from the fresh enriched result
                PlanSupplyAllocations.deleteWhere { PlanSupplyAllocations.planRunId eq targetRunId }
                @Suppress("UNCHECKED_CAST")
                val supplyAllocs = (enriched["supply_allocations"] as? List<Map<String, Any?>>).orEmpty()
                if (supplyAllocs.isNotEmpty()) {
                    PlanSupplyAllocations.batchInsert(supplyAllocs) { alloc ->
                        this[PlanSupplyAllocations.caseId]      = caseId
                        this[PlanSupplyAllocations.planRunId]   = targetRunId
                        this[PlanSupplyAllocations.supplyId]    = alloc["supply_id"] as String
                        this[PlanSupplyAllocations.demandId]    = alloc["demand_id"] as? String
                        this[PlanSupplyAllocations.qtyConsumed] = (alloc["qty_consumed"] as? Number)?.toDouble() ?: 0.0
                    }
                }
                // Delete the ready row (cascade wipes its plan_run_event rows)
                PlanRuns.deleteWhere { PlanRuns.id eq runId }
                com.allocator.services.emitPlanRunEvent(caseId, targetRunId, "overridden", buildJsonObject {
                    put("replaced_ready_run_id", JsonPrimitive(runId))
                    nameArg?.ifBlank { null }?.let { put("name", JsonPrimitive(it)) }
                    notesArg?.ifBlank { null }?.let { put("notes", JsonPrimitive(it)) }
                })
            }
            call.respond(buildJsonObject { put("id", targetRunId); put("status", "success") })
            return@post
        }

        if (currentStatus == "success" || currentStatus == "contingent") {
            // Already persisted — apply name/notes update if provided, then return
            if (nameArg != null || notesArg != null) {
                val oldName = runRow[PlanRuns.name]
                val oldNotes = runRow[PlanRuns.notes]
                val newName = nameArg?.ifBlank { null } ?: oldName
                val newNotes = notesArg?.ifBlank { null } ?: oldNotes
                transaction {
                    PlanRuns.update({ PlanRuns.id eq runId }) {
                        if (nameArg != null) it[PlanRuns.name] = nameArg.ifBlank { null }
                        if (notesArg != null) it[PlanRuns.notes] = notesArg.ifBlank { null }
                    }
                    if (oldName != newName || oldNotes != newNotes) {
                        com.allocator.services.emitPlanRunEvent(caseId, runId, "renamed", buildJsonObject {
                            put("old_name", oldName?.let(::JsonPrimitive) ?: JsonNull)
                            put("new_name", newName?.let(::JsonPrimitive) ?: JsonNull)
                            put("old_notes", oldNotes?.let(::JsonPrimitive) ?: JsonNull)
                            put("new_notes", newNotes?.let(::JsonPrimitive) ?: JsonNull)
                        })
                    }
                }
            }
            call.respond(buildJsonObject { put("id", runId); put("status", currentStatus) })
            return@post
        }
        if (currentStatus != "ready") {
            throw IllegalStateException("Cannot save plan run with status '$currentStatus'")
        }

        val enriched = casePlanResults[caseId]
            ?: throw NoSuchElementException("No in-memory plan result for case $caseId. Re-run plan first.")
        val resultJson = runCatching { anyToJson(enriched).toString() }.getOrElse { null }
        transaction {
            PlanRuns.update({ PlanRuns.id eq runId }) {
                it[PlanRuns.status] = "success"
                it[PlanRuns.result] = resultJson
                if (nameArg != null) it[PlanRuns.name] = nameArg.ifBlank { null }
                if (notesArg != null) it[PlanRuns.notes] = notesArg.ifBlank { null }
            }
            @Suppress("UNCHECKED_CAST")
            val supplyAllocs = (enriched["supply_allocations"] as? List<Map<String, Any?>>).orEmpty()
            if (supplyAllocs.isNotEmpty()) {
                // Idempotent: clear any prior allocations for this run before re-inserting
                PlanSupplyAllocations.deleteWhere { PlanSupplyAllocations.planRunId eq runId }
                PlanSupplyAllocations.batchInsert(supplyAllocs) { alloc ->
                    this[PlanSupplyAllocations.caseId]      = caseId
                    this[PlanSupplyAllocations.planRunId]   = runId
                    this[PlanSupplyAllocations.supplyId]    = alloc["supply_id"] as String
                    this[PlanSupplyAllocations.demandId]    = alloc["demand_id"] as? String
                    this[PlanSupplyAllocations.qtyConsumed] = (alloc["qty_consumed"] as? Number)?.toDouble() ?: 0.0
                }
            }
            com.allocator.services.emitPlanRunEvent(caseId, runId, "saved", buildJsonObject {
                nameArg?.ifBlank { null }?.let { put("name", JsonPrimitive(it)) }
                notesArg?.ifBlank { null }?.let { put("notes", JsonPrimitive(it)) }
            })
        }
        call.respond(buildJsonObject { put("id", runId); put("status", "success") })
    }

    // ── PATCH /cases/{case_id}/plan-runs/{run_id} — update name / notes ──────
    patch("/cases/{case_id}/plan-runs/{run_id}") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")

        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) JsonObject(emptyMap())
                      else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }

        transaction {
            val existingRow = PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }.singleOrNull()
                ?: throw NoSuchElementException("Plan run $runId not found for case $caseId")
            val oldName = existingRow[PlanRuns.name]
            val oldNotes = existingRow[PlanRuns.notes]
            val newName = if (payload.containsKey("name")) payload["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } else oldName
            val newNotes = if (payload.containsKey("notes")) payload["notes"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } else oldNotes
            PlanRuns.update({ (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }) {
                if (payload.containsKey("name")) it[PlanRuns.name] = newName
                if (payload.containsKey("notes")) it[PlanRuns.notes] = newNotes
            }
            if (oldName != newName || oldNotes != newNotes) {
                com.allocator.services.emitPlanRunEvent(caseId, runId, "renamed", buildJsonObject {
                    put("old_name", oldName?.let(::JsonPrimitive) ?: JsonNull)
                    put("new_name", newName?.let(::JsonPrimitive) ?: JsonNull)
                    put("old_notes", oldNotes?.let(::JsonPrimitive) ?: JsonNull)
                    put("new_notes", newNotes?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        call.respond(buildJsonObject { put("id", runId) })
    }

    // ── POST /cases/{case_id}/plan-runs/{run_id}/designate-active ────────────
    // Pin this run as the case's active plan run. Only successful runs are eligible.
    post("/cases/{case_id}/plan-runs/{run_id}/designate-active") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")

        transaction {
            val row = PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }.singleOrNull()
                ?: throw NoSuchElementException("Plan run $runId not found for case $caseId")
            val status = row[PlanRuns.status]
            if (status != "success") {
                throw IllegalStateException("Only runs with status='success' can be designated active (got '$status')")
            }
            Cases.update({ Cases.id eq caseId }) {
                it[Cases.designatedActivePlanRunId] = runId
            }
            com.allocator.services.emitPlanRunEvent(caseId, runId, "designated_active")
        }
        call.respond(buildJsonObject { put("id", runId); put("designated_active_plan_run_id", runId) })
    }

    // ── DELETE /cases/{case_id}/designated-active — clear pinned active ──────
    delete("/cases/{case_id}/designated-active") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val priorId = transaction {
            val caseRow = Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
            val prior = caseRow[Cases.designatedActivePlanRunId]
            Cases.update({ Cases.id eq caseId }) { it[Cases.designatedActivePlanRunId] = null }
            if (prior != null) com.allocator.services.emitPlanRunEvent(caseId, prior, "undesignated")
            prior
        }
        call.respond(buildJsonObject { put("prior_designated_active_plan_run_id", priorId?.let(::JsonPrimitive) ?: JsonNull) })
    }
}

// ── Plan helper functions ──────────────────────────────────────────────────────

/** Convert Any? to JsonElement for response serialization. */
private fun anyToJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, vv) -> put(k.toString(), anyToJson(vv)) } }
    is List<*> -> buildJsonArray { v.forEach { add(anyToJson(it)) } }
    else -> JsonPrimitive(v.toString())
}

/**
 * Inverse of anyToJson: converts a stored JsonElement back to the Map<String,Any?>/List<Any?>
 * structure expected by findWoNode and other plan-result helpers.
 */
private fun jsonToAny(v: JsonElement): Any? = when (v) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        v.isString -> v.content
        v.booleanOrNull != null -> v.boolean
        v.intOrNull != null -> v.int
        v.longOrNull != null -> v.long
        v.doubleOrNull != null -> v.double
        else -> v.content
    }
    is JsonObject -> v.mapValues { (_, vv) -> jsonToAny(vv) }
    is JsonArray  -> v.map { jsonToAny(it) }
}

/**
 * Load a plan result for a case from the DB.
 * - If [runId] is provided, load that specific run (any status).
 * - Otherwise, load the latest successful run and warm up [casePlanResults].
 * Returns null if not found or the stored result cannot be parsed.
 */
@Suppress("UNCHECKED_CAST")
private fun loadPlanResultFromDb(caseId: Int, runId: Int? = null): Map<String, Any>? {
    val resultJson = transaction {
        if (runId != null) {
            PlanRuns.selectAll()
                .where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
                .firstOrNull()
                ?.get(PlanRuns.result)
        } else {
            PlanRuns.selectAll()
                .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                .orderBy(PlanRuns.id, SortOrder.DESC)
                .firstOrNull()
                ?.get(PlanRuns.result)
        }
    } ?: return null
    return runCatching {
        jsonToAny(Json.parseToJsonElement(resultJson)) as? Map<String, Any>
    }.getOrNull()
}

/** Return set of (parent_id, child_id) for real BOM rows (VIRTUAL != Y). */
private fun getBomRealPairs(): Set<Pair<String, String>> {
    val bomCsv = resolveBomCsv()
    val pairs = mutableSetOf<Pair<String, String>>()
    if (!bomCsv.toFile().exists()) return pairs
    try {
        CSVReaderHeaderAware(FileReader(bomCsv.toFile())).use { reader ->
            var row: Map<String, String>? = reader.readMap() ?: return pairs
            val hasVirtual = "VIRTUAL" in row!!
            while (row != null) {
                val r = row!!
                val parent = (r["PARENT_ID"] ?: "").trim()
                val child = (r["CHILD_ID"] ?: "").trim()
                if (parent.isNotBlank() && child.isNotBlank()) {
                    if (!hasVirtual || (r["VIRTUAL"] ?: "").trim().uppercase() != "Y") {
                        pairs.add(Pair(parent, child))
                    }
                }
                row = reader.readMap()
            }
        }
    } catch (e: Exception) { log.warn("Failed to read bom.csv for plan: ${e.message}") }
    return pairs
}

/** Return set of (product_id, from_location, to_location) for moves with transit_time > 0. */
private fun getMoveTriples(caseId: Int): Set<Triple<String, String, String>> = transaction {
    MethodMoves.selectAll()
        .where { (MethodMoves.caseId eq caseId) and (MethodMoves.transitTime neq null) }
        .filter { it[MethodMoves.transitTime] != null && it[MethodMoves.transitTime]!! > 0 }
        .map { Triple(it[MethodMoves.productId].trim(), it[MethodMoves.fromLocationId].trim(), it[MethodMoves.toLocationId].trim()) }
        .toSet()
}

@Suppress("UNCHECKED_CAST")
private fun findWoNode(tree: Any?, productId: String, locationId: String, method: String): Map<String, Any?>? {
    val node = tree as? Map<String, Any?> ?: return null
    if (node["type"] == "work_order" &&
        (node["product_id"] as? String ?: "").trim() == productId &&
        (node["location_id"] as? String ?: "").trim() == locationId &&
        (node["method"] as? String ?: "").trim() == method) return node
    for (ch in (node["children"] as? List<*> ?: emptyList<Any?>())) {
        findWoNode(ch, productId, locationId, method)?.let { return it }
    }
    return null
}

/** Collect all work_order nodes from the pegging tree as "pid@lid/method" strings — for debugging. */
@Suppress("UNCHECKED_CAST")
private fun collectAllWoKeys(tree: Any?, result: MutableList<String> = mutableListOf()): List<String> {
    val node = tree as? Map<String, Any?> ?: return result
    if (node["type"] == "work_order") {
        val pid = (node["product_id"] as? String ?: "").trim()
        val lid = (node["location_id"] as? String ?: "").trim()
        val m   = (node["method"] as? String ?: "").trim()
        result.add("$pid@$lid/$m")
    }
    for (ch in (node["children"] as? List<*> ?: emptyList<Any?>())) collectAllWoKeys(ch, result)
    return result
}

@Suppress("UNCHECKED_CAST")
private fun subtreeContainsRealMake(node: Any?, bomPairs: Set<Pair<String, String>>): Boolean {
    val n = node as? Map<String, Any?> ?: return false
    if (n["type"] == "work_order" && (n["method"] as? String ?: "").trim() == "make") {
        // Any make at a physical (non-VIRTUAL) location is a real make operation.
        // VIRTUAL locations are used only for synthetic BOM alt-group intermediate nodes.
        val location = (n["location_id"] as? String ?: "").trim().uppercase()
        if (location != "VIRTUAL") return true
        // For VIRTUAL location makes, recurse into children to find a real make deeper.
    }
    for (ch in (n["children"] as? List<*> ?: emptyList<Any?>())) {
        if (subtreeContainsRealMake(ch, bomPairs)) return true
    }
    return false
}

@Suppress("UNCHECKED_CAST")
private fun subtreeContainsBuy(node: Any?): Boolean {
    val n = node as? Map<String, Any?> ?: return false
    if (n["type"] == "purchase") return true
    for (ch in (n["children"] as? List<*> ?: emptyList<Any?>())) {
        if (subtreeContainsBuy(ch)) return true
    }
    return false
}

@Suppress("UNCHECKED_CAST")
private fun subtreeContainsRealMove(node: Any?, moveTriples: Set<Triple<String, String, String>>): Boolean {
    val n = node as? Map<String, Any?> ?: return false
    if (n["type"] == "work_order" && (n["method"] as? String ?: "").trim() == "move") {
        val pid = (n["product_id"] as? String ?: "").trim()
        val fromLoc = (n["location_source"] as? String ?: "").trim()
        val toLoc = (n["location_id"] as? String ?: "").trim()
        if (Triple(pid, fromLoc, toLoc) in moveTriples) return true
    }
    for (ch in (n["children"] as? List<*> ?: emptyList<Any?>())) {
        if (subtreeContainsRealMove(ch, moveTriples)) return true
    }
    return false
}

@Suppress("UNCHECKED_CAST")
private fun peggingSupplyConsumed(node: Any?): Double {
    val n = node as? Map<String, Any?> ?: return 0.0
    var total = if (n["type"] == "supply") (n["quantity"] as? Number ?: 0).toDouble() else 0.0
    for (ch in (n["children"] as? List<*> ?: emptyList<Any?>())) total += peggingSupplyConsumed(ch)
    return total
}

/** Add pegging flags to each work order (returns new list with added keys). */
/**
 * BOM-graph direct children of (pid, lid): make→bom children at the production location,
 * move→same product at source location. Pure BOM graph — no pegging involved.
 */
private fun bomGraphChildren(
    pid: String, lid: String,
    data: Map<String, List<Map<String, Any?>>>,
): Set<Pair<String, String>> {
    val result = mutableSetOf<Pair<String, String>>()
    for (m in getMethods(pid, lid, data)) {
        when (m["type"]) {
            "make" -> {
                val prodLoc = (m["location_id"] as? String)?.trim() ?: lid
                val bomId = (m["bom_id"] as? String) ?: continue
                for (row in (data["bom"] ?: emptyList())) {
                    if ((row["bom_id"] as? String) != bomId) continue
                    val childId = (row["child_id"] as? String)?.trim() ?: continue
                    result.add(Pair(childId, prodLoc))
                }
            }
            "move" -> {
                val fromLoc = (m["from_location_id"] as? String)?.trim() ?: continue
                result.add(Pair(pid, fromLoc))
            }
            // "purchase" → leaf node, no children in BOM graph
        }
    }
    return result
}

private fun enrichWorkOrders(
    workOrders: List<Map<String, Any?>>,
    planningPegging: List<Map<String, Any?>>,
    bomPairs: Set<Pair<String, String>>,
    moveTriples: Set<Triple<String, String, String>>,
    data: Map<String, List<Map<String, Any?>>>,
): List<Map<String, Any?>> {
    // Multiple pegging trees can share the same demand_id (one per component group from consolidation).
    // Build a multimap so we can search all trees for a given demand_id.
    val treesByDemand: Map<String, List<Any?>> = planningPegging
        .filter { (it["demand_id"]?.toString() ?: "").isNotBlank() }
        .groupBy { e -> (e["demand_id"]?.toString() ?: "").trim() }
        .mapValues { (_, entries) -> entries.mapNotNull { it["tree"] } }
    // Consolidated WOs (demand_id=null) have their own pegging trees
    val consolidatedTrees = planningPegging
        .filter { (it["demand_id"]?.toString() ?: "").isBlank() }
        .mapNotNull { it["tree"] }
    // Cross-demand consolidated entries: a WO with a real demand_id may live inside one of these
    // when ConsolidationEngine merges multiple demands wanting the same product+location.
    // Index each consolidated tree under every demand_id listed in its consolidated_demand_ids.
    val consolidatedTreesByMember: Map<String, List<Any?>> = run {
        val acc = mutableMapOf<String, MutableList<Any?>>()
        for (e in planningPegging) {
            if ((e["demand_id"]?.toString() ?: "").isNotBlank()) continue
            @Suppress("UNCHECKED_CAST")
            val members = e["consolidated_demand_ids"] as? List<String> ?: continue
            val tree = e["tree"] ?: continue
            for (m in members) acc.getOrPut(m) { mutableListOf() }.add(tree)
        }
        acc
    }

    // BOM-graph traversal: for each unique demand product+location, BFS through BOM graph
    // to find all reachable nodes. Build: "pid|lid" → count of distinct demand products that reach it.
    val childCache = mutableMapOf<String, Set<Pair<String, String>>>()
    fun cachedChildren(p: String, l: String) =
        childCache.getOrPut("$p|$l") { bomGraphChildren(p, l, data) }

    val nodeDemanderSets = mutableMapOf<String, MutableSet<String>>()
    val uniqueDemandRoots = (data["demand"] ?: emptyList()).mapNotNull { d ->
        val dp = (d["product_id"] as? String)?.trim() ?: return@mapNotNull null
        val dl = (d["location_id"] as? String)?.trim() ?: return@mapNotNull null
        Pair(dp, dl)
    }.toSet()

    for ((dp, dl) in uniqueDemandRoots) {
        val demandKey = "$dp|$dl"
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        queue.add(Pair(dp, dl))
        while (queue.isNotEmpty()) {
            val (np, nl) = queue.removeFirst()
            val key = "$np|$nl"
            if (!visited.add(key)) continue
            nodeDemanderSets.getOrPut(key) { mutableSetOf() }.add(demandKey)
            for (child in cachedChildren(np, nl)) {
                if ("${child.first}|${child.second}" !in visited) queue.add(child)
            }
        }
    }

    return workOrders.map { wo ->
        val pid = (wo["product_id"] as? String ?: "").trim()
        val lid = (wo["location_id"] as? String ?: "").trim()
        val demandId = (wo["demand_id"]?.toString() ?: "").trim()
        val method = (wo["method"] as? String ?: "").trim()
        val woNode = if (demandId.isNotBlank()) {
            // Search per-demand trees first, then cross-demand consolidated trees that include this demand
            val candidates = (treesByDemand[demandId] ?: emptyList()) +
                (consolidatedTreesByMember[demandId] ?: emptyList())
            candidates.firstNotNullOfOrNull { findWoNode(it, pid, lid, method) }
        } else {
            // Consolidated WO — search through all consolidated pegging trees
            consolidatedTrees.firstNotNullOfOrNull { findWoNode(it, pid, lid, method) }
        }
        val woKey = "$pid|$lid"
        val competingDemands = nodeDemanderSets[woKey]?.toList() ?: emptyList<String>()
        wo + mapOf(
            "pegging_includes_real_make" to (woNode?.let { subtreeContainsRealMake(it, bomPairs) } ?: false),
            "pegging_includes_buy" to (woNode?.let { subtreeContainsBuy(it) } ?: false),
            "pegging_includes_real_move" to (woNode?.let { subtreeContainsRealMove(it, moveTriples) } ?: false),
            "demanded_by_multiple" to (competingDemands.size > 1),
            "multi_supply_available" to (getMethods(pid, lid, data).size > 1),
            // Explanation fields — propagated from the pegging node so the WO view can show them without opening the pegging tree
            "wo_explanation_method" to (woNode?.get("method_choice_explanation") as? String),
            "wo_explanation_variant" to (woNode?.get("variant_choice_explanation") as? String),
            "wo_competing_demands" to competingDemands,
            // Consolidation split explanation — only present on consolidated WOs
            "wo_consolidation_split_mode" to (wo["consolidation_split_mode"] as? String),
            "wo_consolidation_total_planned" to (wo["consolidation_total_planned"] as? Number)?.toDouble(),
            @Suppress("UNCHECKED_CAST")
            "wo_consolidation_split_details" to (wo["consolidation_split_details"] as? List<Map<String, Any?>>),
            // Override active flags — propagated from planning/consolidation engines
            "override_active" to (wo["override_active"] as? Boolean ?: false),
            "consolidation_override_active" to (wo["consolidation_override_active"] as? Boolean ?: false),
        )
    }
}

/** Compute plan KPIs (delivery, inventory, procurement, manufacturing, logistics). */
@Suppress("UNCHECKED_CAST")
private fun planKpis(
    data: Map<String, List<Map<String, Any?>>>,
    result: Map<String, Any>,
    bomPairs: Set<Pair<String, String>>,
): Map<String, Any?> {
    val demands = data["demand"] ?: emptyList()
    val committed = result["committed_demands"] as? List<Map<String, Any?>> ?: emptyList()
    val workOrders = result["work_orders"] as? List<Map<String, Any?>> ?: emptyList()
    val planningPegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()

    val totalRequested = demands.sumOf { (it["quantity"] as? Number ?: 0).toDouble() }
    val totalCommitted = committed.sumOf { (it["quantity"] as? Number ?: 0).toDouble() }
    val fillRatePct = if (totalRequested > 0) totalCommitted / totalRequested * 100.0 else null

    val commitByDemand = mutableMapOf<String, String?>()
    for (c in committed) {
        val did = (c["demand_id"]?.toString() ?: "").trim()
        val ct = c["commit_time"] as? String
        if (did.isNotBlank() && ct != null) {
            val existing = commitByDemand[did]
            if (existing == null || ct > existing) commitByDemand[did] = ct
        }
    }
    var onTimeCount = 0
    for (d in demands) {
        val did = (d["demand_id"]?.toString() ?: "").trim()
        val due = (d["request_due_time"] as? String ?: d["request_time"] as? String) ?: continue
        if (did.isBlank()) continue
        val ct = commitByDemand[did] ?: continue
        if (ct <= due) onTimeCount++
    }

    val treeByDemand = planningPegging.associate { e ->
        (e["demand_id"]?.toString() ?: "").trim() to e["tree"]
    }
    val fulfilledIds = committed
        .filter { (it["quantity"] as? Number ?: 0).toDouble() > 0 }
        .map { (it["demand_id"]?.toString() ?: "").trim() }
        .filter { it.isNotBlank() }
        .toSet()
    val fulfilledWithTree = fulfilledIds.filter { it in treeByDemand }
    var fulfilledByRealMake = 0
    var fulfilledByInventoryOnly = 0
    for (did in fulfilledWithTree) {
        val tree = treeByDemand[did] ?: continue
        if (subtreeContainsRealMake(tree, bomPairs)) fulfilledByRealMake++ else fulfilledByInventoryOnly++
    }

    // Supply summary
    val supplyList = data["supply"] ?: emptyList()
    val initialTotal = supplyList.sumOf { (it["qty"] as? Number ?: 0).toDouble() }
    // Exclude passthrough entries (single-demand consolidation pass-through trees) from the
    // supply-consumption sum to avoid double-counting: those trees show the original supply
    // consumed by the consolidation run, while the main-loop trees show the same demand
    // consuming the injected consolidated supply bucket.
    val consumedTotal = planningPegging.filter { it["passthrough"] != true }.sumOf { e -> peggingSupplyConsumed(e["tree"]) }
    val consumptionRate = if (initialTotal > 0) consumedTotal / initialTotal else null

    fun methodStats(methodVal: String): Map<String, Any?> {
        val wos = workOrders.filter { wo ->
            (wo["method"] as? String ?: "").trim().lowercase() == methodVal &&
            when (methodVal) {
                "make" -> wo["pegging_includes_real_make"] == true
                "move" -> wo["pegging_includes_real_move"] == true
                else -> true
            }
        }
        val seen = mutableMapOf<String, Double>()
        for (wo in wos) {
            val key = "${wo["demand_id"]}|${wo["product_id"]}|${wo["location_id"]}|${wo["method"]}"
            seen[key] = (seen[key] ?: 0.0) + (wo["quantity"] as? Number ?: 0).toDouble()
        }
        return mapOf("order_count" to seen.size, "total_quantity" to roundQty(seen.values.sum()))
    }

    return mapOf(
        "delivery" to mapOf(
            "total_requested" to roundQty(totalRequested),
            "total_committed" to roundQty(totalCommitted),
            "fill_rate_pct" to fillRatePct?.let { Math.round(it * 100).toDouble() / 100.0 },
            "demand_count" to demands.size,
            "on_time_count" to onTimeCount,
            "fulfilled_with_tree_count" to fulfilledWithTree.size,
            "fulfilled_by_real_make_count" to fulfilledByRealMake,
            "fulfilled_by_inventory_only_count" to fulfilledByInventoryOnly,
        ),
        "inventory" to mapOf(
            "initial_total" to roundQty(initialTotal),
            "consumed_total" to roundQty(consumedTotal),
            "consumption_rate" to consumptionRate?.let { Math.round(it * 10000).toDouble() / 10000.0 },
        ),
        "procurement" to methodStats("purchase"),
        "manufacturing" to methodStats("make"),
        "logistics" to methodStats("move"),
    )
}

/** Commit reasons that mean planning could not fulfill the demand (no real supply was secured). */
private val FAILURE_REASONS = setOf("depth_limit", "cycle_stopped", "no_methods", "no_preferred_method")
private fun isFailureReason(reason: String?) =
    reason != null && (reason in FAILURE_REASONS || reason.startsWith("child_failed:"))

/** Add requested_qty, shortage, and is_failed to each committed demand row.
 *  Failure-reason rows (no_methods, depth_limit, etc.) are marked is_failed=true and their
 *  quantity is excluded from the effective committed sum so shortage is computed correctly. */
private fun enrichCommittedDemands(
    committed: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): List<Map<String, Any?>> {
    // Build demand_id → requested_qty from source demand data
    val requestedByDemandId = (data["demand"] ?: emptyList()).associate { d ->
        (d["demand_id"]?.toString() ?: "").trim() to ((d["quantity"] as? Number)?.toDouble() ?: 0.0)
    }
    // Sum only non-failure committed qty per demand_id
    val effectiveCommittedByDemandId = mutableMapOf<String, Double>()
    for (row in committed) {
        if (isFailureReason(row["commit_reason"] as? String)) continue
        val id = (row["demand_id"]?.toString() ?: "").trim()
        effectiveCommittedByDemandId[id] = (effectiveCommittedByDemandId[id] ?: 0.0) + ((row["quantity"] as? Number)?.toDouble() ?: 0.0)
    }
    return committed.map { row ->
        val id = (row["demand_id"]?.toString() ?: "").trim()
        val requested = requestedByDemandId[id] ?: 0.0
        val effectiveCommitted = effectiveCommittedByDemandId[id] ?: 0.0
        val shortage = maxOf(0.0, requested - effectiveCommitted)
        val failed = isFailureReason(row["commit_reason"] as? String)
        row + mapOf("requested_qty" to roundQty(requested), "shortage" to roundQty(shortage), "is_failed" to failed)
    }
}

/** Enrich a raw runPlanning() result with pegging flags and KPIs. */
private fun enrichPlanResultWithData(
    caseId: Int,
    result: Map<String, Any>,
    data: Map<String, List<Map<String, Any?>>>,
): Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    val workOrders = result["work_orders"] as? List<Map<String, Any?>> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val planningPegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()
    val bomPairs = getBomRealPairs()
    val moveTriples = getMoveTriples(caseId)
    val enrichedWos = enrichWorkOrders(workOrders, planningPegging, bomPairs, moveTriples, data)
    val enrichedCommitted = enrichCommittedDemands(
        result["committed_demands"].let { @Suppress("UNCHECKED_CAST") it as? List<Map<String, Any?>> ?: emptyList() },
        data
    )
    val enriched = result.toMutableMap()
    enriched["work_orders"] = enrichedWos
    enriched["committed_demands"] = enrichedCommitted
    val kpis = planKpis(data, enriched, bomPairs)
    enriched["plan_kpis"] = kpis
    enriched["supply_summary"] = (kpis["inventory"] ?: emptyMap<String, Any?>())

    return enriched
}

/** Parse the persisted `attempts` JSON column into a JsonArray (or null on bad/missing input). */
private fun parseAttempts(raw: String?): JsonArray? {
    if (raw == null) return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
}

/** When optimal-depth search ran, the meaningful elapsed time is the chosen
 *  depth's own attempt — not the cumulative search. Falls back to (finish - start)
 *  for fixed-depth runs or when the chosen attempt isn't present in the log. */
private fun displayedDurationMs(
    createdAt: kotlinx.datetime.Instant,
    finishedAt: kotlinx.datetime.Instant?,
    chosenDepth: Int?,
    attempts: JsonArray?,
): Long? {
    if (chosenDepth != null && attempts != null) {
        val match = attempts.firstOrNull { el ->
            (el as? JsonObject)?.get("depth")?.jsonPrimitive?.intOrNull == chosenDepth
        } as? JsonObject
        val ms = match?.get("duration_ms")?.jsonPrimitive?.longOrNull
        if (ms != null) return ms
    }
    return finishedAt?.let { it.toEpochMilliseconds() - createdAt.toEpochMilliseconds() }
}

/** Read a single dimension out of plan_kpis (defaulting to 0.0 when missing). */
private fun kpiTriplet(enriched: Map<String, Any>): Triple<Double, Double, Double> {
    @Suppress("UNCHECKED_CAST")
    val kpis = enriched["plan_kpis"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val delivery = kpis["delivery"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val inventory = kpis["inventory"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val procurement = kpis["procurement"] as? Map<String, Any?> ?: emptyMap()
    val onTime = (delivery["on_time_count"] as? Number)?.toDouble() ?: 0.0
    val invConsumed = (inventory["consumed_total"] as? Number)?.toDouble() ?: 0.0
    val purchaseQty = (procurement["total_quantity"] as? Number)?.toDouble() ?: 0.0
    return Triple(onTime, invConsumed, purchaseQty)
}

/** Compute the weighted relative improvement of `cur` vs `prev` using the user's
 *  score_weights (commit_time / inventory_consumed / purchase). Returns a scale-free
 *  number; positive ⇒ improvement, ≤ 0 ⇒ stop.
 *
 *  Each dimension is normalized by its previous magnitude so weights act on
 *  fractional change. Purchase is inverted (lower is better). */
private fun weightedImprovement(
    prev: Triple<Double, Double, Double>,
    cur: Triple<Double, Double, Double>,
    weights: Map<String, Any?>?,
): Double {
    val wc = (weights?.get("commit_time") as? Number)?.toDouble() ?: 0.4
    val wi = (weights?.get("inventory_consumed") as? Number)?.toDouble() ?: 0.35
    val wp = (weights?.get("purchase") as? Number)?.toDouble() ?: 0.25
    val (oP, iP, pP) = prev
    val (oC, iC, pC) = cur
    val rc = (oC - oP) / kotlin.math.max(1.0, kotlin.math.abs(oP))
    val ri = (iC - iP) / kotlin.math.max(1.0, kotlin.math.abs(iP))
    val rp = (pP - pC) / kotlin.math.max(1.0, kotlin.math.abs(pP))   // lower purchase is better
    return wc * rc + wi * ri + wp * rp
}

/** Build a fresh config map with method_selection.depth = `depth` and elaborate forced on. */
private fun configForDepth(config: Map<String, Any?>?, depth: Int): Map<String, Any?> {
    val base = config?.toMutableMap() ?: mutableMapOf()
    @Suppress("UNCHECKED_CAST")
    val ms = (base["method_selection"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
    ms["mode"] = "elaborate"
    ms["depth"] = depth
    ms["depth_optimal"] = false   // collapse to a normal elaborate run within the loop
    base["method_selection"] = ms
    return base
}

/** Same shape as configForDepth, but emits JSON for the persisted config snapshot. */
private fun overrideConfigDepth(config: Map<String, Any?>?, depth: Int): JsonElement {
    return resolveEffectiveConfig(configForDepth(config, depth))
}

/** Iterate elaborate planning at depth=1, 2, … until the weighted improvement
 *  vs. the previous depth is non-positive, or MAX_OPTIMAL_DEPTH is reached.
 *  Returns the enriched best plan, the depth that produced it, and a per-attempt
 *  duration log so the UI can show what each depth cost. */
private suspend fun runOptimalDepthPlanning(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    caseId: Int,
    progressCallback: (Map<String, Any?>) -> Unit,
    methodCfg: com.allocator.services.MethodSelectionConfig,
): Triple<Map<String, Any>, Int, List<Map<String, Long>>> {
    var bestEnriched: Map<String, Any>? = null
    var bestDepth = 1
    var prevTriplet: Triple<Double, Double, Double>? = null
    val attempts = mutableListOf<Map<String, Long>>()
    val cap = kotlin.math.min(com.allocator.services.MAX_OPTIMAL_DEPTH, 500)
    for (d in 1..cap) {
        val cfg = configForDepth(config, d)
        val attemptStart = kotlinx.datetime.Clock.System.now()
        val raw = runPlanning(data, config = cfg, progressCallback = progressCallback)
        val enriched = enrichPlanResultWithData(caseId, raw, data)
        val attemptMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - attemptStart.toEpochMilliseconds()
        attempts.add(mapOf("depth" to d.toLong(), "duration_ms" to attemptMs))
        val triplet = kpiTriplet(enriched)
        if (prevTriplet == null) {
            bestEnriched = enriched
            bestDepth = d
            prevTriplet = triplet
            continue
        }
        val delta = weightedImprovement(prevTriplet, triplet, methodCfg.scoreWeights)
        if (delta > 1e-6) {
            bestEnriched = enriched
            bestDepth = d
            prevTriplet = triplet
        } else {
            break  // first non-improvement: keep the previous depth's plan
        }
    }
    return Triple(bestEnriched!!, bestDepth, attempts.toList())
}

private suspend fun runPlanBackground(
    jobId: String,
    caseId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
) {
    // Insert plan_run record at start, capturing current override snapshot
    val planRunId = transaction {
        val overrides = ManualOverrides.selectAll().where { ManualOverrides.caseId eq caseId }
            .map { row ->
                buildJsonObject {
                    put("id", row[ManualOverrides.id])
                    put("case_id", row[ManualOverrides.caseId])
                    put("entity_type", row[ManualOverrides.entityType])
                    put("entity_key", row[ManualOverrides.entityKey])
                    put("payload", runCatching { Json.parseToJsonElement(row[ManualOverrides.payload]) }.getOrElse { JsonNull })
                }
            }
        val overrideSnapshotJson = JsonArray(overrides).toString()
        val configJson = resolveEffectiveConfig(config).toString()
        val insertedId = PlanRuns.insert {
            it[PlanRuns.caseId] = caseId
            it[PlanRuns.jobId] = jobId
            it[PlanRuns.status] = "running"
            it[PlanRuns.config] = configJson
            it[PlanRuns.overrideSnapshot] = overrideSnapshotJson
        }[PlanRuns.id]
        com.allocator.services.emitPlanRunEvent(caseId, insertedId, "created", buildJsonObject {
            put("source", "plan")
            put("override_count", JsonPrimitive(overrides.size))
        })
        insertedId
    }
    planJobRunIds[jobId] = planRunId

    try {
        val total = data["demand"]?.size ?: 0
        val progressCb: (Map<String, Any?>) -> Unit = { p ->
            planJobs[jobId]?.let { job ->
                if (job["status"] == "running") {
                    val payload = mutableMapOf<String, Any?>(
                        "current" to (p["current"] ?: 0),
                        "total" to total,
                    )
                    p["iteration"]?.let { payload["iteration"] = it }
                    p["iterations_max"]?.let { payload["iterations_max"] = it }
                    job["progress"] = payload
                }
            }
        }

        val methodCfg = com.allocator.services.resolveMethodSelection(config)
        val (enriched, chosenDepth, attempts) = if (methodCfg.depthOptimal) {
            val (e, d, a) = runOptimalDepthPlanning(data, config, caseId, progressCb, methodCfg)
            Triple(e, d as Int?, a)
        } else {
            val raw = runPlanning(data, config = config, progressCallback = progressCb)
            Triple(enrichPlanResultWithData(caseId, raw, data), null as Int?, emptyList<Map<String, Long>>())
        }
        casePlanResults[caseId] = enriched

        // Mark run as ready (result not yet persisted — user must explicitly save).
        // When optimal-depth search ran, rewrite the config snapshot so subsequent
        // "view config" displays reflect the depth that was actually used, and
        // persist per-depth attempt durations so the UI can attribute elapsed
        // time to the depth that was actually chosen.
        transaction {
            PlanRuns.update({ PlanRuns.id eq planRunId }) {
                it[PlanRuns.status] = "ready"
                it[PlanRuns.finishedAt] = kotlinx.datetime.Clock.System.now()
                if (chosenDepth != null) {
                    it[PlanRuns.config] = overrideConfigDepth(config, chosenDepth).toString()
                    it[PlanRuns.chosenDepth] = chosenDepth
                }
                if (attempts.isNotEmpty()) {
                    it[PlanRuns.attempts] = JsonArray(attempts.map { a ->
                        buildJsonObject {
                            put("depth", JsonPrimitive(a["depth"]))
                            put("duration_ms", JsonPrimitive(a["duration_ms"]))
                        }
                    }).toString()
                }
            }
            if (chosenDepth != null) {
                com.allocator.services.emitPlanRunEvent(caseId, planRunId, "optimal_depth_chosen", buildJsonObject {
                    put("depth", JsonPrimitive(chosenDepth))
                })
            }
        }

        planJobs[jobId]?.let { job ->
            job["status"] = "completed"
            job["result"] = enriched
            job["progress"] = mapOf("current" to total, "total" to total)
        }
    } catch (e: Exception) {
        log.error("Plan job $jobId failed: ${e.message}", e)
        transaction {
            PlanRuns.update({ PlanRuns.id eq planRunId }) {
                it[PlanRuns.status] = "failed"
                it[PlanRuns.error] = e.message ?: "Unknown error"
                it[PlanRuns.finishedAt] = kotlinx.datetime.Clock.System.now()
            }
        }
        planJobs[jobId]?.let { job ->
            job["status"] = "failed"
            job["error"] = e.message ?: "Unknown error"
        }
    }
}

// ── Background allocation worker ───────────────────────────────────────────────

private const val BATCH_SIZE = 2000

private suspend fun runAllocationBackground(caseId: Int, runId: Int) {
    try {
        val data = transaction { CaseLoader.load(caseId) }
        if (data["demand"].isNullOrEmpty() || data["supply"].isNullOrEmpty()) {
            markRunFailed(runId, caseId, "No demand or supply data")
            return
        }

        val progressCallback: (Map<String, Any>) -> Unit = { progress ->
            try {
                transaction {
                    val runRow = AllocationRuns.selectAll().where {
                        (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                    }.singleOrNull() ?: return@transaction

                    if (runRow[AllocationRuns.status] != "running") return@transaction

                    val configObj = runRow[AllocationRuns.config]
                        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject.toMutableMap() }.getOrElse { mutableMapOf() } }
                        ?: mutableMapOf()

                    // Update progress sub-object (exclude slice/prune keys)
                    val progressMap = progress.filterKeys { it !in setOf("allocation_slice", "prune_after_step", "prune_components") }
                        .mapValues { Json.encodeToJsonElement(it.value.toString()) }
                    configObj["progress"] = JsonObject(progressMap)

                    // Accumulate prune events
                    if ("prune_after_step" in progress && "prune_components" in progress) {
                        val existing = (configObj["prunes"] as? JsonArray)?.toMutableList() ?: mutableListOf()
                        existing.add(buildJsonObject {
                            put("after_step", progress["prune_after_step"].toString())
                            put("comp_keys", buildJsonArray { (progress["prune_components"] as? List<*>)?.forEach { add(it.toString()) } })
                        })
                        configObj["prunes"] = JsonArray(existing)
                    }

                    AllocationRuns.update({ AllocationRuns.id eq runId }) {
                        it[config] = JsonObject(configObj).toString()
                    }

                    // Persist incremental slice
                    @Suppress("UNCHECKED_CAST")
                    val slice = progress["allocation_slice"] as? List<Map<String, Any?>>
                    if (!slice.isNullOrEmpty()) {
                        AllocationActions.batchInsert(slice) { a ->
                            this[AllocationActions.runId] = runId
                            this[AllocationActions.variantKey] = a["variant_key"] as? String ?: ""
                            this[AllocationActions.reqComponentIds] = buildJsonArray {
                                (a["req_component_ids"] as? List<*>)?.forEach { add(it.toString()) }
                            }.toString()
                            this[AllocationActions.reqRates] = (a["req_rates"] as? List<*>)?.let { rates ->
                                "[${rates.joinToString(",") { it.toString() }}]"
                            }
                            this[AllocationActions.qty] = (a["qty"] as? Number)?.toDouble() ?: 0.0
                            this[AllocationActions.demandId] = a["demand_id"] as? String
                            this[AllocationActions.targetProductId] = a["target_product_id"] as? String
                            this[AllocationActions.targetLocationId] = a["target_location_id"] as? String
                            this[AllocationActions.outputPeriod] = (a["output_period"] as? Int)
                            this[AllocationActions.edgeType] = a["edge_type"] as? String
                            this[AllocationActions.scarcityRank] = a["scarcity_rank"] as? Int
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Progress callback error: ${e.message}")
            }
        }

        val result = runAllocation(
            data = data,
            progressCallback = progressCallback,
        )

        val allocationList = result.allocation
        if (allocationList.isEmpty()) {
            markRunFailed(runId, caseId, "Allocation returned no actions")
            return
        }

        transaction {
            // Clear any incremental slices written during progress
            AllocationActions.deleteWhere { AllocationActions.runId eq runId }

            // Batch insert all final actions
            for (i in allocationList.indices step BATCH_SIZE) {
                val batch = allocationList.subList(i, minOf(i + BATCH_SIZE, allocationList.size))
                AllocationActions.batchInsert(batch) { a ->
                    this[AllocationActions.runId] = runId
                    this[AllocationActions.variantKey] = a["variant_key"] as? String ?: ""
                    this[AllocationActions.reqComponentIds] = buildJsonArray {
                        (a["req_component_ids"] as? List<*>)?.forEach { add(it.toString()) }
                    }.toString()
                    this[AllocationActions.reqRates] = (a["req_rates"] as? List<*>)?.let { rates ->
                        "[${rates.joinToString(",") { it.toString() }}]"
                    }
                    this[AllocationActions.qty] = (a["qty"] as? Number)?.toDouble() ?: 0.0
                    this[AllocationActions.demandId] = a["demand_id"] as? String
                    this[AllocationActions.targetProductId] = a["target_product_id"] as? String
                    this[AllocationActions.targetLocationId] = a["target_location_id"] as? String
                    this[AllocationActions.outputPeriod] = a["output_period"] as? Int
                    this[AllocationActions.edgeType] = a["edge_type"] as? String
                    this[AllocationActions.scarcityRank] = a["scarcity_rank"] as? Int
                }
            }

            // Update run to success with metadata
            val runRow = AllocationRuns.selectAll().where { AllocationRuns.id eq runId }.singleOrNull()
            val configObj = runRow?.get(AllocationRuns.config)
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject.toMutableMap() }.getOrElse { mutableMapOf() } }
                ?: mutableMapOf()
            configObj["raw_material_trace"] = Json.encodeToJsonElement(result.rawMaterialTrace.toString())
            configObj["consumed_by_node"] = Json.encodeToJsonElement(result.consumedByNode.toString())
            AllocationRuns.update({ AllocationRuns.id eq runId }) {
                it[status] = "success"
                it[config] = JsonObject(configObj).toString()
            }
        }
        log.info("Allocation run $runId completed: ${allocationList.size} actions")

    } catch (e: Exception) {
        log.error("Allocation run $runId failed: ${e.message}", e)
        markRunFailed(runId, caseId, e.message ?: "Unknown error")
    }
}

private fun markRunFailed(runId: Int, caseId: Int, error: String) {
    try {
        transaction {
            AllocationRuns.update({ (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId) }) {
                it[status] = "failed"
                it[config] = """{"error": "${error.replace("\"", "'")}"}"""
            }
        }
    } catch (e: Exception) {
        log.error("Failed to mark run $runId as failed: ${e.message}")
    }
}

/**
 * Expand a raw planning config (which may be null or partially specified) into a fully
 * populated map that records every parameter at its effective runtime value, including
 * defaults.  Persisting this instead of the raw config means a stored plan run is
 * self-describing — future code changes to defaults cannot alter its interpretation.
 */
@Suppress("UNCHECKED_CAST")
private fun resolveEffectiveConfig(config: Map<String, Any?>?): JsonObject {
    val c = config ?: emptyMap()
    val consolidation = (c["consolidation"]     as? Map<*, *>)?.let { it as Map<String, Any?> } ?: emptyMap()
    val methodCfg = resolveMethodSelection(c)
    val variantCfg = resolveVariantSelection(c)

    return buildJsonObject {
        put("purchase_allowed", c["purchase_allowed"] as? Boolean ?: true)
        putJsonObject("method_selection") {
            put("mode",      methodCfg.mode)
            put("depth",     methodCfg.depth)
            put("elaborate", methodCfg.elaborate)  // legacy mirror — consumers still read this
            put("multiple",  methodCfg.multiple)
            put("depth_optimal", methodCfg.depthOptimal)
            // Always materialize score_weights (with engine defaults) so the persisted
            // snapshot is self-describing — viewing config later shows the exact weights
            // that were in effect, not a hole the reader has to know to fill in.
            val w = methodCfg.scoreWeights
            putJsonObject("score_weights") {
                put("commit_time",        (w?.get("commit_time")        as? Number)?.toDouble() ?: 0.4)
                put("inventory_consumed", (w?.get("inventory_consumed") as? Number)?.toDouble() ?: 0.35)
                put("purchase",           (w?.get("purchase")           as? Number)?.toDouble() ?: 0.25)
            }
        }
        putJsonObject("variant_selection") {
            put("multiple", variantCfg.multiple ?: true)
            variantCfg.scoreWeights?.let { put("score_weights", anyToJson(it)) }
            variantCfg.topN?.let { put("top_n", it) }
        }
        putJsonObject("consolidation") {
            put("enabled",         consolidation["enabled"]      as? Boolean ?: false)
            put("period_days",     ((consolidation["period_days"] as? Number)?.toInt() ?: 7).coerceIn(1, 365))
            put("allocation_mode", when (consolidation["allocation_mode"]?.toString()) {
                "proportional"   -> "proportional"
                "priority_first" -> "priority_first"
                else             -> "fair"
            })
        }
    }
}

/** Resolve bom.csv path — walks up from working directory. */
private fun resolveBomCsv() = run {
    val base = Paths.get("").toAbsolutePath()
    val candidates = listOf(
        base.resolve("csv/bom.csv"),
        base.parent?.resolve("csv/bom.csv"),
        base.parent?.parent?.resolve("csv/bom.csv"),
    )
    candidates.firstOrNull { it != null && it.toFile().exists() } ?: base.resolve("csv/bom.csv")
}

// Re-use the timestamp formatter from Cases.kt
private fun formatTs(ts: kotlinx.datetime.Instant): String {
    val ISO = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(java.time.ZoneOffset.UTC)
    return ISO.format(ts.toJavaInstant())
}

/** Recursively converts a JsonElement to native Kotlin types so config maps can be read with normal == checks. */
internal fun jsonElementToNative(element: JsonElement): Any? = when (element) {
    is JsonNull      -> null
    is JsonPrimitive -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
    is JsonObject    -> element.entries.associate { (k, v) -> k to jsonElementToNative(v) }
    is JsonArray     -> element.map { jsonElementToNative(it) }
}
