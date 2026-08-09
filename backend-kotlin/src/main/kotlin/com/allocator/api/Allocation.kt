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
import com.allocator.services.TsaOverride
import com.allocator.services.buildAllocationBudgetRowsFull
import com.allocator.services.buildSupplyAllocation
import com.allocator.services.computeCriticalStockPositions
import com.allocator.services.hasAnyTargetedSupply
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.AllocationRoute")
private val KIND = ConfigVersionKind.CASEALLOC

// ── Targeted Supply Allocation (TSA) — repurposed `case_allocation` ─────────────
//
// `case_allocation` used to store the DERIVED (supply_lot, demand) -> qty_allocated budget grid,
// a snapshot of buildSupplyAllocation's own output that a user could hand-edit. That table had
// limited real usage: it's an intermediate step (post-BOM-walk), not something a user naturally
// reasons about editing directly, and any edit to it was disconnected from the actual inputs
// (qty/target per critical-material lot) that drive the real computation.
//
// This table is repurposed to instead store the INPUT side: one row per critical-material lot,
// carrying only the two fields a user actually wants to try out — [CaseAllocRow.qtyCap] (a cap
// override) and [CaseAllocRow.target] (customer earmark). Lead time is never stored here — it's
// always derived live from BOM structure (see [TsaOverride]'s own doc).
//
// The old derived grid still exists — it's just never persisted anymore. It's recomputed
// on-demand ([computeAllocationPreview]) from whatever TSA rows currently exist for a version,
// via the exact same [buildSupplyAllocation] pipeline a real plan run uses, so the preview always
// matches what planning would actually do with those inputs.

data class CaseAllocRow(val supplyId: String, val qtyCap: Double?, val target: String?)

/** One row of the read-only, recomputed (supply_lot, demand) -> qty_allocated preview grid — the
 *  same shape `case_allocation` used to persist, now produced on-demand by
 *  [computeAllocationPreview] instead. */
data class AllocPreviewRow(val supplyId: String, val demandId: String?, val qtyAllocated: Double)

/** Loads a version's TSA input rows. Returns null when none exist (an unedited version —
 *  planning proceeds with zero overrides, i.e. exactly pre-TSA behavior). */
internal fun loadCaseAllocRows(versionId: Int?): List<CaseAllocRow>? {
    if (versionId == null) return null
    return transaction {
        val rows = CaseAllocations.selectAll()
            .where { CaseAllocations.versionId eq versionId }
            .map { CaseAllocRow(it[CaseAllocations.supplyId], it[CaseAllocations.qtyCap], it[CaseAllocations.target]) }
        if (rows.isEmpty()) null else rows
    }
}

/** Converts a version's TSA rows into the override map [buildSupplyAllocation] takes directly. */
internal fun buildTsaOverridesFromCaseAlloc(rows: List<CaseAllocRow>): Map<String, TsaOverride> =
    rows.associate { it.supplyId to TsaOverride(qtyCap = it.qtyCap, target = it.target) }

/** The set of product ids covered by [rows] (a TSA version's stored input rows) — i.e. the
 *  critical-material set that version's rows were generated against. Compared against
 *  [computeCriticalPids][com.allocator.services.computeCriticalPids]'s live result at plan-submit
 *  time to detect a version that's gone stale (see call sites in Allocate.kt's
 *  `runPlanBackground`/`runOneBootstrapPreset`). */
internal fun caseAllocMaterialSet(
    rows: List<CaseAllocRow>,
    supplies: List<Map<String, Any?>>,
): Set<String> {
    val supplyToPid = supplies.associate { s ->
        (s["supply_id"] as? String)?.trim().orEmpty() to (s["product_id"] as? String)?.trim().orEmpty()
    }
    return rows.mapNotNull { supplyToPid[it.supplyId]?.takeIf { pid -> pid.isNotBlank() } }.toSet()
}

