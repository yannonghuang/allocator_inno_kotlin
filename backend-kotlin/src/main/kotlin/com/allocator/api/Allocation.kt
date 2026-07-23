package com.allocator.api

import com.allocator.CaseAllocationConfigs
import com.allocator.CaseAllocations
import com.allocator.Cases
import com.allocator.Demands
import com.allocator.PlanRuns
import com.allocator.services.CaseLoader
import com.allocator.services.KbFingerprint
import com.allocator.services.buildAllocationBudgetRows
import com.allocator.services.buildSupplyAllocation
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

// ── Helper: load case_allocation rows as perLotBudgets ───────────────────────

data class CaseAllocRow(val supplyId: String, val demandId: String?, val qtyAllocated: Double)

/** Loads case_allocation rows for [caseId]. Returns null when none exist (planning falls back to SupplyAllocator). */
internal fun loadCaseAllocRows(caseId: Int): List<CaseAllocRow>? = transaction {
    val rows = CaseAllocations.selectAll()
        .where { CaseAllocations.caseId eq caseId }
        .map { CaseAllocRow(it[CaseAllocations.supplyId], it[CaseAllocations.demandId], it[CaseAllocations.qtyAllocated]) }
    if (rows.isEmpty()) null else rows
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

/**
 * Recompute and persist case_allocation's content-hash fingerprint for [caseId] — call after
 * any write to [CaseAllocations], inside the SAME transaction as the row mutation so hash and
 * rows commit atomically. Re-reads the FULL current row set (not a delta) so PUT's partial
 * row-by-row edits still produce a hash reflecting the true final table state. Read later by
 * the KB signature's plan-submission fingerprint injection (CaseBootstrap.signatureFor /
 * Allocate.kt's resolveEffectiveConfig), not by anything in the live planning path itself.
 *
 * Known accepted limitation: does not take a row lock before recomputing, so two genuinely
 * concurrent writers on the same case (double-click Save, two tabs) could each compute a hash
 * from a snapshot that doesn't include the other's edit, and the transaction that commits last
 * "wins" the hash column. Low-probability given the UI's stage-then-Save-once pattern; revisit
 * with a `SELECT ... FOR UPDATE` on the config row if this proves to matter in practice.
 */
private fun recomputeCaseAllocationHash(caseId: Int) {
    val rows = CaseAllocations.selectAll().where { CaseAllocations.caseId eq caseId }
        .map { "${it[CaseAllocations.supplyId]}|${it[CaseAllocations.demandId] ?: ""}|${it[CaseAllocations.qtyAllocated]}" }
    val hash = KbFingerprint.hashRows(rows)
    val existing = CaseAllocationConfigs.selectAll().where { CaseAllocationConfigs.caseId eq caseId }.firstOrNull()
    if (existing != null) {
        CaseAllocationConfigs.update({ CaseAllocationConfigs.caseId eq caseId }) {
            it[CaseAllocationConfigs.contentHash] = hash
        }
    } else {
        CaseAllocationConfigs.insert {
            it[CaseAllocationConfigs.caseId] = caseId
            it[CaseAllocationConfigs.contentHash] = hash
        }
    }
}

/**
 * Run the supply allocation for [caseId] using [data] and [config], persist the result to
 * case_allocation, and return the saved rows. Config is required for purchasable_materials
 * filtering — pass null only when no plan run has been run yet for the case.
 *
 * This is the single shared implementation used by both the Generate endpoint and the
 * plan-run seeding path in runPlanBackground.
 */
internal fun generateAndSeedCaseAllocation(
    caseId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<CaseAllocRow> {
    val demands = data["demand"] ?: emptyList()
    val result  = buildSupplyAllocation(demands, data, config)
    val rows    = buildAllocationBudgetRows(result.perLotBudgets)
                      .map { (sid, did, qty) -> CaseAllocRow(sid, did, qty) }
    if (rows.isNotEmpty()) {
        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.caseId eq caseId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]       = caseId
                this[CaseAllocations.supplyId]     = row.supplyId
                this[CaseAllocations.demandId]     = row.demandId
                this[CaseAllocations.qtyAllocated] = row.qtyAllocated
            }
            recomputeCaseAllocationHash(caseId)
        }
    }
    return rows
}

// ── Routes ────────────────────────────────────────────────────────────────────

