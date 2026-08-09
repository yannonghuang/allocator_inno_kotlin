package com.allocator.api

import com.allocator.CaseConstraintConfigs
import com.allocator.CaseConstraints
import com.allocator.Cases
import com.allocator.services.CaseConfigVersioning
import com.allocator.services.ConfigVersionKind
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
 *
 * Versioned (see CaseConfigVersions' own doc) — same pattern as PurchasableMaterials.kt: every
 * route resolves an explicit or default `version_id`; PUT/import/DELETE are blocked (409) when
 * the resolved version is referenced by an existing plan_run/kb_record.
 */

private val KIND = ConfigVersionKind.CONSTR

data class CaseConstraintRow(
    val customerId: String,
    val parent: String,
    val location: String,
    val child: String,
)

/** Loads the constraint rules for [versionId]. Empty list means "no constraints" — the same
 *  convention the old embedded `constraints: []` array always used. */
internal fun loadCaseConstraintRows(versionId: Int?): List<CaseConstraintRow> {
    if (versionId == null) return emptyList()
    return transaction {
        CaseConstraints.selectAll()
            .where { CaseConstraints.versionId eq versionId }
            .map {
                CaseConstraintRow(
                    customerId = it[CaseConstraints.customerId],
                    parent = it[CaseConstraints.parent],
                    location = it[CaseConstraints.location],
                    child = it[CaseConstraints.child],
                )
            }
    }
}

/** Recompute and persist the content-hash fingerprint for [versionId]'s current constraint set —
 *  see KbFingerprint.kt's own doc. Call inside the same transaction as the row mutation. */
private fun recomputeConstraintHash(caseId: Int, versionId: Int, rows: List<CaseConstraintRow>) {
    val hash = KbFingerprint.hashRows(rows.map { "${it.customerId}|${it.parent}|${it.location}|${it.child}" })
    val existing = CaseConstraintConfigs.selectAll().where { CaseConstraintConfigs.versionId eq versionId }.singleOrNull()
    if (existing != null) {
        CaseConstraintConfigs.update({ CaseConstraintConfigs.versionId eq versionId }) {
            it[CaseConstraintConfigs.contentHash] = hash
        }
    } else {
        CaseConstraintConfigs.insert {
            it[CaseConstraintConfigs.caseId] = caseId
            it[CaseConstraintConfigs.versionId] = versionId
            it[CaseConstraintConfigs.contentHash] = hash
        }
    }
}

/** [versionId] null means this case has never had a Constraints version at all — a legitimate
 *  "nothing yet" state (see CaseConfigVersioning's own doc), not an error. Reports as a freely
 *  editable, unreferenced, non-default placeholder; the frontend shows "no version yet" rather
 *  than a phantom "Version N". */
private fun versionJson(caseId: Int, versionId: Int?): JsonObject {
    val summary = versionId?.let { vid -> CaseConfigVersioning.listVersions(caseId, KIND).firstOrNull { it.id == vid } }
    return buildJsonObject {
        put("id", versionId)
        put("name", summary?.name)
        put("comments", summary?.comments)
        put("referenced", summary?.referenced ?: false)
    }
}

fun Routing.constraintsRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    fun requireCaseId(call: ApplicationCall): Int {
        val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        return caseId
    }

    // Read paths (GET/DELETE-clear/export): never creates. PUT/import (real writes) use
    // resolvedOrCreatedVersionId instead — the one legitimate create-on-demand moment.
    fun resolvedVersionId(call: ApplicationCall, caseId: Int): Int? =
        CaseConfigVersioning.resolveVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

    fun resolvedOrCreatedVersionId(call: ApplicationCall, caseId: Int): Int =
        CaseConfigVersioning.resolveOrCreateVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

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
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseConstraintRows(versionId)
        call.respond(buildJsonObject {
            put("rows", JsonArray(rows.map { rowJson(it) }))
            put("version", versionJson(caseId, versionId))
        })
    }

    // ── PUT /cases/{case_id}/constraints ──────────────────────────────────────
    // Body: { "rows": [{ "customer_id", "parent", "location", "child" }] } — replaces the whole
    // rule set (matches PurchasableMaterials' "checklist submits its full state" semantics).
    put("/cases/{case_id}/constraints") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@put
        }
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val rows = rowsJson.map { parseRow(it) }.distinct()
        transaction {
            CaseConstraints.deleteWhere { CaseConstraints.versionId eq versionId }
            if (rows.isNotEmpty()) {
                CaseConstraints.batchInsert(rows) { row ->
                    this[CaseConstraints.caseId] = caseId
                    this[CaseConstraints.versionId] = versionId
                    this[CaseConstraints.customerId] = row.customerId
                    this[CaseConstraints.parent] = row.parent
                    this[CaseConstraints.location] = row.location
                    this[CaseConstraints.child] = row.child
                }
            }
            recomputeConstraintHash(caseId, versionId, rows)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", rows.size); put("version_id", versionId) })
    }

    // ── DELETE /cases/{case_id}/constraints ───────────────────────────────────
    // "Clear" — empties the resolved version's rows (does not delete the version itself; use
    // DELETE .../versions/{id} for that).
    delete("/cases/{case_id}/constraints") {
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
            val n = CaseConstraints.deleteWhere { CaseConstraints.versionId eq versionId }
            recomputeConstraintHash(caseId, versionId, emptyList())
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/constraints/import ──────────────────────────────
    // Body: CSV text, header: customer_id,parent,location,child
    post("/cases/{case_id}/constraints/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val rows = parseCsvConstraints(csvText)
        log.info("[constraints] importing {} rows for case {} version {}", rows.size, caseId, versionId)
        transaction {
            CaseConstraints.deleteWhere { CaseConstraints.versionId eq versionId }
            if (rows.isNotEmpty()) {
                CaseConstraints.batchInsert(rows) { row ->
                    this[CaseConstraints.caseId] = caseId
                    this[CaseConstraints.versionId] = versionId
                    this[CaseConstraints.customerId] = row.customerId
                    this[CaseConstraints.parent] = row.parent
                    this[CaseConstraints.location] = row.location
                    this[CaseConstraints.child] = row.child
                }
            }
            recomputeConstraintHash(caseId, versionId, rows)
        }
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it) })); put("version_id", versionId) })
    }

    // ── GET /cases/{case_id}/constraints/export ───────────────────────────────
    get("/cases/{case_id}/constraints/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseConstraintRows(versionId)
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

    // ── GET /cases/{case_id}/constraints/versions ─────────────────────────────
    get("/cases/{case_id}/constraints/versions") {
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

    // ── POST /cases/{case_id}/constraints/versions ("Save As") ────────────────
    // Body: { name?, comments?, rows: [{ customer_id, parent, location, child }] }
    post("/cases/{case_id}/constraints/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val rows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { parseRow(it) }.distinct()
        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseConstraints.batchInsert(rows) { row ->
                    this[CaseConstraints.caseId] = caseId
                    this[CaseConstraints.versionId] = newId
                    this[CaseConstraints.customerId] = row.customerId
                    this[CaseConstraints.parent] = row.parent
                    this[CaseConstraints.location] = row.location
                    this[CaseConstraints.child] = row.child
                }
            }
            recomputeConstraintHash(caseId, newId, rows)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/constraints/versions/{version_id} ────────────────
    // Body: { name?, comments? } — rename, always allowed.
    put("/cases/{case_id}/constraints/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/constraints/versions/{version_id} ─────────────
    delete("/cases/{case_id}/constraints/versions/{version_id}") {
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