/** Runs [buildSupplyAllocation] with [tsaOverrides] applied and flattens the result into the same
 *  (supply_lot, demand) -> qty_allocated shape `case_allocation` used to persist — the read-only
 *  preview grid shown by the Allocation page's matrix view, and read by the two
 *  `PlanningAgentRoutes` tools that used to read stored rows directly. */
internal fun computeAllocationPreview(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    tsaOverrides: Map<String, TsaOverride>,
): List<AllocPreviewRow> {
    val demands = data["demand"] ?: emptyList()
    val result = buildSupplyAllocation(demands, data, config, tsaOverrides)
    return buildAllocationBudgetRowsFull(result.perLotBudgets, result.criticalMatrix, data["supply"] ?: emptyList())
        .map { (sid, did, qty) -> AllocPreviewRow(sid, did, qty) }
}

/** Recompute and persist case_allocation's content-hash fingerprint for [versionId] — call after
 *  any write to [CaseAllocations], inside the SAME transaction as the row mutation so hash and
 *  rows commit atomically. Re-reads the FULL current row set (not a delta) so PUT's partial
 *  row-by-row edits still produce a hash reflecting the true final table state. Read later by
 *  the KB signature's plan-submission fingerprint injection (CaseBootstrap.signatureFor /
 *  Allocate.kt's resolveEffectiveConfig), not by anything in the live planning path itself.
 *
 * Known accepted limitation: does not take a row lock before recomputing, so two genuinely
 * concurrent writers on the same version (double-click Save, two tabs) could each compute a hash
 * from a snapshot that doesn't include the other's edit, and the transaction that commits last
 * "wins" the hash column. Low-probability given the UI's stage-then-Save-once pattern; revisit
 * with a `SELECT ... FOR UPDATE` on the config row if this proves to matter in practice.
 */
