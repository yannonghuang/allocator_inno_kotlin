package com.allocator.api

import com.allocator.CaseMethodMoveConfigs
import com.allocator.CaseMethodMoves
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

private val log = LoggerFactory.getLogger("com.allocator.MethodMoveOverridesRoute")

/**
 * Case-level method_move OVERRIDE — 7th "external config object" (see [CaseMethodMoves]' own doc
 * in Tables.kt). Same shape/pattern as Constraints.kt / MethodMakeOverrides.kt: versioned, pure
 * user input, no "Generate" step. Rows REPLACE the CSV-imported `method_move` data entirely when
 * a version is resolved (see `Allocate.kt`'s `effectiveData`).
 */

private val KIND = ConfigVersionKind.METHMOVE

data class CaseMethodMoveRow(
    val productId: String,
    val fromLocationId: String,
    val toLocationId: String,
    val transitTime: Double?,
    val transitTimeUom: String?,
    val preference: Int?,
)

/** Loads the method_move override rows for [versionId]. Empty list means "no rows". */
internal fun loadCaseMethodMoveRows(versionId: Int?): List<CaseMethodMoveRow> {
    if (versionId == null) return emptyList()
    return transaction {
        CaseMethodMoves.selectAll()
            .where { CaseMethodMoves.versionId eq versionId }
            .map {
                CaseMethodMoveRow(
                    productId = it[CaseMethodMoves.productId],
                    fromLocationId = it[CaseMethodMoves.fromLocationId],
                    toLocationId = it[CaseMethodMoves.toLocationId],
                    transitTime = it[CaseMethodMoves.transitTime],
                    transitTimeUom = it[CaseMethodMoves.transitTimeUom],
                    preference = it[CaseMethodMoves.preference],
                )
            }
    }
}

/** Replaces [versionId]'s whole method_move override set with [rows] (delete-then-insert,
 *  matching a full-state submit) and refreshes its content-hash fingerprint. Shared by the PUT
 *  route below and the `set_method_move_overrides` agent tool (PlanningAgentRoutes.kt) — mirrors
 *  saveCaseMethodMakeRows'/saveCaseConstraintRows' identical pattern. Caller is responsible for
 *  the `version_in_use` conflict check. Returns the updated row count (after de-duplication). */
internal fun saveCaseMethodMoveRows(caseId: Int, versionId: Int, rows: List<CaseMethodMoveRow>): Int {
    val distinctRows = rows.distinct()
    transaction {
        CaseMethodMoves.deleteWhere { CaseMethodMoves.versionId eq versionId }
        if (distinctRows.isNotEmpty()) {
            CaseMethodMoves.batchInsert(distinctRows) { row ->
                this[CaseMethodMoves.caseId] = caseId
                this[CaseMethodMoves.versionId] = versionId
                this[CaseMethodMoves.productId] = row.productId
                this[CaseMethodMoves.fromLocationId] = row.fromLocationId
                this[CaseMethodMoves.toLocationId] = row.toLocationId
                this[CaseMethodMoves.transitTime] = row.transitTime
                this[CaseMethodMoves.transitTimeUom] = row.transitTimeUom
                this[CaseMethodMoves.preference] = row.preference
            }
        }
        recomputeMethodMoveHash(caseId, versionId, distinctRows)
    }
    return distinctRows.size
}

private fun recomputeMethodMoveHash(caseId: Int, versionId: Int, rows: List<CaseMethodMoveRow>) {
    val hash = KbFingerprint.hashRows(rows.map { "${it.productId}|${it.fromLocationId}|${it.toLocationId}|${it.transitTime}|${it.transitTimeUom}|${it.preference}" })
    val existing = CaseMethodMoveConfigs.selectAll().where { CaseMethodMoveConfigs.versionId eq versionId }.singleOrNull()
    if (existing != null) {
        CaseMethodMoveConfigs.update({ CaseMethodMoveConfigs.versionId eq versionId }) {
            it[CaseMethodMoveConfigs.contentHash] = hash
        }
    } else {
        CaseMethodMoveConfigs.insert {
            it[CaseMethodMoveConfigs.caseId] = caseId
            it[CaseMethodMoveConfigs.versionId] = versionId
            it[CaseMethodMoveConfigs.contentHash] = hash
        }
    }
}

