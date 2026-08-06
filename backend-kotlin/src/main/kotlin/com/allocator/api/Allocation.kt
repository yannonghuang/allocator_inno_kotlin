package com.allocator.api

import com.allocator.CaseAllocationConfigs
import com.allocator.CaseAllocations
import com.allocator.Cases
import com.allocator.Demands
import com.allocator.PlanRuns
import com.allocator.Supplies
import com.allocator.services.CaseConfigVersioning
import com.allocator.services.CaseLoader
import com.allocator.services.ConfigVersionKind
import com.allocator.services.KbFingerprint
import com.allocator.services.SupplyKey
import com.allocator.services.allocateCriticalSuppliesPerLot
import com.allocator.services.buildAllocationBudgetRowsFull
import com.allocator.services.buildBomGraph
import com.allocator.services.buildReachabilityMatrix
import com.allocator.services.buildSupplyAllocation
import com.allocator.services.collapseCriticalStockBudgets
import com.allocator.services.computeCriticalStockPositions
import com.allocator.services.expandCriticalStockSupplies
import com.allocator.services.hasAnyTargetedSupply
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.AllocationRoute")
private val KIND = ConfigVersionKind.CASEALLOC

// ── Helper: load case_allocation rows as perLotBudgets ───────────────────────

data class CaseAllocRow(val supplyId: String, val demandId: String?, val qtyAllocated: Double)

/** Loads case_allocation rows for [versionId]. Returns null when none exist (planning falls back
 *  to SupplyAllocator). */
internal fun loadCaseAllocRows(versionId: Int?): List<CaseAllocRow>? {
    if (versionId == null) return null
    return transaction {
        val rows = CaseAllocations.selectAll()
            .where { CaseAllocations.versionId eq versionId }
            .map { CaseAllocRow(it[CaseAllocations.supplyId], it[CaseAllocations.demandId], it[CaseAllocations.qtyAllocated]) }
        if (rows.isEmpty()) null else rows
    }
}

/**
 * Converts case_allocation rows into the perLotBudgets format used by planning:
 * demandId → { "$pid|$lid|$supplyId" → qty, "$pid|$lid" → qty (aggregate) }.
 *
 * Requires [supplies] from case data to look up product+location for each supply_id.
 */
internal fun buildBudgetsFromCaseAlloc(
    rows: List<CaseAllocRow>,
    supplies: List<Map<String, Any?>>,
): Map<Any?, MutableMap<String, Double>> {
    val supplyMeta = supplies.associate { s ->
        val sid = (s["supply_id"] as? String)?.trim() ?: ""
        val pid = (s["product_id"] as? String)?.trim() ?: ""
        val lid = (s["location_id"] as? String)?.trim() ?: ""
        sid to (pid to lid)
    }
    val result = mutableMapOf<Any?, MutableMap<String, Double>>()
    for (row in rows) {
        val (pid, lid) = supplyMeta[row.supplyId] ?: continue
        if (pid.isBlank() || lid.isBlank()) continue
        val aggKey = "$pid|$lid"
        val lotKey = "$aggKey|${row.supplyId}"
        val budget = result.getOrPut(row.demandId) { mutableMapOf() }
        budget[lotKey] = (budget[lotKey] ?: 0.0) + row.qtyAllocated
        budget[aggKey] = (budget[aggKey] ?: 0.0) + row.qtyAllocated
    }
    return result
}

/** The set of product ids covered by [rows] (an allocation version's stored rows) — i.e. the
 *  critical-material set that version was generated for, based on the actual supply lots it
 *  budgeted. Compared against [computeCriticalPids][com.allocator.services.computeCriticalPids]'s
 *  live result at plan-submit time to detect a version that's gone stale (see call sites in
 *  Allocate.kt's `runPlanBackground`/`runOneBootstrapPreset`). */
internal fun caseAllocMaterialSet(
    rows: List<CaseAllocRow>,
    supplies: List<Map<String, Any?>>,
): Set<String> {
    val supplyToPid = supplies.associate { s ->
        (s["supply_id"] as? String)?.trim().orEmpty() to (s["product_id"] as? String)?.trim().orEmpty()
    }
    return rows.mapNotNull { supplyToPid[it.supplyId]?.takeIf { pid -> pid.isNotBlank() } }.toSet()
}