fun Routing.allocationRoutes() {

    // ── GET /cases/{case_id}/demands ─────────────────────────────────────────
    get("/cases/{case_id}/demands") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
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
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val rows = transaction {
            CaseAllocations.selectAll()
                .where { CaseAllocations.caseId eq caseId }
                .orderBy(CaseAllocations.supplyId to SortOrder.ASC, CaseAllocations.demandId to SortOrder.ASC)
                .map { row ->
                    buildJsonObject {
                        put("supply_id", row[CaseAllocations.supplyId])
                        val did = row[CaseAllocations.demandId]
                        if (did != null) put("demand_id", did) else put("demand_id", JsonNull)
                        put("qty_allocated", row[CaseAllocations.qtyAllocated])
                    }
                }
        }
        if (rows.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(buildJsonObject { put("rows", JsonArray(rows)) })
        }
    }

    // ── POST /cases/{case_id}/allocation/generate ─────────────────────────────
    post("/cases/{case_id}/allocation/generate") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
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

        log.info("[allocation] generating allocation for case {}", caseId)
        val newRows = generateAndSeedCaseAllocation(caseId, data, config)
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
    // Upserts (insert-or-replace) the given rows; leaves other rows unchanged.
    put("/cases/{case_id}/allocation") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val rows = rowsJson.map { el ->
            val obj = el.jsonObject
            CaseAllocRow(
                supplyId     = obj["supply_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing supply_id"),
                demandId     = obj["demand_id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                qtyAllocated = obj["qty_allocated"]?.jsonPrimitive?.double ?: throw IllegalArgumentException("Missing qty_allocated"),
            )
        }
        transaction {
            for (row in rows) {
                val existing = CaseAllocations.selectAll().where {
                    (CaseAllocations.caseId eq caseId) and
                    (CaseAllocations.supplyId eq row.supplyId) and
                    (if (row.demandId != null) CaseAllocations.demandId eq row.demandId else CaseAllocations.demandId.isNull())
                }.singleOrNull()
                if (existing != null) {
                    CaseAllocations.update({
                        (CaseAllocations.caseId eq caseId) and
                        (CaseAllocations.supplyId eq row.supplyId) and
                        (if (row.demandId != null) CaseAllocations.demandId eq row.demandId else CaseAllocations.demandId.isNull())
                    }) { it[CaseAllocations.qtyAllocated] = row.qtyAllocated }
                } else {
                    CaseAllocations.insert {
                        it[CaseAllocations.caseId]       = caseId
                        it[CaseAllocations.supplyId]     = row.supplyId
                        it[CaseAllocations.demandId]     = row.demandId
                        it[CaseAllocations.qtyAllocated] = row.qtyAllocated
                    }
                }
            }
            recomputeCaseAllocationHash(caseId)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", rows.size) })
    }

    // ── DELETE /cases/{case_id}/allocation ────────────────────────────────────
    delete("/cases/{case_id}/allocation") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val deleted = transaction {
            val n = CaseAllocations.deleteWhere { CaseAllocations.caseId eq caseId }
            CaseAllocationConfigs.deleteWhere { CaseAllocationConfigs.caseId eq caseId }
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/allocation/import ───────────────────────────────
    // Body: CSV text with header row: supply_id,demand_id,qty_allocated
    post("/cases/{case_id}/allocation/import") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val csvText = call.receiveText()
        val rows = parseCsvAllocation(csvText)
        log.info("[allocation] importing {} rows for case {}", rows.size, caseId)
        transaction {
            CaseAllocations.deleteWhere { CaseAllocations.caseId eq caseId }
            CaseAllocations.batchInsert(rows) { row ->
                this[CaseAllocations.caseId]       = caseId
                this[CaseAllocations.supplyId]     = row.supplyId
                this[CaseAllocations.demandId]     = row.demandId
                this[CaseAllocations.qtyAllocated] = row.qtyAllocated
            }
            recomputeCaseAllocationHash(caseId)
        }
        val responseRows = rows.map { row ->
            buildJsonObject {
                put("supply_id", row.supplyId)
                if (row.demandId != null) put("demand_id", row.demandId) else put("demand_id", JsonNull)
                put("qty_allocated", row.qtyAllocated)
            }
        }
        call.respond(buildJsonObject { put("rows", JsonArray(responseRows)) })
    }

    // ── GET /cases/{case_id}/allocation/export ────────────────────────────────
    get("/cases/{case_id}/allocation/export") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }
        val rows = transaction {
            CaseAllocations.selectAll()
                .where { CaseAllocations.caseId eq caseId }
                .orderBy(CaseAllocations.supplyId to SortOrder.ASC, CaseAllocations.demandId to SortOrder.ASC)
                .map { CaseAllocRow(it[CaseAllocations.supplyId], it[CaseAllocations.demandId], it[CaseAllocations.qtyAllocated]) }
        }
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
