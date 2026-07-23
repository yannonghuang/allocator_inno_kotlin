package com.allocator.api

import com.allocator.CaseConstraintConfigs
import com.allocator.CaseConstraints
import com.allocator.Cases
import com.allocator.services.KbFingerprint
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

private val log = LoggerFactory.getLogger("com.allocator.ConstraintsRoute")

/**
 * Case-level customer-specific BOM-alternative constraints — promoted out of plan_run.config's
 * embedded `constraints` array (see CaseConstraints' own doc in Tables.kt). Same rationale as
 * PurchasableMaterials.kt: pure user input, no "Generate" step.
 */

data class CaseConstraintRow(
    val customerId: String,
    val parent: String,
    val location: String,
    val child: String,
)

/** Loads the current constraint rules for [caseId]. Empty list means "no constraints" — the
 *  same convention the old embedded `constraints: []` array always used. */
internal fun loadCaseConstraintRows(caseId: Int): List<CaseConstraintRow> = transaction {
    CaseConstraints.selectAll()
        .where { CaseConstraints.caseId eq caseId }
        .map {
            CaseConstraintRow(
                customerId = it[CaseConstraints.customerId],
                parent = it[CaseConstraints.parent],
                location = it[CaseConstraints.location],
                child = it[CaseConstraints.child],
            )
        }
}

/** Recompute and persist the content-hash fingerprint for [caseId]'s current constraint set —
 *  see KbFingerprint.kt's own doc. Call inside the same transaction as the row mutation. */
private fun recomputeConstraintHash(caseId: Int, rows: List<CaseConstraintRow>) {
    val hash = KbFingerprint.hashRows(rows.map { "${it.customerId}|${it.parent}|${it.location}|${it.child}" })
    val existing = CaseConstraintConfigs.selectAll().where { CaseConstraintConfigs.caseId eq caseId }.singleOrNull()
    if (existing != null) {
        CaseConstraintConfigs.update({ CaseConstraintConfigs.caseId eq caseId }) {
            it[CaseConstraintConfigs.contentHash] = hash
        }
    } else {
        CaseConstraintConfigs.insert {
            it[CaseConstraintConfigs.caseId] = caseId
            it[CaseConstraintConfigs.contentHash] = hash
        }
    }
}

fun Routing.constraintsRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    fun rowJson(row: CaseConstraintRow): JsonObject = buildJsonObject {
        put("customer_id", row.customerId)
        put("parent", row.parent)
        put("location", row.location)
        put("child", row.child)
    }

    fun parseRow(el: JsonElement): CaseConstraintRow {
        val obj = el.jsonObject
        return CaseConstraintRow(
            customerId = obj["customer_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing customer_id"),
            parent = obj["parent"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing parent"),
            location = obj["location"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "*",
            child = obj["child"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing child"),
        )
    }

    // ── GET /cases/{case_id}/constraints ──────────────────────────────────────
    get("/cases/{case_id}/constraints") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = loadCaseConstraintRows(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it) })) })
    }

    // ── PUT /cases/{case_id}/constraints ──────────────────────────────────────
    // Body: { "rows": [{ "customer_id", "parent", "location", "child" }] } — replaces the whole
    // rule set (matches PurchasableMaterials' "checklist submits its full state" semantics).
    put("/cases/{case_id}/constraints") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val rows = rowsJson.map { parseRow(it) }.distinct()
        transaction {
            CaseConstraints.deleteWhere { CaseConstraints.caseId eq caseId }
            if (rows.isNotEmpty()) {
                CaseConstraints.batchInsert(rows) { row ->
                    this[CaseConstraints.caseId] = caseId
                    this[CaseConstraints.customerId] = row.customerId
                    this[CaseConstraints.parent] = row.parent
                    this[CaseConstraints.location] = row.location
                    this[CaseConstraints.child] = row.child
                }
            }
            recomputeConstraintHash(caseId, rows)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", rows.size) })
    }

    // ── DELETE /cases/{case_id}/constraints ───────────────────────────────────
    delete("/cases/{case_id}/constraints") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val deleted = transaction {
            val n = CaseConstraints.deleteWhere { CaseConstraints.caseId eq caseId }
            CaseConstraintConfigs.deleteWhere { CaseConstraintConfigs.caseId eq caseId }
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/constraints/import ──────────────────────────────
    // Body: CSV text, header: customer_id,parent,location,child
    post("/cases/{case_id}/constraints/import") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val csvText = call.receiveText()
        val rows = parseCsvConstraints(csvText)
        log.info("[constraints] importing {} rows for case {}", rows.size, caseId)
        transaction {
            CaseConstraints.deleteWhere { CaseConstraints.caseId eq caseId }
            if (rows.isNotEmpty()) {
                CaseConstraints.batchInsert(rows) { row ->
                    this[CaseConstraints.caseId] = caseId
                    this[CaseConstraints.customerId] = row.customerId
                    this[CaseConstraints.parent] = row.parent
                    this[CaseConstraints.location] = row.location
                    this[CaseConstraints.child] = row.child
                }
            }
            recomputeConstraintHash(caseId, rows)
        }
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it) })) })
    }

    // ── GET /cases/{case_id}/constraints/export ───────────────────────────────
    get("/cases/{case_id}/constraints/export") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = loadCaseConstraintRows(caseId)
        val sb = StringBuilder("customer_id,parent,location,child\n")
        for (row in rows) {
            sb.append(csvEscapeC(row.customerId)).append(',')
            sb.append(csvEscapeC(row.parent)).append(',')
            sb.append(csvEscapeC(row.location)).append(',')
            sb.append(csvEscapeC(row.child))
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"constraints_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }
}

private fun parseCsvConstraints(csv: String): List<CaseConstraintRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val custIdx = header.indexOf("customer_id")
    val parentIdx = header.indexOf("parent")
    val locIdx = header.indexOf("location")
    val childIdx = header.indexOf("child")
    if (custIdx < 0 || parentIdx < 0 || childIdx < 0)
        throw IllegalArgumentException("CSV must have customer_id, parent, and child columns")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val cust = cols.getOrNull(custIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val parent = cols.getOrNull(parentIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val loc = (if (locIdx >= 0) cols.getOrNull(locIdx)?.trim() else null)?.takeIf { it.isNotBlank() } ?: "*"
        val child = cols.getOrNull(childIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CaseConstraintRow(cust, parent, loc, child)
    }.distinct()
}

private fun csvEscapeC(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
