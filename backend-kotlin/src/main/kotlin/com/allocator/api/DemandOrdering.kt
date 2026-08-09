package com.allocator.api

import com.allocator.CaseDemandOrderConfigs
import com.allocator.CaseDemandOrders
import com.allocator.Cases
import com.allocator.services.CaseConfigVersioning
import com.allocator.services.CaseLoader
import com.allocator.services.ConfigVersionKind
import com.allocator.services.DemandOrderRow
import com.allocator.services.KbFingerprint
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
private val KIND = ConfigVersionKind.ORD

// ── Helper: load/persist case_demand_order rows ──────────────────────────────

data class CaseDemandOrderRow(
    val demandId: String,
    val order: Int,
)

data class CaseDemandOrderConfig(
    val generatedAt: String,
)

/** Loads case_demand_order rows for [versionId]. Returns null when none exist (planning falls
 *  back to raw `(priority, demand_id)` sort, per-demand). */
internal fun loadCaseDemandOrderRows(versionId: Int?): List<CaseDemandOrderRow>? {
    if (versionId == null) return null
    return transaction {
        val rows = CaseDemandOrders.selectAll()
            .where { CaseDemandOrders.versionId eq versionId }
            .map { CaseDemandOrderRow(demandId = it[CaseDemandOrders.demandId], order = it[CaseDemandOrders.order]) }
        if (rows.isEmpty()) null else rows
    }
}

/** Loads the lookup structure threaded into planning: demand_id -> canonical order. Returns
 *  null when no KB exists for [versionId] (planning falls back to raw `(priority, demand_id)`
 *  sort, per-demand). */
internal fun loadDemandOrderMap(versionId: Int?): Map<String, Int>? =
    loadCaseDemandOrderRows(versionId)?.associate { it.demandId to it.order }

private fun toRows(candidates: List<DemandOrderRow>): List<CaseDemandOrderRow> =
    candidates.map { CaseDemandOrderRow(it.demandId, it.order) }

/** Recompute and persist case_demand_order's content-hash fingerprint for [versionId] from the
 *  given full current row set — see Preferences.kt's recomputeCasePreferenceHash for the same
 *  upsert-not-update-only rationale (import here never created a config row before this
 *  either). */
private fun recomputeCaseDemandOrderHash(caseId: Int, versionId: Int, rows: List<CaseDemandOrderRow>) {
    val hash = KbFingerprint.hashRows(rows.map { "${it.demandId}|${it.order}" })
    val existing = CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.versionId eq versionId }.singleOrNull()
    if (existing != null) {
        CaseDemandOrderConfigs.update({ CaseDemandOrderConfigs.versionId eq versionId }) {
            it[CaseDemandOrderConfigs.contentHash] = hash
        }
    } else {
        CaseDemandOrderConfigs.insert {
            it[CaseDemandOrderConfigs.caseId] = caseId
            it[CaseDemandOrderConfigs.versionId] = versionId
            it[CaseDemandOrderConfigs.contentHash] = hash
        }
    }
}

/** [versionId] null means this case has never had a Demand Ordering version at all — a
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
 * Runs [buildDemandOrder] for [caseId]/[versionId] and persists the result — full delete +
 * batch-insert into case_demand_order, plus an upsert of case_demand_order_config.generated_at.
 * Called only from the Generate endpoint — deliberately NOT auto-seeded from the plan-run
 * background job, since Demand Ordering is optional-by-design (planning falls back to raw
 * `(priority, demand_id)` sort when absent; auto-seeding would silently change that fallback
 * behavior without user action). Deliberately does NOT check "is this version referenced" — see
 * Allocation.kt's generateDefaultTsaRows's own doc for why that guard belongs in the
 * route layer, not here.
 */
/** Pure computation half of [generateAndSeedCaseDemandOrder] — no DB writes. Split out so the
 *  Generate route can compute-and-return without persisting when no version is resolved yet (see
 *  Allocation.kt's /allocation/generate route for the full rationale). */
