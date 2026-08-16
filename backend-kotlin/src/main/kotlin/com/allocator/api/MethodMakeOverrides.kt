package com.allocator.api

import com.allocator.CaseMethodMakeConfigs
import com.allocator.CaseMethodMakes
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

private val log = LoggerFactory.getLogger("com.allocator.MethodMakeOverridesRoute")

/**
 * Case-level method_make OVERRIDE — 6th "external config object" (see [CaseMethodMakes]' own doc
 * in Tables.kt). Same shape/pattern as Constraints.kt: versioned, pure user input, no "Generate"
 * step. When a version is resolved for a plan run, its rows REPLACE the
 * CSV-imported `method_make` data entirely (see `Allocate.kt`'s `effectiveData`) — an empty
 * version means "no make methods at all", not "fall back to CSV".
 *
 * No `bom_id` field — it's derivable (`"BOM_" + resolved_product_id`, synthesized by
 * `getMethods`/`deriveMakeBomId` in PlanningEngine.kt at the point a wildcard row's product_id is
 * already being resolved to a concrete product), not something this object's rows carry. See
 * CaseMethodMakes' own doc in Tables.kt for the full "why".
 */

private val KIND = ConfigVersionKind.METHMAKE

data class CaseMethodMakeRow(
    val productId: String,
    val locationId: String,
    val preference: Int?,
    val leadTime: Int?,
    val yield_: Double?,
)

/** Loads the method_make override rows for [versionId]. Empty list means "no rows" — same
 *  convention as [loadCaseConstraintRows]. */
internal fun loadCaseMethodMakeRows(versionId: Int?): List<CaseMethodMakeRow> {
    if (versionId == null) return emptyList()
    return transaction {
        CaseMethodMakes.selectAll()
            .where { CaseMethodMakes.versionId eq versionId }
            .map {
                CaseMethodMakeRow(
                    productId = it[CaseMethodMakes.productId],
                    locationId = it[CaseMethodMakes.locationId],
                    preference = it[CaseMethodMakes.preference],
                    leadTime = it[CaseMethodMakes.leadTime],
                    yield_ = it[CaseMethodMakes.yield],
                )
            }
    }
}

/** Replaces [versionId]'s whole method_make override set with [rows] (delete-then-insert,
 *  matching a full-state submit) and refreshes its content-hash fingerprint. Shared by the PUT
 *  route below and the `set_method_make_overrides` agent tool (PlanningAgentRoutes.kt) — the ONE
 *  place this write happens, mirroring saveCaseConstraintRows' identical pattern (Constraints.kt).
 *  Caller is responsible for the `version_in_use` conflict check. Returns the updated row count
 *  (after de-duplication). */
internal fun saveCaseMethodMakeRows(caseId: Int, versionId: Int, rows: List<CaseMethodMakeRow>): Int {
    val distinctRows = rows.distinct()
    transaction {
        CaseMethodMakes.deleteWhere { CaseMethodMakes.versionId eq versionId }
        if (distinctRows.isNotEmpty()) {
            CaseMethodMakes.batchInsert(distinctRows) { row ->
                this[CaseMethodMakes.caseId] = caseId
                this[CaseMethodMakes.versionId] = versionId
                this[CaseMethodMakes.productId] = row.productId
                this[CaseMethodMakes.locationId] = row.locationId
                this[CaseMethodMakes.preference] = row.preference
                this[CaseMethodMakes.leadTime] = row.leadTime
                this[CaseMethodMakes.yield] = row.yield_
            }
        }
        recomputeMethodMakeHash(caseId, versionId, distinctRows)
    }
    return distinctRows.size
}

