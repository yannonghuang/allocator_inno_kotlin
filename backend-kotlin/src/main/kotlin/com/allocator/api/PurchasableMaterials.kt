package com.allocator.api

import com.allocator.CasePurchasableMaterialConfigs
import com.allocator.CasePurchasableMaterials
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

private val log = LoggerFactory.getLogger("com.allocator.PurchasableMaterialsRoute")

/**
 * Case-level "Purchasable Materials" whitelist — promoted out of plan_run.config's embedded
 * `purchasable_materials` array (see CasePurchasableMaterials' own doc in Tables.kt). Unlike
 * Allocation/Preferences/DemandOrdering, there's no "Generate" step — this is pure user input,
 * no algorithm computes a default — so this file only has GET/PUT/DELETE/import/export, no
 * generate route.
 *
 * Versioned (see CaseConfigVersions' own doc): every route resolves an explicit or default
 * `version_id`; PUT/import/DELETE are blocked (409) when the resolved version is referenced by
 * an existing plan_run/kb_record, in which case the client must POST .../versions ("Save As")
 * instead.
 */

private val KIND = ConfigVersionKind.PURCHMAT

/** Loads [versionId]'s whitelist as a plain set of product_ids. Empty set means "allow all"
 *  (see CasePurchasableMaterials' own doc) — callers should treat empty the same way the old
 *  embedded `purchasable_materials: []` array was always treated. */
internal fun loadPurchasableMaterialIds(versionId: Int?): Set<String> {
    if (versionId == null) return emptySet()
    return transaction {
        CasePurchasableMaterials.selectAll()
            .where { CasePurchasableMaterials.versionId eq versionId }
            .map { it[CasePurchasableMaterials.productId] }
            .toSet()
    }
}

/** Recompute and persist the content-hash fingerprint for [versionId]'s current whitelist — see
 *  KbFingerprint.kt's own doc. Call inside the same transaction as the row mutation. */
private fun recomputePurchasableMaterialHash(caseId: Int, versionId: Int, productIds: Collection<String>) {
    val hash = KbFingerprint.hashRows(productIds.map { it })
    val existing = CasePurchasableMaterialConfigs.selectAll()
        .where { CasePurchasableMaterialConfigs.versionId eq versionId }.singleOrNull()
    if (existing != null) {
        CasePurchasableMaterialConfigs.update({ CasePurchasableMaterialConfigs.versionId eq versionId }) {
            it[CasePurchasableMaterialConfigs.contentHash] = hash
        }
    } else {
        CasePurchasableMaterialConfigs.insert {
            it[CasePurchasableMaterialConfigs.caseId] = caseId
            it[CasePurchasableMaterialConfigs.versionId] = versionId
            it[CasePurchasableMaterialConfigs.contentHash] = hash
        }
    }
}