/**
 * Recompute and persist case_allocation's content-hash fingerprint for [versionId] — call after
 * any write to [CaseAllocations], inside the SAME transaction as the row mutation so hash and
 * rows commit atomically. Re-reads the FULL current row set (not a delta) so PUT's partial
 * row-by-row edits still produce a hash reflecting the true final table state. Read later by
 * the KB signature's plan-submission fingerprint injection (CaseBootstrap.signatureFor /
 * Allocate.kt's resolveEffectiveConfig), not by anything in the live planning path itself.
 *
 * Known accepted limitation: does not take a row lock before recomputing, so two genuinely
 * concurrent writers on the same version (double-click Save, two tabs) could each compute a hash
 * from a snapshot that doesn't include the other's edit, and the transaction that commits last
 * "wins" the hash column. Low-probability given the UI's stage-then-Save-once pattern; revisit
 * with a `SELECT ... FOR UPDATE` on the config row if this proves to matter in practice.
 */
private fun recomputeCaseAllocationHash(caseId: Int, versionId: Int) {
    val rows = CaseAllocations.selectAll().where { CaseAllocations.versionId eq versionId }
        .map { "${it[CaseAllocations.supplyId]}|${it[CaseAllocations.demandId] ?: ""}|${it[CaseAllocations.qtyAllocated]}" }
    val hash = KbFingerprint.hashRows(rows)
    val existing = CaseAllocationConfigs.selectAll().where { CaseAllocationConfigs.versionId eq versionId }.firstOrNull()
    if (existing != null) {
        CaseAllocationConfigs.update({ CaseAllocationConfigs.versionId eq versionId }) {
            it[CaseAllocationConfigs.contentHash] = hash
        }
    } else {
        CaseAllocationConfigs.insert {
            it[CaseAllocationConfigs.caseId] = caseId
            it[CaseAllocationConfigs.versionId] = versionId
            it[CaseAllocationConfigs.contentHash] = hash
        }
    }
}

/** [versionId] null means this case has never had a Critical Material Allocation version at all — a
 *  legitimate "nothing yet" state (see CaseConfigVersioning's own doc), not an error. */
private fun versionJson(caseId: Int, versionId: Int?): JsonObject {
    val summary = versionId?.let { vid -> CaseConfigVersioning.listVersions(caseId, KIND).firstOrNull { it.id == vid } }
    return buildJsonObject {
        put("id", versionId)
        put("name", summary?.name)
        put("comments", summary?.comments)
        put("referenced", summary?.referenced ?: false)
    }
}

/**
 * Run the supply allocation for [caseId]/[versionId] using [data] and [config], persist the
 * result into that version's rows, and return the saved rows. Config is required for
 * purchasable_materials filtering — pass null only when no plan run has been run yet for the
 * case. Deliberately does NOT check "is this version referenced" — see call sites: the Generate
 * route checks before calling this; `runPlanBackground`'s auto-seed-on-first-run path is an
 * internal write performed by the very run that just referenced this version, not a user-
 * initiated edit of pre-existing data, so it must stay unconditional (matches this function's
 * pre-versioning behavior exactly).
 *
 * This is the single shared implementation used by both the Generate endpoint and the
 * plan-run seeding path in runPlanBackground.
 *
 * Already includes critical-stock rows with no extra work: [buildSupplyAllocation]'s own
 * `criticalMatrix`/`perLotBudgets` are critical-stock-aware (see `computeCriticalStockPositions`),
 * so `buildAllocationBudgetRowsFull` below picks them up for free, split against each mandatory raw
 * material's own physical lot quantities (no persisted allocation exists yet on a fresh generate —
 * see `expandCriticalStockSupplies`'s fallback). Only the manual-edit routes (`PUT`/`import`/
 * `versions`) need [recomputeCriticalStockAllocationRows] — they can leave a case's raw-material
 * rows in a state this function never produced (e.g. rebalanced away from physical-lot
 * proportions), which the derived stock split must then follow.
 */