private fun recomputeMethodMakeHash(caseId: Int, versionId: Int, rows: List<CaseMethodMakeRow>) {
    val hash = KbFingerprint.hashRows(rows.map { "${it.productId}|${it.locationId}|${it.preference}|${it.leadTime}|${it.yield_}" })
    val existing = CaseMethodMakeConfigs.selectAll().where { CaseMethodMakeConfigs.versionId eq versionId }.singleOrNull()
    if (existing != null) {
        CaseMethodMakeConfigs.update({ CaseMethodMakeConfigs.versionId eq versionId }) {
            it[CaseMethodMakeConfigs.contentHash] = hash
        }
    } else {
        CaseMethodMakeConfigs.insert {
            it[CaseMethodMakeConfigs.caseId] = caseId
            it[CaseMethodMakeConfigs.versionId] = versionId
            it[CaseMethodMakeConfigs.contentHash] = hash
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

fun Routing.methodMakeOverrideRoutes() {

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

    fun rowJson(row: CaseMethodMakeRow): JsonObject = buildJsonObject {
        put("product_id", row.productId)
        put("location_id", row.locationId)
        put("preference", row.preference)
        put("lead_time", row.leadTime)
        put("yield", row.yield_)
    }

    fun parseRow(el: JsonElement): CaseMethodMakeRow {
        val obj = el.jsonObject
        return CaseMethodMakeRow(
            productId = obj["product_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "*",
            locationId = obj["location_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "*",
            preference = obj["preference"]?.jsonPrimitive?.intOrNull,
            leadTime = obj["lead_time"]?.jsonPrimitive?.intOrNull,
            yield_ = obj["yield"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    // ── GET /cases/{case_id}/method-make-overrides ─────────────────────────────
    get("/cases/{case_id}/method-make-overrides") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseMethodMakeRows(versionId)
        call.respond(buildJsonObject {
            put("rows", JsonArray(rows.map { rowJson(it) }))
            put("version", versionJson(caseId, versionId))
        })
    }

    // ── PUT /cases/{case_id}/method-make-overrides ──────────────────────────────
    // Body: { "rows": [{ "product_id", "location_id", "preference"?, "lead_time"?, "yield"? }] }
    put("/cases/{case_id}/method-make-overrides") {
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
        val updated = saveCaseMethodMakeRows(caseId, versionId, rows)
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", updated); put("version_id", versionId) })
    }

    // ── DELETE /cases/{case_id}/method-make-overrides ───────────────────────────
    delete("/cases/{case_id}/method-make-overrides") {
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
            val n = CaseMethodMakes.deleteWhere { CaseMethodMakes.versionId eq versionId }
            recomputeMethodMakeHash(caseId, versionId, emptyList())
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/method-make-overrides/import ──────────────────────
    // Body: CSV text, header: product_id,location_id,preference,lead_time,yield
    post("/cases/{case_id}/method-make-overrides/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val rows = parseCsvMethodMake(csvText)
        log.info("[method-make-overrides] importing {} rows for case {} version {}", rows.size, caseId, versionId)
        transaction {
            CaseMethodMakes.deleteWhere { CaseMethodMakes.versionId eq versionId }
            if (rows.isNotEmpty()) {
                CaseMethodMakes.batchInsert(rows) { row ->
                    this[CaseMethodMakes.caseId] = caseId
                    this[CaseMethodMakes.versionId] = versionId
                    this[CaseMethodMakes.productId] = row.productId
                    this[CaseMethodMakes.locationId] = row.locationId
                    this[CaseMethodMakes.preference] = row.preference
                    this[CaseMethodMakes.leadTime] = row.leadTime
                    this[CaseMethodMakes.yield] = row.yield_
                }
            }
            recomputeMethodMakeHash(caseId, versionId, rows)
        }
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it) })); put("version_id", versionId) })
    }

    // ── GET /cases/{case_id}/method-make-overrides/export ───────────────────────
    get("/cases/{case_id}/method-make-overrides/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val rows = loadCaseMethodMakeRows(versionId)
        val sb = StringBuilder("product_id,location_id,preference,lead_time,yield\n")
        for (row in rows) {
            sb.append(csvEscapeMm(row.productId)).append(',')
            sb.append(csvEscapeMm(row.locationId)).append(',')
            sb.append(row.preference?.toString() ?: "").append(',')
            sb.append(row.leadTime?.toString() ?: "").append(',')
            sb.append(row.yield_?.toString() ?: "")
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"method_make_overrides_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── GET /cases/{case_id}/method-make-overrides/versions ─────────────────────
    get("/cases/{case_id}/method-make-overrides/versions") {
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

    // ── POST /cases/{case_id}/method-make-overrides/versions ("Save As") ────────
    post("/cases/{case_id}/method-make-overrides/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val rows = (payload["rows"]?.jsonArray ?: JsonArray(emptyList())).map { parseRow(it) }.distinct()
        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (rows.isNotEmpty()) {
                CaseMethodMakes.batchInsert(rows) { row ->
                    this[CaseMethodMakes.caseId] = caseId
                    this[CaseMethodMakes.versionId] = newId
                    this[CaseMethodMakes.productId] = row.productId
                    this[CaseMethodMakes.locationId] = row.locationId
                    this[CaseMethodMakes.preference] = row.preference
                    this[CaseMethodMakes.leadTime] = row.leadTime
                    this[CaseMethodMakes.yield] = row.yield_
                }
            }
            recomputeMethodMakeHash(caseId, newId, rows)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/method-make-overrides/versions/{version_id} ────────
    put("/cases/{case_id}/method-make-overrides/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/method-make-overrides/versions/{version_id} ─────
    delete("/cases/{case_id}/method-make-overrides/versions/{version_id}") {
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

private fun parseCsvMethodMake(csv: String): List<CaseMethodMakeRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val pidIdx = header.indexOf("product_id")
    val locIdx = header.indexOf("location_id")
    val prefIdx = header.indexOf("preference")
    val leadIdx = header.indexOf("lead_time")
    val yieldIdx = header.indexOf("yield")
    if (pidIdx < 0)
        throw IllegalArgumentException("CSV must have a product_id column")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val pid = cols.getOrNull(pidIdx)?.trim()?.takeIf { it.isNotBlank() } ?: "*"
        val loc = (if (locIdx >= 0) cols.getOrNull(locIdx)?.trim() else null)?.takeIf { it.isNotBlank() } ?: "*"
        val pref = (if (prefIdx >= 0) cols.getOrNull(prefIdx)?.trim() else null)?.toIntOrNull()
        val lead = (if (leadIdx >= 0) cols.getOrNull(leadIdx)?.trim() else null)?.toIntOrNull()
        val yld = (if (yieldIdx >= 0) cols.getOrNull(yieldIdx)?.trim() else null)?.toDoubleOrNull()
        CaseMethodMakeRow(pid, loc, pref, lead, yld)
    }.distinct()
}

private fun csvEscapeMm(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