internal fun computeDemandOrderRows(data: Map<String, List<Map<String, Any?>>>): List<CaseDemandOrderRow> =
    toRows(buildDemandOrder(data))

internal fun generateAndSeedCaseDemandOrder(
    caseId: Int,
    versionId: Int,
    data: Map<String, List<Map<String, Any?>>>,
): List<CaseDemandOrderRow> {
    val rows = computeDemandOrderRows(data)
    transaction {
        CaseDemandOrders.deleteWhere { CaseDemandOrders.versionId eq versionId }
        if (rows.isNotEmpty()) {
            CaseDemandOrders.batchInsert(rows) { row ->
                this[CaseDemandOrders.caseId] = caseId
                this[CaseDemandOrders.versionId] = versionId
                this[CaseDemandOrders.demandId] = row.demandId
                this[CaseDemandOrders.order] = row.order
            }
        }
        val existing = CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.versionId eq versionId }.singleOrNull()
        if (existing != null) {
            CaseDemandOrderConfigs.update({ CaseDemandOrderConfigs.versionId eq versionId }) {
                it[CaseDemandOrderConfigs.generatedAt] = kotlinx.datetime.Clock.System.now()
            }
        } else {
            CaseDemandOrderConfigs.insert {
                it[CaseDemandOrderConfigs.caseId] = caseId
                it[CaseDemandOrderConfigs.versionId] = versionId
            }
        }
        recomputeCaseDemandOrderHash(caseId, versionId, rows)
    }
    return rows
}

