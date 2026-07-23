package com.allocator.api

import com.allocator.CasePurchasableMaterialConfigs
import com.allocator.CasePurchasableMaterials
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

private val log = LoggerFactory.getLogger("com.allocator.PurchasableMaterialsRoute")

/**
 * Case-level "Purchasable Materials" whitelist — promoted out of plan_run.config's embedded
 * `purchasable_materials` array (see CasePurchasableMaterials' own doc in Tables.kt). Unlike
 * Allocation/Preferences/DemandOrdering, there's no "Generate" step — this is pure user input,
 * no algorithm computes a default — so this file only has GET/PUT/DELETE/import/export, no
 * generate route.
 */

/** Loads the current whitelist for [caseId] as a plain set of product_ids. Empty set means
 *  "allow all" (see CasePurchasableMaterials' own doc) — callers should treat empty the same
 *  way the old embedded `purchasable_materials: []` array was always treated. */
internal fun loadPurchasableMaterialIds(caseId: Int): Set<String> = transaction {
    CasePurchasableMaterials.selectAll()
        .where { CasePurchasableMaterials.caseId eq caseId }
        .map { it[CasePurchasableMaterials.productId] }
        .toSet()
}

/** Recompute and persist the content-hash fingerprint for [caseId]'s current whitelist — see
 *  KbFingerprint.kt's own doc. Call inside the same transaction as the row mutation. */
private fun recomputePurchasableMaterialHash(caseId: Int, productIds: Collection<String>) {
    val hash = KbFingerprint.hashRows(productIds.map { it })
    val existing = CasePurchasableMaterialConfigs.selectAll()
        .where { CasePurchasableMaterialConfigs.caseId eq caseId }.singleOrNull()
    if (existing != null) {
        CasePurchasableMaterialConfigs.update({ CasePurchasableMaterialConfigs.caseId eq caseId }) {
            it[CasePurchasableMaterialConfigs.contentHash] = hash
        }
    } else {
        CasePurchasableMaterialConfigs.insert {
            it[CasePurchasableMaterialConfigs.caseId] = caseId
            it[CasePurchasableMaterialConfigs.contentHash] = hash
        }
    }
}

fun Routing.purchasableMaterialsRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    // ── GET /cases/{case_id}/purchasable-materials ────────────────────────────
    get("/cases/{case_id}/purchasable-materials") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val ids = loadPurchasableMaterialIds(caseId).sorted()
        call.respond(buildJsonObject {
            put("rows", JsonArray(ids.map { buildJsonObject { put("product_id", it) } }))
        })
    }

    // ── PUT /cases/{case_id}/purchasable-materials ────────────────────────────
    // Body: { "product_ids": string[] } — replaces the whole set (a checklist submits its full
    // current state, unlike Allocation/Preferences' per-row upsert PUT).
    put("/cases/{case_id}/purchasable-materials") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val ids = (payload["product_ids"]?.jsonArray ?: throw IllegalArgumentException("Missing 'product_ids'"))
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            .toSet()
        transaction {
            CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.caseId eq caseId }
            if (ids.isNotEmpty()) {
                CasePurchasableMaterials.batchInsert(ids) { pid ->
                    this[CasePurchasableMaterials.caseId] = caseId
                    this[CasePurchasableMaterials.productId] = pid
                }
            }
            recomputePurchasableMaterialHash(caseId, ids)
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", ids.size) })
    }

    // ── DELETE /cases/{case_id}/purchasable-materials ─────────────────────────
    delete("/cases/{case_id}/purchasable-materials") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val deleted = transaction {
            val n = CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.caseId eq caseId }
            CasePurchasableMaterialConfigs.deleteWhere { CasePurchasableMaterialConfigs.caseId eq caseId }
            n
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/purchasable-materials/import ────────────────────
    // Body: CSV text, header: product_id
    post("/cases/{case_id}/purchasable-materials/import") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val csvText = call.receiveText()
        val ids = parseCsvProductIds(csvText)
        log.info("[purchasable-materials] importing {} rows for case {}", ids.size, caseId)
        transaction {
            CasePurchasableMaterials.deleteWhere { CasePurchasableMaterials.caseId eq caseId }
            if (ids.isNotEmpty()) {
                CasePurchasableMaterials.batchInsert(ids) { pid ->
                    this[CasePurchasableMaterials.caseId] = caseId
                    this[CasePurchasableMaterials.productId] = pid
                }
            }
            recomputePurchasableMaterialHash(caseId, ids)
        }
        call.respond(buildJsonObject {
            put("rows", JsonArray(ids.sorted().map { buildJsonObject { put("product_id", it) } }))
        })
    }

    // ── GET /cases/{case_id}/purchasable-materials/export ─────────────────────
    get("/cases/{case_id}/purchasable-materials/export") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val ids = loadPurchasableMaterialIds(caseId).sorted()
        val sb = StringBuilder("product_id\n")
        for (id in ids) sb.append(csvEscapePm(id)).append('\n')
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"purchasable_materials_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
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