private fun recomputeCaseAllocationHash(caseId: Int, versionId: Int) {
    val rows = CaseAllocations.selectAll().where { CaseAllocations.versionId eq versionId }
        .map { "${it[CaseAllocations.supplyId]}|${it[CaseAllocations.qtyCap] ?: ""}|${it[CaseAllocations.target] ?: ""}" }
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

/** [versionId] null means this case has never had a Targeted Supply Allocation version at all — a
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
 * The set of `supply_id`s that are genuinely RAW critical-material lots for the case's CURRENT
 * data/config — i.e. eligible for a direct TSA (qty_cap/target) override. Excludes critical-STOCK
 * positions (on-hand inventory of an otherwise-elastic, non-raw product that only INHERITS
 * targeting from its mandatory raw material — see
 * [com.allocator.services.computeCriticalStockPositions]'s own doc): their effective targeting
 * must stay entirely DERIVED, never set directly (see [generateDefaultTsaRows]'s own doc for why).
 * Shared by [generateDefaultTsaRows] (which supply_ids to seed) and the `/preview` route (which
 * cells the TSA table renders as editable vs read-only-derived).
 *
 * Empty for a case that has never used TARGET at all ([hasAnyTargetedSupply] false) — a case that
 * hasn't opted into targeting (e.g. case 173) keeps its raw critical materials fully read-only,
 * the same "intermediate allocation, not an input surface" treatment as critical stock, rather
 * than retroactively exposing every such case's raw lots as newly editable. Mirrors the exact
 * same case-level gate [buildSupplyAllocation] already uses to choose which per-lot allocator
 * runs (`allocateCriticalSuppliesPerLot` vs `allocateSuppliesPerLot`), so a case's TSA
 * editability can never disagree with which algorithm actually produced its numbers.
 */
internal fun rawCriticalSupplyIds(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Set<String> {
    if (!hasAnyTargetedSupply(data)) return emptySet()
    val demands = data["demand"] ?: emptyList()
    val result = buildSupplyAllocation(demands, data, config)
    val criticalStockKeys = computeCriticalStockPositions(data, config).keys
    val rawCriticalKeys = result.criticalMatrix.byColumn.keys - criticalStockKeys
    return (data["supply"] ?: emptyList()).mapNotNull { s ->
        val pid = (s["product_id"] as? String)?.trim() ?: return@mapNotNull null
        val lid = (s["location_id"] as? String)?.trim() ?: return@mapNotNull null
        if (SupplyKey(pid, lid) !in rawCriticalKeys) return@mapNotNull null
        (s["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
    }.toSet()
}

/**
 * Seeds [versionId]'s TSA rows with the case's CURRENT critical-material lots — one row per lot,
 * `qty_cap`/`target` copied verbatim from the lot's own physical qty/target (a no-op override,
 * identical to having none, but gives the UI real starting numbers to edit from instead of blank
 * inputs). Config is required for purchasable_materials filtering (which materials are critical
 * depends on it) — pass null only when no plan run has been run yet for the case.
 *
 * Only genuinely RAW critical-material lots qualify — `criticalMatrix.byColumn.keys` also
 * contains critical-STOCK positions (on-hand inventory of an otherwise-elastic, non-raw product
 * that merely INHERITS targeting from its mandatory raw material — see
 * [com.allocator.services.computeCriticalStockPositions]'s own doc), which are excluded here via
 * a set difference. A critical-stock lot's effective targeting must stay entirely DERIVED,
 * computed automatically inside `buildSupplyAllocation` — an explicit TSA override on one would
 * short-circuit `expandCriticalStockSupplies`'s inherited-target split (see its own doc: an
 * explicit target on a stock row is treated as the row's OWN, skipping the derived split
 * entirely — exactly the corruption this exclusion prevents).
 *
 * Deliberately does NOT check "is this version referenced" — see call sites: the Generate route
 * checks before calling this.
 */
/** Pure computation half of [generateDefaultTsaRows] — no DB writes. Split out so the Generate
 *  route can compute-and-return without persisting when no version is resolved yet (see that
 *  route's own doc: Generate must never silently create a version — only Save/Save As may). */
internal fun computeDefaultTsaRows(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<CaseAllocRow> {
    val rawCriticalIds = rawCriticalSupplyIds(data, config)
    return (data["supply"] ?: emptyList()).mapNotNull { s ->
        val sid = (s["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (sid !in rawCriticalIds) return@mapNotNull null
        val qty = (s["qty"] as? Number)?.toDouble() ?: return@mapNotNull null
        val target = (s["target"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        CaseAllocRow(sid, qty, target)
    }
}

internal fun generateDefaultTsaRows(
    caseId: Int,
    versionId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<CaseAllocRow> {
    val rows = computeDefaultTsaRows(data, config)
    if (rows.isNotEmpty()) {
        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.versionId eq versionId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]    = caseId
                this[CaseAllocations.versionId] = versionId
                this[CaseAllocations.supplyId]  = row.supplyId
                this[CaseAllocations.qtyCap]    = row.qtyCap
                this[CaseAllocations.target]    = row.target
            }
            recomputeCaseAllocationHash(caseId, versionId)
        }
    }
    return rows
}

/** Best-effort config for a route with no caller-supplied override in its own body schema —
 *  the latest successful plan run's config, so `purchasable_materials`/`purchase_allowed` (which
 *  affect critical-material classification) match what the case was actually last planned with.
 *  Returns null if the case has never had a successful plan run. */
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

/** Cheap, direct DB check for "does this case have any TARGETed supply row at all" — used as a
 *  route-layer early-exit BEFORE paying for a full `CaseLoader.load(caseId)` +
 *  [computeCriticalStockPositions] walk on every write, so a non-TARGET case (the common case)
 *  stays exactly as cheap as it was before this feature existed. */
private fun caseHasTargetedSupply(caseId: Int): Boolean = transaction {
    Supplies.select(Supplies.targetCustomerId)
        .where { Supplies.caseId eq caseId }
        .any { (it[Supplies.targetCustomerId])?.trim()?.isNotBlank() == true }
}

/**
 * Rejects any submitted TSA row whose `supply_id` is a critical-STOCK position — see
 * [generateDefaultTsaRows]'s own doc for why: a critical-stock lot's effective targeting must
 * stay entirely DERIVED from its mandatory raw material, never set directly. Returns
 * `(rows, emptyList())` unchanged whenever the case has no critical stock at all (the common
 * case, cheaply short-circuited by [computeCriticalStockPositions]'s own `hasAnyTargetedSupply`
 * gate before this is ever called from a route).
 */
internal fun filterOutCriticalStockRows(
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

    fun rowJson(row: CaseAllocRow): JsonObject = buildJsonObject {
        put("supply_id", row.supplyId)
        put("qty_cap", row.qtyCap)
        put("target", row.target)
    }

    fun previewRowJson(row: AllocPreviewRow): JsonObject = buildJsonObject {
        put("supply_id", row.supplyId)
        if (row.demandId != null) put("demand_id", row.demandId) else put("demand_id", JsonNull)
        put("qty_allocated", row.qtyAllocated)
    }

    /** Config: prefer caller-supplied `config`; fall back to latest plan run config so
     *  purchasable_materials filtering is always applied correctly. Shared by generate/preview. */
    suspend fun resolveConfigFromBody(call: ApplicationCall, caseId: Int): Map<String, Any?>? {
        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) null
                      else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val configJson = payload?.get("config")
        return if (configJson != null && configJson !is JsonNull) {
            @Suppress("UNCHECKED_CAST")
            runCatching { jsonElementToNative(configJson) as? Map<String, Any?> }.getOrNull()
        } else {
            latestPlanRunConfig(caseId).also { if (it != null) log.info("[allocation] using config from latest plan run for case {}", caseId) }
        }
    }

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
    // Returns the version's TSA input rows (qty_cap/target per critical-material lot) —
    // NOT the derived grid; see POST .../allocation/preview for that.
    get("/cases/{case_id}/allocation") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = (loadCaseAllocRows(versionId) ?: emptyList()).sortedBy { it.supplyId }
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map(::rowJson))); put("version", versionJson(caseId, versionId)) })
    }

    // ── POST /cases/{case_id}/allocation/generate ─────────────────────────────
    // Body: { config? } — seeds TSA rows for the case's current critical-material lots.
    // Deliberately does NOT auto-create a version when none is resolved — unlike PUT/import, which
    // DO (see resolvedOrCreatedVersionId's own doc: "the ONE place a version gets materialized on
    // demand... about to WRITE data"). Generate is a "try it out" action, not a write the user
    // asked for; silently persisting it as a new unnamed version surprised users who never
    // intended to keep it (confirmed live: Generate then "Save As" produced TWO versions — the
    // silent one plus the named one). When no version is resolved, this computes and returns rows
    // WITHOUT touching the DB at all; the frontend stages them exactly like a pending edit, and
    // the eventual Save/Save As is the one place a version gets created — consistent with every
    // other write path's existing "no version yet" contract.
    post("/cases/{case_id}/allocation/generate") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        if (versionId != null && CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")
        if ((data["supply"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No supply data for case $caseId")

        val config = resolveConfigFromBody(call, caseId)

        val newRows = if (versionId != null) {
            log.info("[allocation] generating TSA rows for case {} version {}", caseId, versionId)
            generateDefaultTsaRows(caseId, versionId, data, config)
        } else {
            log.info("[allocation] computing TSA rows for case {} (no version resolved — not persisted)", caseId)
            computeDefaultTsaRows(data, config)
        }
        log.info("[allocation] generated {} TSA rows for case {}", newRows.size, caseId)

        call.respond(buildJsonObject {
            put("rows", JsonArray(newRows.map(::rowJson)))
            put("version_id", versionId?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }

    // ── PUT /cases/{case_id}/allocation ───────────────────────────────────────
    // Body: { "rows": [{ "supply_id", "qty_cap"?, "target"? }] }
    // Upserts (insert-or-replace) the given rows; leaves other rows unchanged. A row whose
    // supply_id is a critical-stock position is rejected — see filterOutCriticalStockRows' own
    // doc — and reported back in "rejected".
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
                supplyId = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                qtyCap   = obj["qty_cap"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double,
                target   = obj["target"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
        }

        var rows = submittedRows
        var rejected: List<String> = emptyList()
        if (caseHasTargetedSupply(caseId)) {
            val data = transaction { CaseLoader.load(caseId) }
            val config = latestPlanRunConfig(caseId)
            val (editable, rej) = filterOutCriticalStockRows(submittedRows, data, config)
            rows = editable
            rejected = rej
        }

        transaction {
            for (row in rows) {
                val existing = CaseAllocations.selectAll().where {
                    (CaseAllocations.versionId eq versionId) and (CaseAllocations.supplyId eq row.supplyId)
                }.singleOrNull()
                if (existing != null) {
                    CaseAllocations.update({
                        (CaseAllocations.versionId eq versionId) and (CaseAllocations.supplyId eq row.supplyId)
                    }) {
                        it[CaseAllocations.qtyCap] = row.qtyCap
                        it[CaseAllocations.target] = row.target
                    }
                } else {
                    CaseAllocations.insert {
                        it[CaseAllocations.caseId]    = caseId
                        it[CaseAllocations.versionId] = versionId
                        it[CaseAllocations.supplyId]  = row.supplyId
                        it[CaseAllocations.qtyCap]    = row.qtyCap
                        it[CaseAllocations.target]    = row.target
                    }
                }
            }
            recomputeCaseAllocationHash(caseId, versionId)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject {
            put("updated", rows.size)
            put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
            put("version_id", versionId)
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
    // Body: CSV text with header row: supply_id,qty_cap,target
    // A row whose supply_id is a critical-stock position is dropped from the import — see
    // filterOutCriticalStockRows' own doc — and reported in "rejected".
    post("/cases/{case_id}/allocation/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val parsedRows = parseCsvTsa(csvText)

        var rows = parsedRows
        var rejected: List<String> = emptyList()
        if (caseHasTargetedSupply(caseId)) {
            val data = transaction { CaseLoader.load(caseId) }
            val config = latestPlanRunConfig(caseId)
            val (editable, rej) = filterOutCriticalStockRows(parsedRows, data, config)
            rows = editable
            rejected = rej
        }
        log.info("[allocation] importing {} TSA rows for case {} version {} ({} rejected as critical-stock)",
            rows.size, caseId, versionId, rejected.size)

        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.versionId eq versionId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]    = caseId
                this[CaseAllocations.versionId] = versionId
                this[CaseAllocations.supplyId]  = row.supplyId
                this[CaseAllocations.qtyCap]    = row.qtyCap
                this[CaseAllocations.target]    = row.target
            }
            recomputeCaseAllocationHash(caseId, versionId)
        }
        call.respond(buildJsonObject {
            put("rows", JsonArray(rows.map(::rowJson)))
            put("rejected", JsonArray(rejected.map { JsonPrimitive(it) }))
            put("version_id", versionId)
        })
    }

    // ── GET /cases/{case_id}/allocation/export ────────────────────────────────
    get("/cases/{case_id}/allocation/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = (loadCaseAllocRows(versionId) ?: emptyList()).sortedBy { it.supplyId }
        val sb = StringBuilder("supply_id,qty_cap,target\n")
        for (row in rows) {
            sb.append(csvEscape(row.supplyId))
            sb.append(',')
            sb.append(row.qtyCap?.toString() ?: "")
            sb.append(',')
            sb.append(row.target?.let { csvEscape(it) } ?: "")
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"targeted_supply_allocation_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── POST /cases/{case_id}/allocation/preview ──────────────────────────────
    // Body: { config?, rows?: [{ supply_id, qty_cap?, target? }] }
    // Recomputes the read-only (supply_lot, demand) -> qty_allocated grid via buildSupplyAllocation
    // using the given `rows` as TSA overrides — pending, unsaved UI edits by default, falling back
    // to the resolved version's own saved rows when `rows` is omitted. Never persists anything.
    post("/cases/{case_id}/allocation/preview") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) null else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

        val pendingRowsJson = payload?.get("rows")?.takeIf { it !is JsonNull }?.jsonArray
        val tsaRows = if (pendingRowsJson != null) {
            pendingRowsJson.map { el ->
                val obj = el.jsonObject
                CaseAllocRow(
                    supplyId = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                    qtyCap   = obj["qty_cap"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double,
                    target   = obj["target"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                )
            }
        } else {
            loadCaseAllocRows(versionId) ?: emptyList()
        }

        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")

        val configJson = payload?.get("config")
        val config = if (configJson != null && configJson !is JsonNull) {
            @Suppress("UNCHECKED_CAST")
            runCatching { jsonElementToNative(configJson) as? Map<String, Any?> }.getOrNull()
        } else {
            latestPlanRunConfig(caseId)
        }

        // Same critical-stock exclusion as PUT/import/versions — a stray override on a
        // stock-derived lot would corrupt its inherited split (see filterOutCriticalStockRows).
        val (editableTsaRows, _) = filterOutCriticalStockRows(tsaRows, data, config)
        val previewRows = computeAllocationPreview(data, config, buildTsaOverridesFromCaseAlloc(editableTsaRows))
        // Tells the frontend TSA table which supply_ids to render as directly editable (raw
        // critical lots) vs read-only/derived (critical stock, plus anything not critical at
        // all) — see rawCriticalSupplyIds' own doc.
        val rawCriticalIds = rawCriticalSupplyIds(data, config)
        call.respond(buildJsonObject {
            put("rows", JsonArray(previewRows.sortedWith(compareBy({ it.supplyId }, { it.demandId ?: "" })).map(::previewRowJson)))
            put("version", versionJson(caseId, versionId))
            putJsonArray("raw_critical_supply_ids") { rawCriticalIds.sorted().forEach { add(it) } }
        })
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
    // Body: { name?, comments?, rows: [{ supply_id, qty_cap?, target? }] }
    // Any submitted row whose supply_id is a critical-stock position is dropped — see
    // filterOutCriticalStockRows' own doc.
    post("/cases/{case_id}/allocation/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val submittedRows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { el ->
            val obj = el.jsonObject
            CaseAllocRow(
                supplyId = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                qtyCap   = obj["qty_cap"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double,
                target   = obj["target"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
        }

        var rows = submittedRows
        if (caseHasTargetedSupply(caseId)) {
            val data = transaction { CaseLoader.load(caseId) }
            val config = latestPlanRunConfig(caseId)
            rows = filterOutCriticalStockRows(submittedRows, data, config).first
        }

        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseAllocations.batchInsert(rows) { row ->
                    this[CaseAllocations.caseId]    = caseId
                    this[CaseAllocations.versionId] = newId
                    this[CaseAllocations.supplyId]  = row.supplyId
                    this[CaseAllocations.qtyCap]    = row.qtyCap
                    this[CaseAllocations.target]    = row.target
                }
            }
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

private fun parseCsvTsa(csv: String): List<CaseAllocRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val supplyIdx = header.indexOf("supply_id")
    val qtyIdx    = header.indexOfFirst { it == "qty_cap" || it == "qty" }
    val targetIdx = header.indexOf("target")
    if (supplyIdx < 0) throw IllegalArgumentException("CSV must have a supply_id column")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val supplyId = cols.getOrNull(supplyIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val qty = if (qtyIdx >= 0) cols.getOrNull(qtyIdx)?.trim()?.toDoubleOrNull() else null
        val target = if (targetIdx >= 0) cols.getOrNull(targetIdx)?.trim()?.takeIf { it.isNotBlank() } else null
        CaseAllocRow(supplyId, qty, target)
    }
}

private fun csvEscape(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