internal fun loadCaseDemandOrderConfig(versionId: Int?): CaseDemandOrderConfig? {
    if (versionId == null) return null
    return transaction {
        CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.versionId eq versionId }.singleOrNull()?.let {
            CaseDemandOrderConfig(generatedAt = it[CaseDemandOrderConfigs.generatedAt].toString())
        }
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
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseDemandOrderRows(versionId)
        if (rows == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            val ctxById = demandContextByIdFor(caseId)
            val cfg = loadCaseDemandOrderConfig(versionId)
            call.respond(buildJsonObject {
                put("rows", JsonArray(rows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) }))
                if (cfg != null) put("config", configJson(cfg)) else put("config", JsonNull)
                put("version", versionJson(caseId, versionId))
            })
        }
    }

    // ── POST /cases/{case_id}/demand-ordering/generate ────────────────────────
    // Deliberately does NOT auto-create a version when none is resolved — see Allocation.kt's
    // /allocation/generate route for the full rationale.
    post("/cases/{case_id}/demand-ordering/generate") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        if (versionId != null && CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")

        log.info("[demand-ordering] generating for case {} version {}", caseId, versionId)
        val newRows = if (versionId != null) {
            generateAndSeedCaseDemandOrder(caseId, versionId, data)
        } else {
            computeDemandOrderRows(data)
        }
        log.info("[demand-ordering] generated {} rows for case {}", newRows.size, caseId)

        val ctxById = demandContextByIdFor(caseId)
        call.respond(buildJsonObject {
            put("rows", JsonArray(newRows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) }))
            put("version_id", versionId?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }

    // ── PUT /cases/{case_id}/demand-ordering ──────────────────────────────────
    // Body: { "rows": [{ "demand_id", "order" }] }
    // Upserts by natural key (demand_id); context columns (request_due_time/priority/product_id/
    // customer_id) are read-only/derived and not settable here.
    put("/cases/{case_id}/demand-ordering") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@put
        }
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
                    (CaseDemandOrders.versionId eq versionId) and (CaseDemandOrders.demandId eq demandId)
                }) { it[CaseDemandOrders.order] = order }
            }
            val currentRows = loadCaseDemandOrderRows(versionId) ?: emptyList()
            recomputeCaseDemandOrderHash(caseId, versionId, currentRows)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", edits.size); put("version_id", versionId) })
    }

    // ── DELETE /cases/{case_id}/demand-ordering ───────────────────────────────
    // "Clear" — empties the resolved version's rows (does not delete the version itself; use
    // DELETE .../versions/{id} for that).
    delete("/cases/{case_id}/demand-ordering") {
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
            val n = CaseDemandOrders.deleteWhere { CaseDemandOrders.versionId eq versionId }
            recomputeCaseDemandOrderHash(caseId, versionId, emptyList())
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/demand-ordering/import ──────────────────────────
    // Body: CSV text, header: demand_id,order
    post("/cases/{case_id}/demand-ordering/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val rows = parseCsvDemandOrder(csvText)
        log.info("[demand-ordering] importing {} rows for case {} version {}", rows.size, caseId, versionId)
        transaction {
            CaseDemandOrders.deleteWhere { CaseDemandOrders.versionId eq versionId }
            if (rows.isNotEmpty()) {
                CaseDemandOrders.batchInsert(rows) { row ->
                    this[CaseDemandOrders.caseId] = caseId
                    this[CaseDemandOrders.versionId] = versionId
                    this[CaseDemandOrders.demandId] = row.demandId
                    this[CaseDemandOrders.order] = row.order
                }
            }
            recomputeCaseDemandOrderHash(caseId, versionId, rows)
        }
        val ctxById = demandContextByIdFor(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(rows.sortedBy { it.order }.map { rowJson(it, ctxById[it.demandId]) })); put("version_id", versionId) })
    }

    // ── GET /cases/{case_id}/demand-ordering/export ───────────────────────────
    get("/cases/{case_id}/demand-ordering/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = (loadCaseDemandOrderRows(versionId) ?: emptyList()).sortedBy { it.order }
        val sb = StringBuilder("demand_id,order\n")
        for (row in rows) {
            sb.append(csvEscapeDo(row.demandId)).append(',')
            sb.append(row.order)
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"demand_ordering_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── GET /cases/{case_id}/demand-ordering/versions ─────────────────────────
    get("/cases/{case_id}/demand-ordering/versions") {
        val caseId = requireCaseId(call)
        val versions = CaseConfigVersioning.listVersions(caseId, KIND)
        call.respond(buildJsonObject {
            put("versions", JsonArray(versions.map { v ->
                buildJsonObject {
                    put("id", v.id); put("name", v.name); put("comments", v.comments)
                    put("referenced", v.referenced)
                    put("referenced_by_plan_run", v.referencedByPlanRun)
                    put("referenced_by_kb", v.referencedByKb)
                    put("created_at", v.createdAt); put("updated_at", v.updatedAt)
                }
            }))
        })
    }

    // ── POST /cases/{case_id}/demand-ordering/versions ("Save As") ────────────
    // Body: { name?, comments?, rows: [{ demand_id, order }] }
    post("/cases/{case_id}/demand-ordering/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val rows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { el ->
            val obj = el.jsonObject
            CaseDemandOrderRow(
                demandId = obj["demand_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing demand_id"),
                order = obj["order"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing order"),
            )
        }
        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseDemandOrders.batchInsert(rows) { row ->
                    this[CaseDemandOrders.caseId] = caseId
                    this[CaseDemandOrders.versionId] = newId
                    this[CaseDemandOrders.demandId] = row.demandId
                    this[CaseDemandOrders.order] = row.order
                }
            }
            CaseDemandOrderConfigs.insert {
                it[CaseDemandOrderConfigs.caseId] = caseId
                it[CaseDemandOrderConfigs.versionId] = newId
            }
            recomputeCaseDemandOrderHash(caseId, newId, rows)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/demand-ordering/versions/{version_id} ────────────
    // Body: { name?, comments? } — rename, always allowed.
    put("/cases/{case_id}/demand-ordering/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/demand-ordering/versions/{version_id} ─────────
    delete("/cases/{case_id}/demand-ordering/versions/{version_id}") {
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