private fun versionJson(caseId: Int, versionId: Int?): JsonObject {
    val summary = versionId?.let { vid -> CaseConfigVersioning.listVersions(caseId, KIND).firstOrNull { it.id == vid } }
    return buildJsonObject {
        put("id", versionId)
        put("name", summary?.name)
        put("comments", summary?.comments)
        put("referenced", summary?.referenced ?: false)
    }
}

fun Routing.methodMoveOverrideRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    fun requireCaseId(call: ApplicationCall): Int {
        val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        return caseId
    }

    fun resolvedVersionId(call: ApplicationCall, caseId: Int): Int? =
        CaseConfigVersioning.resolveVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

    fun resolvedOrCreatedVersionId(call: ApplicationCall, caseId: Int): Int =
        CaseConfigVersioning.resolveOrCreateVersionId(caseId, KIND, call.request.queryParameters["version_id"]?.toIntOrNull())

    fun rowJson(row: CaseMethodMoveRow): JsonObject = buildJsonObject {
        put("product_id", row.productId)
        put("from_location_id", row.fromLocationId)
        put("to_location_id", row.toLocationId)
        put("transit_time", row.transitTime)
        put("transit_time_uom", row.transitTimeUom)
        put("preference", row.preference)
    }

    fun parseRow(el: JsonElement): CaseMethodMoveRow {
        val obj = el.jsonObject
        return CaseMethodMoveRow(
            productId = obj["product_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "*",
            fromLocationId = obj["from_location_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "*",
            toLocationId = obj["to_location_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing to_location_id"),
            transitTime = obj["transit_time"]?.jsonPrimitive?.doubleOrNull,
            transitTimeUom = obj["transit_time_uom"]?.jsonPrimitive?.contentOrNull,
            preference = obj["preference"]?.jsonPrimitive?.intOrNull,
        )
    }

    // ── GET /cases/{case_id}/method-move-overrides ─────────────────────────────
    get("/cases/{case_id}/method-move-overrides") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseMethodMoveRows(versionId)
        call.respond(buildJsonObject {
            put("rows", JsonArray(rows.map { rowJson(it) }))
            put("version", versionJson(caseId, versionId))
        })
    }

    // ── PUT /cases/{case_id}/method-move-overrides ──────────────────────────────
    put("/cases/{case_id}/method-move-overrides") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@put
        }
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val rows = rowsJson.map { parseRow(it) }
        val updated = saveCaseMethodMoveRows(caseId, versionId, rows)
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", updated); put("version_id", versionId) })
    }

    // ── DELETE /cases/{case_id}/method-move-overrides ───────────────────────────
    delete("/cases/{case_id}/method-move-overrides") {
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
            val n = CaseMethodMoves.deleteWhere { CaseMethodMoves.versionId eq versionId }
            recomputeMethodMoveHash(caseId, versionId, emptyList())
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/method-move-overrides/import ──────────────────────
    // Body: CSV text, header: product_id,from_location_id,to_location_id,transit_time,transit_time_uom,preference
    post("/cases/{case_id}/method-move-overrides/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val rows = parseCsvMethodMove(csvText)
        log.info("[method-move-overrides] importing {} rows for case {} version {}", rows.size, caseId, versionId)
        transaction {
            CaseMethodMoves.deleteWhere { CaseMethodMoves.versionId eq versionId }
            if (rows.isNotEmpty()) {
                CaseMethodMoves.batchInsert(rows) { row ->
                    this[CaseMethodMoves.caseId] = caseId
                    this[CaseMethodMoves.versionId] = versionId
                    this[CaseMethodMoves.productId] = row.productId
                    this[CaseMethodMoves.fromLocationId] = row.fromLocationId
                    this[CaseMethodMoves.toLocationId] = row.toLocationId
                    this[CaseMethodMoves.transitTime] = row.transitTime
                    this[CaseMethodMoves.transitTimeUom] = row.transitTimeUom
                    this[CaseMethodMoves.preference] = row.preference
                }
            }
            recomputeMethodMoveHash(caseId, versionId, rows)
        }
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it) })); put("version_id", versionId) })
    }

    // ── GET /cases/{case_id}/method-move-overrides/export ───────────────────────
    get("/cases/{case_id}/method-move-overrides/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseMethodMoveRows(versionId)
        val sb = StringBuilder("product_id,from_location_id,to_location_id,transit_time,transit_time_uom,preference\n")
        for (row in rows) {
            sb.append(csvEscapeMv(row.productId)).append(',')
            sb.append(csvEscapeMv(row.fromLocationId)).append(',')
            sb.append(csvEscapeMv(row.toLocationId)).append(',')
            sb.append(row.transitTime?.toString() ?: "").append(',')
            sb.append(row.transitTimeUom?.let { csvEscapeMv(it) } ?: "").append(',')
            sb.append(row.preference?.toString() ?: "")
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"method_move_overrides_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── GET /cases/{case_id}/method-move-overrides/versions ─────────────────────
    get("/cases/{case_id}/method-move-overrides/versions") {
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

    // ── POST /cases/{case_id}/method-move-overrides/versions ("Save As") ────────
    post("/cases/{case_id}/method-move-overrides/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val rows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { parseRow(it) }.distinct()
        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseMethodMoves.batchInsert(rows) { row ->
                    this[CaseMethodMoves.caseId] = caseId
                    this[CaseMethodMoves.versionId] = newId
                    this[CaseMethodMoves.productId] = row.productId
                    this[CaseMethodMoves.fromLocationId] = row.fromLocationId
                    this[CaseMethodMoves.toLocationId] = row.toLocationId
                    this[CaseMethodMoves.transitTime] = row.transitTime
                    this[CaseMethodMoves.transitTimeUom] = row.transitTimeUom
                    this[CaseMethodMoves.preference] = row.preference
                }
            }
            recomputeMethodMoveHash(caseId, newId, rows)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/method-move-overrides/versions/{version_id} ────────
    put("/cases/{case_id}/method-move-overrides/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/method-move-overrides/versions/{version_id} ─────
    delete("/cases/{case_id}/method-move-overrides/versions/{version_id}") {
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

private fun parseCsvMethodMove(csv: String): List<CaseMethodMoveRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val pidIdx = header.indexOf("product_id")
    val fromIdx = header.indexOf("from_location_id")
    val toIdx = header.indexOf("to_location_id")
    val transitIdx = header.indexOf("transit_time")
    val uomIdx = header.indexOf("transit_time_uom")
    val prefIdx = header.indexOf("preference")
    if (toIdx < 0)
        throw IllegalArgumentException("CSV must have a to_location_id column")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val pid = (if (pidIdx >= 0) cols.getOrNull(pidIdx)?.trim() else null)?.takeIf { it.isNotBlank() } ?: "*"
        val from = (if (fromIdx >= 0) cols.getOrNull(fromIdx)?.trim() else null)?.takeIf { it.isNotBlank() } ?: "*"
        val to = cols.getOrNull(toIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val transit = (if (transitIdx >= 0) cols.getOrNull(transitIdx)?.trim() else null)?.toDoubleOrNull()
        val uom = (if (uomIdx >= 0) cols.getOrNull(uomIdx)?.trim() else null)?.takeIf { it.isNotBlank() }
        val pref = (if (prefIdx >= 0) cols.getOrNull(prefIdx)?.trim() else null)?.toIntOrNull()
        CaseMethodMoveRow(pid, from, to, transit, uom, pref)
    }.distinct()
}

private fun csvEscapeMv(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