/** [versionId] null means this case has never had a Purchasable Materials version at all — a
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

fun Routing.purchasableMaterialsRoutes() {

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

    // ── GET /cases/{case_id}/purchasable-materials ────────────────────────────
    get("/cases/{case_id}/purchasable-materials") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val ids = loadPurchasableMaterialIds(versionId).sorted()
        call.respond(buildJsonObject {
            put("rows", JsonArray(ids.map { buildJsonObject { put("product_id", it) } }))
            put("version", versionJson(caseId, versionId))
        })
    }

    // ── PUT /cases/{case_id}/purchasable-materials ────────────────────────────
    // Body: { "product_ids": string[] } — replaces the whole set (a checklist submits its full
    // current state, unlike Allocation/Preferences' per-row upsert PUT).
    put("/cases/{case_id}/purchasable-materials") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@put
        }
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val ids = (payload["product_ids"]?.jsonArray ?: throw IllegalArgumentException("Missing 'product_ids'"))
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            .toSet()
        transaction {
            CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.versionId eq versionId }
            if (ids.isNotEmpty()) {
                CasePurchasableMaterials.batchInsert(ids) { pid ->
                    this[CasePurchasableMaterials.caseId] = caseId
                    this[CasePurchasableMaterials.versionId] = versionId
                    this[CasePurchasableMaterials.productId] = pid
                }
            }
            recomputePurchasableMaterialHash(caseId, versionId, ids)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", ids.size) })
    }

    // ── DELETE /cases/{case_id}/purchasable-materials ─────────────────────────
    // "Clear" — empties the resolved version's rows (does not delete the version itself; use
    // DELETE .../versions/{id} for that).
    delete("/cases/{case_id}/purchasable-materials") {
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
            val n = CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.versionId eq versionId }
            recomputePurchasableMaterialHash(caseId, versionId, emptyList())
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/purchasable-materials/import ────────────────────
    // Body: CSV text, header: product_id
    post("/cases/{case_id}/purchasable-materials/import") {
        val caseId = requireCaseId(call)
        val versionId = resolvedOrCreatedVersionId(call, caseId)
        if (CaseConfigVersioning.isVersionReferenced(versionId, KIND)) {
            call.respond(HttpStatusCode.Conflict, buildJsonObject { put("error", "version_in_use") })
            return@post
        }
        val csvText = call.receiveText()
        val ids = parseCsvProductIds(csvText)
        log.info("[purchasable-materials] importing {} rows for case {} version {}", ids.size, caseId, versionId)
        transaction {
            CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.versionId eq versionId }
            if (ids.isNotEmpty()) {
                CasePurchasableMaterials.batchInsert(ids) { pid ->
                    this[CasePurchasableMaterials.caseId] = caseId
                    this[CasePurchasableMaterials.versionId] = versionId
                    this[CasePurchasableMaterials.productId] = pid
                }
            }
            recomputePurchasableMaterialHash(caseId, versionId, ids)
        }
        call.respond(buildJsonObject {
            put("rows", JsonArray(ids.sorted().map { buildJsonObject { put("product_id", it) } }))
        })
    }

    // ── GET /cases/{case_id}/purchasable-materials/export ─────────────────────
    get("/cases/{case_id}/purchasable-materials/export") {
        val caseId = requireCaseId(call)
        val versionId = resolvedVersionId(call, caseId)
        val ids = loadPurchasableMaterialIds(versionId).sorted()
        val sb = StringBuilder("product_id\n")
        for (id in ids) sb.append(csvEscapePm(id)).append('\n')
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"purchasable_materials_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }

    // ── GET /cases/{case_id}/purchasable-materials/versions ───────────────────
    get("/cases/{case_id}/purchasable-materials/versions") {
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

    // ── POST /cases/{case_id}/purchasable-materials/versions ("Save As") ──────
    // Body: { name?, comments?, product_ids: string[] }
    post("/cases/{case_id}/purchasable-materials/versions") {
        val caseId = requireCaseId(call)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val name = payload["name"]?.jsonPrimitive?.contentOrNull
        val comments = payload["comments"]?.jsonPrimitive?.contentOrNull
        val ids = (payload["product_ids"]?.jsonArray ?: JsonArray(emptyList()))
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            .toSet()
        val versionId = transaction {
            val newId = CaseConfigVersioning.createVersion(caseId, KIND, name, comments)
            if (ids.isNotEmpty()) {
                CasePurchasableMaterials.batchInsert(ids) { pid ->
                    this[CasePurchasableMaterials.caseId] = caseId
                    this[CasePurchasableMaterials.versionId] = newId
                    this[CasePurchasableMaterials.productId] = pid
                }
            }
            recomputePurchasableMaterialHash(caseId, newId, ids)
            newId
        }
        call.respond(HttpStatusCode.Created, versionJson(caseId, versionId))
    }

    // ── PUT /cases/{case_id}/purchasable-materials/versions/{version_id} ──────
    // Body: { name?, comments? } — rename, always allowed.
    put("/cases/{case_id}/purchasable-materials/versions/{version_id}") {
        val caseId = requireCaseId(call)
        val versionId = call.parameters["version_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid version_id")
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        if (payload.containsKey("name") || payload.containsKey("comments")) {
            CaseConfigVersioning.renameVersion(versionId, payload["name"]?.jsonPrimitive?.contentOrNull, payload["comments"]?.jsonPrimitive?.contentOrNull)
        }
        call.respond(versionJson(caseId, versionId))
    }

    // ── DELETE /cases/{case_id}/purchasable-materials/versions/{version_id} ───
    delete("/cases/{case_id}/purchasable-materials/versions/{version_id}") {
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

private fun parseCsvProductIds(csv: String): List<String> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val idIdx = header.indexOf("product_id")
    if (idIdx < 0) throw IllegalArgumentException("CSV must have a product_id column")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        line.split(',').getOrNull(idIdx)?.trim()?.takeIf { it.isNotBlank() }
    }.distinct()
}

private fun csvEscapePm(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