internal fun generateAndSeedCaseAllocation(
    caseId: Int,
    versionId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<CaseAllocRow> {
    val demands = data["demand"] ?: emptyList()
    val result  = buildSupplyAllocation(demands, data, config)
    val rows    = buildAllocationBudgetRowsFull(result.perLotBudgets, result.criticalMatrix, data["supply"] ?: emptyList())
                      .map { (sid, did, qty) -> CaseAllocRow(sid, did, qty) }
    if (rows.isNotEmpty()) {
        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.versionId eq versionId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]       = caseId
                this[CaseAllocations.versionId]    = versionId
                this[CaseAllocations.supplyId]     = row.supplyId
                this[CaseAllocations.demandId]     = row.demandId
                this[CaseAllocations.qtyAllocated] = row.qtyAllocated
            }
            recomputeCaseAllocationHash(caseId, versionId)
        }
    }
    return rows
}

// ── Critical stock: derived, read-only rows ─────────────────────────────────────

/** Cheap, direct DB check for "does this case have any TARGETed supply row at all" — used as the
 *  route-layer early-exit BEFORE paying for a full `CaseLoader.load(caseId)` + critical-stock
 *  detection walk on every `PUT`/`import`/`versions` write, so a non-TARGET case (e.g. case 173)
 *  stays exactly as cheap as it was before this feature existed. Mirrors
 *  [hasAnyTargetedSupply][com.allocator.services.hasAnyTargetedSupply]'s in-memory check exactly,
 *  just queried directly (one column, one case) instead of against an already-loaded case map. */
private fun caseHasTargetedSupply(caseId: Int): Boolean = transaction {
    Supplies.select(Supplies.targetCustomerId)
        .where { Supplies.caseId eq caseId }
        .any { (it[Supplies.targetCustomerId])?.trim()?.isNotBlank() == true }
}

/** Best-effort config for a route with no caller-supplied override in its own body schema (`PUT`/
 *  `import`/`versions` all take rows/CSV, not a config blob) — the latest successful plan run's
 *  config, same fallback the Generate route already uses when its own optional `config` field is
 *  absent, so `purchasable_materials`/`purchase_allowed` (which affect both critical-material and
 *  critical-stock classification) match what the case was actually last planned with. Returns null
 *  if the case has never had a successful plan run — detection still runs, just without any
 *  purchasable-whitelist narrowing. */
