package com.allocator.api

import com.allocator.CaseDemandOrderConfigs
import com.allocator.CaseDemandOrders
import com.allocator.Cases
import com.allocator.services.CaseLoader
import com.allocator.services.DemandOrderRow
import com.allocator.services.buildDemandOrder
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

private val log = LoggerFactory.getLogger("com.allocator.DemandOrderingRoute")

// ── Helper: load/persist case_demand_order rows ──────────────────────────────

data class CaseDemandOrderRow(
    val demandId: String,
    val order: Int,
)

data class CaseDemandOrderConfig(
    val generatedAt: String,
)

/** Loads case_demand_order rows for [caseId]. Returns null when none exist (planning falls back
 *  to raw `(priority, demand_id)` sort, per-demand). */
internal fun loadCaseDemandOrderRows(caseId: Int): List<CaseDemandOrderRow>? = transaction {
    val rows = CaseDemandOrders.selectAll()
        .where { CaseDemandOrders.caseId eq caseId }
        .map { CaseDemandOrderRow(demandId = it[CaseDemandOrders.demandId], order = it[CaseDemandOrders.order]) }
    if (rows.isEmpty()) null else rows
}

/** Loads the lookup structure threaded into planning: demand_id -> canonical order. Returns
 *  null when no KB exists for this case (planning falls back to raw `(priority, demand_id)`
 *  sort, per-demand). */
internal fun loadDemandOrderMap(caseId: Int): Map<String, Int>? =
    loadCaseDemandOrderRows(caseId)?.associate { it.demandId to it.order }

private fun toRows(candidates: List<DemandOrderRow>): List<CaseDemandOrderRow> =
    candidates.map { CaseDemandOrderRow(it.demandId, it.order) }

/**
 * Runs [buildDemandOrder] for [caseId] and persists the result — full delete + batch-insert
 * into case_demand_order, plus an upsert of case_demand_order_config.generated_at. Called only
 * from the Generate endpoint — deliberately NOT auto-seeded from the plan-run background job,
 * since Demand Ordering is optional-by-design (planning falls back to raw `(priority,
 * demand_id)` sort when absent; auto-seeding would silently change that fallback behavior
 * without user action).
 */
internal fun generateAndSeedCaseDemandOrder(
    caseId: Int,
    data: Map<String, List<Map<String, Any?>>>,
): List<CaseDemandOrderRow> {
    val rows = toRows(buildDemandOrder(data))
    transaction {
        CaseDemandOrders.deleteWhere { CaseDemandOrders.caseId eq caseId }
        if (rows.isNotEmpty()) {
            CaseDemandOrders.batchInsert(rows) { row ->
                this[CaseDemandOrders.caseId] = caseId
                this[CaseDemandOrders.demandId] = row.demandId
                this[CaseDemandOrders.order] = row.order
            }
        }
        val existing = CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.caseId eq caseId }.singleOrNull()
        if (existing != null) {
            CaseDemandOrderConfigs.update({ CaseDemandOrderConfigs.caseId eq caseId }) {
                it[CaseDemandOrderConfigs.generatedAt] = kotlinx.datetime.Clock.System.now()
            }
        } else {
            CaseDemandOrderConfigs.insert {
                it[CaseDemandOrderConfigs.caseId] = caseId
            }
        }
    }
    return rows
}

internal fun loadCaseDemandOrderConfig(caseId: Int): CaseDemandOrderConfig? = transaction {
    CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.caseId eq caseId }.singleOrNull()?.let {
        CaseDemandOrderConfig(generatedAt = it[CaseDemandOrderConfigs.generatedAt].toString())
    }
}

/** Read-only context joined onto each row for display purposes — not persisted. */
private data class DemandContext(
    val requestDueTime: String?,
    val priority: Int,
    val productId: String,
    val customerId: String,
)

private fun demandContextByIdFor(caseId: Int): Map<String, DemandContext> {
    val data = transaction { CaseLoader.load(caseId) }
    return (data["demand"] ?: emptyList()).associate { d ->
        val id = d["demand_id"]?.toString() ?: ""
        id to DemandContext(
            requestDueTime = d["request_due_time"] as? String,
            priority = (d["priority"] as? Number)?.toInt() ?: 0,
            productId = d["product_id"]?.toString() ?: "",
            customerId = d["customer_id"]?.toString() ?: "",
        )
    }
}

// ── Routes ────────────────────────────────────────────────────────────────────