private fun latestPlanRunConfig(caseId: Int): Map<String, Any?>? {
    val latestConfigJson = transaction {
        PlanRuns.select(PlanRuns.config)
            .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
            .orderBy(PlanRuns.id to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(PlanRuns.config)
    }
    return latestConfigJson?.let {
        @Suppress("UNCHECKED_CAST")
        runCatching { jsonElementToNative(Json.parseToJsonElement(it)) as? Map<String, Any?> }.getOrNull()
    }
}

/**
 * Partitions submitted `case_allocation` rows into ones a caller may actually write ([Pair.first])
 * and ones that belong to a derived, read-only critical-stock position ([Pair.second] — the
 * stripped `supply_id`s). Critical-stock rows can never be directly edited: they're a deterministic
 * function of their mandatory raw material's OWN current allocation (see
 * [recomputeCriticalStockAllocationRows]), so a direct edit would just be silently overwritten (or
 * worse, drift out of sync) the next time anything touches that raw material's rows. Returns
 * `(rows, emptyList())` unchanged whenever the case has no critical stock at all — the common case.
 */
internal fun partitionEditableAllocationRows(
    rows: List<CaseAllocRow>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Pair<List<CaseAllocRow>, List<String>> {
    val criticalStocks = computeCriticalStockPositions(data, config)
    if (criticalStocks.isEmpty()) return rows to emptyList()
    val supplyToKey: Map<String, SupplyKey> = (data["supply"] ?: emptyList()).mapNotNull { row ->
        val sid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
        val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
        sid to SupplyKey(pid, lid)
    }.toMap()
    val (rejected, editable) = rows.partition { row -> supplyToKey[row.supplyId]?.let { it in criticalStocks } == true }
    return editable to rejected.map { it.supplyId }.distinct()
}

/**
 * Computes and overwrites [versionId]'s critical-stock `case_allocation` rows, reading that
 * version's JUST-PERSISTED raw-material rows (whatever the caller wrote immediately before calling
 * this, in the SAME transaction) as the Ramification-1 split weight — see
 * `expandCriticalStockSupplies`'s own doc for why this (a lot's CURRENT effective allocation, not
 * its static physical qty) is the correct weight once any manual edit could have happened. Must be
 * called inside a transaction that already wrote whatever raw-material change triggered this — does
 * NOT open its own.
 *
 * Always clears this version's existing critical-stock rows first, even when there's nothing to
 * re-derive (e.g. the triggering edit zeroed out the raw material's own allocation entirely) — a
 * stale split must never linger. Reuses the exact same demand-competition machinery
 * [buildSupplyAllocation] uses ([allocateCriticalSuppliesPerLot] over a
 * [buildReachabilityMatrix]/[expandCriticalStockSupplies] pair restricted to critical-stock
 * `SupplyKey`s only) plus the same [collapseCriticalStockBudgets] step, so a version's derived rows
 * are always byte-identical to what a live plan run would enforce for the same raw-material state.
 */
internal fun recomputeCriticalStockAllocationRows(
    caseId: Int,
    versionId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<CaseAllocRow> {
    val criticalStocks = computeCriticalStockPositions(data, config)

    val stockSupplyIds: Set<String> = (data["supply"] ?: emptyList()).mapNotNull { row ->
        val pid = (row["product_id"] as? String)?.trim()
        val lid = (row["location_id"] as? String)?.trim()
        val sid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        if (pid != null && lid != null && sid != null && SupplyKey(pid, lid) in criticalStocks) sid else null
    }.toSet()
    if (stockSupplyIds.isNotEmpty()) {
        CaseAllocations.deleteWhere {
            (CaseAllocations.versionId eq versionId) and (CaseAllocations.supplyId inList stockSupplyIds)
        }
    }
    if (criticalStocks.isEmpty()) return emptyList()

    val rawLotAllocatedTotals: Map<String, Double> = CaseAllocations.selectAll()
        .where { CaseAllocations.versionId eq versionId }
        .groupBy({ it[CaseAllocations.supplyId] }, { it[CaseAllocations.qtyAllocated] })
        .mapValues { (_, qtys) -> qtys.sum() }

    val demands = data["demand"] ?: emptyList()
    val graph = buildBomGraph(demands, data)
    val criticalStockMatrix = buildReachabilityMatrix(
        demands, graph, criticalPids = emptySet(), criticalStockKeys = criticalStocks.keys,
    )
    val (expandedSupplies, virtualToReal) = expandCriticalStockSupplies(
        supplies              = data["supply"] ?: emptyList(),
        criticalStocks        = criticalStocks,
        rawLotAllocatedTotals = rawLotAllocatedTotals,
        data                  = data,
        config                = config,
    )
    val rawBudgets = allocateCriticalSuppliesPerLot(
        matrix = criticalStockMatrix, supplies = expandedSupplies, demands = demands, data = data,
    )
    val collapsedBudgets = collapseCriticalStockBudgets(rawBudgets, virtualToReal)
    val rows = buildAllocationBudgetRowsFull(collapsedBudgets, criticalStockMatrix, data["supply"] ?: emptyList())
        .map { (sid, did, qty) -> CaseAllocRow(sid, did, qty) }

    if (rows.isNotEmpty()) {
        CaseAllocations.batchInsert(rows) { row ->
            this[CaseAllocations.caseId]       = caseId
            this[CaseAllocations.versionId]    = versionId
            this[CaseAllocations.supplyId]     = row.supplyId
            this[CaseAllocations.demandId]     = row.demandId
            this[CaseAllocations.qtyAllocated] = row.qtyAllocated
        }
    }
    return rows
}

// ── Routes ────────────────────────────────────────────────────────────────────

fun Routing.allocationRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    fun requireCaseId(call: ApplicationCall): Int {
        val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        return caseId
    }

    // Read paths (GET/DELETE-clear/export): never creates. generate/PUT/import (real writes) use
    // resolvedOrCreatedVersionId instead — the one legitimate create-on-demand moment.
    fun resolvedVersionId(call: ApplicationCall, caseId: Int): Int? =
        CaseConfigVersioning.resolveVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

    fun resolvedOrCreatedVersionId(call: ApplicationCall, caseId: Int): Int =
        CaseConfigVersioning.resolveOrCreateVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

    // ── GET /cases/{case_id}/demands ─────────────────────────────────────────
    get("/cases/{case_id}/demands") {
        val caseId = requireCaseId(call)
        val rows = transaction {
            Demands.selectAll().where { Demands.caseId eq caseId }
                .orderBy(Demands.demandId to SortOrder.ASC)
                .map { row ->
                    buildJsonObject {
                        put("demand_id",   row[Demands.demandId])
                        put("customer_id", row[Demands.customerId])
                        put("product_id",  row[Demands.productId])
                        val lid = row[Demands.locationId]; if (lid != null) put("location_id", lid) else put("location_id", JsonNull)
                        put("quantity",    row[Demands.quantity])
                        val dt = row[Demands.requestDueTime]; if (dt != null) put("request_due_time", dt) else put("request_due_time", JsonNull)
                    }
                }
        }
        call.respond(buildJsonObject { put("demands", JsonArray(rows)) })
    }

    // ── GET /cases/{case_id}/allocation ──────────────────────────────────────
    get("/cases/{case_id}/allocation") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = (loadCaseAllocRows(versionId) ?: emptyList())
            .sortedWith(compareBy({ it.supplyId }, { it.demandId ?: "" }))
            .map { row ->
                buildJsonObject {
                    put("supply_id", row.supplyId)
                    if (row.demandId != null) put("demand_id", row.demandId) else put("demand_id", JsonNull)
                    put("qty_allocated", row.qtyAllocated)
                }
            }
        call.respond(buildJsonObject { put("rows", JsonArray(rows)); put("version", versionJson(caseId, versionId)) })
    }

    // ── POST /cases/{case_id}/allocation/generate ─────────────────────────────
    post("/cases/{case_id}/allocation/generate") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")
        if ((data["supply"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No supply data for case $caseId")

        // Config: prefer caller-supplied; fall back to latest plan run config so
        // purchasable_materials filtering is always applied correctly.
        val config: Map<String, Any?>? = run {
            val body = runCatching { call.receiveText() }.getOrElse { "" }
            val payload = if (body.isBlank()) null
                          else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            val configJson = payload?.get("config")
            if (configJson != null && configJson !is JsonNull) {
                @Suppress("UNCHECKED_CAST")
                runCatching { jsonElementToNative(configJson) as? Map<String, Any?> }.getOrNull()
            } else {
                val latestConfigJson = transaction {
                    PlanRuns.select(PlanRuns.config)
                        .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                        .orderBy(PlanRuns.id to SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        ?.get(PlanRuns.config)
                }
                latestConfigJson?.let {
                    @Suppress("UNCHECKED_CAST")
                    runCatching { jsonElementToNative(Json.parseToJsonElement(it)) as? Map<String, Any?> }.getOrNull()
                }.also { if (it != null) log.info("[allocation] using config from latest plan run for case {}", caseId) }
            }
        }

        log.info("[allocation] generating allocation for case {} version {}", caseId, versionId)
        val newRows = generateAndSeedCaseAllocation(caseId, versionId, data, config)
        log.info("[allocation] generated {} rows for case {} ({} critical supply lots)", newRows.size, caseId, newRows.map { it.supplyId }.distinct().size)

        val responseRows = newRows.map { row ->
            buildJsonObject {
                put("supply_id", row.supplyId)
                if (row.demandId != null) put("demand_id", row.demandId) else put("demand_id", JsonNull)
                put("qty_allocated", row.qtyAllocated)
            }
        }
        call.respond(buildJsonObject { put("rows", JsonArray(responseRows)) })
    }

    // ── PUT /cases/{case_id}/allocation ───────────────────────────────────────
    // Body: { "rows": [{ "supply_id", "demand_id", "qty_allocated" }] }
    // Upserts (insert-or-replace) the given rows; leaves other rows unchanged. A row whose
    // supply_id is a derived critical-stock position is rejected — see
    // recomputeCriticalStockAllocationRows's own doc — and reported back in "rejected".
    put("/cases/{case_id}/allocation") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@put
        }
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val submittedRows = rowsJson.map { el ->
            val obj = el.jsonObject
            CaseAllocRow(
                supplyId     = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                demandId     = obj["demand_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                qtyAllocated = obj["qty_allocated"]?.jsonPrimitive?.double ?: throw IllegalArgumentException("Missing qty_allocated"),
            )
        }

        // Cheap DB-only check first: a non-TARGET case (e.g. case 173) never loads case data or
        // runs critical-stock detection at all, staying exactly as cheap as before this feature.
        var rows = submittedRows
        var rejected: List<String> = emptyList()
        var caseData: Map<String, List<Map<String, Any?>>>? = null
        var caseConfig: Map<String, Any?>? = null
        if (caseHasTargetedSupply(caseId)) {
            caseData = transaction { CaseLoader.load(caseId) }
            caseConfig = latestPlanRunConfig(caseId)
            val (editable, rej) = partitionEditableAllocationRows(submittedRows, caseData, caseConfig)
            rows = editable
            rejected = rej
        }

        transaction {
            for (row in rows) {
                val existing = CaseAllocations.selectAll().where {
                    (CaseAllocations.versionId eq versionId) and
                    (CaseAllocations.supplyId eq row.supplyId) and
                    (if (row.demandId != null) CaseAllocations.demandId eq row.demandId else CaseAllocations.demandId.isNull())
                }.singleOrNull()
                if (existing != null) {
                    CaseAllocations.update({
                        (CaseAllocations.versionId eq versionId) and
                        (CaseAllocations.supplyId eq row.supplyId) and
                        (if (row.demandId != null) CaseAllocations.demandId eq row.demandId else CaseAllocations.demandId.isNull())
                    }) { it[CaseAllocations.qtyAllocated] = row.qtyAllocated }
                } else {
                    CaseAllocations.insert {
                        it[CaseAllocations.caseId]       = caseId
                        it[CaseAllocations.versionId]    = versionId
                        it[CaseAllocations.supplyId]     = row.supplyId
                        it[CaseAllocations.demandId]     = row.demandId
                        it[CaseAllocations.qtyAllocated] = row.qtyAllocated
                    }
                }
            }
            val cd = caseData
            if (cd != null) recomputeCriticalStockAllocationRows(caseId, versionId, cd, caseConfig)
            recomputeCaseAllocationHash(caseId, versionId)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject {
            put("updated", rows.size)
            put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
        })
    }

    // ── DELETE /cases/{case_id}/allocation ────────────────────────────────────
    // "Clear" — empties the resolved version's rows (does not delete the version itself; use
    // DELETE .../versions/{id} for that).
    delete("/cases/{case_id}/allocation") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        if (versionId == null) {
            call.respond(buildJsonObject { put("deleted", 0) })
            return@delete
        }
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@delete
        }
        val deleted = transaction {
            val n = CaseAllocations.deleteWhere { CaseAllocations.versionId eq versionId }
            recomputeCaseAllocationHash(caseId, versionId)
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/allocation/import ───────────────────────────────
    // Body: CSV text with header row: supply_id,demand_id,qty_allocated
    // Any row whose supply_id is a derived critical-stock position is dropped from the import —
    // see recomputeCriticalStockAllocationRows's own doc — and reported in "rejected".
    post("/cases/{case_id}/allocation/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val parsedRows = parseCsvAllocation(csvText)

        var rows = parsedRows
        var rejected: List<String> = emptyList()
        var caseData: Map<String, List<Map<String, Any?>>>? = null
        var caseConfig: Map<String, Any?>? = null
        if (caseHasTargetedSupply(caseId)) {
            caseData = transaction { CaseLoader.load(caseId) }
            caseConfig = latestPlanRunConfig(caseId)
            val (editable, rej) = partitionEditableAllocationRows(parsedRows, caseData, caseConfig)
            rows = editable
            rejected = rej
        }
        log.info("[allocation] importing {} rows for case {} version {} ({} rejected as critical-stock)",
            rows.size, caseId, versionId, rejected.size)

        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.versionId eq versionId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]       = caseId
                this[CaseAllocations.versionId]    = versionId
                this[CaseAllocations.supplyId]     = row.supplyId
                this[CaseAllocations.demandId]     = row.demandId
                this[CaseAllocations.qtyAllocated] = row.qtyAllocated
            }
            val cd = caseData
            if (cd != null) recomputeCriticalStockAllocationRows(caseId, versionId, cd, caseConfig)
            recomputeCaseAllocationHash(caseId, versionId)
        }
        val responseRows = rows.map { row ->
            buildJsonObject {
                put("supply_id", row.supplyId)
                if (row.demandId != null) put("demand_id", row.demandId) else put("demand_id", JsonNull)
                put("qty_allocated", row.qtyAllocated)
            }
        }
        call.respond(buildJsonObject {
            put("rows", JsonArray(responseRows))
            put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
        })
    }

    // ── GET /cases/{case_id}/allocation/export ────────────────────────────────
    get("/cases/{case_id}/allocation/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = (loadCaseAllocRows(versionId) ?: emptyList())
            .sortedWith(compareBy({ it.supplyId }, { it.demandId ?: "" }))
        val sb = StringBuilder("supply_id,demand_id,qty_allocated\n")
        for (row in rows) {
            sb.append(csvEscape(row.supplyId))
            sb.append(',')
            sb.append(if (row.demandId != null) csvEscape(row.demandId) else "")
            sb.append(',')
            sb.append(row.qtyAllocated)
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"allocation_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── GET /cases/{case_id}/allocation/versions ──────────────────────────────
    get("/cases/{case_id}/allocation/versions") {
        val caseId = requireCaseId(call)
        val versions = CaseConfigVersioning.listVersions(caseId, KIND)
        call.respond(buildJsonObject {
            put("versions", JsonArray(versions.map { v ->
                buildJsonObject {
                    put("id", v.id); put("name", v.name); put("comments", v.comments)
                    put("referenced", v.referenced)
                    put("created_at", v.createdAt); put("updated_at", v.updatedAt)
                }
            }))
        })
    }

    // ── POST /cases/{case_id}/allocation/versions ("Save As") ─────────────────
    // Body: { name?, comments?, rows: [{ supply_id, demand_id, qty_allocated }] }
    // Any submitted row whose supply_id is a derived critical-stock position is dropped — see
    // recomputeCriticalStockAllocationRows's own doc — and the new version's own derived rows are
    // computed fresh instead.
    post("/cases/{case_id}/allocation/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val submittedRows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { el ->
            val obj = el.jsonObject
            CaseAllocRow(
                supplyId     = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                demandId     = obj["demand_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                qtyAllocated = obj["qty_allocated"]?.jsonPrimitive?.double ?: throw IllegalArgumentException("Missing qty_allocated"),
            )
        }

        var rows = submittedRows
        var caseData: Map<String, List<Map<String, Any?>>>? = null
        var caseConfig: Map<String, Any?>? = null
        if (caseHasTargetedSupply(caseId)) {
            caseData = transaction { CaseLoader.load(caseId) }
            caseConfig = latestPlanRunConfig(caseId)
            rows = partitionEditableAllocationRows(submittedRows, caseData, caseConfig).first
        }

        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseAllocations.batchInsert(rows) { row ->
                    this[CaseAllocations.caseId]       = caseId
                    this[CaseAllocations.versionId]    = newId
                    this[CaseAllocations.supplyId]     = row.supplyId
                    this[CaseAllocations.demandId]     = row.demandId
                    this[CaseAllocations.qtyAllocated] = row.qtyAllocated
                }
            }
            val cd = caseData
            if (cd != null) recomputeCriticalStockAllocationRows(caseId, newId, cd, caseConfig)
            recomputeCaseAllocationHash(caseId, newId)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/allocation/versions/{version_id} ─────────────────
    // Body: { name?, comments? } — rename, always allowed.
    put("/cases/{case_id}/allocation/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/allocation/versions/{version_id} ──────────────
    delete("/cases/{case_id}/allocation/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        try {
            CaseConfigVersioning.deleteVersion(caseId, KIND, versionId)
            call.respond(HttpStatusCode.OK, buildJsonObject { put("deleted", true) })
        } catch (e: CaseConfigVersioning.VersionInUseException) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
        }
    }
}

// ── CSV helpers ───────────────────────────────────────────────────────────────

private fun parseCsvAllocation(csv: String): List<CaseAllocRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val supplyIdx = header.indexOf("supply_id")
    val demandIdx = header.indexOf("demand_id")
    val qtyIdx    = header.indexOfFirst { it == "qty_allocated" || it == "qty" }
    if (supplyIdx < 0 || qtyIdx < 0) throw IllegalArgumentException("CSV must have supply_id and qty_allocated columns")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val supplyId = cols.getOrNull(supplyIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val demandId = if (demandIdx >= 0) cols.getOrNull(demandIdx)?.trim()?.takeIf { it.isNotBlank() } else null
        val qty = cols.getOrNull(qtyIdx)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
        CaseAllocRow(supplyId, demandId, qty)
    }
}

private fun csvEscape(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