fun Routing.demandOrderingRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    fun rowJson(row: CaseDemandOrderRow, ctx: DemandContext?): JsonObject = buildJsonObject {
        put("demand_id", row.demandId)
        put("order", row.order)
        put("request_due_time", ctx?.requestDueTime?.let { JsonPrimitive(it) } ?: JsonNull)
        put("priority", ctx?.priority ?: 0)
        put("product_id", ctx?.productId ?: "")
        put("customer_id", ctx?.customerId ?: "")
    }

    fun configJson(cfg: CaseDemandOrderConfig): JsonObject = buildJsonObject {
        put("generated_at", cfg.generatedAt)
    }

    // ── GET /cases/{case_id}/demand-ordering ──────────────────────────────────
    get("/cases/{case_id}/demand-ordering") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = loadCaseDemandOrderRows(caseId)
        if (rows == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            val ctxById = demandContextByIdFor(caseId)
            val cfg = loadCaseDemandOrderConfig(caseId)
            call.respond(buildJsonObject {
                put("rows", JsonArray(rows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) }))
                if (cfg != null) put("config", configJson(cfg)) else put("config", JsonNull)
            })
        }
    }

    // ── POST /cases/{case_id}/demand-ordering/generate ────────────────────────
    post("/cases/{case_id}/demand-ordering/generate") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")

        log.info("[demand-ordering] generating for case {}", caseId)
        val newRows = generateAndSeedCaseDemandOrder(caseId, data)
        log.info("[demand-ordering] generated {} rows for case {}", newRows.size, caseId)

        val ctxById = demandContextByIdFor(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(newRows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) })) })
    }

    // ── PUT /cases/{case_id}/demand-ordering ──────────────────────────────────
    // Body: { "rows": [{ "demand_id", "order" }] }
    // Upserts by natural key (demand_id); context columns (request_due_time/priority/product_id/
    // customer_id) are read-only/derived and not settable here.
    put("/cases/{case_id}/demand-ordering") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val edits = rowsJson.map { el ->
            val obj = el.jsonObject
            obj["demand_id"]?.jsonPrimitive?.content?.let { id ->
                id to (obj["order"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing order"))
            } ?: throw IllegalArgumentException("Missing demand_id")
        }
        transaction {
            for ((demandId, order) in edits) {
                CaseDemandOrders.update({
                    (CaseDemandOrders.caseId eq caseId) and (CaseDemandOrders.demandId eq demandId)
                }) { it[CaseDemandOrders.order] = order }
            }
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", edits.size) })
    }

    // ── DELETE /cases/{case_id}/demand-ordering ───────────────────────────────
    delete("/cases/{case_id}/demand-ordering") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val deleted = transaction {
            CaseDemandOrderConfigs.deleteWhere { CaseDemandOrderConfigs.caseId eq caseId }
            CaseDemandOrders.deleteWhere { CaseDemandOrders.caseId eq caseId }
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/demand-ordering/import ──────────────────────────
    // Body: CSV text, header: demand_id,order
    post("/cases/{case_id}/demand-ordering/import") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val csvText = call.receiveText()
        val rows = parseCsvDemandOrder(csvText)
        log.info("[demand-ordering] importing {} rows for case {}", rows.size, caseId)
        transaction {
            CaseDemandOrders.deleteWhere { CaseDemandOrders.caseId eq caseId }
            if (rows.isNotEmpty()) {
                CaseDemandOrders.batchInsert(rows) { row ->
                    this[CaseDemandOrders.caseId] = caseId
                    this[CaseDemandOrders.demandId] = row.demandId
                    this[CaseDemandOrders.order] = row.order
                }
            }
        }
        val ctxById = demandContextByIdFor(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(rows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) })) })
    }

    // ── GET /cases/{case_id}/demand-ordering/export ───────────────────────────
    get("/cases/{case_id}/demand-ordering/export") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = transaction {
            CaseDemandOrders.selectAll()
                .where { CaseDemandOrders.caseId eq caseId }
                .orderBy(CaseDemandOrders.order to SortOrder.ASC)
                .map { CaseDemandOrderRow(it[CaseDemandOrders.demandId], it[CaseDemandOrders.order]) }
        }
        val sb = StringBuilder("demand_id,order\n")
        for (row in rows) {
            sb.append(csvEscapeDo(row.demandId)).append(',')
            sb.append(row.order)
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"demand_ordering_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }
}

// ── CSV helpers ───────────────────────────────────────────────────────────────

private fun parseCsvDemandOrder(csv: String): List<CaseDemandOrderRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val idIdx = header.indexOf("demand_id")
    val orderIdx = header.indexOf("order")
    if (idIdx < 0 || orderIdx < 0)
        throw IllegalArgumentException("CSV must have demand_id and order columns")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val id = cols.getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val order = cols.getOrNull(orderIdx)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        CaseDemandOrderRow(id, order)
    }
}

private fun csvEscapeDo(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
